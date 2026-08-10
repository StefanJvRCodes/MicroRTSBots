package ai.evolution.gp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class GPMatchupSampler {
    private GPMatchupSampler() {}

    public static List<GPMatch.EvaluationCase> allCases(String[] allMapPaths, String[] selectedMapPaths,
                                                         String[] opponents) {
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < allMapPaths.length; i++) indices.put(allMapPaths[i], i);
        List<GPMatch.EvaluationCase> cases = new ArrayList<>();
        for (String mapPath : selectedMapPaths) {
            Integer mapIndex = indices.get(mapPath);
            if (mapIndex == null) {
                throw new IllegalArgumentException("Curriculum map is not in GPConfig.maps: " + mapPath);
            }
            for (String opponent : opponents) {
                cases.add(new GPMatch.EvaluationCase(mapIndex, opponent));
            }
        }
        return cases;
    }

    public static List<GPMatch.EvaluationCase> sample(List<GPMatch.EvaluationCase> pool, int count,
                                                      int generation, long seed) {
        return sample(pool, count, generation, seed, null);
    }

    public static List<GPMatch.EvaluationCase> sample(List<GPMatch.EvaluationCase> pool, int count,
                                                      int generation, long seed, String[] mapPaths) {
        if (pool.isEmpty()) throw new IllegalArgumentException("Training case pool must not be empty");
        if (count <= 0 || count >= pool.size()) return new ArrayList<>(pool);
        List<GPMatch.EvaluationCase> remaining = new ArrayList<>(pool);
        Collections.shuffle(remaining, new Random(seed ^ (0x9E3779B97F4A7C15L * (generation + 1L))));
        List<GPMatch.EvaluationCase> result = new ArrayList<>(count);
        Map<String, Integer> opponentGroups = new HashMap<>();
        Map<String, Integer> mapGroups = new HashMap<>();
        Map<String, Integer> exactOpponents = new HashMap<>();
        while (result.size() < count) {
            int bestIndex = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                GPMatch.EvaluationCase candidate = remaining.get(i);
                String opponentGroup = opponentGroup(candidate.opponentName);
                String mapGroup = mapGroup(candidate.mapIndex, mapPaths);
                double score = unseenBonus(opponentGroups, opponentGroup, 100.0)
                        + unseenBonus(mapGroups, mapGroup, 50.0)
                        + 10.0 / (1 + count(exactOpponents, candidate.opponentName))
                        + 5.0 / (1 + count(opponentGroups, opponentGroup))
                        + 2.0 / (1 + count(mapGroups, mapGroup));
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }
            GPMatch.EvaluationCase selected = remaining.remove(bestIndex);
            result.add(selected);
            increment(opponentGroups, opponentGroup(selected.opponentName));
            increment(mapGroups, mapGroup(selected.mapIndex, mapPaths));
            increment(exactOpponents, selected.opponentName);
        }
        return result;
    }

    static String opponentGroup(String opponent) {
        if (opponent.contains("Defense")) return "defense";
        if (opponent.contains("Economy") || opponent.startsWith("EMR")) return "economy";
        if (opponent.contains("Rush")) return "rush";
        return "other";
    }

    static String mapGroup(int mapIndex, String[] mapPaths) {
        if (mapPaths == null || mapIndex < 0 || mapIndex >= mapPaths.length) {
            return "map-" + Math.floorMod(mapIndex, 3);
        }
        String path = mapPaths[mapIndex];
        if (path.contains("Obstacle") || path.contains("TwoBases") || path.contains("Distant")
                || path.contains("chambers") || path.contains("barricades")) return "terrain";
        if (path.contains("24x24") || path.contains("32x32") || path.contains("64x64")
                || path.contains("BroodWar") || path.contains("Garden")) return "large";
        if (path.contains("12x12") || path.contains("16x16")) return "medium";
        return "small";
    }

    private static double unseenBonus(Map<String, Integer> counts, String key, double bonus) {
        return count(counts, key) == 0 ? bonus : 0;
    }

    private static int count(Map<String, Integer> counts, String key) {
        Integer value = counts.get(key);
        return value == null ? 0 : value;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.put(key, count(counts, key) + 1);
    }
}
