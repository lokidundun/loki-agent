package com.loki.agent.tool.tools;

import com.loki.agent.llm.LlmProvider;
import com.loki.agent.memory.MemoryStore;
import com.loki.agent.tool.Tool;

import java.util.List;
import java.util.Map;

public class MemoryTool extends Tool {

    private final MemoryStore memoryStore;
    private final LlmProvider llmProvider;

    public MemoryTool(MemoryStore memoryStore, LlmProvider llmProvider) {
        this.memoryStore = memoryStore;
        this.llmProvider = llmProvider;
    }

    @Override
    public String name() { return "memory"; }

    @Override
    public String description() {
        return "Manage agent memory. Actions: memorize (store a fact), recall (retrieve memories), forget (remove a fact).";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", List.of("memorize", "recall", "forget"),
                                "description", "The memory action to perform"),
                        "content", Map.of(
                                "type", "string",
                                "description", "For memorize: the fact to store. For forget: the fact to remove.")
                ),
                "required", List.of("action")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) return "Error: 'action' is required";

        return switch (action) {
            case "memorize" -> doMemorize(args);
            case "recall" -> doRecall();
            case "forget" -> doForget(args);
            default -> "Error: unknown action '" + action + "'. Use: memorize, recall, forget";
        };
    }

    private String doMemorize(Map<String, Object> args) {
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            return "Error: 'content' is required for memorize";
        }
        memoryStore.appendPending(content.strip());
        return "Memorized: " + content.strip();
    }

    private String doRecall() {
        String longTerm = memoryStore.readLongTerm();
        String pending = memoryStore.readPending();
        String self = memoryStore.readSelf();

        StringBuilder sb = new StringBuilder();
        if (!longTerm.isBlank()) {
            sb.append("## Long-term Memory\n").append(longTerm).append("\n");
        }
        if (!pending.isBlank()) {
            sb.append("## Pending Facts\n").append(pending).append("\n");
        }
        if (!self.isBlank()) {
            sb.append("## Self Model\n").append(self).append("\n");
        }
        return sb.length() > 0 ? sb.toString() : "(no memories stored)";
    }

    private String doForget(Map<String, Object> args) {
        String query = (String) args.get("content");
        if (query == null || query.isBlank()) {
            return "Error: 'content' is required for forget (specify what to forget)";
        }

        String existing = memoryStore.readLongTerm();
        if (existing.isBlank()) {
            return "Nothing to forget — memory is empty.";
        }

        String prompt = """
                Remove any facts related to "%s" from this memory.
                Return the cleaned memory. If nothing matches, return the memory unchanged.
                If all facts match, return [EMPTY].

                Memory:
                %s
                """.formatted(query, existing);

        try {
            var response = llmProvider.chat(
                    List.of(Map.of("role", "user", "content", prompt)),
                    List.of(), null, 2000);
            String result = response.content();
            if (result == null || result.isBlank()) return "LLM returned empty response";

            result = result.strip();
            if (result.contains("[EMPTY]")) {
                memoryStore.writeLongTerm("");
                return "All matching memories have been forgotten.";
            }
            memoryStore.writeLongTerm(result);
            return "Updated memory (removed facts related to: " + query + ")";
        } catch (Exception e) {
            return "Error during forget: " + e.getMessage();
        }
    }
}
