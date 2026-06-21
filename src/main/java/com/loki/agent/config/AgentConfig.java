package com.loki.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "loki.agent")
public record AgentConfig(
        String workspace,
        int maxIterations,
        ProactiveConfig proactive
) {
    @ConfigurationProperties(prefix = "loki.agent.proactive")
    public record ProactiveConfig(
            boolean enabled,
            int dailyMax,
            String defaultSession
    ) {}
}
