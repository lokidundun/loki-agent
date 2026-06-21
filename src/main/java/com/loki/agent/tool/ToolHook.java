package com.loki.agent.tool;

import java.util.Map;

public interface ToolHook {
    default void beforeExecute(String toolName, Map<String, Object> args) {}
    default void afterExecute(String toolName, Map<String, Object> args, String result) {}
}
