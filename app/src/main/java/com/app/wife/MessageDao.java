package com.wife.app;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MessageDao {
    @Insert
    void insert(MessageEntity message);

    @Query("SELECT * FROM messages WHERE (sender = :peer AND receiver = :self) OR (sender = :self AND receiver = :peer) ORDER BY timestamp ASC")
    List<MessageEntity> getChatHistory(String peer, String self);

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    List<MessageEntity> getAllMessages();

    // Local Delete: Purges a message from the local database using its primary ID key
    @Query("DELETE FROM messages WHERE id = :messageId")
    void deleteById(long messageId);

    // Global Unsend: Purges a message using its unique millisecond timestamp
    @Query("DELETE FROM messages WHERE timestamp = :targetTimestamp")
    void deleteByTimestamp(long targetTimestamp);
}