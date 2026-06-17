package com.wife.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonObject;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessageSender {
    private static final String TAG = "MessageSender";
    private static volatile MessageSender instance;

    private final Context context;
    private final ExecutorService executorService;

    public static MessageSender getInstance(Context context) {
        if (instance == null) {
            synchronized (MessageSender.class) {
                if (instance == null) {
                    instance = new MessageSender(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private MessageSender(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public void sendMessage(final String text) {
        final String peerIp = ConnectionManager.getInstance(context).getPeerIpAddress();
        WifeLogger.log(TAG, "sendMessage() invoked. Resolved Peer IP for destination: " + (peerIp == null || peerIp.isEmpty() ? "None (Empty)" : peerIp));

        if (peerIp == null || peerIp.isEmpty()) {
            Log.e(TAG, "Cannot send message. No active connected peer IP.");
            WifeLogger.log(TAG, "sendMessage() aborted: Abrupt exit due to missing connected Peer IP.");
            return;
        }

        final String selfId = Utils.getDeviceId(context);
        final String messageId = UUID.randomUUID().toString();
        final long timestamp = System.currentTimeMillis();

        WifeLogger.log(TAG, "Generated Message Metadata. ID: " + messageId + " | Self ID: " + selfId + " | Timestamp: " + timestamp + " | Queueing background transmission task.");

        // Save locally to Database and Transmit to Peer in the background executor thread
        executorService.execute(() -> {
            WifeLogger.log(TAG, "Outbound message transmission task started execution on background worker thread.");
            try {
                // 1. Save locally to Database safely on background thread
                MessageEntity entity = new MessageEntity(selfId, "peer_device", text, timestamp);
                RoomDatabaseManager.getInstance(context).messageDao().insert(entity);
                WifeLogger.log(TAG, "Successfully saved sent message locally to SQLite database.");

                // 2. Transmit to Peer
                WifeLogger.log(TAG, "Attempting outbound socket connection to " + peerIp + " on text communication port: " + Constants.OFF_PORT_TEXT);
                try (Socket socket = new Socket(peerIp, Constants.OFF_PORT_TEXT);
                     OutputStream os = socket.getOutputStream();
                     PrintWriter pw = new PrintWriter(os, true)) {

                    WifeLogger.log(TAG, "Socket connection established successfully with " + peerIp + " on port " + Constants.OFF_PORT_TEXT);

                    SharedPreferences prefs = context.getSharedPreferences("WifeSettings", Context.MODE_PRIVATE);
                    String customName = prefs.getString("custom_alias", Utils.getDeviceModel());

                    JsonObject json = new JsonObject();
                    json.addProperty("type", "message");
                    json.addProperty("id", messageId);
                    json.addProperty("sender", selfId);
                    json.addProperty("senderName", customName);
                    json.addProperty("time", timestamp);
                    json.addProperty("text", text);

                    String payload = json.toString();
                    WifeLogger.log(TAG, "Prepared JSON text packet payload: " + payload);

                    pw.println(payload);
                    pw.flush();
                    Log.d(TAG, "Sent message packet to " + peerIp + ": " + text);
                    WifeLogger.log(TAG, "Sent message packet successfully written and flushed to output stream.");
                    
                    // Mirror back to active ChatActivity
                    WifeLogger.log(TAG, "Notifying local ChatManager observers of successful transmission to update chat bubbles.");
                    ChatManager.getInstance(context).notifyMessageReceived(entity);

                } catch (Exception e) {
                    Log.e(TAG, "Failed sending socket packet to " + peerIp + ": " + e.getMessage());
                    WifeLogger.log(TAG, "Outbound socket delivery failed. Target Peer: " + peerIp + " | Port: " + Constants.OFF_PORT_TEXT + " | Exception: " + e.getMessage(), e);
                }

            } catch (Exception dbException) {
                Log.e(TAG, "Failed saving message locally: " + dbException.getMessage());
                WifeLogger.log(TAG, "Failed to write sent message locally to local database: " + dbException.getMessage(), dbException);
            }
        });
    }
}