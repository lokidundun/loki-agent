package com.loki.agent.channel;

import com.loki.agent.bus.InboundMessage;
import com.loki.agent.bus.MessageBus;
import com.loki.agent.bus.OutboundMessage;
import com.loki.agent.llm.StreamingCallback;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CliChannel implements Channel {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private MessageBus bus;

    @Override
    public void start(MessageBus bus) {
        this.bus = bus;
        running.set(true);

        executor.submit(this::inputLoop);
        executor.submit(this::outputLoop);

        System.out.println("=== Loki Agent CLI ===");
        System.out.println("Type your message and press Enter. Type /quit to exit.");
        System.out.println();
    }

    @Override
    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }

    @Override
    public StreamingCallback getStreamingCallback() {
        return token -> System.out.print(token);
    }

    private void inputLoop() {
        Scanner scanner = new Scanner(System.in);
        while (running.get()) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            if ("/quit".equals(line) || "/exit".equals(line)) {
                System.out.println("Bye!");
                running.set(false);
                System.exit(0);
                break;
            }

            InboundMessage msg = new InboundMessage(
                    "cli", "user", "default", line,
                    Instant.now(), List.of(), Map.of()
            );
            bus.publishInbound(msg);
        }
    }

    private void outputLoop() {
        while (running.get()) {
            try {
                OutboundMessage msg = bus.consumeOutbound();
                System.out.println();
                System.out.println("Agent: " + msg.content());
                System.out.println();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
