package com.loki.agent.tool;

import com.loki.agent.event.Event;
import com.loki.agent.event.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final List<ToolHook> hooks = new ArrayList<>();
    private EventBus eventBus;

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        log.debug("Registered tool: {}", tool.name());
    }

    public void setHooks(List<ToolHook> hooks) {
        this.hooks.clear();
        this.hooks.addAll(hooks);
    }

    public void setEventBus(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Map<String, Object>> getSchemas() {
        return tools.values().stream()
                .map(Tool::toSchema)
                .toList();
    }

    public List<Map<String, Object>> getSchemas(List<String> names) {
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (String name : names) {
            Tool tool = tools.get(name);
            if (tool != null) {
                schemas.add(tool.toSchema());
            }
        }
        return schemas;
    }

    public String execute(String name, Map<String, Object> args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "Error: unknown tool '" + name + "'";
        }

        // Pre-hooks
        for (ToolHook hook : hooks) {
            try { hook.beforeExecute(name, args); } catch (Exception e) { /* ignore */ }
        }

        String result;
        try {
            result = tool.execute(args);
        } catch (Exception e) {
            log.error("Tool '{}' execution failed", name, e);
            result = "Error: " + e.getMessage();
        }

        // Post-hooks
        for (ToolHook hook : hooks) {
            try { hook.afterExecute(name, args, result); } catch (Exception e) { /* ignore */ }
        }

        // EventBus
        if (eventBus != null) {
            eventBus.emit("tool.executed", Map.of(
                    "tool", name,
                    "args", args,
                    "result_length", result != null ? result.length() : 0
            ));
        }

        return result;
    }

    public int size() {
        return tools.size();
    }
}
