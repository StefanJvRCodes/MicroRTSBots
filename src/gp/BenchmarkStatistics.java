package gp;

import ai.core.AI;
import bots.EvolvedBot;
import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.gp.GPTree;
import ec.simple.SimpleStatistics;
import ec.util.Parameter;
import eval.Panel;
import rts.units.UnitTypeTable;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * At the end of evolution, take the best individual OF THE RUN, write it to disk, and
 * play it against the full scripted panel.
 *
 * BEST OF RUN, NOT BEST OF FINAL POPULATION. An earlier version scanned state.population
 * at finalStatistics time. Those are not the same individual -- elitism does not
 * guarantee the run's best survives, and on one run the best of run was 1.4101 from
 * generation 53 while the final generation's best was 1.4202. The benchmark therefore
 * reported a different, slightly worse bot than the one evolution had found, which is
 * how a run recording Hits=1 could benchmark as 10T against the opponent it had beaten.
 * SimpleStatistics tracks best_of_run across generations; use it.
 *
 * PRINTING THE TREE. Two ECJ methods have misleading names and neither does what is
 * wanted here:
 *
 *   genotypeToStringForHumans()  falls through to Object.toString() for a GPIndividual.
 *                                It produced the literal line
 *                                "ec.gp.GPIndividual@143110009{1547381312}" where a tree
 *                                was expected -- a 50,000-evaluation run whose result
 *                                could not be read.
 *   genotypeToString()           does not emit the trees either, and produced a 40-byte
 *                                .ind file that could never have been reloaded.
 *
 * The reliable calls are GPNode.makeLispTree() for the readable form and
 * Individual.printIndividual(state, PrintWriter) for the reloadable one. Both are used
 * below, and the .txt now also records node count and depth so tree bloat is visible in
 * the archive rather than only in the .stat file.
 *
 * PERSISTENCE. Three files per run, stamped with seed and timestamp so repeated runs
 * never overwrite each other:
 *
 *   best-<stamp>.txt   Lisp tree, size, depth, fitness, config, benchmark table.
 *   best-<stamp>.ind   ECJ parseable format, for reloading.
 *   bench-<stamp>.csv  one row per opponent.
 *
 * IMPORTANT: the panel includes the training opponents. Numbers against those are
 * TRAINING performance, not a result, and are marked as such in both outputs.
 */
public class BenchmarkStatistics extends SimpleStatistics {

    private static final long serialVersionUID = 1L;

    public static final String P_BENCH_MAPS = "bench-maps";
    public static final String P_BENCH_OPPONENTS = "bench-opponents";
    public static final String P_BENCH_GAMES = "bench-games";
    public static final String P_BENCH_MAX_CYCLES = "bench-max-cycles";
    public static final String P_RESULTS_DIR = "results-dir";

    public String[] benchMaps;
    public String[] benchOpponents;
    public int gamesPer;
    public int maxCycles;
    public String resultsDir;

    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);
        benchMaps = split(state.parameters.getString(base.push(P_BENCH_MAPS), null));
        String opps = state.parameters.getString(base.push(P_BENCH_OPPONENTS), null);
        benchOpponents = (opps == null || opps.trim().equalsIgnoreCase("all"))
                ? Panel.FULL_PANEL : split(opps);
        gamesPer = state.parameters.getIntWithDefault(base.push(P_BENCH_GAMES), null, 20);
        maxCycles = state.parameters.getIntWithDefault(base.push(P_BENCH_MAX_CYCLES), null, 3000);
        resultsDir = state.parameters.getStringWithDefault(base.push(P_RESULTS_DIR), null, "results");
    }

    private static String[] split(String s) {
        return (s == null) ? new String[0] : s.trim().split("\\s*,\\s*");
    }

    @Override
    public void finalStatistics(final EvolutionState state, final int result) {
        super.finalStatistics(state, result);

        if (benchMaps.length == 0) return;

        Individual best = bestOfRun(state);
        if (!(best instanceof GPIndividual)) return;
        GPIndividual gpBest = (GPIndividual) best;

        MicroRTSProblem problem = (MicroRTSProblem) state.evaluator.p_problem;
        UnitTypeTable utt = problem.utt;
        ScoreData data = (ScoreData) problem.input;

        String stamp = stamp(state);

        state.output.message("");
        state.output.message("=== Benchmark of best-of-run individual ===");
        state.output.message("fitness: " + best.fitness.fitnessToStringForHumans());
        state.output.message("tree: " + describeTrees(gpBest));
        state.output.message("maps: " + String.join(", ", benchMaps)
                + "   games per opponent: " + gamesPer + "   cycle cap: " + maxCycles);

        long worstCycleMs = 0;
        List<String> csv = new ArrayList<>();
        List<String> table = new ArrayList<>();
        csv.add("opponent,wins,draws,losses,games,win_rate,trained_against");

        for (String opponent : benchOpponents) {
            int w = 0, t = 0, l = 0;
            for (String map : benchMaps) {
                for (int g = 0; g < gamesPer; g++) {
                    try {
                        EvolvedBot ours = new EvolvedBot(utt, gpBest, state, 0,
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
            double rate = (n == 0) ? 0.0 : 100.0 * w / n;
            boolean trained = wasTrainedAgainst(problem, opponent);
            String line = String.format("vs %-22s: %3dW %3dT %3dL / %3d games  (win rate %.0f%%)%s",
                    opponent, w, t, l, n, rate, trained ? "   [TRAINING]" : "");
            state.output.message(line);
            table.add(line);
            csv.add(String.format("%s,%d,%d,%d,%d,%.1f,%s",
                    opponent, w, t, l, n, rate, trained ? "yes" : "no"));
        }

        state.output.message("");
        state.output.message("Worst per-cycle decision time: " + worstCycleMs
                + " ms   (G1 gate is 100 ms)");

        writeArtifacts(state, gpBest, problem, stamp, table, csv, worstCycleMs);
    }

    /**
     * SimpleStatistics keeps best_of_run per subpopulation, updated every generation.
     * Scanning the final population is only a fallback.
     */
    private Individual bestOfRun(final EvolutionState state) {
        if (best_of_run != null && best_of_run.length > 0 && best_of_run[0] != null) {
            return best_of_run[0];
        }
        Individual best = null;
        for (Individual ind : state.population.subpops.get(0).individuals) {
            if (best == null || ind.fitness.betterThan(best.fitness)) best = ind;
        }
        state.output.warning("best_of_run was unavailable; benchmarking best of final "
                + "population instead, which may not be the best individual of the run.");
        return best;
    }

    private static boolean wasTrainedAgainst(MicroRTSProblem problem, String opponent) {
        if (problem.opponents == null) return false;
        for (String o : problem.opponents) {
            if (o.equalsIgnoreCase(opponent)) return true;
        }
        return false;
    }

    private static String stamp(EvolutionState state) {
        String seed = state.parameters.getString(new Parameter("seed.0"), null);
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "seed" + (seed == null ? "x" : seed.trim()) + "-" + when;
    }

    /** One-line size summary, e.g. "1 tree, 143 nodes, depth 9". */
    private static String describeTrees(final GPIndividual ind) {
        if (ind.trees == null || ind.trees.length == 0) return "(no trees)";
        int nodes = 0;
        int depth = 0;
        for (GPTree t : ind.trees) {
            if (t == null || t.child == null) continue;
            nodes += t.child.numNodes(GPNode.NODESEARCH_ALL);
            depth = Math.max(depth, t.child.depth());
        }
        return ind.trees.length + " tree(s), " + nodes + " nodes, depth " + depth;
    }

    /**
     * The readable scoring function. makeLispTree() is the call that actually renders a
     * GP tree; the genotypeTo*() convenience methods do not.
     */
    private static String lispForm(final GPIndividual ind) {
        if (ind.trees == null || ind.trees.length == 0) return "(no trees)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ind.trees.length; i++) {
            GPTree t = ind.trees[i];
            if (ind.trees.length > 1) sb.append("tree ").append(i).append(":\n");
            sb.append(t == null || t.child == null ? "(empty)" : t.child.makeLispTree());
            sb.append('\n');
        }
        return sb.toString();
    }

    /** ECJ's parseable individual format -- the one a loader can read back. */
    private static String reloadableForm(final EvolutionState state, final Individual ind) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ind.printIndividual(state, pw);
        pw.flush();
        return sw.toString();
    }

    private void writeArtifacts(final EvolutionState state, final GPIndividual best,
                                final MicroRTSProblem problem, final String stamp,
                                final List<String> table, final List<String> csv,
                                final long worstCycleMs) {
        try {
            Path dir = Paths.get(resultsDir);
            Files.createDirectories(dir);

            StringBuilder txt = new StringBuilder();
            txt.append("microRTS GP -- best of run\n");
            txt.append("==========================\n\n");
            txt.append("stamp             : ").append(stamp).append('\n');
            txt.append("generations       : ").append(state.numGenerations).append('\n');
            txt.append("population        : ")
               .append(state.population.subpops.get(0).individuals.size()).append('\n');
            txt.append("training maps     : ").append(join(problem.maps)).append('\n');
            txt.append("training opponents: ").append(join(problem.opponents)).append('\n');
            txt.append("repeats           : ").append(problem.repeats)
               .append("  (stochastic opponents only)\n");
            txt.append("cycle cap         : ").append(problem.maxCycles).append('\n');
            txt.append("win bonus         : ").append(problem.winBonus).append('\n');
            txt.append("eval range        : [").append(problem.evalLo).append(", ")
               .append(problem.evalHi).append("]\n");
            txt.append("fitness           : ").append(best.fitness.fitnessToStringForHumans())
               .append('\n');
            txt.append("tree              : ").append(describeTrees(best)).append('\n');
            txt.append("worst cycle (ms)  : ").append(worstCycleMs).append("   (G1 gate 100)\n");

            txt.append("\nEvolved scoring function\n------------------------\n");
            txt.append(lispForm(best)).append('\n');

            txt.append("\nBenchmark\n---------\n");
            for (String line : table) txt.append(line).append('\n');
            txt.append("\nNOTE: rows marked [TRAINING] are training performance, not a result.\n");

            write(dir.resolve("best-" + stamp + ".txt"), txt.toString());
            write(dir.resolve("best-" + stamp + ".ind"), reloadableForm(state, best));
            write(dir.resolve("bench-" + stamp + ".csv"), String.join("\n", csv) + "\n");

            state.output.message("");
            state.output.message("Wrote " + new File(resultsDir).getAbsolutePath()
                    + File.separator + "best-" + stamp + ".{txt,ind} and bench-" + stamp + ".csv");
        } catch (IOException e) {
            // Never let an unwritable results directory destroy a finished run: the
            // benchmark table has already been printed to stdout above.
            state.output.warning("Could not write results to '" + resultsDir + "': " + e
                    + " -- the table above is still valid, but this run was not archived.");
        }
    }

    private static String join(String[] a) {
        return (a == null || a.length == 0) ? "(none)" : String.join(", ", a);
    }

    private static void write(Path p, String content) throws IOException {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }
}