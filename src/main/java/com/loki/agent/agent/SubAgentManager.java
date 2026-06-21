package com.loki.agent.agent;

import com.loki.agent.llm.LlmProvider;
import com.loki.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class SubAgentManager {

    private static final Logger log = LoggerFactory.getLogger(SubAgentManager.class);

    private final LlmProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Value("${spring.ai.openai.chat.model:deepseek-chat}")
    private String model;

    @Value("${loki.agent.max-iterations:10}")
    private int maxIterations;

    public SubAgentManager(LlmProvider llmProvider, @Lazy ToolRegistry toolRegistry) {
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Spawn a SubAgent and wait for its result (blocking).
     */
    public String delegate(String task, List<String> allowedTools, int timeoutSeconds) {
        SubAgent subAgent = new SubAgent(task, llmProvider, toolRegistry,
                allowedTools, model, maxIterations);

        try {
            Future<String> future = executor.submit(subAgent::run);
            String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            log.debug("SubAgent [{}] returned: {} chars", subAgent.id(), result.length());
            return result;
        } catch (TimeoutException e) {
            log.warn("SubAgent timed out after {}s", timeoutSeconds);
            return "(SubAgent timed out after " + timeoutSeconds + "s)";
        } catch (Exception e) {
            log.error("SubAgent failed: {}", e.getMessage());
            return "(SubAgent error: " + e.getMessage() + ")";
        }
    }

    /**
     * Spawn a SubAgent asynchronously.
     */
    public Future<String> delegateAsync(String task, List<String> allowedTools) {
        SubAgent subAgent = new SubAgent(task, llmProvider, toolRegistry,
                allowedTools, model, maxIterations);
        return executor.submit(subAgent::run);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
