package com.loki.agent.agent;

import com.loki.agent.bus.InboundMessage;
import com.loki.agent.bus.MessageBus;
import com.loki.agent.bus.OutboundMessage;
import com.loki.agent.channel.CliChannel;
import com.loki.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AgentLoop implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final MessageBus bus;
    private final CliChannel cliChannel;
    private final PassiveTurnPipeline pipeline;
    private final ToolRegistry toolRegistry;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AgentLoop(MessageBus bus, CliChannel cliChannel,
                     PassiveTurnPipeline pipeline, ToolRegistry toolRegistry) {
        this.bus = bus;
        this.cliChannel = cliChannel;
        this.pipeline = pipeline;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        cliChannel.start(bus);
        running.set(true);
        executor.submit(this::mainLoop);
        log.info("AgentLoop started, tools={}", toolRegistry.size());
    }

    private void mainLoop() {
        while (running.get()) {
            InboundMessage msg = null;
            try {
                msg = bus.consumeInbound();
                log.debug("Received: [{}] {}", msg.sessionKey(), msg.content());

                OutboundMessage reply = pipeline.run(msg);
                bus.publishOutbound(reply);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing message", e);
                if (msg != null) {
                    bus.publishOutbound(new OutboundMessage(
                            msg.channel(), msg.chatId(),
                            "Error: " + e.getMessage(), null));
                }
            }
        }
    }
}
