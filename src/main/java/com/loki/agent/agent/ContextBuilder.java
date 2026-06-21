package com.loki.agent.agent;

import com.loki.agent.bus.InboundMessage;
import com.loki.agent.memory.MemoryStore;
import com.loki.agent.prompt.PromptTemplates;
import com.loki.agent.prompt.SystemPromptBuilder;
import com.loki.agent.session.Session;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ContextBuilder {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private final SystemPromptBuilder systemPromptBuilder;
    private final MemoryStore memoryStore;

    public ContextBuilder(SystemPromptBuilder systemPromptBuilder, MemoryStore memoryStore) {
        this.systemPromptBuilder = systemPromptBuilder;
        this.memoryStore = memoryStore;
    }

    public record ContextResult(String systemPrompt, List<Map<String, Object>> messages) {}

    public ContextResult build(Session session, InboundMessage msg,
                               String memoryBlock, String skillsInfo) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // Build system prompt
        String selfModel = memoryStore.readSelf();
        String recentContext = memoryStore.readRecentContext();
        String timestamp = TS_FMT.format(msg.timestamp());
        String sessionHeader = PromptTemplates.buildSessionHeader(
                msg.channel(), msg.chatId(), timestamp);

        String systemPrompt = systemPromptBuilder.build(
                memoryBlock, selfModel, recentContext, sessionHeader, skillsInfo);

        // [0] system prompt
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // [1..N] conversation history
        List<Map<String, Object>> history = session.getHistory(50);
        for (Map<String, Object> h : history) {
            messages.add(Map.of(
                    "role", h.get("role"),
                    "content", h.get("content")
            ));
        }

        // [N+1] context frame (memory + recent context reminder)
        String contextFrame = buildContextFrame(memoryBlock, recentContext, skillsInfo);
        if (contextFrame != null) {
            messages.add(Map.of("role", "user", "content", contextFrame));
        }

        // [N+2] actual user message with timestamp
        String userContent = "[" + timestamp + "]\n" + msg.content();
        messages.add(Map.of("role", "user", "content", userContent));

        return new ContextResult(systemPrompt, messages);
    }

    private String buildContextFrame(String memoryBlock, String recentContext,
                                     String skillsInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append(PromptTemplates.CONTEXT_FRAME_PREFIX);

        boolean hasContent = false;

        if (memoryBlock != null && !memoryBlock.isBlank()) {
            sb.append("\n### Long-term Memory\n").append(memoryBlock);
            hasContent = true;
        }

        if (recentContext != null && !recentContext.isBlank()) {
            sb.append("\n### Recent Context\n").append(recentContext);
            hasContent = true;
        }

        if (skillsInfo != null && !skillsInfo.isBlank()) {
            sb.append("\n### Available Skills\n").append(skillsInfo);
            hasContent = true;
        }

        sb.append("\n").append(PromptTemplates.CONTEXT_FRAME_SUFFIX);

        return hasContent ? sb.toString() : null;
    }
}
