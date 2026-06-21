package com.loki.agent.tool.tools;

import com.loki.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class EditFileTool extends Tool {

    private final Path workspace;

    public EditFileTool(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() { return "edit_file"; }

    @Override
    public String description() {
        return "Edit a file by replacing old_text with new_text. Returns diff.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "File path to edit"),
                        "old_text", Map.of("type", "string", "description", "Text to find and replace"),
                        "new_text", Map.of("type", "string", "description", "Replacement text")
                ),
                "required", List.of("path", "old_text", "new_text")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String path = (String) args.get("path");
        String oldText = (String) args.get("old_text");
        String newText = (String) args.get("new_text");

        Path filePath = resolvePath(path);
        if (!Files.exists(filePath)) {
            return "Error: file not found: " + path;
        }

        try {
            String content = Files.readString(filePath);
            if (!content.contains(oldText)) {
                return "Error: old_text not found in " + path;
            }
            String updated = content.replace(oldText, newText);
            Files.writeString(filePath, updated);

            int count = countOccurrences(content, oldText);
            return "Replaced " + count + " occurrence(s) in " + path;
        } catch (IOException e) {
            return "Error editing file: " + e.getMessage();
        }
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    private Path resolvePath(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute()) return p;
        return workspace.resolve(path).normalize();
    }
}
