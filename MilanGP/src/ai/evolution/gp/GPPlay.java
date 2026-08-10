package ai.evolution.gp;

import rts.PhysicalGameState;
import rts.units.UnitTypeTable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class GPPlay {
    public static void main(String[] args) throws Exception {
        GPConfig cfg = GPConfig.fromArgs(args);
        String botFile = cfg.playBotFile;
        int iterations = cfg.playIterations;
        boolean visualize = cfg.playVisualize;
        String[] mapPaths = cfg.playHoldout ? cfg.holdoutMaps : new String[]{cfg.playMap};
        String[] opponents = cfg.playHoldout ? cfg.holdoutOpponents : cfg.playOpponents;
        if (cfg.playHoldout) assertDisjoint(cfg.maps, mapPaths, "map");
        if (cfg.playHoldout) assertDisjoint(cfg.opponents, opponents, "opponent");

        UnitTypeTable utt = new UnitTypeTable(cfg.unitTypeTableVersion, cfg.conflictPolicy);
        double worstScore = 1.0;
        int totalWins = 0, totalTies = 0, totalLosses = 0;

        for (String mapPath : mapPaths) {
            PhysicalGameState map = PhysicalGameState.load(mapPath, utt);
            for (String rawName : opponents) {
                String opponentName = rawName.trim();
                if (opponentName.isEmpty()) continue;

                int wins = 0, ties = 0, losses = 0, games = 0;
                int p0Wins = 0, p0Ties = 0, p0Losses = 0;
                int p1Wins = 0, p1Ties = 0, p1Losses = 0;
                int limitedGames = 0;
                long totalCycles = 0;
                for (int i = 0; i < iterations; i++) {
                    long seed = cfg.evaluationSeed + 2L * i;
                    GPMatch.Result r1 = GPMatch.playOneGame(new StructuredGPAI(utt, botFile),
                            GPOpponents.build(opponentName, utt, seed),
                            map, utt, cfg.maxCycles, cfg.maxInactiveCycles, 0,
                            visualize, cfg.playVisualDelayMillis);
                    games++;
                    totalCycles += r1.cycles;
                    if (r1.score == 1.0) { wins++; p0Wins++; }
                    else if (r1.score == 0.5) { ties++; p0Ties++; }
                    else { losses++; p0Losses++; }
                    if (r1.endedByLimit) limitedGames++;

                    GPMatch.Result r2 = GPMatch.playOneGame(GPOpponents.build(opponentName, utt, seed + 1),
                            new StructuredGPAI(utt, botFile),
                            map, utt, cfg.maxCycles, cfg.maxInactiveCycles, 1,
                            visualize, cfg.playVisualDelayMillis);
                    games++;
                    totalCycles += r2.cycles;
                    if (r2.score == 1.0) { wins++; p1Wins++; }
                    else if (r2.score == 0.5) { ties++; p1Ties++; }
                    else { losses++; p1Losses++; }
                    if (r2.endedByLimit) limitedGames++;
                }
                double strictWinRate = wins / (double) games;
                double scoreRate = (wins + 0.5 * ties) / games;
                double[] interval = wilsonInterval(wins, games, 1.96);
                worstScore = Math.min(worstScore, scoreRate);
                totalWins += wins;
                totalTies += ties;
                totalLosses += losses;
                System.out.printf("%s | vs %-20s: %dW %dT %dL | score %.3f | win %.3f "
                                + "(95%% CI %.3f..%.3f) | mean cycles %.0f%n",
                        mapPath, opponentName, wins, ties, losses, scoreRate, strictWinRate,
                        interval[0], interval[1], totalCycles / (double) games);
                System.out.printf("  sides: GP-as-P0 %dW %dT %dL | GP-as-P1 %dW %dT %dL"
                                + " | cycle/inactivity limits %d/%d%n",
                        p0Wins, p0Ties, p0Losses, p1Wins, p1Ties, p1Losses,
                        limitedGames, games);
            }
        }
        int games = totalWins + totalTies + totalLosses;
        System.out.printf("OVERALL: %dW %dT %dL | score %.3f | worst matchup score %.3f | maps=%s%n",
                totalWins, totalTies, totalLosses,
                games == 0 ? 0 : (totalWins + 0.5 * totalTies) / games,
                worstScore, Arrays.toString(mapPaths));
    }

    static double[] wilsonInterval(int successes, int trials, double z) {
        if (trials == 0) return new double[]{0, 0};
        double p = successes / (double) trials;
        double z2 = z * z;
        double denominator = 1 + z2 / trials;
        double center = (p + z2 / (2 * trials)) / denominator;
        double radius = z * Math.sqrt((p * (1 - p) + z2 / (4 * trials)) / trials) / denominator;
        return new double[]{Math.max(0, center - radius), Math.min(1, center + radius)};
    }

    private static void assertDisjoint(String[] training, String[] holdout, String label) {
        Set<String> used = new HashSet<>(Arrays.asList(training));
        for (String value : holdout) {
            if (used.contains(value)) {
                throw new IllegalArgumentException("Holdout " + label + " also appears in training: " + value);
            }
        }
    }
}
