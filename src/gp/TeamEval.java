package gp;

import ai.core.AI;
import bots.EvolvedBot;
import ec.gp.ADFStack;
import eval.Panel;
import rts.units.UnitTypeTable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Team-protocol evaluation (agreed 2026-10-19): many runs x 9 eval maps x the 10-bot
 * panel, both sides, 2000-cycle cap. One CSV row per game, so every metric (win rate,
 * SD per run, SD across runs) can be recomputed later from raw data whatever definition
 * the team settles on.
 *
 * DISCIPLINE. These maps and the competition bots are the final evaluation. Never use
 * this tool to choose between runs, configs or checkpoints -- selection is on the
 * validation maps only (gp.Bench).
 *
 * TIMING. Coac, mayari, Rojo and Droplet search under a per-cycle time budget. If the
 * CPU is shared with anything else (an evolution run, too many -threads) they get less
 * search and play weaker, which inflates our win rates. Run this with nothing else on
 * the machine and -threads at most the PHYSICAL core count.
 *
 * DETERMINISM. Only the opponents in -deterministic play once per (map, side); every
 * other opponent plays -reps games per (map, side). The deterministic list is checked
 * at startup by replaying one game twice.
 *
 * RESUME. Re-running with the same -csv skips games already recorded (errors are
 * retried), so a killed job loses nothing.
 *
 * USAGE
 *   java -cp "out;lib/*;lib/bots/*" gp.TeamEval -method sbgp -inds '@results/list.txt'
 *        -csv results/teameval-sbgp.csv [-threads N] [-reps N] [-maxcycles 2000]
 *        [-maps a,b] [-opponents a,b] [-deterministic a,b|none] [-utt original|finetuned] [-dry]
 *   -inds takes a comma list of .ind paths, or @file with one path per line (# = comment).
 */
public final class TeamEval {

    static final String[] TEAM_MAPS = {
        "8x8A", "basesWorkersBarracks8x8", "8x8Obstacle", "12x12", "12x12F",
        "SixBasesWorkers12x12", "melee16x16Mixed12", "TwoBasesBarracks16x16", "EightBasesWorkers16x16",
    };

    static final String[] TEAM_PANEL = {
        "RandomBiasedAI", "WorkerRush", "LightRush", "HeavyRush", "RangedRush",
        "EconomyLightRush", "mayari", "Rojo", "Droplet", "Coac",
    };

    static final String[] DEFAULT_DETERMINISTIC = { "WorkerRush", "LightRush", "HeavyRush", "RangedRush" };

    private TeamEval() {}

    private static final class Run {
        final String label;
        final String seed;
        final IndividualLoader.Loaded loaded;
        Run(String label, String seed, IndividualLoader.Loaded loaded) {
            this.label = label; this.seed = seed; this.loaded = loaded;
        }
    }

    private static final class Game {
        final int run; final String opponent; final String map; final int side; final int rep;
        Game(int run, String opponent, String map, int side, int rep) {
            this.run = run; this.opponent = opponent; this.map = map; this.side = side; this.rep = rep;
        }
    }

    private static void usage() {
        System.out.println("usage: gp.TeamEval -method <name> -inds a.ind,b.ind|@list.txt -csv out.csv");
        System.out.println("       [-threads N] [-reps N] [-maxcycles N] [-maps a,b] [-opponents a,b]");
        System.out.println("       [-deterministic a,b|none] [-utt original|finetuned] [-dry]");
    }

    public static void main(String[] args) throws Exception {
        String method = null, indsArg = null, csvArg = null, uttName = "original";
        String[] mapTokens = TEAM_MAPS, opponents = TEAM_PANEL, detList = DEFAULT_DETERMINISTIC;
        int reps = 5, maxCycles = 2000;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        boolean dry = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-method":        method = args[++i]; break;
                case "-inds":          indsArg = args[++i]; break;
                case "-csv":           csvArg = args[++i]; break;
                case "-maps":          mapTokens = split(args[++i]); break;
                case "-opponents":     opponents = split(args[++i]); break;
                case "-deterministic": { String v = args[++i]; detList = v.equalsIgnoreCase("none") ? new String[0] : split(v); } break;
                case "-reps":          reps = Integer.parseInt(args[++i]); break;
                case "-threads":       threads = Integer.parseInt(args[++i]); break;
                case "-maxcycles":     maxCycles = Integer.parseInt(args[++i]); break;
                case "-utt":           uttName = args[++i].toLowerCase(Locale.ROOT); break;
                case "-dry":           dry = true; break;
                default: System.out.println("Unknown option: " + args[i]); usage(); return;
            }
        }
        if (method == null || indsArg == null || csvArg == null) { usage(); return; }
        newUtt(uttName);   // fail fast on a bad -utt

        Set<String> deterministic = new HashSet<>();
        for (String d : detList) deterministic.add(d.trim().toLowerCase(Locale.ROOT));

        // ---- maps
        List<Path> allMaps;
        try (Stream<Path> s = Files.walk(Paths.get("maps"))) {
            allMaps = s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                       .collect(Collectors.toList());
        }
        String[] maps = new String[mapTokens.length];
        for (int i = 0; i < mapTokens.length; i++) maps[i] = resolveMap(mapTokens[i], allMaps);

        // ---- opponents: all must construct, or nothing runs
        for (String o : opponents) {
            try {
                if (Panel.make(o, newUtt(uttName)) == null) throw new IllegalStateException("factory returned null");
            } catch (Throwable e) {
                System.out.println("Opponent '" + o + "' cannot be constructed: " + e.getMessage());
                System.out.println("Nothing was run.");
                return;
            }
        }

        // ---- individuals
        List<String> specs = new ArrayList<>();
        if (indsArg.startsWith("@")) {
            for (String line : Files.readAllLines(Paths.get(indsArg.substring(1)), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) specs.add(s);
            }
        } else {
            specs.addAll(Arrays.asList(split(indsArg)));
        }
        if (specs.isEmpty()) { System.out.println("No individuals given."); return; }

        List<Run> runs = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        Pattern seedPat = Pattern.compile("seed(\\d+)");
        for (String spec : specs) {
            IndividualLoader.Loaded l = IndividualLoader.load(spec);
            String name = new File(String.valueOf(l.indPath)).getName();
            String label = name.endsWith(".ind") ? name.substring(0, name.length() - 4) : name;
            if (!labels.add(label)) { System.out.println("Duplicate individual: " + label); return; }
            Matcher m = seedPat.matcher(label);
            runs.add(new Run(label, m.find() ? m.group(1) : "?", l));
        }

        // ---- game list
        int perRun = 0;
        for (String o : opponents) perRun += (isDet(o, deterministic) ? 1 : reps) * maps.length * 2;
        List<Game> all = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++)
            for (String o : opponents)
                for (String map : maps)
                    for (int side = 0; side < 2; side++)
                        for (int rep = 0; rep < (isDet(o, deterministic) ? 1 : reps); rep++)
                            all.add(new Game(r, o, map, side, rep));

        // ---- resume
        Path csv = Paths.get(csvArg);
        if (csv.getParent() != null) Files.createDirectories(csv.getParent());
        Set<String> done = new HashSet<>();
        if (Files.isRegularFile(csv)) {
            for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
                String[] c = line.split(",", -1);
                if (c.length < 8 || c[0].equals("method") || !c[0].equals(method) || c[7].equals("E")) continue;
                done.add(c[1] + "|" + c[3] + "|" + c[4] + "|" + c[5] + "|" + c[6]);
            }
        }
        List<Game> todo = new ArrayList<>();
        for (Game g : all) if (!done.contains(key(runs.get(g.run).label, g))) todo.add(g);

        System.out.println();
        System.out.println("=== TeamEval ===");
        System.out.println("method      : " + method + "   runs: " + runs.size());
        for (Run r : runs) System.out.println("  " + r.label + "   " + r.loaded.describe());
        System.out.println("maps (" + maps.length + ")    : " + String.join(", ", maps));
        System.out.println("opponents   : " + String.join(", ", opponents));
        System.out.println("deterministic (1 game per map-side): " + String.join(", ", detList)
                + "   others: " + reps + " per map-side");
        System.out.println("cycle cap " + maxCycles + ", unit table " + uttName + ", threads " + threads);
        System.out.println("games: " + perRun + " per run, " + all.size() + " total, "
                + (all.size() - todo.size()) + " already in " + csv + ", " + todo.size() + " to play");
        if (dry) { System.out.println("(dry run: nothing played)"); return; }

        // ---- determinism check
        final String utt0 = uttName;
        final int cap = maxCycles;
        for (String o : opponents) {
            if (!isDet(o, deterministic)) continue;
            Panel.Result a = playOne(runs.get(0), o, maps[0], 0, utt0, cap);
            Panel.Result b = playOne(runs.get(0), o, maps[0], 0, utt0, cap);
            boolean same = a.winner == b.winner && a.cyclesTaken == b.cyclesTaken;
            System.out.println("determinism check " + o + ": " + (same ? "OK"
                    : "WARNING -- two identical games differed (" + a.cyclesTaken + " vs " + b.cyclesTaken
                      + " cycles). Remove it from -deterministic."));
        }

        if (!Files.isRegularFile(csv)) {
            Files.write(csv, "method,run,seed,opponent,map,side,rep,result,cycles,worst_ms,game_s,error\n"
                    .getBytes(StandardCharsets.UTF_8));
        }

        // ---- play
        final String meth = method;
        final Object lock = new Object();
        final AtomicInteger finished = new AtomicInteger();
        final long t0 = System.currentTimeMillis();
        final int total = todo.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (Game g : todo) {
            futures.add(pool.submit(() -> {
                Run r = runs.get(g.run);
                char c; int cycles = -1; long ms = -1; String err = ""; long gt0 = System.nanoTime();
                try {
                    UnitTypeTable utt = newUtt(utt0);
                    MicroRTSProblem p = (MicroRTSProblem) r.loaded.problem.clone();
                    var ind = r.loaded.individual.getClass().cast(r.loaded.individual.clone());
                    EvolvedBot ours = new EvolvedBot(utt, ind, r.loaded.state, 0, p,
                            (ScoreData) p.input.clone(), (ADFStack) p.stack.clone());
                    AI theirs = Panel.make(g.opponent, utt);
                    Panel.Result res = Panel.play(ours, theirs, g.map, utt, g.side, cap);
                    cycles = res.cyclesTaken;
                    ms = ours.worstCycleMillis;
                    c = res.won() ? 'W' : res.drew() ? 'T' : 'L';
                } catch (Throwable e) {
                    c = 'E';
                    err = String.valueOf(e).replace(',', ';').replace('\n', ' ');
                }
                String row = String.format(Locale.ROOT, "%s,%s,%s,%s,%s,%d,%d,%c,%d,%d,%.1f,%s%n",
                        meth, r.label, r.seed, g.opponent, g.map, g.side, g.rep, c, cycles, ms, (System.nanoTime() - gt0) / 1e9, err);
                synchronized (lock) {
                    try {
                        Files.write(csv, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                    } catch (IOException io) {
                        System.out.println("CSV WRITE FAILED: " + io);
                    }
                    int n = finished.incrementAndGet();
                    if (n % 25 == 0 || n == total || c == 'E') {
                        double el = (System.currentTimeMillis() - t0) / 1000.0;
                        System.out.printf(Locale.ROOT, "%d/%d games  %.0f s elapsed  ETA %.0f min%s%n",
                                n, total, el, el / n * (total - n) / 60.0,
                                c == 'E' ? "   ERROR " + r.label + " vs " + g.opponent + " on " + g.map + ": " + err : "");
                    }
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        summarise(csv, method, runs, opponents, perRun);
    }

    private static Panel.Result playOne(Run r, String opp, String map, int side, String utt0, int cap) throws Exception {
        UnitTypeTable utt = newUtt(utt0);
        MicroRTSProblem p = (MicroRTSProblem) r.loaded.problem.clone();
        var ind = r.loaded.individual.getClass().cast(r.loaded.individual.clone());
        EvolvedBot ours = new EvolvedBot(utt, ind, r.loaded.state, 0, p,
                (ScoreData) p.input.clone(), (ADFStack) p.stack.clone());
        return Panel.play(ours, Panel.make(opp, utt), map, utt, side, cap);
    }

    private static void summarise(Path csv, String method, List<Run> runs, String[] opponents, int perRun)
            throws IOException {
        Map<String, Map<String, int[]>> byRun = new LinkedHashMap<>();
        Map<String, Long> worst = new LinkedHashMap<>();
        for (Run r : runs) {
            Map<String, int[]> m = new LinkedHashMap<>();
            for (String o : opponents) m.put(o, new int[3]);
            byRun.put(r.label, m);
            worst.put(r.label, -1L);
        }
        int errors = 0;
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            String[] c = line.split(",", -1);
            if (c.length < 10 || !c[0].equals(method)) continue;
            Map<String, int[]> m = byRun.get(c[1]);
            if (m == null) continue;
            int[] t = m.get(c[3]);
            if (t == null) continue;
            switch (c[7]) {
                case "W": t[0]++; break;
                case "T": t[1]++; break;
                case "L": t[2]++; break;
                default: errors++; continue;
            }
            try { worst.put(c[1], Math.max(worst.get(c[1]), Long.parseLong(c[9]))); } catch (NumberFormatException ignored) { }
        }

        System.out.println();
        System.out.println("=== Summary: " + method + " (" + runs.size() + " runs) ===");
        System.out.println("Win rate = W / (W+T+L); draws count as non-wins. Per run, each opponent is");
        System.out.println("weighted equally (macro), so stochastic opponents' extra reps do not dominate.");
        System.out.println();
        System.out.printf(Locale.ROOT, "%-40s %9s %7s %9s%n", "run", "games", "macro", "worst ms");
        List<Double> macros = new ArrayList<>();
        Map<String, List<Double>> perOpp = new LinkedHashMap<>();
        for (String o : opponents) perOpp.put(o, new ArrayList<>());
        for (Run r : runs) {
            int games = 0; double sum = 0; int n = 0;
            for (String o : opponents) {
                int[] t = byRun.get(r.label).get(o);
                int played = t[0] + t[1] + t[2];
                games += played;
                if (played > 0) {
                    double wr = (double) t[0] / played;
                    sum += wr; n++;
                    perOpp.get(o).add(wr);
                }
            }
            double macro = n == 0 ? Double.NaN : sum / n;
            if (n == opponents.length) macros.add(macro);
            System.out.printf(Locale.ROOT, "%-40s %4d/%-4d %6.1f%% %9s%s%n", r.label, games, perRun,
                    100 * macro, worst.get(r.label) < 0 ? "-" : String.valueOf(worst.get(r.label)),
                    games < perRun ? "   INCOMPLETE" : "");
        }
        System.out.println();
        System.out.println("Across runs (complete runs only): " + stats(macros));
        System.out.println();
        System.out.println("Per opponent, across runs:");
        for (String o : opponents) System.out.printf(Locale.ROOT, "  %-18s %s%n", o, stats(perOpp.get(o)));
        if (errors > 0) System.out.println("\n" + errors + " ERROR rows in the CSV; rerun the same command to retry them.");

        Path out = Paths.get(csv.toString().replaceAll("\\.csv$", "") + "-summary.csv");
        List<String> rows = new ArrayList<>();
        rows.add("method,run,opponent,W,T,L,winrate");
        for (Run r : runs)
            for (String o : opponents) {
                int[] t = byRun.get(r.label).get(o);
                int played = t[0] + t[1] + t[2];
                rows.add(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%s", method, r.label, o, t[0], t[1], t[2],
                        played == 0 ? "" : String.format(Locale.ROOT, "%.4f", (double) t[0] / played)));
            }
        Files.write(out, (String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("\nWrote " + out);
    }

    private static String stats(List<Double> v) {
        if (v.isEmpty()) return "n=0";
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        double mean = 0;
        for (double x : s) mean += x;
        mean /= s.size();
        double ss = 0;
        for (double x : s) ss += (x - mean) * (x - mean);
        String sd = s.size() < 2 ? "-" : String.format(Locale.ROOT, "%.1f", 100 * Math.sqrt(ss / (s.size() - 1)));
        double med = s.size() % 2 == 1 ? s.get(s.size() / 2) : (s.get(s.size() / 2 - 1) + s.get(s.size() / 2)) / 2;
        return String.format(Locale.ROOT, "n=%d  mean %.1f%%  sd %s  median %.1f%%  min %.1f%%  max %.1f%%",
                s.size(), 100 * mean, sd, 100 * med, 100 * s.get(0), 100 * s.get(s.size() - 1));
    }

    static UnitTypeTable newUtt(String name) {
        switch (name) {
            case "original":  return new UnitTypeTable();
            case "finetuned": return new UnitTypeTable(UnitTypeTable.VERSION_ORIGINAL_FINETUNED);
            default: throw new IllegalArgumentException("-utt must be original or finetuned, not " + name);
        }
    }

    private static boolean isDet(String opp, Set<String> det) {
        return det.contains(opp.trim().toLowerCase(Locale.ROOT));
    }

    private static String key(String runLabel, Game g) {
        return runLabel + "|" + g.opponent + "|" + g.map + "|" + g.side + "|" + g.rep;
    }

    /** "8x8A" or "basesWorkers8x8A" or a path -> one map file under maps/, or fail with candidates. */
    private static String resolveMap(String token, List<Path> all) {
        if (token.toLowerCase(Locale.ROOT).endsWith(".xml") && Files.isRegularFile(Paths.get(token))) return token;
        String t = token.trim().toLowerCase(Locale.ROOT);
        List<Path> hits = new ArrayList<>();
        for (Path p : all) {
            String n = stem(p).toLowerCase(Locale.ROOT);
            if (n.equals(t) || n.equals("basesworkers" + t)) hits.add(p);
        }
        if (hits.size() == 1) return hits.get(0).toString().replace('\\', '/');
        List<String> cands = new ArrayList<>();
        for (Path p : (hits.isEmpty() ? all : hits)) {
            if (!hits.isEmpty() || stem(p).toLowerCase(Locale.ROOT).contains(t)) cands.add(p.toString());
        }
        throw new IllegalArgumentException((hits.isEmpty() ? "No map matches '" : "Ambiguous map '") + token
                + "'. Candidates: " + (cands.isEmpty() ? "(none)" : String.join(", ", cands)));
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        return n.toLowerCase(Locale.ROOT).endsWith(".xml") ? n.substring(0, n.length() - 4) : n;
    }

    private static String[] split(String s) {
        return s.trim().split("\\s*,\\s*");
    }
}