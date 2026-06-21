package com.loki.agent.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Session {

    private final String key;
    private final List<Map<String, Object>> messages;
    private Instant createdAt;
    private Instant updatedAt;
    private int lastConsolidated;
    private final List<Map<String, Object>> unsavedMessages = new ArrayList<>();

    public Session(String key) {
        this.key = key;
        this.messages = new ArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String key() { return key; }
    public List<Map<String, Object>> messages() { return messages; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public int lastConsolidated() { return lastConsolidated; }
    public List<Map<String, Object>> unsavedMessages() { return unsavedMessages; }

    public void setCreatedAt(Instant t) { this.createdAt = t; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
    public void setLastConsolidated(int v) { this.lastConsolidated = v; }

    public void addMessage(String role, String content) {
        Map<String, Object> msg = Map.of("role", role, "content", content);
        messages.add(msg);
        unsavedMessages.add(msg);
        updatedAt = Instant.now();
    }

    public List<Map<String, Object>> getHistory(int maxMessages) {
        int size = messages.size();
        int start = Math.max(0, size - maxMessages);
        return new ArrayList<>(messages.subList(start, size));
    }

    public void loadMessages(List<Map<String, Object>> stored) {
        messages.clear();
        messages.addAll(stored);
    }

    public void clear() {
        messages.clear();
        unsavedMessages.clear();
    }
}
