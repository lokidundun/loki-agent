package com.loki.agent.tool.tools;

import com.loki.agent.agent.SubAgentManager;
import com.loki.agent.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM-callable tool that delegates a task to a SubAgent.
 */
public class DelegateTool extends Tool {

    private final SubAgentManager subAgentManager;

    public DelegateTool(SubAgentManager subAgentManager) {
        this.subAgentManager = subAgentManager;
    }

    @Override
    public String name() { return "delegate"; }

    @Override
    public String description() {
        return "Delegate a complex subtask to a background sub-agent. " +
                "The sub-agent will work independently and return its result. " +
                "Use for research, analysis, or multi-step tasks that would benefit from isolation.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of("type", "string",
                                "description", "The task description for the sub-agent"),
                        "tools", Map.of("type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Tool names the sub-agent may use (empty = all tools)"),
                        "timeout", Map.of("type", "integer",
                                "description", "Timeout in seconds (default: 120)",
                                "default", 120)
                ),
                "required", List.of("task")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String task = (String) args.get("task");
        if (task == null || task.isBlank()) return "Error: 'task' is required";

        List<String> tools = new ArrayList<>();
        Object toolsObj = args.get("tools");
        if (toolsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) tools.add(s);
            }
        }

        int timeout = 120;
        Object timeoutObj = args.get("timeout");
        if (timeoutObj instanceof Number n) timeout = n.intValue();

        return subAgentManager.delegate(task, tools, timeout);
    }
}
