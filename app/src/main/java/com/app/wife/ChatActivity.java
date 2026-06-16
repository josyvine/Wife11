package com.wife.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.wife.app.databinding.ActivityChatBinding;

import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends AppCompatActivity implements ChatManager.MessageListener {

    private ActivityChatBinding binding;
    private ChatAdapter adapter;
    private final List<MessageEntity> messagesList = new ArrayList<>();
    private RoomDatabaseManager db;
    private String selfId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WifeLogger.log("ChatActivity", "onCreate() invoked. Constructing Chat Session UI components.");

        db = RoomDatabaseManager.getInstance(this);
        selfId = Utils.getDeviceId(this);
        WifeLogger.log("ChatActivity", "Local Hardware Signature ID resolved: " + selfId);

        setupToolbar();
        setupRecyclerView();

        binding.btnSendChatMessage.setOnClickListener(v -> {
            String text = binding.etChatMessage.getText().toString().trim();
            if (!TextUtils.isEmpty(text)) {
                WifeLogger.log("ChatActivity", "User triggered SEND button. Outgoing text length: " + text.length());
                MessageSender.getInstance(this).sendMessage(text);
                binding.etChatMessage.setText("");
            } else {
                WifeLogger.log("ChatActivity", "User tapped SEND button, but text input field was empty.");
            }
        });

        loadHistory();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarChat);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarChat.setNavigationOnClickListener(v -> {
            WifeLogger.log("ChatActivity", "Navigation back button clicked. Exiting Chat Session.");
            onBackPressed();
        });
    }

    private void setupRecyclerView() {
        WifeLogger.log("ChatActivity", "Initializing ChatAdapter and binding LayoutManager to RecyclerView.");
        adapter = new ChatAdapter(this, messagesList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Always align chats from the bottom up like modern chats
        binding.rvChatHistory.setLayoutManager(layoutManager);
        binding.rvChatHistory.setAdapter(adapter);
    }

    private void loadHistory() {
        WifeLogger.log("ChatActivity", "Accessing local SQLite database to load chat logs...");
        try {
            // Query database on separate or main thread allowed
            List<MessageEntity> history = db.messageDao().getAllMessages();
            messagesList.clear();
            
            WifeLogger.log("ChatActivity", "Successfully retrieved " + history.size() + " messages from Room database.");
            
            // Reverse because we queried DESC from database for chronological ordering in list
            for (int i = history.size() - 1; i >= 0; i--) {
                messagesList.add(history.get(i));
            }
            
            adapter.notifyDataSetChanged();
            scrollToBottom();
            WifeLogger.log("ChatActivity", "Dataset loaded and adapter notifications dispatched.");
        } catch (Exception e) {
            WifeLogger.log("ChatActivity", "Failed to query local database chat history: " + e.getMessage(), e);
        }
    }

    private void scrollToBottom() {
        if (!messagesList.isEmpty()) {
            WifeLogger.log("ChatActivity", "Scrolling list view focus to position index: " + (messagesList.size() - 1));
            binding.rvChatHistory.smoothScrollToPosition(messagesList.size() - 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        WifeLogger.log("ChatActivity", "onResume() invoked. Registering ChatActivity observer to ChatManager listener list.");
        ChatManager.getInstance(this).registerMessageListener(this);

        // Clear unread notification counts and save state to clear the unread badge
        try {
            SharedPreferences prefs = getSharedPreferences("WifeSettings", MODE_PRIVATE);
            prefs.edit().putInt("unread_count", 0).apply();
            WifeLogger.log("ChatActivity", "Unread chat message count reset to 0 inside local preferences.");
        } catch (Exception e) {
            WifeLogger.log("ChatActivity", "Error resetting unread message counts: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WifeLogger.log("ChatActivity", "onPause() invoked. Unregistering ChatActivity observer from ChatManager listener list.");
        ChatManager.getInstance(this).unregisterMessageListener(this);
    }

    @Override
    public void onMessageReceived(MessageEntity message) {
        WifeLogger.log("ChatActivity", "onMessageReceived callback triggered on ChatActivity. From: " + message.getSender() + " | Text: " + message.getText());
        runOnUiThread(() -> {
            try {
                messagesList.add(message);
                adapter.notifyDataSetChanged();
                scrollToBottom();
                WifeLogger.log("ChatActivity", "Real-time list update redrawn. Current list size: " + messagesList.size());
            } catch (Exception e) {
                WifeLogger.log("ChatActivity", "Error rendering real-time message bubble update: " + e.getMessage(), e);
            }
        });
    }
}