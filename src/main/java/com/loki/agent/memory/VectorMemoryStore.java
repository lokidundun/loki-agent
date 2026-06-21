package com.loki.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Vector-based memory store using TF-IDF + cosine similarity.
 * Character bigrams for CJK compatibility. No external embedding API required.
 */
@Component
public class VectorMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path storeFile;
    private final MemoryStore memoryStore;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Double> idfCache = new HashMap<>();
    private List<MemoryChunk> chunks = new ArrayList<>();
    private boolean dirty = false;

    public VectorMemoryStore(Path workspace, MemoryStore memoryStore) {
        this.storeFile = workspace.resolve("memory").resolve("vector_store.json");
        this.memoryStore = memoryStore;
    }

    @PostConstruct
    public void init() {
        loadFromDisk();
        reindex(memoryStore);
    }

    /**
     * Re-index all memory layers. Called after memory merge.
     */
    public void reindex(MemoryStore memoryStore) {
        List<MemoryChunk> newChunks = new ArrayList<>();

        addChunksFromSource(newChunks, "MEMORY.md", memoryStore.readLongTerm());
        addChunksFromSource(newChunks, "SELF.md", memoryStore.readSelf());
        addChunksFromSource(newChunks, "PENDING.md", memoryStore.readPending());
        addChunksFromSource(newChunks, "RECENT_CONTEXT.md", memoryStore.readRecentContext());

        List<String> journalDirs = listJournalFiles(memoryStore);
        for (String journalContent : journalDirs) {
            addChunksFromSource(newChunks, "journal", journalContent);
        }

        if (newChunks.isEmpty()) {
            log.debug("No memory content to index");
            return;
        }

        buildIdf(newChunks);
        for (MemoryChunk chunk : newChunks) {
            Map<String, Double> vector = embed(tokenize(chunk.content()));
            chunks.add(new MemoryChunk(chunk.id(), chunk.content(), chunk.source(), vector));
        }

        deduplicate();
        persist();
        log.info("Vector index rebuilt: {} chunks", chunks.size());
    }

    /**
     * Search memory for chunks relevant to the query.
     */
    public List<MemoryChunk> search(String query, int topK) {
        if (query == null || query.isBlank() || chunks.isEmpty()) {
            return List.of();
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return List.of();

        Map<String, Double> queryVec = embed(queryTokens);

        List<MemoryChunk> results;
        lock.readLock().lock();
        try {
            results = chunks.stream()
                    .filter(c -> hasOverlap(queryTokens, c.vector()))
                    .map(c -> new ScoredChunk(c, cosineSimilarity(queryVec, c.vector())))
                    .filter(sc -> sc.score > 0.01)
                    .sorted(Comparator.comparingDouble((ScoredChunk sc) -> sc.score).reversed())
                    .limit(topK)
                    .map(sc -> sc.chunk)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }

        return results;
    }

    /**
     * Format search results as a context block for injection into the prompt.
     */
    public String formatResults(List<MemoryChunk> results) {
        if (results == null || results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            MemoryChunk c = results.get(i);
            String content = c.content();
            if (content.length() > 200) content = content.substring(0, 200) + "...";
            sb.append(i + 1).append(". [").append(c.source()).append("] ").append(content).append("\n");
        }
        return sb.toString().strip();
    }

    // ===== Persistence =====

    @SuppressWarnings("unchecked")
    public void loadFromDisk() {
        if (!Files.exists(storeFile)) return;
        lock.writeLock().lock();
        try {
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            List<Map<String, Object>> list = mapper.readValue(json, new TypeReference<>() {});
            chunks = new ArrayList<>();
            for (Map<String, Object> map : list) {
                chunks.add(MemoryChunk.fromStoreMap(map));
            }
            log.info("Loaded {} vector chunks from disk", chunks.size());
        } catch (Exception e) {
            log.warn("Failed to load vector store: {}", e.getMessage());
            chunks = new ArrayList<>();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void persist() {
        lock.writeLock().lock();
        try {
            Files.createDirectories(storeFile.getParent());
            List<Map<String, Object>> list = chunks.stream()
                    .map(MemoryChunk::toStoreMap)
                    .toList();
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(storeFile.toFile(), list);
            dirty = false;
            log.debug("Persisted {} vector chunks", chunks.size());
        } catch (IOException e) {
            log.error("Failed to persist vector store", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ===== Text Processing =====

    private void addChunksFromSource(List<MemoryChunk> target, String source, String content) {
        if (content == null || content.isBlank()) return;
        for (String chunkText : splitIntoChunks(content)) {
            if (chunkText.isBlank()) continue;
            String id = hash(source + "|" + chunkText);
            target.add(new MemoryChunk(id, chunkText.strip(), source, Map.of()));
        }
    }

    private List<String> splitIntoChunks(String content) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = content.split("\n{2,}");
        StringBuilder buffer = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            if (buffer.length() + trimmed.length() < 300) {
                if (!buffer.isEmpty()) buffer.append("\n");
                buffer.append(trimmed);
            } else {
                if (!buffer.isEmpty()) {
                    result.add(buffer.toString());
                    buffer.setLength(0);
                }
                if (trimmed.length() > 500) {
                    for (String line : trimmed.split("\n")) {
                        if (!line.isBlank()) result.add(line.strip());
                    }
                } else {
                    result.add(trimmed);
                }
            }
        }
        if (!buffer.isEmpty()) result.add(buffer.toString());

        return result;
    }

    private List<String> listJournalFiles(MemoryStore memoryStore) {
        List<String> contents = new ArrayList<>();
        try {
            Path journalDir = memoryStore.memoryDir().resolve("journal");
            if (!Files.exists(journalDir)) return contents;
            try (var stream = Files.list(journalDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .sorted()
                        .skip(Math.max(0, countFiles(journalDir) - 7))
                        .forEach(p -> {
                            try {
                                contents.add(Files.readString(p, StandardCharsets.UTF_8));
                            } catch (IOException ignored) {}
                        });
            }
        } catch (IOException ignored) {}
        return contents;
    }

    private long countFiles(Path dir) {
        try (var s = Files.list(dir)) { return s.count(); }
        catch (IOException e) { return 0; }
    }

    /**
     * Tokenize: character bigrams (CJK-friendly, no external NLP required).
     * Also extracts any ASCII words.
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();

        String normalized = text.toLowerCase()
                .replaceAll("[\\p{Punct}&&[^-]]", " ")
                .replaceAll("\\s+", " ")
                .strip();

        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < normalized.length()) {
            int cp = normalized.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (cp >= 0x4E00 && cp <= 0x9FFF) {
                // CJK character — emit bigram
                if (i + charCount < normalized.length()) {
                    int nextCp = normalized.codePointAt(i + charCount);
                    if (nextCp >= 0x4E00 && nextCp <= 0x9FFF) {
                        tokens.add(new String(Character.toChars(cp))
                                + new String(Character.toChars(nextCp)));
                    }
                }
                // Also emit unigram for recall
                tokens.add(new String(Character.toChars(cp)));
            } else if (Character.isLetterOrDigit(cp)) {
                // ASCII word
                int end = i;
                while (end < normalized.length() && Character.isLetterOrDigit(normalized.codePointAt(end))) {
                    end += Character.charCount(normalized.codePointAt(end));
                }
                String word = normalized.substring(i, end);
                if (word.length() > 1) tokens.add(word);
                i = end;
                continue;
            }
            i += charCount;
        }
        return tokens;
    }

    // ===== TF-IDF =====

    private void buildIdf(List<MemoryChunk> allChunks) {
        Map<String, Integer> docFreq = new HashMap<>();
        int n = allChunks.size();

        for (MemoryChunk chunk : allChunks) {
            Set<String> unique = new HashSet<>(tokenize(chunk.content()));
            for (String token : unique) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }

        idfCache.clear();
        for (var entry : docFreq.entrySet()) {
            idfCache.put(entry.getKey(), Math.log((double) n / (1 + entry.getValue())));
        }
    }

    private Map<String, Double> embed(List<String> tokens) {
        Map<String, Double> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1.0, Double::sum);

        double maxTf = tf.values().stream().mapToDouble(d -> d).max().orElse(1.0);
        Map<String, Double> vector = new HashMap<>();
        for (var e : tf.entrySet()) {
            double tfNorm = 0.5 + 0.5 * (e.getValue() / maxTf);
            double idf = idfCache.getOrDefault(e.getKey(), Math.log(100.0));
            vector.put(e.getKey(), tfNorm * idf);
        }
        return vector;
    }

    // ===== Similarity =====

    private double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Map<String, Double> smaller = a.size() <= b.size() ? a : b;
        Map<String, Double> larger = a.size() <= b.size() ? b : a;

        double dot = 0.0;
        for (var e : smaller.entrySet()) {
            Double v = larger.get(e.getKey());
            if (v != null) dot += e.getValue() * v;
        }
        if (dot == 0.0) return 0.0;

        double normA = norm(a), normB = norm(b);
        return (normA == 0 || normB == 0) ? 0.0 : dot / (normA * normB);
    }

    private double norm(Map<String, Double> vec) {
        double sum = 0.0;
        for (double v : vec.values()) sum += v * v;
        return Math.sqrt(sum);
    }

    private boolean hasOverlap(List<String> tokens, Map<String, Double> vector) {
        for (String t : tokens) {
            if (vector.containsKey(t)) return true;
        }
        return false;
    }

    private void deduplicate() {
        Set<String> seen = new HashSet<>();
        chunks.removeIf(c -> !seen.add(c.id()));
    }

    private String hash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return Integer.toHexString(h);
    }

    private record ScoredChunk(MemoryChunk chunk, double score) {}

    // Expose memoryDir for journal listing
    // Accessed via memoryStore.memoryDir (package-private)
}
