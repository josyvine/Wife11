package com.wife.app;

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

        db = RoomDatabaseManager.getInstance(this);
        selfId = Utils.getDeviceId(this);

        setupToolbar();
        setupRecyclerView();

        binding.btnSendChatMessage.setOnClickListener(v -> {
            String text = binding.etChatMessage.getText().toString().trim();
            if (!TextUtils.isEmpty(text)) {
                MessageSender.getInstance(this).sendMessage(text);
                binding.etChatMessage.setText("");
            }
        });

        loadHistory();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarChat);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarChat.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void setupRecyclerView() {
        adapter = new ChatAdapter(this, messagesList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Always align chats from the bottom up like modern chats
        binding.rvChatHistory.setLayoutManager(layoutManager);
        binding.rvChatHistory.setAdapter(adapter);
    }

    private void loadHistory() {
        // Query database on separate or main thread allowed
        List<MessageEntity> history = db.messageDao().getAllMessages();
        messagesList.clear();
        
        // Reverse because we queried DESC from database for chronological ordering in list
        for (int i = history.size() - 1; i >= 0; i--) {
            messagesList.add(history.get(i));
        }
        
        adapter.notifyDataSetChanged();
        scrollToBottom();
    }

    private void scrollToBottom() {
        if (!messagesList.isEmpty()) {
            binding.rvChatHistory.smoothScrollToPosition(messagesList.size() - 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatManager.getInstance(this).registerMessageListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatManager.getInstance(this).unregisterMessageListener(this);
    }

    @Override
    public void onMessageReceived(MessageEntity message) {
        runOnUiThread(() -> {
            messagesList.add(message);
            adapter.notifyDataSetChanged();
            scrollToBottom();
        });
    }
}