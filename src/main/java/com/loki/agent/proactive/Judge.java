package com.loki.agent.proactive;

import com.loki.agent.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class Judge {

    private static final Logger log = LoggerFactory.getLogger(Judge.class);

    private static final double THRESHOLD = 0.60;
    private static final double BALANCE_VETO = 0.1;

    private final LlmProvider llmProvider;

    public Judge(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    public record JudgeResult(double score, boolean shouldSend, String reason) {}

    // Deterministic pre-compose veto
    public boolean preComposeVeto(int sentToday, int dailyMax) {
        if (sentToday >= dailyMax) {
            log.debug("Judge: veto — sentToday {} >= dailyMax {}", sentToday, dailyMax);
            return true;
        }
        return false;
    }

    public JudgeResult judgeMessage(String candidateMessage,
                                     List<String> recentProactive,
                                     double interruptFactor,
                                     long ageMinutes) {
        // --- Deterministic dimensions ---
        double urgency = computeUrgency(ageMinutes);
        double balance = computeBalance(recentProactive);
        double dynamics = interruptFactor;

        if (balance < BALANCE_VETO) {
            return new JudgeResult(0.0, false,
                    "balance too low (" + balance + ")");
        }

        // --- LLM-scored dimensions (scored 1-5, normalized to 0-1) ---
        Map<String, Double> llmScores = scoreWithLlm(candidateMessage, recentProactive);

        double informationGap = llmScores.getOrDefault("information_gap", 3.0) / 5.0;
        double relevance       = llmScores.getOrDefault("relevance", 3.0) / 5.0;
        double expectedImpact  = llmScores.getOrDefault("expected_impact", 3.0) / 5.0;

        // Weighted final score
        // deterministic: urgency(0.10), balance(0.10), dynamics(0.10)
        // LLM: information_gap(0.25), relevance(0.20), expected_impact(0.25)
        double finalScore = 0.10 * urgency
                          + 0.10 * balance
                          + 0.10 * dynamics
                          + 0.25 * informationGap
                          + 0.20 * relevance
                          + 0.25 * expectedImpact;

        boolean shouldSend = finalScore >= THRESHOLD;

        log.debug("Judge: urgency={}, balance={}, dynamics={}, infoGap={}, relevance={}, impact={} -> final={} send={}",
                urgency, balance, dynamics, informationGap, relevance, expectedImpact,
                finalScore, shouldSend);

        return new JudgeResult(finalScore, shouldSend,
                shouldSend ? "approved" : "score below threshold (" + finalScore + ")");
    }

    private double computeUrgency(long ageMinutes) {
        // Higher urgency when user has been away longer
        return Math.min(1.0, ageMinutes / 120.0);
    }

    private double computeBalance(List<String> recentProactive) {
        // Balance: how many of last 10 messages were proactive
        // Fewer proactive messages -> higher balance score
        int proactiveCount = recentProactive.size();
        return Math.max(0.0, 1.0 - proactiveCount / 10.0);
    }

    private Map<String, Double> scoreWithLlm(String candidateMessage,
                                              List<String> recentProactive) {
        String recentStr = recentProactive.isEmpty()
                ? "(none)"
                : String.join("\n- ", recentProactive);

        String prompt = """
                Score this proactive message on 3 dimensions (1-5 each).
                Reply ONLY with JSON: {"information_gap":N,"relevance":N,"expected_impact":N}

                Candidate message:
                "%s"

                Recent proactive messages sent:
                - %s

                Scoring guide:
                - information_gap: Does this add info the user doesn't already have? (5=highly novel, 1=already known)
                - relevance: Is this useful/relevant to the user right now? (5=very relevant, 1=not relevant)
                - expected_impact: Will the user appreciate receiving this? (5=will be glad, 1=will be annoyed)
                """.formatted(candidateMessage, recentStr);

        try {
            var response = llmProvider.chat(
                    List.of(Map.of("role", "user", "content", prompt)),
                    List.of(), null, 200);
            String content = response.content();
            if (content == null || content.isBlank()) return Map.of();

            // Extract JSON from response
            content = content.strip();
            if (content.startsWith("```")) {
                content = content.replaceAll("```json\\s*", "").replaceAll("```", "").strip();
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(content, Map.class);

            return Map.of(
                    "information_gap", toDouble(parsed.get("information_gap")),
                    "relevance", toDouble(parsed.get("relevance")),
                    "expected_impact", toDouble(parsed.get("expected_impact"))
            );
        } catch (Exception e) {
            log.warn("LLM scoring failed, using defaults: {}", e.getMessage());
            return Map.of("information_gap", 3.0, "relevance", 3.0, "expected_impact", 3.0);
        }
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 3.0;
    }
}
