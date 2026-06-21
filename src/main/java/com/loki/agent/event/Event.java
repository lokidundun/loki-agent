package com.loki.agent.event;

import java.time.Instant;
import java.util.Map;

public record Event(String type, Map<String, Object> data, Instant timestamp) {
    public Event(String type, Map<String, Object> data) {
        this(type, data, Instant.now());
    }
}
