package com.loki.agent.tool.tools;

import com.loki.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ReadFileTool extends Tool {

    private final Path workspace;

    public ReadFileTool(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() { return "read_file"; }

    @Override
    public String description() {
        return "Read the content of a file. Returns numbered lines.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "File path to read"),
                        "offset", Map.of("type", "integer", "description", "Line offset (0-based)", "default", 0),
                        "limit", Map.of("type", "integer", "description", "Max lines to read", "default", 200)
                ),
                "required", List.of("path")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String path = (String) args.get("path");
        int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 0;
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 200;

        Path filePath = resolvePath(path);
        if (!Files.exists(filePath)) {
            return "Error: file not found: " + path;
        }
        if (Files.isDirectory(filePath)) {
            return "Error: " + path + " is a directory, use list_dir instead";
        }

        try {
            List<String> lines = Files.readAllLines(filePath);
            if (offset >= lines.size()) {
                return "(empty - offset " + offset + " >= total " + lines.size() + " lines)";
            }
            int end = Math.min(offset + limit, lines.size());
            StringBuilder sb = new StringBuilder();
            for (int i = offset; i < end; i++) {
                sb.append(String.format("%5d-> %s%n", i + 1, lines.get(i)));
            }
            if (end < lines.size()) {
                sb.append("... (").append(lines.size() - end).append(" more lines)");
            }
            return sb.toString();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    private Path resolvePath(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute()) return p;
        return workspace.resolve(path).normalize();
    }
}
