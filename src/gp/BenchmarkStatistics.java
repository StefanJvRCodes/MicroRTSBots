package gp;

import ai.core.AI;
import bots.EvolvedBot;
import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPIndividual;
import ec.simple.SimpleStatistics;
import ec.util.Parameter;
import eval.Panel;
import rts.units.UnitTypeTable;

/**
 * At the end of evolution, take the best individual and play it against the full
 * scripted panel, printing the same W/T/L table your teammate's branch produces.
 *
 * Doing the benchmark INSIDE the run avoids having to serialize and reload an evolved
 * tree, which is the fiddliest part of ECJ. Persistence can come later; a comparable
 * number today is worth more.
 *
 * IMPORTANT: these opponents include the ones the GP trained against. Numbers against
 * those are training performance, not a result. The honest figures are the held-out
 * ones -- keep the training list short and read the rest of the table as the real test.
 */
public class BenchmarkStatistics extends SimpleStatistics {

    private static final long serialVersionUID = 1L;

    public static final String P_BENCH_MAPS = "bench-maps";
    public static final String P_BENCH_OPPONENTS = "bench-opponents";
    public static final String P_BENCH_GAMES = "bench-games";
    public static final String P_BENCH_MAX_CYCLES = "bench-max-cycles";

    public String[] benchMaps;
    public String[] benchOpponents;
    public int gamesPer;
    public int maxCycles;

    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);
        benchMaps = split(state.parameters.getString(base.push(P_BENCH_MAPS), null));
        String opps = state.parameters.getString(base.push(P_BENCH_OPPONENTS), null);
        benchOpponents = (opps == null || opps.trim().equalsIgnoreCase("all"))
                ? Panel.FULL_PANEL : split(opps);
        gamesPer = state.parameters.getIntWithDefault(base.push(P_BENCH_GAMES), null, 20);
        maxCycles = state.parameters.getIntWithDefault(base.push(P_BENCH_MAX_CYCLES), null, 3000);
    }

    private static String[] split(String s) {
        return (s == null) ? new String[0] : s.trim().split("\\s*,\\s*");
    }

    @Override
    public void finalStatistics(final EvolutionState state, final int result) {
        super.finalStatistics(state, result);

        if (benchMaps.length == 0) return;

        Individual best = null;
        for (Individual ind : state.population.subpops.get(0).individuals) {
            if (best == null || ind.fitness.betterThan(best.fitness)) best = ind;
        }
        if (!(best instanceof GPIndividual)) return;

        MicroRTSProblem problem = (MicroRTSProblem) state.evaluator.p_problem;
        UnitTypeTable utt = problem.utt;
        ScoreData data = (ScoreData) problem.input;

        state.output.message("");
        state.output.message("=== Benchmark of best-of-run individual ===");
        state.output.message("maps: " + String.join(", ", benchMaps)
                + "   games per opponent: " + gamesPer + "   cycle cap: " + maxCycles);

        long worstCycleMs = 0;

        for (String opponent : benchOpponents) {
            int w = 0, t = 0, l = 0;
            for (String map : benchMaps) {
                for (int g = 0; g < gamesPer; g++) {
                    try {
                        EvolvedBot ours = new EvolvedBot(utt, (GPIndividual) best, state, 0,
                                                         problem, data, problem.stack);
                        AI theirs = Panel.make(opponent, utt);
                        // Alternate sides so first-player advantage cancels.
                        Panel.Result r = Panel.play(ours, theirs, map, utt, g % 2, maxCycles);
                        if (r.won()) w++;
                        else if (r.drew()) t++;
                        else l++;
                        worstCycleMs = Math.max(worstCycleMs, ours.worstCycleMillis);
                    } catch (Exception e) {
                        l++;
                    }
                }
            }
            int n = w + t + l;
            state.output.message(String.format("vs %-22s: %3dW %3dT %3dL / %3d games  (win rate %.0f%%)",
                    opponent, w, t, l, n, n == 0 ? 0.0 : 100.0 * w / n));
        }

        state.output.message("");
        state.output.message("Worst per-cycle decision time: " + worstCycleMs
                + " ms   (G1 gate is 100 ms)");
    }
}
