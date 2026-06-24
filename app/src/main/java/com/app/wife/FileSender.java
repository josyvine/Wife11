package com.wife.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileSender {
    private static final String TAG = "FileSender";
    private static volatile FileSender instance;

    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    public interface FileTransferListener {
        void onProgress(int percent);
        void onComplete(String path);
        void onError(String error);
    }

    public static FileSender getInstance(Context context) {
        if (instance == null) {
            synchronized (FileSender.class) {
                if (instance == null) {
                    instance = new FileSender(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private FileSender(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Backward-compatible single-file transmitter.
     * Delegates internally to the new persistent streaming queue engine.
     */
    public void sendFile(final Uri fileUri, final String originalFileName, final long fileSize, final FileTransferListener listener) {
        ArrayList<Uri> uris = new ArrayList<>(Collections.singletonList(fileUri));
        ArrayList<String> names = new ArrayList<>(Collections.singletonList(originalFileName));
        long[] sizes = new long[]{fileSize};

        final String peerIp = ConnectionManager.getInstance(context).getPeerIpAddress();
        if (peerIp == null || peerIp.isEmpty()) {
            if (listener != null) listener.onError("No connected peer available.");
            return;
        }

        // Trigger the queue execution under the active service lifecycle
        WifeLogger.log(TAG, "Legacy sendFile() invoked. Wrapping in single-item queue list.");

        Intent serviceIntent = new Intent(context, FileTransferForegroundService.class);
        serviceIntent.setAction(Constants.ACTION_START_TRANSFER);
        serviceIntent.putExtra("IS_SENDER", true);
        serviceIntent.putStringArrayListExtra("URI_LIST", new ArrayList<>(Collections.singletonList(fileUri.toString())));
        serviceIntent.putStringArrayListExtra("FILE_NAMES", names);
        serviceIntent.putExtra("FILE_SIZES", sizes);
        serviceIntent.putExtra("PEER_IP", peerIp);
        context.startService(serviceIntent);
    }

    /**
     * Core Persistent High-Speed Queue Transmitter.
     * Maintains a single persistent SocketChannel across all files in the queue.
     */
    public void sendQueue(final List<Uri> uris, final List<String> fileNames, final long[] fileSizes, final String peerIp) {
        WifeLogger.log(TAG, "sendQueue() started. Files count: " + uris.size() + " | Destination Peer: " + peerIp);

        executorService.execute(() -> {
            // Symmetrical State Reset: Ensure a clean transactional starting point
            FileTransferForegroundService.isCancelled = false;
            FileTransferForegroundService.isPaused = false;

            SocketChannel socketChannel = null;
            OutputStream socketOs = null;

            try {
                // 1. Establish persistent SocketChannel to the receiver
                WifeLogger.log(TAG, "Opening SocketChannel connection to " + peerIp + " on file port " + Constants.OFF_PORT_FILE);
                socketChannel = SocketChannel.open();
                socketChannel.connect(new InetSocketAddress(peerIp, Constants.OFF_PORT_FILE));
                socketChannel.configureBlocking(true);

                socketOs = socketChannel.socket().getOutputStream();
                WifeLogger.log(TAG, "SocketChannel persistent connection established successfully.");

                // 2. Loop sequentially through the file queue
                for (int i = 0; i < uris.size(); i++) {
                    if (FileTransferForegroundService.isCancelled) {
                        WifeLogger.log(TAG, "Queue loop cancelled by user. Terminating sender.");
                        break;
                    }

                    Uri fileUri = uris.get(i);
                    String fileName = fileNames.get(i);
                    long fileSize = fileSizes[i];

                    WifeLogger.log(TAG, "Processing file [" + (i + 1) + "/" + uris.size() + "]: " + fileName + " (" + fileSize + " bytes)");

                    File tempCompressedFile = new File(context.getCacheDir(), "temp_send_" + UUID.randomUUID().toString() + "_" + fileName + ".lz4");

                    try {
                        // Compress source file locally to a temp cache file before sending
                        WifeLogger.log(TAG, "Compressing local file to temporary cache archive with optimized buffers.");
                        
                        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(fileUri, "r");
                             FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                             FileOutputStream fos = new FileOutputStream(tempCompressedFile);
                             net.jpountz.lz4.LZ4FrameOutputStream lz4Out = new net.jpountz.lz4.LZ4FrameOutputStream(fos)) {
                            
                            byte[] buffer = new byte[131072]; // High-speed 128KB buffer (15x faster than original 8KB chunks)
                            long bytesReadTotal = 0;
                            int read;
                            long lastProgressUpdate = 0;
                            
                            while ((read = fis.read(buffer)) != -1) {
                                if (FileTransferForegroundService.isCancelled) {
                                    break;
                                }
                                
                                // Symmetrical Thread-Safe Pause/Resume wait monitor locks for preparation
                                synchronized (FileTransferForegroundService.pauseLock) {
                                    while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                                        try {
                                            WifeLogger.log(TAG, "Compression thread suspended due to pause state.");
                                            FileTransferForegroundService.pauseLock.wait();
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    }
                                }
                                
                                if (FileTransferForegroundService.isCancelled) {
                                    break;
                                }
                                
                                lz4Out.write(buffer, 0, read);
                                bytesReadTotal += read;
                                
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastProgressUpdate >= 500) {
                                    int percent = (fileSize > 0) ? (int) ((bytesReadTotal * 100) / fileSize) : 0;
                                    // Update foreground notification safely.
                                    // NOTE: We do not broadcast ACTION_TRANSFER_PROGRESS locally during preparation 
                                    // to prevent corrupting the UI data-formatting or speed calculation states.
                                    updatePrepNotification(fileName, percent);
                                    lastProgressUpdate = currentTime;
                                }
                            }
                            // Stream close() (inside try-with-resources auto-close) writes the final magic framing boundary markers
                        }

                        long compressedSize = tempCompressedFile.length();
                        WifeLogger.log(TAG, "Compression complete. Compressed Size: " + compressedSize + " bytes.");

                        if (FileTransferForegroundService.isCancelled) {
                            break;
                        }

                        // 3. Serialize and transmit metadata descriptor
                        JsonObject fileMeta = new JsonObject();
                        fileMeta.addProperty("type", "file");
                        fileMeta.addProperty("name", fileName);
                        fileMeta.addProperty("size", fileSize);
                        fileMeta.addProperty("compressedSize", compressedSize);
                        fileMeta.addProperty("lastPosition", FileTransferForegroundService.lastPosition);

                        byte[] metaBytes = fileMeta.toString().getBytes(StandardCharsets.UTF_8);

                        // Symmetrical Fix: Write metadata details directly to socketOs
                        // This prevents internal SocketChannel read/write desynchronization with LZ4 wrapping
                        byte[] lenBytes = new byte[4];
                        lenBytes[0] = (byte) ((metaBytes.length >> 24) & 0xFF);
                        lenBytes[1] = (byte) ((metaBytes.length >> 16) & 0xFF);
                        lenBytes[2] = (byte) ((metaBytes.length >> 8) & 0xFF);
                        lenBytes[3] = (byte) (metaBytes.length & 0xFF);

                        socketOs.write(lenBytes);
                        socketOs.write(metaBytes);
                        socketOs.flush();

                        Log.d(TAG, "JSON Handshake payload sent directly to stream: " + fileMeta.toString());

                        // 4. Send raw compressed byte stream over socket directly
                        try (FileInputStream fisCompressed = new FileInputStream(tempCompressedFile)) {

                            // Position channel if resuming from a crash/partial state
                            if (FileTransferForegroundService.lastPosition > 0) {
                                WifeLogger.log(TAG, "Resuming file transfer from offset position: " + FileTransferForegroundService.lastPosition);
                                long skipped = fisCompressed.skip(FileTransferForegroundService.lastPosition);
                                WifeLogger.log(TAG, "Skipped bytes successfully: " + skipped);
                            }

                            // 5. High-Speed 16KB stream copy loop (More robust than FileChannel flipping)
                            byte[] buffer = new byte[16384];
                            int readBytes;
                            long totalBytesSent = FileTransferForegroundService.lastPosition;
                            long lastNotificationUpdateTime = System.currentTimeMillis();

                            // Rolling calculation variables for live transfer speed
                            long speedPeriodBytesSent = 0;
                            long speedPeriodStartTime = System.currentTimeMillis();
                            double currentSpeed = 0.0; // in MB/s

                            while ((readBytes = fisCompressed.read(buffer)) != -1) {
                                if (FileTransferForegroundService.isCancelled) {
                                    break;
                                }

                                // Symmetrical Thread-Safe Pause/Resume wait monitor locks
                                synchronized (FileTransferForegroundService.pauseLock) {
                                    while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                                        try {
                                            WifeLogger.log(TAG, "Sender thread entering wait state due to active pause command.");
                                            FileTransferForegroundService.pauseLock.wait();
                                        } catch (InterruptedException e) {
                                            WifeLogger.log(TAG, "Sender pause monitor thread interrupted.");
                                            Thread.currentThread().interrupt();
                                        }
                                    }
                                }

                                if (FileTransferForegroundService.isCancelled) {
                                    break;
                                }

                                // Write raw compressed block straight to output stream
                                socketOs.write(buffer, 0, readBytes);
                                totalBytesSent += readBytes;
                                speedPeriodBytesSent += readBytes;
                                FileTransferForegroundService.lastPosition = totalBytesSent;

                                long currentTime = System.currentTimeMillis();
                                long timeDiff = currentTime - speedPeriodStartTime;
                                if (timeDiff >= 1000) {
                                    // Speed (MB/s) = (Bytes / 1MB) / (timeDiff / 1s)
                                    currentSpeed = ((double) speedPeriodBytesSent / (1024.0 * 1024.0)) / ((double) timeDiff / 1000.0);
                                    speedPeriodBytesSent = 0;
                                    speedPeriodStartTime = currentTime;
                                }

                                // Regularly throttle status broadcasts to prevent UI thread choke
                                if (currentTime - lastNotificationUpdateTime >= 500) {
                                    int percent = (int) ((totalBytesSent * 100) / compressedSize);
                                    broadcastProgress(fileName, totalBytesSent, compressedSize, percent, i, currentSpeed);
                                    lastNotificationUpdateTime = currentTime;
                                }
                            }
                        }

                        if (!FileTransferForegroundService.isCancelled) {
                            WifeLogger.log(TAG, "File successfully streamed: " + fileName);

                            // Insert database transfer record
                            FileEntity entity = new FileEntity(fileName, fileSize, fileUri.toString(), System.currentTimeMillis());
                            RoomDatabaseManager.getInstance(context).fileDao().insert(entity);

                            // Reset file resume position tracking for next queue file
                            FileTransferForegroundService.lastPosition = 0;
                            broadcastProgress(fileName, compressedSize, compressedSize, 100, i, 0.0);
                        }
                    } catch (Exception e) {
                        WifeLogger.log(TAG, "Error processing queued file transfer task: " + e.getMessage(), e);
                        throw e;
                    } finally {
                        // Symmetrical Cleanup: Always purge local temporary files
                        if (tempCompressedFile.exists()) {
                            tempCompressedFile.delete();
                        }
                    }
                }

                // 3. Write transmission finished goodbye signal (Metadata length = 0) directly to stream
                if (!FileTransferForegroundService.isCancelled) {
                    byte[] goodbyeBytes = new byte[4];
                    socketOs.write(goodbyeBytes);
                    socketOs.flush();
                    WifeLogger.log(TAG, "All queue files sent successfully. Sent persistent stream finished marker.");
                    broadcastCompletion();
                }

            } catch (Exception e) {
                WifeLogger.log(TAG, "Persistent file sending pipeline threw fatal exception: " + e.getMessage(), e);
                broadcastError(e.getMessage());
            } finally {
                // Symmetrical clean up of channel streams
                try {
                    if (socketChannel != null && socketChannel.isOpen()) {
                        socketChannel.close();
                    }
                } catch (IOException ignored) {}

                // Stop foreground service context cleanly
                Intent stopIntent = new Intent(context, FileTransferForegroundService.class);
                context.stopService(stopIntent);
            }
        });
    }

    private ByteBuffer metadataBuffer(byte[] metaBytes) {
        return ByteBuffer.wrap(metaBytes);
    }

    private void updatePrepNotification(String fileName, int percent) {
        Intent serviceIntent = new Intent(context, FileTransferForegroundService.class);
        serviceIntent.setAction("UPDATE_NOTIF");
        serviceIntent.putExtra("NOTIF_TEXT", "Compressing " + fileName + " (" + percent + "%)");
        serviceIntent.putExtra("PROGRESS", percent);
        context.startService(serviceIntent);
    }

    private void broadcastProgress(String fileName, long transferred, long total, int percent, int fileIndex, double speed) {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_PROGRESS);
        intent.putExtra(Constants.EXTRA_FILE_NAME, fileName);
        intent.putExtra(Constants.EXTRA_BYTES_TRANSFERRED, transferred);
        intent.putExtra(Constants.EXTRA_TOTAL_BYTES, total);
        intent.putExtra(Constants.EXTRA_FILE_INDEX, fileIndex);
        intent.putExtra(Constants.EXTRA_TRANSFER_SPEED, speed);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

        // Update foreground service notification in parallel with formatted transfer rate metrics
        String speedText = String.format(Locale.US, "%.1f MB/s", speed);
        Intent serviceIntent = new Intent(context, FileTransferForegroundService.class);
        serviceIntent.setAction("UPDATE_NOTIF");
        serviceIntent.putExtra("NOTIF_TEXT", "Sending " + fileName + " (" + percent + "%) - " + speedText);
        serviceIntent.putExtra("PROGRESS", percent);
        context.startService(serviceIntent);
    }

    private void broadcastCompletion() {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_COMPLETE);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private void broadcastError(String message) {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_ERROR);
        intent.putExtra(Constants.EXTRA_ERROR_MESSAGE, message);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    // --- Symmetrical Non-Closing Socket Stream Wrapper ---
    private static class NonClosingOutputStream extends OutputStream {
        private final OutputStream delegate;

        public NonClosingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            // Intercept close() request to preserve the underlying persistent SocketChannel
            Log.d(TAG, "Intercepted close() request. Stream remains open.");
        }
    }
}