package com.loki.agent.tool.tools;

import com.loki.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ListDirTool extends Tool {

    private final Path workspace;

    public ListDirTool(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() { return "list_dir"; }

    @Override
    public String description() {
        return "List files and directories in a directory.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Directory path to list", "default", ".")
                ),
                "required", List.of()
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String path = args.containsKey("path") ? (String) args.get("path") : ".";
        Path dirPath = resolvePath(path);

        if (!Files.exists(dirPath)) {
            return "Error: directory not found: " + path;
        }
        if (!Files.isDirectory(dirPath)) {
            return "Error: " + path + " is not a directory";
        }

        try (Stream<Path> stream = Files.list(dirPath)) {
            String listing = stream
                    .sorted()
                    .map(p -> {
                        String prefix = Files.isDirectory(p) ? "[DIR]  " : "[FILE] ";
                        return prefix + p.getFileName();
                    })
                    .collect(Collectors.joining("\n"));
            return listing.isEmpty() ? "(empty directory)" : listing;
        } catch (IOException e) {
            return "Error listing directory: " + e.getMessage();
        }
    }

    private Path resolvePath(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute()) return p;
        return workspace.resolve(path).normalize();
    }
}
