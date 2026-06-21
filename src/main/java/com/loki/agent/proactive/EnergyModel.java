package com.loki.agent.proactive;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class EnergyModel {

    // E(t) = 0.50*exp(-t/30) + 0.35*exp(-t/240) + 0.15*exp(-t/2880)
    // t = minutes since last user interaction
    public double computeEnergy(long minutesSinceLastUser) {
        double t = minutesSinceLastUser;
        return 0.50 * Math.exp(-t / 30.0)
             + 0.35 * Math.exp(-t / 240.0)
             + 0.15 * Math.exp(-t / 2880.0);
    }

    // d_energy = 1 - energy  (higher when energy is low, i.e. user has been away)
    public double dEnergy(double energy) {
        return 1.0 - energy;
    }

    // d_content = 1 - exp(-newItems / 3.0)
    public double dContent(int newItems) {
        return 1.0 - Math.exp(-newItems / 3.0);
    }

    // d_recent = log(1 + msgCount) / log(1 + 10)
    public double dRecent(int msgCount) {
        double result = Math.log(1 + msgCount) / Math.log(11);
        return Math.min(result, 1.0);
    }

    // composite_score = 0.40*d_energy + 0.40*d_content + 0.20*d_recent
    public double compositeScore(double de, double dc, double dr) {
        return 0.40 * de + 0.40 * dc + 0.20 * dr;
    }

    // Next tick interval (seconds) based on score
    public int nextTickFromScore(double score) {
        int base;
        if (score > 0.70)      base = 420;   // 7 min
        else if (score > 0.40) base = 1080;  // 18 min
        else if (score > 0.20) base = 2400;  // 40 min
        else                   base = 4800;  // 80 min

        // +/- 30% jitter
        double jitter = 0.7 + ThreadLocalRandom.current().nextDouble() * 0.6;
        return (int) (base * jitter);
    }
}
