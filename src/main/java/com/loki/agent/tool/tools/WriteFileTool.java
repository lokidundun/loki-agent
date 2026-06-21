package com.loki.agent.tool.tools;

import com.loki.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class WriteFileTool extends Tool {

    private final Path workspace;

    public WriteFileTool(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "Write content to a file. Creates parent directories if needed.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "File path to write"),
                        "content", Map.of("type", "string", "description", "Content to write")
                ),
                "required", List.of("path", "content")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String path = (String) args.get("path");
        String content = (String) args.get("content");

        Path filePath = resolvePath(path);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            return "Written " + content.length() + " chars to " + path;
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    private Path resolvePath(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute()) return p;
        return workspace.resolve(path).normalize();
    }
}
