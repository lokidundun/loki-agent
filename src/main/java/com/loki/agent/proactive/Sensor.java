package com.loki.agent.proactive;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class Sensor {

    /**
     * Compute interruptibility: how okay it is to send a proactive message now.
     *
     * @param replyFactor  1.0 if user recently replied, decays over time
     * @param activityFactor 1.0 if user is active, lower if idle
     * @param fatigueFactor 0.0 at start of day, grows with proactive messages sent
     */
    public double computeInterruptibility(double replyFactor,
                                           double activityFactor,
                                           double fatigueFactor) {
        double jitter = 0.85 + ThreadLocalRandom.current().nextDouble() * 0.3; // 0.85–1.15
        return replyFactor * activityFactor * (1.0 - fatigueFactor) * jitter;
    }

    /**
     * Compute fatigue from number of proactive messages sent today.
     * 0 msgs -> 0.0, dailyMax msgs -> 1.0
     */
    public double fatigueFactor(int sentToday, int dailyMax) {
        if (dailyMax <= 0) return 1.0;
        return Math.min(1.0, (double) sentToday / dailyMax);
    }

    /**
     * Compute reply factor: how recently the user interacted.
     * Uses exponential decay: minutesSinceReply = 0 -> 1.0, large -> ~0
     */
    public double replyFactor(long minutesSinceReply) {
        return Math.exp(-minutesSinceReply / 15.0);
    }
}
