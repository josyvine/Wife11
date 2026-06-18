package com.wife.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;

public class FileTransferForegroundService extends Service {
    private static final String TAG = "FileTransferService";
    private static final String CHANNEL_ID = "WifeFileTransferChannel";
    private static final int NOTIF_ID = 1004;

    // --- Symmetrical Monitor Locks & Shared Volatile State ---
    public static final Object pauseLock = new Object();
    public static volatile boolean isPaused = false;
    public static volatile boolean isCancelled = false;
    public static volatile long lastPosition = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        WifeLogger.log(TAG, "onCreate() invoked. FileTransferForegroundService initialized.");
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
                    // Reset global transaction flags for a fresh start
                    isCancelled = false;
                    isPaused = false;
                    lastPosition = 0;

                    boolean isSender = intent.getBooleanExtra("IS_SENDER", false);
                    WifeLogger.log(TAG, "ACTION_START_TRANSFER initiated. Transfer Role: " + (isSender ? "Sender" : "Receiver"));

                    // Elevate service to Foreground using DATA_SYNC constraints
                    Notification notification = buildProgressNotification("Initializing transfer stream...", 0, true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                        startForeground(NOTIF_ID, notification);
                    }

                    if (isSender) {
                        // Extract persistent queue details
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
                        // Bind server socket channel receiver loop
                        WifeLogger.log(TAG, "Spawning ServerSocketChannel persistent receiver thread.");
                        FileReceiver.startServer(this);
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
}