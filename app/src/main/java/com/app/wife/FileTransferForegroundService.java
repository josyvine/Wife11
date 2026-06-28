package com.wife.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class FileTransferForegroundService extends Service {
    private static final String TAG = "FileTransferService";
    private static final String CHANNEL_ID = "WifeFileTransferChannel";
    private static final int NOTIF_ID = 1004;

    // --- Symmetrical Monitor Locks & Shared Volatile State ---
    public static final Object pauseLock = new Object();
    public static volatile boolean isPaused = false;
    public static volatile boolean isCancelled = false;
    public static volatile long lastPosition = 0;

    // Global transaction reference counter to track active parallel send/receive sessions
    public static final AtomicInteger activeTransfersCount = new AtomicInteger(0);

    // System IPC Throttling variables (Rule 1 Fix: ANR Prevention)
    private static final long NOTIFICATION_THROTTLE_MS = 2000;
    private static volatile long lastNotificationTime = 0;

    // Broadcast receiver to pause file transfers during active calls to prevent bandwidth saturation
    private final BroadcastReceiver callStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            if ("com.wife.app.ACTION_CALL_ACTIVE".equals(action)) {
                WifeLogger.log(TAG, "Call active broadcast received. Suspending background file transfer stream.");
                isPaused = true;
                updateNotification("Transfer suspended due to active call", 0, true);
            } else if ("com.wife.app.ACTION_CALL_INACTIVE".equals(action)) {
                WifeLogger.log(TAG, "Call ended broadcast received. Resuming suspended background file transfer stream.");
                isPaused = false;
                synchronized (pauseLock) {
                    pauseLock.notifyAll();
                }
                updateNotification("Resuming transfer stream...", 0, true);
            }
        }
    };

    /**
     * Decentralized termination controller. Decrements the active transaction count 
     * and safely stops the service only when all parallel streams have finalized.
     */
    public static void decrementAndCheckStop(Context context) {
        int active = activeTransfersCount.decrementAndGet();
        if (active < 0) {
            activeTransfersCount.set(0);
            active = 0;
        }
        WifeLogger.log(TAG, "decrementAndCheckStop() executed. Remaining active transfer sessions: " + active);
        if (active == 0) {
            WifeLogger.log(TAG, "No active transactions remaining. Halting FileTransferForegroundService.");
            Intent stopIntent = new Intent(context, FileTransferForegroundService.class);
            context.stopService(stopIntent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        WifeLogger.log(TAG, "onCreate() invoked. FileTransferForegroundService initialized.");

        // Register the call-state mutual exclusion receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.wife.app.ACTION_CALL_ACTIVE");
        filter.addAction("com.wife.app.ACTION_CALL_INACTIVE");
        LocalBroadcastManager.getInstance(this).registerReceiver(callStateReceiver, filter);
        WifeLogger.log(TAG, "Call-state mutual exclusion receiver registered successfully.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            WifeLogger.log(TAG, "onStartCommand() received null Intent. Stopping service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        WifeLogger.log(TAG, "onStartCommand() triggered with Action: " + (action == null ? "None" : action));

        if (action != null) {
            switch (action) {
                case Constants.ACTION_START_TRANSFER:
                    isCancelled = false;
                    isPaused = false;
                    lastPosition = 0;
                    lastNotificationTime = 0; // Reset throttling mark

                    boolean isSender = intent.getBooleanExtra("IS_SENDER", false);
                    WifeLogger.log(TAG, "ACTION_START_TRANSFER initiated. Transfer Role: " + (isSender ? "Sender" : "Receiver"));

                    Notification notification = buildProgressNotification("Initializing transfer stream...", 0, true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                        startForeground(NOTIF_ID, notification);
                    }

                    if (isSender) {
                        ArrayList<String> uriStrings = intent.getStringArrayListExtra("URI_LIST");
                        ArrayList<String> fileNames = intent.getStringArrayListExtra("FILE_NAMES");
                        long[] fileSizes = intent.getLongArrayExtra("FILE_SIZES");
                        String peerIp = intent.getStringExtra("PEER_IP");

                        if (uriStrings != null && !uriStrings.isEmpty() && peerIp != null) {
                            ArrayList<Uri> uris = new ArrayList<>();
                            for (String uriStr : uriStrings) {
                                uris.add(Uri.parse(uriStr));
                            }
                            WifeLogger.log(TAG, "Spawning high-speed FileSender queue thread. Queue size: " + uris.size());
                            FileSender.getInstance(this).sendQueue(uris, fileNames, fileSizes, peerIp);
                        } else {
                            WifeLogger.log(TAG, "Aborted sender initialization: Empty file queue or missing peer destination IP.");
                            stopSelf();
                        }
                    } else {
                        WifeLogger.log(TAG, "Spawning ServerSocketChannel persistent receiver thread.");
                        FileReceiver.startServer(this);
                    }
                    break;

                case "UPDATE_NOTIF":
                    if (isCancelled) {
                        WifeLogger.log(TAG, "UPDATE_NOTIF ignored: Transfer session is inactive. Stopping service.");
                        stopSelf();
                        break;
                    }

                    String notifText = intent.getStringExtra("NOTIF_TEXT");
                    int progressValue = intent.getIntExtra("PROGRESS", 0);

                    // Throttling IPC: Enforces a maximum rate of 1 IPC transaction every 2 seconds to prevent ANR system freezes (Rule 1 Fix)
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastNotificationTime >= NOTIFICATION_THROTTLE_MS) {
                        updateNotification(notifText, progressValue, false);
                        lastNotificationTime = currentTime;
                    }
                    break;

                case Constants.ACTION_PAUSE_TRANSFER:
                    WifeLogger.log(TAG, "ACTION_PAUSE_TRANSFER received. Suspending file stream threads.");
                    isPaused = true;
                    updateNotification("Transfer Paused", 0, true);
                    break;

                case Constants.ACTION_RESUME_TRANSFER:
                    WifeLogger.log(TAG, "ACTION_RESUME_TRANSFER received. Notifying waiting locks.");
                    isPaused = false;
                    synchronized (pauseLock) {
                        pauseLock.notifyAll();
                    }
                    updateNotification("Resuming transfer stream...", 0, true);
                    break;

                case Constants.ACTION_CANCEL_TRANSFER:
                    WifeLogger.log(TAG, "ACTION_CANCEL_TRANSFER received. Purging sockets and shutting down.");
                    isCancelled = true;
                    isPaused = false;
                    synchronized (pauseLock) {
                        pauseLock.notifyAll();
                    }

                    Intent cancelIntent = new Intent(Constants.ACTION_TRANSFER_ERROR);
                    cancelIntent.putExtra(Constants.EXTRA_ERROR_MESSAGE, "Transfer cancelled by user.");
                    LocalBroadcastManager.getInstance(this).sendBroadcast(cancelIntent);

                    stopForeground(true);
                    stopSelf();
                    break;

                default:
                    WifeLogger.log(TAG, "Unrecognized action passed to service: " + action);
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    /**
     * Public helper to allow active background Sender and Receiver threads 
     * to update the persistent foreground notification in real-time.
     */
    public void updateNotification(String contentText, int progress, boolean indeterminate) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && !isCancelled) {
            Notification notification = buildProgressNotification(contentText, progress, indeterminate);
            manager.notify(NOTIF_ID, notification);
        }
    }

    private Notification buildProgressNotification(String contentText, int progress, boolean indeterminate) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Wife File Sharing")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOnlyAlertOnce(true)
                .setOngoing(true);

        if (indeterminate) {
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(100, progress, false);
        }
        return builder.build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Wife File Sharing Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                WifeLogger.log(TAG, "Wife File Sharing Service Notification Channel created.");
            }
        }
    }

    private File getBackupDirectory() {
        File rootDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wife shared/backups");
        } else {
            rootDir = new File(Environment.getExternalStorageDirectory(), "wife shared/backups");
        }
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
        return rootDir;
    }

    @Override
    public void onDestroy() {
        WifeLogger.log(TAG, "onDestroy() invoked. Tearing down file transfer service and cleaning resources.");

        stopForeground(true);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIF_ID);
        }

        isCancelled = true;
        isPaused = false;

        // Unregister the call-state mutual exclusion receiver safely
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(callStateReceiver);
            WifeLogger.log(TAG, "Call-state mutual exclusion receiver unregistered cleanly.");
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error unregistering call-state receiver: " + e.getMessage());
        }

        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        // 1. Force-purge any temporary cache files from internal directory
        File cacheDir = getCacheDir();
        if (cacheDir != null && cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    boolean isTempStream = name.startsWith("temp_send_") || name.startsWith("temp_recv_");
                    boolean isChunkPart = name.startsWith("chunk_") || name.startsWith("temp_chunk_");

                    // FIXED: Symmetrical check prevents deletion of parallel chunks belonging to other active file queues
                    if (isTempStream || (isCancelled && isChunkPart)) {
                        boolean deleted = f.delete();
                        WifeLogger.log(TAG, "Purged temporary cache file during destroy: " + name + " -> " + deleted);
                    }
                }
            }
        }

        // 2. Force-purge any abandoned parallel parts inside the public backups folder (Rule 2 Clean Fix)
        try {
            File backupDir = getBackupDirectory();
            if (backupDir.exists()) {
                File[] files = backupDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        boolean isTempStream = name.startsWith("temp_send_") || name.startsWith("temp_recv_");
                        boolean isChunkPart = name.startsWith("chunk_") || name.startsWith("temp_chunk_");

                        // FIXED: Symmetrical check prevents deletion of parallel chunks belonging to other active file queues
                        if (isTempStream || (isCancelled && isChunkPart)) {
                            boolean deleted = f.delete();
                            WifeLogger.log(TAG, "Purged temporary backups file during destroy: " + name + " -> " + deleted);
                        }
                    }
                }
            }
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed executing public backups purge during destroy: " + e.getMessage());
        }

        super.onDestroy();
    }
}