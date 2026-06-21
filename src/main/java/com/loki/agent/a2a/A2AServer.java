package com.loki.agent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loki.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * A2A HTTP server exposing the agent card and a simple task delegation endpoint.
 * Uses the built-in JDK HttpServer (no Spring Web dependency needed).
 */
@Component
@ConditionalOnProperty(name = "loki.agent.a2a.enabled", havingValue = "true")
public class A2AServer {

    private static final Logger log = LoggerFactory.getLogger(A2AServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final PeerRegistry peerRegistry;

    @Value("${loki.agent.a2a.port:8090}")
    private int port;

    @Value("${loki.agent.name:loki-agent}")
    private String agentName;

    private HttpServer server;

    public A2AServer(ToolRegistry toolRegistry, PeerRegistry peerRegistry) {
        this.toolRegistry = toolRegistry;
        this.peerRegistry = peerRegistry;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newCachedThreadPool());

            server.createContext("/.well-known/agent.json", this::handleAgentCard);
            server.createContext("/a2a/task", this::handleTask);
            server.createContext("/a2a/peers", this::handlePeers);

            server.start();
            log.info("A2A server started on port {}", port);
        } catch (IOException e) {
            log.error("Failed to start A2A server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("A2A server stopped");
        }
    }

    private void handleAgentCard(HttpExchange exchange) throws IOException {
        AgentCard card = new AgentCard(
                agentName,
                "0.1.0",
                "Loki Agent — proactive AI companion",
                toolRegistry.getSchemas().stream()
                        .map(s -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> fn = (Map<String, Object>) s.get("function");
                            return fn != null ? (String) fn.get("name") : "unknown";
                        })
                        .toList(),
                "http://localhost:" + port
        );
        sendJson(exchange, 200, card.toMap());
    }

    private void handleTask(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> request = mapper.readValue(body, Map.class);

        String task = (String) request.get("task");
        if (task == null || task.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "task is required"));
            return;
        }

        // Delegate to SubAgentManager would be ideal here, but to avoid circular deps,
        // just acknowledge the task for now
        log.info("A2A task received: {}", task);
        sendJson(exchange, 200, Map.of(
                "status", "accepted",
                "message", "Task received: " + task
        ));
    }

    private void handlePeers(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            // Register a new peer
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = mapper.readValue(body, Map.class);
            AgentCard card = new AgentCard(
                    (String) data.get("name"),
                    (String) data.getOrDefault("version", "unknown"),
                    (String) data.getOrDefault("description", ""),
                    java.util.List.of(),
                    (String) data.get("endpoint")
            );
            peerRegistry.register(card);
            sendJson(exchange, 200, Map.of("status", "registered"));
        } else {
            sendJson(exchange, 200, Map.of("peers",
                    peerRegistry.all().stream().map(AgentCard::toMap).toList()));
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
