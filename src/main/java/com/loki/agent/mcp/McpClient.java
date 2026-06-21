package com.loki.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP (Model Context Protocol) client.
 * Communicates with an MCP server subprocess via JSON-RPC 2.0 over stdio.
 */
public class McpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String name;
    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final AtomicLong idGen = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean initialized = false;
    private List<Map<String, Object>> tools = List.of();

    public McpClient(String name, List<String> command) throws IOException {
        this.name = name;
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        this.process = pb.start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

        readerExecutor.submit(this::readLoop);
        initialize();
    }

    public String name() { return name; }
    public List<Map<String, Object>> tools() { return tools; }

    public Map<String, Object> callTool(String toolName, Map<String, Object> args) {
        Map<String, Object> result = sendRequest("tools/call",
                Map.of("name", toolName, "arguments", args));
        return result;
    }

    private void initialize() {
        // Send initialize request
        Map<String, Object> initResult = sendRequest("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "loki-agent", "version", "0.1.0")
        ));
        if (initResult != null) {
            log.info("MCP server '{}' initialized: {}", name, initResult);
            // Send initialized notification (no response expected)
            sendNotification("notifications/initialized", Map.of());
        }

        // List available tools
        Map<String, Object> toolsResult = sendRequest("tools/list", Map.of());
        if (toolsResult != null && toolsResult.containsKey("tools")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolList = (List<Map<String, Object>>) toolsResult.get("tools");
            this.tools = toolList != null ? toolList : List.of();
            log.info("MCP server '{}' exposes {} tools: {}", name, tools.size(),
                    tools.stream().map(t -> t.get("name")).toList());
        }
        initialized = true;
    }

    private Map<String, Object> sendRequest(String method, Map<String, Object> params) {
        long id = idGen.getAndIncrement();
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", params
        );

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);

        try {
            String json = mapper.writeValueAsString(request);
            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("MCP request '{}' to '{}' failed: {}", method, name, e.getMessage());
            pending.remove(id);
            return null;
        }
    }

    private void sendNotification(String method, Map<String, Object> params) {
        Map<String, Object> notification = Map.of(
                "jsonrpc", "2.0",
                "method", method,
                "params", params
        );
        try {
            String json = mapper.writeValueAsString(notification);
            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }
        } catch (IOException e) {
            log.error("MCP notification '{}' to '{}' failed: {}", method, name, e.getMessage());
        }
    }

    private void readLoop() {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msg = mapper.readValue(line, Map.class);

                    if (msg.containsKey("id")) {
                        long id = ((Number) msg.get("id")).longValue();
                        CompletableFuture<Map<String, Object>> future = pending.remove(id);
                        if (future != null) {
                            if (msg.containsKey("error")) {
                                future.completeExceptionally(
                                        new RuntimeException(msg.get("error").toString()));
                            } else {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> result = (Map<String, Object>) msg.get("result");
                                future.complete(result);
                            }
                        }
                    }
                } catch (JsonProcessingException e) {
                    log.warn("MCP '{}' invalid JSON: {}", name, line);
                }
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                log.error("MCP '{}' read loop error: {}", name, e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        try { stdin.close(); } catch (IOException ignored) {}
        process.destroy();
        readerExecutor.shutdownNow();
        pending.values().forEach(f -> f.completeExceptionally(new RuntimeException("MCP client closed")));
        log.info("MCP client '{}' closed", name);
    }
}
