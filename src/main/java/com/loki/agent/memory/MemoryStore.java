package com.loki.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private final Path memoryDir;

    public MemoryStore(Path workspace) {
        this.memoryDir = workspace.resolve("memory");
        ensureDir(memoryDir);
        ensureDir(memoryDir.resolve("journal"));
    }

    public Path memoryDir() {
        return memoryDir;
    }

    // ===== Layer 1: MEMORY.md (long-term) =====

    public String readLongTerm() {
        return readFile(memoryDir.resolve("MEMORY.md"));
    }

    public void writeLongTerm(String content) {
        writeFile(memoryDir.resolve("MEMORY.md"), content);
    }

    // ===== Layer 2: SELF.md (self-model) =====

    public String readSelf() {
        return readFile(memoryDir.resolve("SELF.md"));
    }

    public void writeSelf(String content) {
        writeFile(memoryDir.resolve("SELF.md"), content);
    }

    // ===== Layer 3: PENDING.md (incremental facts) =====

    public String readPending() {
        return readFile(memoryDir.resolve("PENDING.md"));
    }

    public void appendPending(String facts) {
        appendFile(memoryDir.resolve("PENDING.md"), facts + "\n");
    }

    public void clearPending() {
        writeFile(memoryDir.resolve("PENDING.md"), "");
    }

    public Path snapshotPending() {
        Path src = memoryDir.resolve("PENDING.md");
        Path dst = memoryDir.resolve("PENDING.md.snapshot");
        try {
            if (Files.exists(src)) {
                Files.move(src, dst);
            }
            return dst;
        } catch (IOException e) {
            log.error("Failed to snapshot PENDING.md", e);
            return null;
        }
    }

    public void commitPendingSnapshot() {
        Path dst = memoryDir.resolve("PENDING.md.snapshot");
        try {
            Files.deleteIfExists(dst);
        } catch (IOException e) {
            log.error("Failed to commit pending snapshot", e);
        }
    }

    public void rollbackPendingSnapshot() {
        Path src = memoryDir.resolve("PENDING.md.snapshot");
        Path dst = memoryDir.resolve("PENDING.md");
        try {
            if (Files.exists(src)) {
                Files.move(src, dst);
            }
        } catch (IOException e) {
            log.error("Failed to rollback pending snapshot", e);
        }
    }

    // ===== Layer 4: HISTORY.md (append-only log) =====

    public void appendHistory(String entry) {
        appendFile(memoryDir.resolve("HISTORY.md"), entry + "\n");
    }

    // ===== Layer 5: RECENT_CONTEXT.md =====

    public String readRecentContext() {
        return readFile(memoryDir.resolve("RECENT_CONTEXT.md"));
    }

    public void writeRecentContext(String content) {
        writeFile(memoryDir.resolve("RECENT_CONTEXT.md"), content);
    }

    // ===== Journal =====

    public void appendJournal(String entry) {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path journalFile = memoryDir.resolve("journal").resolve(date + ".md");
        appendFile(journalFile, "- " + entry + "\n");
    }

    // ===== Context injection =====

    public String getMemoryContext() {
        String content = readLongTerm();
        if (content.isBlank()) return "";
        return "## Long-term Memory\n" + content;
    }

    // ===== IO helpers =====

    private String readFile(Path path) {
        try {
            if (!Files.exists(path)) return "";
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read {}", path, e);
            return "";
        }
    }

    private void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to write {}", path, e);
        }
    }

    private void appendFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append {}", path, e);
        }
    }

    private void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create directory {}", dir, e);
        }
    }
}
