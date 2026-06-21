package com.loki.agent.tool;

import java.util.List;
import java.util.Map;

public abstract class Tool {

    public abstract String name();

    public abstract String description();

    public abstract Map<String, Object> parameters();

    public abstract String execute(Map<String, Object> args);

    public Map<String, Object> toSchema() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name(),
                        "description", description(),
                        "parameters", parameters()
                )
        );
    }
}
