package com.loki.agent.mcp;

import com.loki.agent.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * Wraps an MCP server tool as a local Tool instance.
 */
public class McpToolAdapter extends Tool {

    private final McpClient client;
    private final String toolName;
    private final String description;
    private final Map<String, Object> parameters;

    public McpToolAdapter(McpClient client, Map<String, Object> toolSchema) {
        this.client = client;
        this.toolName = (String) toolSchema.get("name");
        this.description = (String) toolSchema.getOrDefault("description", "MCP tool: " + toolName);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) toolSchema.get("inputSchema");
        this.parameters = params != null ? params : Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String name() {
        return "mcp_" + client.name() + "_" + toolName;
    }

    @Override
    public String description() {
        return "[MCP:" + client.name() + "] " + description;
    }

    @Override
    public Map<String, Object> parameters() {
        return parameters;
    }

    @Override
    public String execute(Map<String, Object> args) {
        Map<String, Object> result = client.callTool(toolName, args);
        if (result == null) return "Error: MCP tool call to '" + toolName + "' timed out or failed";

        // MCP tool result has "content" array with text items
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        if (content == null || content.isEmpty()) return "(empty result)";

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : content) {
            String type = (String) item.getOrDefault("type", "text");
            if ("text".equals(type)) {
                sb.append(item.get("text"));
            } else {
                sb.append("[").append(type).append("]");
            }
        }
        return sb.toString();
    }
}
