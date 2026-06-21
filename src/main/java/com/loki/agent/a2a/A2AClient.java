package com.loki.agent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client for calling remote A2A peers.
 */
@Component
public class A2AClient {

    private static final Logger log = LoggerFactory.getLogger(A2AClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Discover an agent at the given endpoint and register it in the peer registry.
     */
    public AgentCard discover(String endpoint, PeerRegistry peerRegistry) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/.well-known/agent.json"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Discovery failed at {}: HTTP {}", endpoint, response.statusCode());
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = mapper.readValue(response.body(), Map.class);
            AgentCard card = new AgentCard(
                    (String) data.get("name"),
                    (String) data.getOrDefault("version", "unknown"),
                    (String) data.getOrDefault("description", ""),
                    java.util.List.of(),
                    (String) data.get("endpoint")
            );
            peerRegistry.register(card);
            log.info("Discovered peer: {} at {}", card.name(), card.endpoint());
            return card;

        } catch (Exception e) {
            log.error("Discovery failed for {}: {}", endpoint, e.getMessage());
            return null;
        }
    }

    /**
     * Send a task to a remote agent.
     */
    public String sendTask(String endpoint, String task) {
        try {
            String json = mapper.writeValueAsString(Map.of("task", task));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/a2a/task"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(response.body(), Map.class);
            return (String) result.getOrDefault("message", response.body());

        } catch (Exception e) {
            log.error("Task send failed to {}: {}", endpoint, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
