package com.loki.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        log.debug("Registered tool: {}", tool.name());
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
        try {
            return tool.execute(args);
        } catch (Exception e) {
            log.error("Tool '{}' execution failed", name, e);
            return "Error: " + e.getMessage();
        }
    }

    public int size() {
        return tools.size();
    }
}
