package com.loki.agent.memory;

import com.loki.agent.llm.LlmProvider;
import com.loki.agent.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    private final MemoryStore memoryStore;
    private final LlmProvider llmProvider;

    @Value("${loki.agent.memory.consolidate-every:5}")
    private int consolidateEvery;

    @Value("${loki.agent.memory.pending-threshold:10}")
    private int pendingThreshold;

    public MemoryConsolidator(MemoryStore memoryStore, LlmProvider llmProvider) {
        this.memoryStore = memoryStore;
        this.llmProvider = llmProvider;
    }

    public void consolidate(Session session) {
        int lastConsolidated = session.lastConsolidated();
        int currentSize = session.messages().size();
        int newCount = currentSize - lastConsolidated;

        if (newCount < consolidateEvery) {
            log.debug("Not enough new messages ({}/{}), skipping consolidation",
                    newCount, consolidateEvery);
            return;
        }

        log.info("Consolidating: {} new messages (from seq {})", newCount, lastConsolidated);

        // Extract recent messages for fact extraction
        List<Map<String, Object>> recent = session.getHistory(20);
        String facts = extractFacts(recent);
        if (facts != null && !facts.isBlank()) {
            memoryStore.appendPending(facts);
            log.debug("Appended facts to PENDING.md");
        }

        // Update lastConsolidated
        session.setLastConsolidated(currentSize);

        // Merge PENDING -> MEMORY if threshold reached
        String pending = memoryStore.readPending();
        int pendingLines = pending.isBlank() ? 0 : pending.split("\n").length;
        if (pendingLines >= pendingThreshold) {
            mergePendingIntoMemory(session);
        }
    }

    private String extractFacts(List<Map<String, Object>> messages) {
        StringBuilder conversation = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.getOrDefault("role", "unknown");
            String content = (String) msg.getOrDefault("content", "");
            if (!content.isBlank()) {
                conversation.append(role).append(": ").append(content).append("\n");
            }
        }

        if (conversation.length() < 50) return null;

        String prompt = """
                Extract key facts about the user from this conversation.
                Return each fact on a line starting with "- ".
                Only extract facts that are NEW (not obvious or generic).
                If nothing worth remembering, reply with exactly: [NONE]

                Conversation:
                %s
                """.formatted(conversation);

        try {
            var response = llmProvider.chat(
                    List.of(Map.of("role", "user", "content", prompt)),
                    List.of(), null, 500);
            String content = response.content();
            if (content == null || content.isBlank() || content.contains("[NONE]")) return null;
            return content.strip();
        } catch (Exception e) {
            log.warn("Fact extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private void mergePendingIntoMemory(Session session) {
        log.info("Merging PENDING -> MEMORY");

        // Snapshot pending (atomic rename)
        var snapshotPath = memoryStore.snapshotPending();
        if (snapshotPath == null) return;

        try {
            String pendingContent = memoryStore.readPending();
            if (pendingContent.isBlank()) {
                memoryStore.commitPendingSnapshot();
                return;
            }

            String existingMemory = memoryStore.readLongTerm();

            String prompt = """
                    You are a memory manager. Merge these NEW facts into the EXISTING memory.
                    Deduplicate, organize by topic, keep it concise.
                    Return the COMPLETE merged memory (not just the new parts).

                    EXISTING memory:
                    %s

                    NEW facts to merge:
                    %s

                    Return ONLY the merged memory content, no preamble.
                    """.formatted(
                            existingMemory.isBlank() ? "(empty)" : existingMemory,
                            pendingContent);

            var response = llmProvider.chat(
                    List.of(Map.of("role", "user", "content", prompt)),
                    List.of(), null, 2000);

            String merged = response.content();
            if (merged != null && !merged.isBlank()) {
                memoryStore.writeLongTerm(merged.strip());

                // Append merge summary to HISTORY
                memoryStore.appendHistory("## Merge " + java.time.LocalDate.now()
                        + "\nMerged " + pendingContent.split("\n").length
                        + " pending facts into MEMORY.md\n");

                log.info("MEMORY.md updated, {} pending facts merged",
                        pendingContent.split("\n").length);
            }

            // Commit: delete snapshot
            memoryStore.commitPendingSnapshot();

        } catch (Exception e) {
            log.error("Merge failed, rolling back", e);
            memoryStore.rollbackPendingSnapshot();
        }
    }
}
