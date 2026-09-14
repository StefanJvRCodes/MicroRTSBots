package ai.evolution.gp;

import rts.PhysicalGameState;
import rts.units.UnitTypeTable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Entry point for {@code make play} and {@code make holdout}: benchmarks a saved bot file against a
 * list of opponents, both sides, with win/tie/loss counts and a 95% Wilson interval on the win rate.
 */
public class GPPlay {

    public static void main(String[] args) throws Exception {
        GPConfig cfg = GPConfig.fromArgs(args);
        String[] mapPaths = cfg.playHoldout ? cfg.holdoutMaps : new String[]{cfg.playMap};
        String[] opponents = cfg.playHoldout ? cfg.holdoutOpponents : cfg.playOpponents;
        if (cfg.playHoldout) {
            assertDisjoint(cfg.maps, mapPaths, "map");
            assertDisjoint(cfg.opponents, opponents, "opponent");
        }

        UnitTypeTable utt = new UnitTypeTable(cfg.unitTypeTableVersion, cfg.conflictPolicy);
        int totalWins = 0, totalTies = 0, totalLosses = 0;
        double worstScore = 1.0;

        for (String mapPath : mapPaths) {
            PhysicalGameState map = PhysicalGameState.load(mapPath, utt);
            for (String opponent : opponents) {
                int[] wins = new int[2], ties = new int[2], losses = new int[2];
                int limited = 0;
                long cycles = 0;
                for (int i = 0; i < cfg.playIterations; i++) {
                    long seed = cfg.evaluationSeed + 2L * i;
                    for (int side = 0; side < 2; side++) {
                        StructuredGPAI bot = new StructuredGPAI(utt, cfg.playBotFile);
                        GPMatch.GameResult r = side == 0
                                ? GPMatch.playOneGame(bot, GPOpponents.build(opponent, utt, seed), map, utt, cfg, 0, cfg.playVisualize)
                                : GPMatch.playOneGame(GPOpponents.build(opponent, utt, seed + 1), bot, map, utt, cfg, 1, cfg.playVisualize);
                        if (r.score() == 1.0) wins[side]++;
                        else if (r.score() == 0.5) ties[side]++;
                        else losses[side]++;
                        if (r.endedByLimit()) limited++;
                        cycles += r.cycles();
                    }
                }
                int w = wins[0] + wins[1], t = ties[0] + ties[1], l = losses[0] + losses[1];
                int games = w + t + l;
                double score = (w + 0.5 * t) / games;
                double[] ci = wilsonInterval(w, games, 1.96);
                worstScore = Math.min(worstScore, score);
                totalWins += w;
                totalTies += t;
                totalLosses += l;
                System.out.printf("%s | vs %-24s: %dW %dT %dL | score %.3f | win %.3f (95%% CI %.3f..%.3f) | mean cycles %.0f%n",
                        mapPath, opponent, w, t, l, score, w / (double) games, ci[0], ci[1], cycles / (double) games);
                System.out.printf("  as P0 %dW %dT %dL | as P1 %dW %dT %dL | hit cycle/inactivity limit %d/%d%n",
                        wins[0], ties[0], losses[0], wins[1], ties[1], losses[1], limited, games);
            }
        }
        int games = totalWins + totalTies + totalLosses;
        System.out.printf("OVERALL: %dW %dT %dL | score %.3f | worst matchup score %.3f | maps=%s%n",
                totalWins, totalTies, totalLosses, games == 0 ? 0 : (totalWins + 0.5 * totalTies) / games,
                worstScore, Arrays.toString(mapPaths));
    }

    static double[] wilsonInterval(int successes, int trials, double z) {
        if (trials == 0) return new double[]{0, 0};
        double p = successes / (double) trials;
        double z2 = z * z;
        double denominator = 1 + z2 / trials;
        double centre = (p + z2 / (2 * trials)) / denominator;
        double radius = z * Math.sqrt((p * (1 - p) + z2 / (4 * trials)) / trials) / denominator;
        return new double[]{Math.max(0, centre - radius), Math.min(1, centre + radius)};
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
