package com.loki.agent.llm;

import com.loki.agent.tool.ToolCall;

import java.util.List;
import java.util.Map;

public record LlmResponse(
        String content,
        List<ToolCall> toolCalls,
        String thinking,
        Map<String, Object> providerFields
) {
    public LlmResponse(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null, Map.of());
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
