package ai.evolution.gp;

import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Map;

public class GPEloTable {
    private final Map<String, Double> ratings = new ConcurrentHashMap<>();
    private final double initialRating;
    private final double k;
    private final double scale;
    private final double minMultiplier;
    private final double maxMultiplier;

    public GPEloTable(GPConfig cfg) {
        this.initialRating = cfg.eloInitialRating;
        this.k = cfg.eloK;
        this.scale = cfg.eloScale;
        this.minMultiplier = cfg.eloMinMultiplier;
        this.maxMultiplier = cfg.eloMaxMultiplier;
    }

    public double rating(String opponentKey) {
        return ratings.computeIfAbsent(opponentKey, key -> initialRating);
    }

    public double rewardMultiplier(String opponentKey) {
        double raw = rating(opponentKey) / initialRating;
        return Math.max(minMultiplier, Math.min(maxMultiplier, raw));
    }

    public void update(String opponentKey, double populationWinRateAgainst) {
        double r = rating(opponentKey);
        double expectedOpponentWin = 1.0 / (1.0 + Math.pow(10, (initialRating - r) / scale));
        double actualOpponentWin = 1.0 - populationWinRateAgainst;
        ratings.put(opponentKey, r + k * (actualOpponentWin - expectedOpponentWin));
    }

    public Map<String, Double> snapshot() {
        return new HashMap<>(ratings);
    }

    public void restore(Map<String, Double> restored) {
        ratings.clear();
        ratings.putAll(restored);
    }
}
