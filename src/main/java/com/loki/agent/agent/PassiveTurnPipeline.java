package com.loki.agent.agent;

import com.loki.agent.bus.InboundMessage;
import com.loki.agent.bus.OutboundMessage;
import com.loki.agent.event.EventBus;
import com.loki.agent.memory.MemoryConsolidator;
import com.loki.agent.memory.MemoryStore;
import com.loki.agent.session.Session;
import com.loki.agent.session.SessionManager;
import com.loki.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PassiveTurnPipeline {

    private static final Logger log = LoggerFactory.getLogger(PassiveTurnPipeline.class);

    private final SessionManager sessionManager;
    private final ContextBuilder contextBuilder;
    private final Reasoner reasoner;
    private final ToolRegistry toolRegistry;
    private final MemoryStore memoryStore;
    private final MemoryConsolidator memoryConsolidator;
    private final EventBus eventBus;

    @Value("${spring.ai.openai.chat.model:deepseek-chat}")
    private String model;

    public PassiveTurnPipeline(SessionManager sessionManager,
                               ContextBuilder contextBuilder,
                               Reasoner reasoner,
                               ToolRegistry toolRegistry,
                               MemoryStore memoryStore,
                               MemoryConsolidator memoryConsolidator,
                               EventBus eventBus) {
        this.sessionManager = sessionManager;
        this.contextBuilder = contextBuilder;
        this.reasoner = reasoner;
        this.toolRegistry = toolRegistry;
        this.memoryStore = memoryStore;
        this.memoryConsolidator = memoryConsolidator;
        this.eventBus = eventBus;
    }

    public OutboundMessage run(InboundMessage msg) {
        // Emit message.received
        eventBus.emit("message.received", Map.of(
                "channel", msg.channel(),
                "sender", msg.sender(),
                "content_length", msg.content().length()
        ));

        // Phase 1: BeforeTurn — get session, prepare memory
        log.debug("Phase 1: BeforeTurn — session={}", msg.sessionKey());
        Session session = sessionManager.getOrCreate(msg.sessionKey());
        String memoryBlock = memoryStore.getMemoryContext();

        // Phase 2: BeforeReasoning — build full message array
        log.debug("Phase 2: BeforeReasoning — building context");
        ContextBuilder.ContextResult ctx = contextBuilder.build(session, msg, memoryBlock);

        // Phase 3-4: Reasoning — ReAct loop
        log.debug("Phase 3-4: Reasoning — running ReAct loop");
        ReasonerResult result = reasoner.run(
                ctx.messages(),
                toolRegistry.getSchemas(),
                model
        );

        // Phase 5: AfterReasoning — persist conversation
        log.debug("Phase 5: AfterReasoning — persisting messages");
        session.addMessage("user", msg.content());
        session.addMessage("assistant", result.reply());
        sessionManager.appendMessages(session);

        // Record in memory journal
        memoryStore.appendJournal("User: " + truncate(msg.content(), 200));
        memoryStore.appendJournal("Agent: " + truncate(result.reply(), 200));

        // Phase 5b: Memory consolidation (async-safe, has internal guards)
        try {
            memoryConsolidator.consolidate(session);
        } catch (Exception e) {
            log.warn("Memory consolidation failed: {}", e.getMessage());
        }

        // Phase 6: AfterTurn — build outbound message
        log.debug("Phase 6: AfterTurn — reply ready ({} chars, tools: {})",
                result.reply().length(), result.toolsUsed());

        // Emit message.replied
        eventBus.emit("message.replied", Map.of(
                "channel", msg.channel(),
                "reply_length", result.reply().length(),
                "tools_used", result.toolsUsed()
        ));

        return new OutboundMessage(msg.channel(), msg.chatId(), result.reply(), null);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
