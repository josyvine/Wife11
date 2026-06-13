package com.wife.app;

import android.content.Context;
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
        if (peerIp == null || peerIp.isEmpty()) {
            Log.e(TAG, "Cannot send message. No active connected peer IP.");
            return;
        }

        final String selfId = Utils.getDeviceId(context);
        final String messageId = UUID.randomUUID().toString();
        final long timestamp = System.currentTimeMillis();

        // Save locally to Database and Transmit to Peer in the background executor thread
        executorService.execute(() -> {
            try {
                // 1. Save locally to Database safely on background thread
                MessageEntity entity = new MessageEntity(selfId, "peer_device", text, timestamp);
                RoomDatabaseManager.getInstance(context).messageDao().insert(entity);

                // 2. Transmit to Peer
                try (Socket socket = new Socket(peerIp, Constants.OFF_PORT_TEXT);
                     OutputStream os = socket.getOutputStream();
                     PrintWriter pw = new PrintWriter(os, true)) {

                    JsonObject json = new JsonObject();
                    json.addProperty("type", "message");
                    json.addProperty("id", messageId);
                    json.addProperty("sender", selfId);
                    json.addProperty("time", timestamp);
                    json.addProperty("text", text);

                    pw.println(json.toString());
                    Log.d(TAG, "Sent message packet to " + peerIp + ": " + text);
                    
                    // Mirror back to active ChatActivity
                    ChatManager.getInstance(context).notifyMessageReceived(entity);

                } catch (Exception e) {
                    Log.e(TAG, "Failed sending socket packet to " + peerIp + ": " + e.getMessage());
                }

            } catch (Exception dbException) {
                Log.e(TAG, "Failed saving message locally: " + dbException.getMessage());
            }
        });
    }
}