package com.loki.agent.tool.hooks;

import com.loki.agent.tool.ToolHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LoggingToolHook implements ToolHook {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolHook.class);

    @Override
    public void beforeExecute(String toolName, Map<String, Object> args) {
        log.debug("Tool >> {} args={}", toolName, args);
    }

    @Override
    public void afterExecute(String toolName, Map<String, Object> args, String result) {
        String preview = result != null && result.length() > 200
                ? result.substring(0, 200) + "..." : result;
        log.debug("Tool << {} result={}", toolName, preview);
    }
}
