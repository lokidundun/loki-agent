package com.loki.agent.a2a;

import java.util.List;
import java.util.Map;

/**
 * Describes an agent's capabilities for peer discovery.
 */
public record AgentCard(
        String name,
        String version,
        String description,
        List<String> tools,
        String endpoint
) {
    public Map<String, Object> toMap() {
        return Map.of(
                "name", name,
                "version", version,
                "description", description,
                "tools", tools,
                "endpoint", endpoint
        );
    }
}
