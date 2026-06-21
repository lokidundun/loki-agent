package com.loki.agent.agent;

import com.loki.agent.bus.InboundMessage;
import com.loki.agent.bus.MessageBus;
import com.loki.agent.bus.OutboundMessage;
import com.loki.agent.channel.CliChannel;
import com.loki.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AgentLoop implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final MessageBus bus;
    private final CliChannel cliChannel;
    private final Reasoner reasoner;
    private final ToolRegistry toolRegistry;

    @Value("${spring.ai.openai.chat.model:deepseek-chat}")
    private String model;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AgentLoop(MessageBus bus, CliChannel cliChannel,
                     Reasoner reasoner, ToolRegistry toolRegistry) {
        this.bus = bus;
        this.cliChannel = cliChannel;
        this.reasoner = reasoner;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        cliChannel.start(bus);
        running.set(true);
        executor.submit(this::mainLoop);
        log.info("AgentLoop started, model={}, tools={}", model, toolRegistry.size());
    }

    private void mainLoop() {
        while (running.get()) {
            try {
                InboundMessage msg = bus.consumeInbound();
                log.debug("Received: [{}] {}", msg.sessionKey(), msg.content());

                String reply = processMessage(msg);
                OutboundMessage outbound = new OutboundMessage(
                        msg.channel(), msg.chatId(), reply, null
                );
                bus.publishOutbound(outbound);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing message", e);
            }
        }
    }

    private String processMessage(InboundMessage msg) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // System prompt
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));

        // User message with timestamp
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(msg.timestamp());
        messages.add(Map.of("role", "user",
                "content", "[" + timestamp + "]\n" + msg.content()));

        // Run reasoner with tools
        ReasonerResult result = reasoner.run(
                messages,
                toolRegistry.getSchemas(),
                model
        );

        log.info("Reply: {} (tools used: {})", result.reply().length(), result.toolsUsed());
        return result.reply();
    }

    private String buildSystemPrompt() {
        return """
                You are Loki Agent, a helpful AI assistant.
                You have access to file system tools and can read, write, and edit files.
                Always respond in the same language the user uses.
                Be concise and helpful.
                """;
    }
}
