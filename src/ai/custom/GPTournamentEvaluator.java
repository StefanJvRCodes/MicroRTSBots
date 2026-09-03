package ai.custom;

import ai.core.AI;
import java.io.StringWriter;
import java.util.List;
import tournaments.RoundRobinTournament;
import rts.units.UnitTypeTable;

public final class GPTournamentEvaluator {
    private GPTournamentEvaluator() {
    }

    public static double[] evaluateRoundRobinWinRates(List<AI> ais,
                                                      List<String> maps,
                                                      UnitTypeTable utt,
                                                      int iterations,
                                                      int maxGameLength,
                                                      int timeBudget,
                                                      int iterationsBudget) throws Exception {
        RoundRobinTournament tournament = new RoundRobinTournament(ais);
        StringWriter out = new StringWriter();
        StringWriter progress = new StringWriter();
        tournament.runTournament(-1, maps, iterations, maxGameLength, timeBudget, iterationsBudget,
                0L, 0L, true, false, false, false, false, utt,
                null, out, progress, "tournament_evaluation");
        return tournament.getAverageWinRates();
    }

    public static double evaluateMeanWinRate(List<AI> ais,
                                             List<String> maps,
                                             UnitTypeTable utt,
                                             int iterations,
                                             int maxGameLength,
                                             int timeBudget,
                                             int iterationsBudget) throws Exception {
        double[] winRates = evaluateRoundRobinWinRates(ais, maps, utt, iterations, maxGameLength, timeBudget, iterationsBudget);
        double total = 0.0;
        int count = 0;
        for (double winRate : winRates) {
            if (Double.isFinite(winRate)) {
                total += winRate;
                count++;
            }
        }
        return count == 0 ? Double.NaN : total / count;
    }
}
