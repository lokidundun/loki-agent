package com.loki.agent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private final SessionStore store;
    private final Map<String, Session> cache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionManager(SessionStore store) {
        this.store = store;
    }

    public Session getOrCreate(String key) {
        return cache.computeIfAbsent(key, k -> {
            Session session = new Session(k);
            store.upsertSession(k, Instant.now(), "{}");

            // Load existing messages from SQLite
            List<Map<String, Object>> rows = store.fetchMessages(k);
            if (!rows.isEmpty()) {
                session.loadMessages(rows.stream()
                        .map(this::rowToMessage)
                        .toList());
                log.debug("Loaded {} messages for session {}", rows.size(), k);
            }

            return session;
        });
    }

    public void appendMessages(Session session) {
        List<Map<String, Object>> unsaved = session.unsavedMessages();
        if (unsaved.isEmpty()) return;

        Instant now = Instant.now();
        String ts = now.toString();

        for (Map<String, Object> msg : unsaved) {
            int seq = store.nextSeq(session.key());
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            String extra = toJson(Map.of());
            store.insertMessage(session.key(), seq, role, content, null, extra, ts);
        }

        store.updatePresence(session.key(), now);
        unsaved.clear();
        log.debug("Persisted {} messages for session {}", unsaved.size(), session.key());
    }

    private Map<String, Object> rowToMessage(Map<String, Object> row) {
        return Map.of(
                "role", row.get("role") != null ? row.get("role") : "",
                "content", row.get("content") != null ? row.get("content") : ""
        );
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
