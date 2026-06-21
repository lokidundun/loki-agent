package com.loki.agent.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        initSchema();
    }

    private void initSchema() {
        try {
            String schema = new ClassPathResource("schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            for (String stmt : schema.split(";")) {
                stmt = stmt.trim();
                if (!stmt.isEmpty()) {
                    jdbc.execute(stmt);
                }
            }
            log.info("SessionStore schema initialized");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load schema.sql", e);
        }
    }

    public void upsertSession(String key, Instant now, String metadataJson) {
        jdbc.update("""
                INSERT INTO sessions (key, created_at, updated_at, metadata, next_seq)
                VALUES (?, ?, ?, ?, 0)
                ON CONFLICT(key) DO UPDATE SET updated_at = excluded.updated_at
                """, key, now.toString(), now.toString(), metadataJson);
    }

    public int nextSeq(String sessionKey) {
        Integer seq = jdbc.queryForObject(
                "SELECT next_seq FROM sessions WHERE key = ?", Integer.class, sessionKey);
        int next = seq != null ? seq : 0;
        jdbc.update("UPDATE sessions SET next_seq = ? WHERE key = ?",
                next + 1, sessionKey);
        return next;
    }

    public void insertMessage(String sessionKey, int seq, String role,
                              String content, String toolChainJson,
                              String extraJson, String ts) {
        String id = sessionKey + ":" + seq;
        jdbc.update("""
                INSERT INTO messages (id, session_key, seq, role, content, tool_chain, extra, ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, sessionKey, seq, role, content, toolChainJson, extraJson, ts);
    }

    public List<Map<String, Object>> fetchMessages(String sessionKey) {
        return jdbc.queryForList(
                "SELECT * FROM messages WHERE session_key = ? ORDER BY seq ASC",
                sessionKey);
    }

    public void updatePresence(String sessionKey, Instant lastUserAt) {
        jdbc.update("UPDATE sessions SET last_user_at = ? WHERE key = ?",
                lastUserAt.toString(), sessionKey);
    }

    public Instant mostRecentUserAt(String sessionKey) {
        String ts = jdbc.queryForObject(
                "SELECT last_user_at FROM sessions WHERE key = ?",
                String.class, sessionKey);
        return ts != null ? Instant.parse(ts) : null;
    }
}
