package com.loki.agent.agent;

import com.loki.agent.tool.ToolCall;

import java.util.List;

public record ReasonerResult(
        String reply,
        List<ToolCall> invocations,
        String thinking,
        List<String> toolsUsed
) {}
