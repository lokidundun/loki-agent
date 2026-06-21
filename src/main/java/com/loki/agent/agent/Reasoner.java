package com.loki.agent.agent;

import com.loki.agent.llm.LlmProvider;
import com.loki.agent.llm.LlmResponse;
import com.loki.agent.tool.ToolCall;
import com.loki.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class Reasoner {

    private static final Logger log = LoggerFactory.getLogger(Reasoner.class);

    private final LlmProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final int maxIterations;

    public Reasoner(LlmProvider llmProvider, ToolRegistry toolRegistry,
                    com.loki.agent.config.AgentConfig config) {
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.maxIterations = config.maxIterations();
    }

    public ReasonerResult run(List<Map<String, Object>> messages,
                              List<Map<String, Object>> toolSchemas,
                              String model) {
        List<Map<String, Object>> workingMessages = new ArrayList<>(messages);
        List<ToolCall> allInvocations = new ArrayList<>();
        List<String> toolsUsed = new ArrayList<>();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            log.debug("Reasoner iteration {}/{}", iteration + 1, maxIterations);

            LlmResponse resp = llmProvider.chat(workingMessages, toolSchemas, model, 4096);

            if (!resp.hasToolCalls()) {
                // No tool calls -> return final answer
                String reply = resp.content() != null ? resp.content() : "";
                log.debug("Reasoner finished: {} chars, {} tool calls", reply.length(), allInvocations.size());
                return new ReasonerResult(reply, allInvocations, resp.thinking(), toolsUsed);
            }

            // Has tool calls -> execute them
            // Append assistant message with tool_calls
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", resp.content() != null ? resp.content() : "");
            assistantMsg.put("tool_calls", serializeToolCalls(resp.toolCalls()));
            workingMessages.add(assistantMsg);

            // Execute each tool call
            for (ToolCall tc : resp.toolCalls()) {
                String toolName = tc.name();
                log.debug("Executing tool: {} with args: {}", toolName, tc.arguments());
                toolsUsed.add(toolName);

                String result = toolRegistry.execute(toolName, tc.arguments());
                allInvocations.add(new ToolCall(tc.id(), tc.name(), tc.arguments(), result));

                // Append tool result message
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tc.id());
                toolMsg.put("content", result);
                workingMessages.add(toolMsg);

                log.debug("Tool {} result: {}", toolName,
                        result.length() > 200 ? result.substring(0, 200) + "..." : result);
            }
        }

        // Max iterations reached
        log.warn("Max iterations ({}) reached, forcing summary", maxIterations);
        return new ReasonerResult(
                "(Reached maximum tool iterations. Please summarize what you've done so far.)",
                allInvocations, null, toolsUsed
        );
    }

    private List<Map<String, Object>> serializeToolCalls(List<ToolCall> toolCalls) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            result.add(Map.of(
                    "id", tc.id(),
                    "type", "function",
                    "function", Map.of(
                            "name", tc.name(),
                            "arguments", tc.arguments() != null ? tc.arguments() : Map.of()
                    )
            ));
        }
        return result;
    }
}
