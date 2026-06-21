package com.loki.agent.proactive;

import com.loki.agent.llm.LlmProvider;
import com.loki.agent.memory.MemoryStore;
import com.loki.agent.session.SessionManager;
import com.loki.agent.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ProactiveLoop {

    private static final Logger log = LoggerFactory.getLogger(ProactiveLoop.class);

    private final EnergyModel energyModel;
    private final Judge judge;
    private final Sensor sensor;
    private final SessionManager sessionManager;
    private final SessionStore sessionStore;
    private final MemoryStore memoryStore;
    private final LlmProvider llmProvider;

    @Value("${loki.agent.proactive.enabled:false}")
    private boolean enabled;

    @Value("${loki.agent.proactive.daily-max:5}")
    private int dailyMax;

    @Value("${loki.agent.proactive.default-session:cli:console}")
    private String defaultSessionKey;

    private volatile boolean running = false;
    private int sentToday = 0;
    private Instant dayStart = Instant.now();

    public ProactiveLoop(EnergyModel energyModel, Judge judge, Sensor sensor,
                          SessionManager sessionManager, SessionStore sessionStore,
                          MemoryStore memoryStore, LlmProvider llmProvider) {
        this.energyModel = energyModel;
        this.judge = judge;
        this.sensor = sensor;
        this.sessionManager = sessionManager;
        this.sessionStore = sessionStore;
        this.memoryStore = memoryStore;
        this.llmProvider = llmProvider;
    }

    public void start() {
        if (!enabled) {
            log.info("ProactiveLoop disabled");
            return;
        }
        running = true;
        Thread thread = new Thread(this::runLoop, "proactive-loop");
        thread.setDaemon(true);
        thread.start();
        log.info("ProactiveLoop started, dailyMax={}", dailyMax);
    }

    public void stop() {
        running = false;
    }

    private void runLoop() {
        while (running) {
            try {
                int intervalSec = tick();
                log.debug("ProactiveLoop sleeping {}s", intervalSec);
                Thread.sleep(intervalSec * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("ProactiveLoop tick error", e);
                try { Thread.sleep(60_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private int tick() {
        // Reset daily counter at midnight
        resetDayIfNeeded();

        // 1. Get last user interaction time
        Instant lastUserAt = sessionStore.mostRecentUserAt(defaultSessionKey);
        if (lastUserAt == null) {
            log.debug("No lastUserAt for {}, skipping", defaultSessionKey);
            return 4800;
        }

        long minutesSinceLastUser = Duration.between(lastUserAt, Instant.now()).toMinutes();

        // 2. Compute energy
        double energy = energyModel.computeEnergy(minutesSinceLastUser);
        double dEnergy = energyModel.dEnergy(energy);

        // 3. Compute content dimension (from memory pending facts)
        String pending = memoryStore.readPending();
        int newItems = pending.isBlank() ? 0 : pending.split("\n").length;
        double dContent = energyModel.dContent(newItems);

        // 4. Compute recent dimension
        var session = sessionManager.getOrCreate(defaultSessionKey);
        int msgCount = session.messages().size();
        double dRecent = energyModel.dRecent(msgCount);

        // 5. Composite score
        double baseScore = energyModel.compositeScore(dEnergy, dContent, dRecent);
        log.debug("Tick: minutesAway={}, energy={}, dE={}, dC={}, dR={}, baseScore={}",
                minutesSinceLastUser, energy, dEnergy, dContent, dRecent, baseScore);

        // 6. If score high enough, try to compose and send
        if (baseScore > 0.40) {
            trySendProactive(minutesSinceLastUser, baseScore);
        }

        return energyModel.nextTickFromScore(baseScore);
    }

    private void trySendProactive(long minutesSinceLastUser, double baseScore) {
        // Pre-compose veto
        if (judge.preComposeVeto(sentToday, dailyMax)) {
            return;
        }

        // Compute interruptibility
        double replyFactor = sensor.replyFactor(minutesSinceLastUser);
        double activityFactor = Math.min(1.0, baseScore);
        double fatigue = sensor.fatigueFactor(sentToday, dailyMax);
        double interruptibility = sensor.computeInterruptibility(replyFactor, activityFactor, fatigue);

        // Compose candidate message via LLM
        String memoryContext = memoryStore.getMemoryContext();
        String candidate = composeCandidate(memoryContext, minutesSinceLastUser);
        if (candidate == null || candidate.isBlank()) {
            log.debug("LLM produced empty candidate, skipping");
            return;
        }

        // Judge
        List<String> recentProactive = getRecentProactive();
        Judge.JudgeResult result = judge.judgeMessage(
                candidate, recentProactive, interruptibility, minutesSinceLastUser);

        if (result.shouldSend()) {
            log.info("Proactive message approved (score={}): {}",
                    result.score(), truncate(candidate, 100));
            // Persist as outbound message — will be picked up by CliChannel
            sessionManager.getOrCreate(defaultSessionKey)
                    .addMessage("assistant", candidate);
            sessionManager.appendMessages(sessionManager.getOrCreate(defaultSessionKey));
            memoryStore.appendJournal("[proactive] " + truncate(candidate, 200));
            sentToday++;
        } else {
            log.debug("Proactive message rejected: {}", result.reason());
        }
    }

    private String composeCandidate(String memoryContext, long minutesAway) {
        String prompt = """
                You are Loki Agent. You haven't heard from the user in %d minutes.
                Compose ONE short, natural proactive message to send them.
                Use what you know about them from memory to make it relevant.

                Memory context:
                %s

                Rules:
                - Max 2 sentences
                - Don't ask "how are you" — say something useful or interesting
                - If you have nothing to say, reply with exactly: [SKIP]
                - Respond in the same language the user likely uses
                """.formatted(minutesAway, memoryContext.isBlank() ? "(none)" : memoryContext);

        try {
            var response = llmProvider.chat(
                    List.of(Map.of("role", "user", "content", prompt)),
                    List.of(), null, 300);
            String content = response.content();
            if (content == null) return null;
            content = content.strip();
            if (content.equals("[SKIP]") || content.contains("[SKIP]")) return null;
            return content;
        } catch (Exception e) {
            log.warn("Failed to compose proactive message: {}", e.getMessage());
            return null;
        }
    }

    private List<String> getRecentProactive() {
        // Return recent assistant messages from journal that start with [proactive]
        try {
            String journal = memoryStore.readRecentContext();
            List<String> result = new ArrayList<>();
            if (journal != null) {
                for (String line : journal.split("\n")) {
                    if (line.contains("[proactive]")) {
                        result.add(line.trim());
                    }
                }
            }
            return result.subList(Math.max(0, result.size() - 5), result.size());
        } catch (Exception e) {
            return List.of();
        }
    }

    private void resetDayIfNeeded() {
        Instant now = Instant.now();
        if (Duration.between(dayStart, now).toHours() >= 24) {
            sentToday = 0;
            dayStart = now;
            log.info("Proactive daily counter reset");
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
