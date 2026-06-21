package com.loki.agent.memory;

import java.util.List;
import java.util.Map;

/**
 * A chunk of memory content with its TF-IDF vector representation.
 */
public record MemoryChunk(
        String id,
        String content,
        String source,
        Map<String, Double> vector
) {

    /**
     * Serialize vector to a JSON-friendly map.
     */
    public Map<String, Object> toStoreMap() {
        return Map.of(
                "id", id,
                "content", content,
                "source", source,
                "vector", vector
        );
    }

    /**
     * Deserialize from a JSON map.
     */
    @SuppressWarnings("unchecked")
    public static MemoryChunk fromStoreMap(Map<String, Object> map) {
        return new MemoryChunk(
                (String) map.get("id"),
                (String) map.get("content"),
                (String) map.get("source"),
                (Map<String, Double>) map.get("vector")
        );
    }
}
