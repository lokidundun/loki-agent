package com.loki.agent.tool;

import java.util.Map;

public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments,
        String result
) {
    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this(id, name, arguments, "");
    }
}
