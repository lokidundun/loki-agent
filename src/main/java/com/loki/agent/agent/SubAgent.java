package com.loki.agent.agent;

import com.loki.agent.llm.LlmProvider;
import com.loki.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A lightweight sub-agent that runs a task with its own context.
 * Used for delegating complex subtasks from the main agent.
 */
public class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    private final String id;
    private final String task;
    private final LlmProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final List<String> allowedTools;
    private final String model;
    private final int maxIterations;

    private String result;
    private List<String> toolsUsed = new ArrayList<>();

    public SubAgent(String task, LlmProvider llmProvider, ToolRegistry toolRegistry,
                     List<String> allowedTools, String model, int maxIterations) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.task = task;
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.allowedTools = allowedTools;
        this.model = model;
        this.maxIterations = maxIterations;
    }

    public String id() { return id; }
    public String task() { return task; }
    public String result() { return result; }
    public List<String> toolsUsed() { return toolsUsed; }

    public String run() {
        log.info("SubAgent [{}] starting: {}", id, truncate(task, 100));

        // Build initial messages
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", """
                You are a sub-agent delegated to complete a specific task.
                Be thorough but concise. Use available tools if needed.
                When done, report your findings clearly.
                """));
        messages.add(Map.of("role", "user", "content", task));

        // Get tool schemas (filtered if allowedTools specified)
        List<Map<String, Object>> toolSchemas = allowedTools != null && !allowedTools.isEmpty()
                ? toolRegistry.getSchemas(allowedTools)
                : toolRegistry.getSchemas();

        // ReAct loop
        for (int i = 0; i < maxIterations; i++) {
            var resp = llmProvider.chat(messages, toolSchemas, model, 4096);

            if (!resp.hasToolCalls()) {
                result = resp.content() != null ? resp.content() : "(no output)";
                log.info("SubAgent [{}] completed: {} chars, tools: {}",
                        id, result.length(), toolsUsed);
                return result;
            }

            // Execute tool calls
            Map<String, Object> assistantMsg = new java.util.HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", resp.content() != null ? resp.content() : "");
            messages.add(assistantMsg);

            for (var tc : resp.toolCalls()) {
                toolsUsed.add(tc.name());
                String toolResult = toolRegistry.execute(tc.name(), tc.arguments());

                Map<String, Object> toolMsg = new java.util.HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("content", toolResult);
                messages.add(toolMsg);
            }
        }

        result = "(SubAgent reached max iterations)";
        return result;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
