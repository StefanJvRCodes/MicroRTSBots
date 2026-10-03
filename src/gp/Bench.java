package gp;

import ai.core.AI;
import bots.EvolvedBot;
import ec.gp.ADFStack;
import ec.util.Parameter;
import eval.Panel;
import rts.units.UnitTypeTable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Benchmark ANY saved individual, outside an evolution run, with a per-game breakdown.
 *
 * WHY THIS EXISTS. BenchmarkStatistics only runs at the end of an ECJ run, on that run's
 * best individual, on the maps in that run's params. Three things need more than that:
 *   - DIAGNOSIS: which (map, side) games does the bot lose? A totals row like
 *     "40W 0T 60L" hides whether it loses every 16x16 game or one side of every map;
 *   - BASELINES: playing an archived individual (e.g. the stage-3 bot) on the SAME maps
 *     as a new one, so the two are actually comparable;
 *   - THE FINAL TEST: playing on the held-out test maps exactly once, and against the
 *     competition bots, without touching any evolution config.
 *
 * OUTPUT. One row per opponent: W/T/L totals, distinct-game count, then a grid of
 * map:results, one character per game in side order (side 0 first). "8x8B:WL" means won
 * as player 0 and lost as player 1 on basesWorkers8x8B. E = the game could not be played
 * (an error, never counted as a loss).
 *
 * MAP KEYWORDS for -maps:
 *   train       the training maps from the individual's params (eval.problem.maps)
 *   validation  the benchmark maps from the individual's params (stat.bench-maps)
 *   anything else is a comma-separated list of map paths.
 * There is deliberately no "test" keyword: the test maps are typed out by hand, once,
 * for the final evaluation, so they cannot be used by accident.
 *
 * REPEATS. Deterministic opponents play once per (map, side) -- a repeat would replay
 * the same game exactly. Stochastic opponents (per the params' stochastic list) play
 * -samples games per (map, side).
 *
 * USAGE
 *   java -cp "out;lib/*;lib/bots/*" gp.Bench results/best-<stamp>.ind
 *   java -cp "out;lib/*;lib/bots/*" gp.Bench results/best-<stamp>.ind -maps train
 *        -opponents WorkerRush,WorkerRushPlusPlus,WorkerDefense
 *   java -cp "out;lib/*;lib/bots/*" gp.Bench results/old.ind@config/microrts.params
 *        -maps validation-of:results/best-<new>.params -maxcycles 4000
 *
 * Options: -maps, -opponents (default: the full scripted panel), -samples N (default 5),
 *          -maxcycles N (default: the individual's own cap), -csv path (one row per game).
 */
public final class Bench {

    private Bench() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: gp.Bench <path.ind>[@path.params] [-maps train|validation|"
                    + "validation-of:<params>|m1.xml,m2.xml]");
            System.out.println("                [-opponents A,B,C] [-samples N] [-maxcycles N]"
                    + " [-csv out.csv]");
            return;
        }

        String spec = args[0];
        String mapsArg = "validation";
        String[] opponents = Panel.FULL_PANEL;
        int samples = 5;
        int maxCycles = -1;
        String csvPath = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-maps":      mapsArg = args[++i]; break;
                case "-opponents": opponents = split(args[++i]); break;
                case "-samples":   samples = Integer.parseInt(args[++i]); break;
                case "-maxcycles": maxCycles = Integer.parseInt(args[++i]); break;
                case "-csv":       csvPath = args[++i]; break;
                default:
                    System.out.println("Unknown option: " + args[i]);
                    return;
            }
        }
        if (opponents.length == 1 && opponents[0].equalsIgnoreCase("all")) {
            opponents = Panel.FULL_PANEL;
        }

        IndividualLoader.Loaded loaded = IndividualLoader.load(spec);
        MicroRTSProblem problem = (MicroRTSProblem) loaded.problem;
        if (maxCycles <= 0) maxCycles = problem.maxCycles;

        String[] maps = resolveMaps(mapsArg, loaded, problem);
        for (String m : maps) {
            if (!new File(m).isFile()) {
                System.out.println("Map not found: " + m + " (relative to "
                        + System.getProperty("user.dir") + ")");
                return;
            }
        }

        System.out.println();
        System.out.println("=== Bench ===");
        System.out.println("individual : " + loaded.indPath);
        System.out.println("params     : " + loaded.paramsPath);
        System.out.println("tree       : " + loaded.describe());
        System.out.println("maps (" + maps.length + ")   : " + labels(maps));
        System.out.println("cycle cap  : " + maxCycles + "   stochastic samples per map-side: "
                + samples);
        System.out.println("grid       : one char per game, side 0 then side 1."
                + " W win, T draw, L loss, E error (not a loss)");
        System.out.println();

        List<String> csv = new ArrayList<>();
        csv.add("opponent,map,side,rep,result,cycles");
        long worstCycleMs = 0;
        int totalW = 0, totalT = 0, totalL = 0;

        for (String opponent : opponents) {
            String probe = probe(opponent);
            if (probe != null) {
                System.out.printf("vs %-24s ERROR -- could not construct (%s)%n", opponent, probe);
                continue;
            }

            boolean stochastic = problem.stochastic != null
                    && problem.stochastic.contains(opponent.trim().toLowerCase(Locale.ROOT));
            int reps = stochastic ? samples : 1;

            int w = 0, t = 0, l = 0, e = 0;
            StringBuilder grid = new StringBuilder();

            for (String map : maps) {
                grid.append(' ').append(label(map)).append(':');
                for (int side = 0; side < 2; side++) {
                    for (int r = 0; r < reps; r++) {
                        char c;
                        int cycles = -1;
                        try {
                            UnitTypeTable utt = new UnitTypeTable();
                            EvolvedBot ours = new EvolvedBot(utt, loaded.individual, loaded.state, 0,
                                    loaded.problem, (ScoreData) loaded.problem.input.clone(),
                                    (ADFStack) loaded.problem.stack.clone());
                            AI theirs = Panel.make(opponent, utt);
                            Panel.Result res = Panel.play(ours, theirs, map, utt, side, maxCycles);
                            cycles = res.cyclesTaken;
                            worstCycleMs = Math.max(worstCycleMs, ours.worstCycleMillis);
                            if (res.won()) { c = 'W'; w++; }
                            else if (res.drew()) { c = 'T'; t++; }
                            else { c = 'L'; l++; }
                        } catch (Throwable ex) {
                            c = 'E';
                            e++;
                        }
                        grid.append(c);
                        csv.add(String.format("%s,%s,%d,%d,%c,%d", opponent, map, side, r, c, cycles));
                    }
                    if (reps > 1 && side == 0) grid.append('|');
                }
            }

            int played = w + t + l;
            int distinct = stochastic ? played : Math.min(played, maps.length * 2);
            totalW += w; totalT += t; totalL += l;
            System.out.printf("vs %-24s %3dW %3dT %3dL  (%3.0f%%, %d distinct)%s%n",
                    opponent, w, t, l, played == 0 ? 0.0 : 100.0 * w / played, distinct,
                    e > 0 ? "   [" + e + " ERRORS]" : "");
            System.out.println("    " + grid.toString().trim());
        }

        System.out.println();
        System.out.printf("TOTAL %dW %dT %dL.  Worst per-cycle decision time %d ms (G1 gate 100).%n",
                totalW, totalT, totalL, worstCycleMs);

        if (csvPath != null) {
            Path p = Paths.get(csvPath);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, (String.join("\n", csv) + "\n").getBytes(StandardCharsets.UTF_8));
            System.out.println("Wrote " + p.toAbsolutePath());
        }
    }

    /** train / validation / validation-of:<params> / explicit list. */
    private static String[] resolveMaps(String arg, IndividualLoader.Loaded loaded,
                                        MicroRTSProblem problem) throws Exception {
        if (arg.equalsIgnoreCase("train")) return problem.maps;
        if (arg.equalsIgnoreCase("validation")) {
            String v = loaded.state.parameters.getString(new Parameter("stat.bench-maps"), null);
            if (v == null) throw new IllegalArgumentException(
                    "No stat.bench-maps in " + loaded.paramsPath + "; pass -maps explicitly or use"
                  + " validation-of:<a stage-4 params file>.");
            return split(v);
        }
        if (arg.toLowerCase(Locale.ROOT).startsWith("validation-of:")) {
            // Read stat.bench-maps from ANOTHER params file -- e.g. to bench the stage-3 bot
            // on exactly the stage-4 validation maps. Plain text scan: no ECJ setup needed.
            Path other = Paths.get(arg.substring("validation-of:".length()));
            for (String line : Files.readAllLines(other, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.startsWith("stat.bench-maps")) {
                    int eq = s.indexOf('=');
                    if (eq > 0) return split(s.substring(eq + 1));
                }
            }
            throw new IllegalArgumentException("No stat.bench-maps line in " + other);
        }
        return split(arg);
    }

    private static String probe(String opponent) {
        try {
            if (Panel.make(opponent, new UnitTypeTable()) == null) return "factory returned null";
            return null;
        } catch (Throwable e) {
            String m = e.getMessage();
            return (m == null || m.isEmpty()) ? e.toString() : m.split("\n")[0];
        }
    }

    private static String[] split(String s) {
        return s.trim().split("\\s*,\\s*");
    }

    /** "maps/8x8/basesWorkers8x8B.xml" -> "8x8B"; other maps keep their file name. */
    static String label(String map) {
        String n = new File(map).getName();
        if (n.endsWith(".xml")) n = n.substring(0, n.length() - 4);
        return n.startsWith("basesWorkers") ? n.substring("basesWorkers".length()) : n;
    }

    private static String labels(String[] maps) {
        StringBuilder sb = new StringBuilder();
        for (String m : maps) sb.append(sb.length() == 0 ? "" : ", ").append(label(m));
        return sb.toString();
    }
}
