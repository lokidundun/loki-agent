package com.loki.agent.llm;

/**
 * Callback interface for streaming LLM responses.
 */
@FunctionalInterface
public interface StreamingCallback {
    void onToken(String token);
}
