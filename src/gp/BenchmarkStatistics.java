package gp;

import ai.core.AI;
import bots.EvolvedBot;
import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.gp.GPTree;
import ec.gp.koza.KozaFitness;
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
import java.util.Locale;

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
 * ------------------------------------------------------------------------------------
 * A FAILURE IS NOT A LOSS. This is the change that matters most in this file.
 *
 * The previous version wrapped opponent CONSTRUCTION and the game in one try block with
 * a bare `catch (Exception e) { l++; }`. So an opponent that could not be built at all --
 * missing jar, class not on the classpath, unexpected constructor -- was recorded as
 * "0W 0T 10L", which is indistinguishable from a real benchmark result and would have
 * gone into the write-up as one. That is a live hazard right now: run.sh does not put
 * lib/bots/* on the classpath, run-watch.sh does, and whatever launches ECJ is a third
 * case. Adding Coac and mayari to the panel under the old code would have produced a
 * clean-looking 0-0-10 against both, with no error anywhere.
 *
 * Now: each opponent is PROBED once before its games. A probe failure produces an ERROR
 * row carrying the reason, and no games are played or counted. A failure during an
 * individual game is counted separately as an error, never folded into losses. Any row
 * with errors is flagged in the console output, in the .txt and in a status column in
 * the CSV, so a downstream reader cannot mistake it for a measurement.
 *
 * The probe catches Throwable, not Exception, deliberately: a class that resolves but
 * whose dependencies are missing raises NoClassDefFoundError, which is an Error, and
 * under the old code would have escaped finalStatistics entirely and destroyed the
 * archive write at the end of a multi-hour run.
 * ------------------------------------------------------------------------------------
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
 * PERSISTENCE. Four files per run, stamped with seed and timestamp so repeated runs
 * never overwrite each other:
 *
 *   best-<stamp>.txt     Lisp tree(s), size, depth, fitness, config, benchmark table.
 *   best-<stamp>.ind     ECJ parseable format, for reloading.
 *   best-<stamp>.params  the GATHERED parameter set the run used (see below).
 *   bench-<stamp>.csv    one row per opponent.
 *
 * ------------------------------------------------------------------------------------
 * 2026-10: THE .params SNAPSHOT WAS MISSING, AND NOW EXISTS.
 *
 * The 2026-09-12 notes record this file writing best-<stamp>.params. The version in the
 * repo on 2026-10-03 did not -- most likely lost in one of the file shuffles. That
 * mattered more after stage 4: config/microrts.params has 29 entries and 1 tree, the
 * stage-4 config 55 entries and 4 trees, so a stage-4 .ind read with the default params
 * cannot load. The snapshot is ParameterDatabase.list(pw, false): every parameter the run
 * actually saw, flattened from the params file, its parents inside the ECJ jar, and any
 * -p overrides. Not a file copy -- a copy would miss the overrides and the inherited
 * koza defaults. IndividualLoader picks the snapshot up automatically.
 *
 * 2026-10: DISTINCT GAMES. With a deterministic bot and a deterministic opponent there
 * are only (maps x sides) distinct games; extra repeats replay them identically. The
 * notes said this file reported that; it did not. Each row now prints the distinct count
 * so "100 games" is never mistaken for 100 independent samples.
 *
 * 2026-10: ROLE TREES. Lisp output labels each tree with the role that uses it, via
 * IndividualLoader.lispForm, so all three tools print individuals the same way.
 * ------------------------------------------------------------------------------------
 *
 * IMPORTANT: the panel includes the training opponents. Numbers against those are
 * TRAINING performance, not a result, and are marked as such in both outputs. On the
 * stage-4 validation maps those rows are a trained-against opponent on UNSEEN maps --
 * more informative than before, but still not a held-out result.
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

    // 2026-10-19 (team protocol): training time and per-generation population spread.
    private long runStartNanos = -1;
    private long evolutionNanos = -1;
    private final List<String> genRows = new ArrayList<>();

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

        // Training time = wall clock from initialisation to the end of evolution, measured
        // BEFORE the benchmark below so benchmark games are not counted as training.
        evolutionNanos = runStartNanos < 0 ? -1 : System.nanoTime() - runStartNanos;
        state.output.message("Training time (wall clock, evolution only): " + fmtDuration(evolutionNanos)
                + "   eval threads " + state.evalthreads + ", cpus " + Runtime.getRuntime().availableProcessors());

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
                + "   games per opponent per map: " + gamesPer + "   cycle cap: " + maxCycles);

        long worstCycleMs = 0;
        int brokenOpponents = 0;
        List<String> csv = new ArrayList<>();
        List<String> table = new ArrayList<>();
        csv.add("opponent,status,wins,draws,losses,errors,games,distinct_games,win_rate,trained_against,note");

        for (String opponent : benchOpponents) {
            boolean trained = wasTrainedAgainst(problem, opponent);

            // PROBE FIRST. If the opponent cannot even be built, say so -- do not play
            // zero games and report ten losses.
            String probeFailure = probe(opponent, utt);
            if (probeFailure != null) {
                brokenOpponents++;
                String line = String.format("vs %-24s: ERROR -- could not construct (%s)",
                        opponent, probeFailure);
                state.output.message(line);
                table.add(line);
                csv.add(String.format("%s,ERROR,0,0,0,0,0,0,,%s,%s",
                        opponent, trained ? "yes" : "no", csvSafe(probeFailure)));
                continue;
            }

            int w = 0, t = 0, l = 0, err = 0;
            String firstError = null;

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
                    } catch (Throwable e) {
                        // A game that could not be played is not a game that was lost.
                        err++;
                        if (firstError == null) firstError = String.valueOf(e);
                    }
                }
            }

            int played = w + t + l;
            int distinct = distinctGames(problem, opponent, played);
            double rate = (played == 0) ? 0.0 : 100.0 * w / played;
            String status = (err == 0) ? "OK" : (played == 0 ? "ERROR" : "PARTIAL");
            if (err > 0) brokenOpponents++;

            String line = String.format(
                    "vs %-24s: %3dW %3dT %3dL / %3d games, %3d distinct  (win rate %.0f%%)%s%s",
                    opponent, w, t, l, played, distinct, rate,
                    err > 0 ? String.format("   [%d ERRORS -- %s]", err, status) : "",
                    trained ? "   [TRAINING OPPONENT]" : "");
            state.output.message(line);
            table.add(line);
            csv.add(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%.1f,%s,%s",
                    opponent, status, w, t, l, err, played, distinct, rate,
                    trained ? "yes" : "no", csvSafe(firstError)));
        }

        state.output.message("");
        state.output.message("'distinct' = independent games. Against a deterministic opponent"
                + " only (maps x sides) games exist; repeats replay them exactly.");
        if (brokenOpponents > 0) {
            state.output.warning(brokenOpponents + " opponent(s) could not be played and are "
                    + "marked ERROR or PARTIAL above. Those rows are NOT results. If these are "
                    + "competition bots, check that lib/bots/* is on the classpath of whatever "
                    + "launches ECJ -- run.sh does not add it.");
        }
        state.output.message("Worst per-cycle decision time: " + worstCycleMs
                + " ms   (G1 gate is 100 ms)");

        writeArtifacts(state, gpBest, problem, stamp, table, csv, worstCycleMs, brokenOpponents);
    }

    /**
     * Number of statistically independent games behind a row. Deterministic opponents:
     * one per (map, side) actually covered. Stochastic opponents: every game played.
     */
    private int distinctGames(MicroRTSProblem problem, String opponent, int played) {
        boolean stochastic = problem.stochastic != null
                && problem.stochastic.contains(opponent.trim().toLowerCase(Locale.ROOT));
        if (stochastic) return played;
        int sidesCovered = Math.min(gamesPer, 2);
        return Math.min(played, benchMaps.length * sidesCovered);
    }

    /**
     * Can this opponent be constructed at all? Returns null on success, else the reason.
     *
     * Throwable, not Exception: a class that resolves but whose dependency jars are
     * absent raises NoClassDefFoundError, which would otherwise escape finalStatistics
     * and take the archive write down with it at the end of a multi-hour run.
     */
    private static String probe(String opponent, UnitTypeTable utt) {
        try {
            Panel.make(opponent, utt);
            return null;
        } catch (Throwable e) {
            String msg = e.getMessage();
            return (msg == null || msg.isEmpty()) ? e.toString() : firstLine(msg);
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Commas and quotes would break the CSV; this is not user input, so stripping is fine. */
    private static String csvSafe(String s) {
        return (s == null) ? "" : s.replace(',', ';').replace('"', '\'').trim();
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

    /** One-line size summary, e.g. "4 tree(s), 143 nodes, depth 9". */
    private static String describeTrees(final GPIndividual ind) {
        if (ind.trees == null || ind.trees.length == 0) return "(no trees)";
        int nodes = 0;
        int depth = 0;
        StringBuilder per = new StringBuilder();
        for (int i = 0; i < ind.trees.length; i++) {
            GPTree t = ind.trees[i];
            int n = (t == null || t.child == null) ? 0 : t.child.numNodes(GPNode.NODESEARCH_ALL);
            nodes += n;
            if (t != null && t.child != null) depth = Math.max(depth, t.child.depth());
            if (ind.trees.length > 1) per.append(i == 0 ? " [" : ", ").append(n);
        }
        if (per.length() > 0) per.append(" per tree]");
        return ind.trees.length + " tree(s), " + nodes + " nodes, depth " + depth + per;
    }

    /** ECJ's parseable individual format -- the one a loader can read back. */
    private static String reloadableForm(final EvolutionState state, final Individual ind) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ind.printIndividual(state, pw);
        pw.flush();
        return sw.toString();
    }

    /** Every parameter the run saw, flattened. See the 2026-10 note in the class comment. */
    private static String paramsSnapshot(final EvolutionState state, final String stamp) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("# Gathered parameter snapshot for best-" + stamp + ".ind");
        pw.println("# Written by gp.BenchmarkStatistics via ParameterDatabase.list(pw, false):");
        pw.println("# the params file, its parents inside the ECJ jar, and all -p overrides.");
        pw.println("# gp.IndividualLoader uses this automatically for the .ind beside it.");
        StringWriter raw = new StringWriter();
        PrintWriter rpw = new PrintWriter(raw);
        state.parameters.list(rpw, false);
        rpw.flush();
        for (String line : raw.toString().split("\r?\n")) {
            // 2026-10-19: a relative parent.N (e.g. microrts-stage7.params) resolves from the
            // snapshot's own folder and breaks loading. The snapshot is already flattened, so
            // parent lines are redundant: comment them out.
            pw.println(line.trim().startsWith("parent.") ? "# " + line : line);
        }
        pw.flush();
        return sw.toString();
    }

    private void writeArtifacts(final EvolutionState state, final GPIndividual best,
                                final MicroRTSProblem problem, final String stamp,
                                final List<String> table, final List<String> csv,
                                final long worstCycleMs, final int brokenOpponents) {
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
            txt.append("training time     : ").append(fmtDuration(evolutionNanos))
               .append("   (wall clock, evolution only; eval threads ").append(state.evalthreads)
               .append(", cpus ").append(Runtime.getRuntime().availableProcessors()).append(")\n");
            txt.append("benchmark maps    : ").append(join(benchMaps)).append('\n');

            txt.append("\nEvolved scoring function\n------------------------\n");
            txt.append(IndividualLoader.lispForm(best)).append('\n');

            txt.append("\nBenchmark\n---------\n");
            for (String line : table) txt.append(line).append('\n');
            txt.append("\nNOTE: rows marked [TRAINING OPPONENT] are an opponent seen in training."
                    + " 'distinct' counts independent games.\n");
            if (brokenOpponents > 0) {
                txt.append("WARNING: ").append(brokenOpponents).append(" opponent(s) could not be ")
                   .append("played (ERROR or PARTIAL above). Those rows are NOT measurements -- ")
                   .append("do not read them as losses. Usual cause: lib/bots/* missing from the ")
                   .append("classpath of whatever launched ECJ.\n");
            }

            write(dir.resolve("best-" + stamp + ".txt"), txt.toString());
            write(dir.resolve("best-" + stamp + ".ind"), reloadableForm(state, best));
            write(dir.resolve("best-" + stamp + ".params"), paramsSnapshot(state, stamp));
            write(dir.resolve("bench-" + stamp + ".csv"), String.join("\n", csv) + "\n");
            write(dir.resolve("gens-" + stamp + ".csv"),
                    "generation,best_fitness,mean_fitness,sd_fitness,worst_fitness,mean_hits,sd_hits,mean_nodes,elapsed_s\n"
                    + String.join("\n", genRows) + "\n");

            state.output.message("");
            state.output.message("Wrote " + new File(resultsDir).getAbsolutePath()
                    + File.separator + "best-" + stamp + ".{txt,ind,params} and bench-"
                    + stamp + ".csv");
        } catch (IOException e) {
            // Never let an unwritable results directory destroy a finished run: the
            // benchmark table has already been printed to stdout above.
            state.output.warning("Could not write results to '" + resultsDir + "': " + e
                    + " -- the table above is still valid, but this run was not archived.");
        }
    }

    @Override
    public void preInitializationStatistics(final EvolutionState state) {
        super.preInitializationStatistics(state);
        runStartNanos = System.nanoTime();
    }

    /**
     * 2026-10-19: per-generation population spread (team protocol "SD over population").
     * Population SD (divide by n) of standardized fitness and of hits (games won), plus
     * mean tree size and elapsed wall clock. Printed every generation and written to
     * gens-<stamp>.csv at the end of the run.
     */
    @Override
    public void postEvaluationStatistics(final EvolutionState state) {
        super.postEvaluationStatistics(state);
        List<Individual> inds = state.population.subpops.get(0).individuals;
        int n = inds.size();
        if (n == 0) return;
        double sf = 0, sf2 = 0, sh = 0, sh2 = 0, nodes = 0;
        double best = Double.POSITIVE_INFINITY, worst = Double.NEGATIVE_INFINITY;
        for (Individual ind : inds) {
            KozaFitness f = (KozaFitness) ind.fitness;
            double v = f.standardizedFitness();
            sf += v; sf2 += v * v;
            sh += f.hits; sh2 += (double) f.hits * f.hits;
            best = Math.min(best, v); worst = Math.max(worst, v);
            if (ind instanceof GPIndividual) {
                for (GPTree t : ((GPIndividual) ind).trees) {
                    if (t != null && t.child != null) nodes += t.child.numNodes(GPNode.NODESEARCH_ALL);
                }
            }
        }
        double mf = sf / n, mh = sh / n;
        double sdf = Math.sqrt(Math.max(0, sf2 / n - mf * mf));
        double sdh = Math.sqrt(Math.max(0, sh2 / n - mh * mh));
        long el = runStartNanos < 0 ? -1 : System.nanoTime() - runStartNanos;
        genRows.add(String.format(Locale.ROOT, "%d,%.6f,%.6f,%.6f,%.6f,%.3f,%.3f,%.1f,%.1f",
                state.generation, best, mf, sdf, worst, mh, sdh, nodes / n, el < 0 ? -1.0 : el / 1e9));
        state.output.message(String.format(Locale.ROOT,
                "population generation %d: fitness best %.4f mean %.4f sd %.4f | hits mean %.2f sd %.2f | mean size %.1f | elapsed %s",
                state.generation, best, mf, sdf, mh, sdh, nodes / n, fmtDuration(el)));
    }

    private static String fmtDuration(long nanos) {
        if (nanos < 0) return "n/a";
        long s = nanos / 1_000_000_000L;
        return String.format(Locale.ROOT, "%dh %02dm %02ds (%d s)", s / 3600, (s % 3600) / 60, s % 60, s);
    }

    private static String join(String[] a) {
        return (a == null || a.length == 0) ? "(none)" : String.join(", ", a);
    }

    private static void write(Path p, String content) throws IOException {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }
}
