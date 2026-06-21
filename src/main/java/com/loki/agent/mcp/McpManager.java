package com.loki.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages MCP client connections. Starts configured MCP servers and
 * exposes their tools via McpToolAdapter.
 */
@Component
public class McpManager {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);

    private final List<McpClient> clients = new ArrayList<>();

    public List<com.loki.agent.tool.Tool> startServers(List<McpServerConfig> servers) {
        List<com.loki.agent.tool.Tool> tools = new ArrayList<>();
        if (servers == null || servers.isEmpty()) return tools;

        for (McpServerConfig config : servers) {
            try {
                McpClient client = new McpClient(config.name(), config.command());
                clients.add(client);

                for (Map<String, Object> toolSchema : client.tools()) {
                    McpToolAdapter adapter = new McpToolAdapter(client, toolSchema);
                    tools.add(adapter);
                }
                log.info("MCP server '{}' started with {} tools", config.name(), client.tools().size());
            } catch (IOException e) {
                log.error("Failed to start MCP server '{}': {}", config.name(), e.getMessage());
            }
        }
        return tools;
    }

    public void shutdown() {
        for (McpClient client : clients) {
            try { client.close(); } catch (Exception ignored) {}
        }
        clients.clear();
        log.info("All MCP clients shut down");
    }

    public record McpServerConfig(String name, List<String> command) {}
}
