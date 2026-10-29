package ai.custom.adapter;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ContinuingAI;
import ai.core.InterruptibleAI;
import ai.custom.GP_bot.GPOpponentLoader;
import ai.custom.GP_bot.TextTable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.UnitTypeTable;

/**
 * Evaluates a trained adapter: the adapter and, for comparison, its base
 * tree alone each play {@code games} games against every opponent on every
 * map (both sampling their policy, as in training). Reports per opponent and
 * map, then averaged per opponent and overall: win rate, its standard
 * deviation, W/D/L, mean game length and errored games.
 *
 * <p>Runs at the end of training (AdapterTrainer) and on its own:
 * <pre>
 *   java -cp "bin;lib/*" ai.custom.adapter.AdapterEvaluator &lt;model.ser&gt; [maps folder] [games] [opponents]
 * </pre>
 * Defaults: maps / opponents / games and the game settings (max cycles,
 * opponent time budget, player) from adapter_parameters.txt. The report is
 * printed and saved next to the model as evaluation_&lt;timestamp&gt;.txt.
 */
public final class AdapterEvaluator {
    private final AdapterModel model;
    private final UnitTypeTable utt;
    private final int maxCycles;
    private final int opponentTimeBudget;
    private final int player;

    public AdapterEvaluator(AdapterModel model, UnitTypeTable utt, int maxCycles, int opponentTimeBudget, int player) {
        this.model = model;
        this.utt = utt;
        this.maxCycles = maxCycles;
        this.opponentTimeBudget = opponentTimeBudget;
        this.player = player;
    }

    /** One game's result for the side being evaluated. */
    private static final class Game {
        /** 1 win, 0.5 draw, 0 loss; NaN if the game errored. */
        final double score;
        final int cycles;

        Game(double score, int cycles) {
            this.score = score;
            this.cycles = cycles;
        }
    }

    public static void main(String[] args) {
        if (args.length < 1 || args[0].equals("--help")) {
            System.out.println("Usage: java -cp \"bin;lib/*\" ai.custom.adapter.AdapterEvaluator <model.ser> "
                    + "[maps folder] [games per opponent per map] [opponents, comma separated]");
            System.exit(args.length < 1 ? 2 : 0);
        }
        AdapterConfig config = AdapterConfig.load("adapter_parameters.txt");
        File modelFile = new File(args[0]);
        AdapterModel model = AdapterModel.load(modelFile);
        if (args.length > 1) {
            config.mapsFolder = args[1];
            config.maps = new ArrayList<>();
        }
        int games = args.length > 2 ? Integer.parseInt(args[2]) : config.finalEvaluationGames;
        List<String> opponentNames = args.length > 3 ? Arrays.asList(args[3].split("\\s*,\\s*"))
                : config.finalEvaluationOpponents;

        UnitTypeTable utt = new UnitTypeTable();
        List<String> maps = AdapterTrainer.resolveMaps(config);
        List<AI> opponents = GPOpponentLoader.loadNamedOpponents(opponentNames, config.opponentsFolder, utt, maps);
        ExecutorService pool = Executors.newFixedThreadPool(config.threads, r -> {
            Thread t = new Thread(r, "adapter-eval");
            t.setDaemon(true);
            return t;
        });
        String source = modelFile.getPath();
        new AdapterEvaluator(model, utt, config.maxCycles, config.opponentTimeBudget, config.player)
                .evaluate(source, maps, opponents, games, pool, new Random(config.seed),
                        new File(modelFile.getAbsoluteFile().getParentFile(), "evaluation_"
                                + java.time.LocalDateTime.now().format(
                                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".txt"));
        pool.shutdownNow();
        System.exit(0);
    }

    /** Plays every game, prints the report and saves it to {@code reportFile}; returns the report. */
    public String evaluate(String source, List<String> maps, List<AI> opponents, int games, ExecutorService pool,
                           Random random, File reportFile) {
        if (opponents.isEmpty() || games < 1) {
            System.out.println("[Adapter] Evaluation skipped - no opponents loaded or no games.");
            return "";
        }
        System.out.printf("[Adapter] Evaluation: base tree alone and adapter vs %d opponents on %d maps, %d games each.%n",
                opponents.size(), maps.size(), games);
        long start = System.currentTimeMillis();

        // futures[variant][opponent][map][game]; variant 0 = base alone, 1 = adapter
        @SuppressWarnings("unchecked")
        List<Future<Game>>[][][] futures = new List[2][opponents.size()][maps.size()];
        for (int v = 0; v < 2; v++) {
            for (int o = 0; o < opponents.size(); o++) {
                for (int m = 0; m < maps.size(); m++) {
                    futures[v][o][m] = new ArrayList<>();
                    for (int g = 0; g < games; g++) {
                        boolean useAdapter = v == 1;
                        AI opponent = opponents.get(o);
                        String map = maps.get(m);
                        long seed = random.nextLong();
                        futures[v][o][m].add(pool.submit(() -> playGame(map, opponent, seed, useAdapter)));
                    }
                }
            }
        }
        Game[][][][] results = new Game[2][opponents.size()][maps.size()][games];
        for (int v = 0; v < 2; v++) {
            for (int o = 0; o < opponents.size(); o++) {
                for (int m = 0; m < maps.size(); m++) {
                    for (int g = 0; g < games; g++) {
                        try {
                            results[v][o][m][g] = futures[v][o][m].get(g).get();
                        } catch (Exception e) {
                            results[v][o][m][g] = new Game(Double.NaN, 0);
                        }
                    }
                }
            }
        }

        TextTable table = new TextTable("Opponent", "Map", "Base win rate", "Base std", "Base W/D/L", "Base cycles",
                "Adapter win rate", "Adapter std", "Adapter W/D/L", "Adapter cycles", "Errored");
        @SuppressWarnings("unchecked")
        List<Game>[] everything = new List[] {new ArrayList<>(), new ArrayList<>()};
        for (int o = 0; o < opponents.size(); o++) {
            String opponent = opponents.get(o).getClass().getSimpleName();
            @SuppressWarnings("unchecked")
            List<Game>[] opponentGames = new List[] {new ArrayList<>(), new ArrayList<>()};
            for (int m = 0; m < maps.size(); m++) {
                @SuppressWarnings("unchecked")
                List<Game>[] cell = new List[] {Arrays.asList(results[0][o][m]), Arrays.asList(results[1][o][m])};
                for (int v = 0; v < 2; v++) {
                    opponentGames[v].addAll(cell[v]);
                    everything[v].addAll(cell[v]);
                }
                addRow(table, opponent, mapName(maps.get(m)), cell);
            }
            if (maps.size() > 1) {
                addRow(table, opponent, "average (all maps)", opponentGames);
            }
            table.separator();
        }
        addRow(table, "All opponents", "average (all maps)", everything);

        String nl = System.lineSeparator();
        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "Evaluation - %s, %s%n", source,
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
        report.append(String.format(Locale.ROOT, "Base: %s (%s, T=%s). %d games per opponent per map, max %d cycles, "
                        + "opponent budget %d ms, playing as player %d.%n", describeBase(), model.baseMode,
                model.temperature, games, maxCycles, opponentTimeBudget, player));
        report.append("Win rate = (wins + 0.5 x draws) / games. Std = standard deviation of the per-game score"
                + nl + "(1 win, 0.5 draw, 0 loss) over that row's games. Cycles = mean game length (a game that"
                + nl + "reaches " + maxCycles + " is a draw). Errored = games that threw and are left out. Average rows"
                + nl + "pool all games of the opponent / of every opponent.").append(nl);
        report.append(nl).append(table.render());
        report.append(String.format(Locale.ROOT, "(%.0f s)%n", (System.currentTimeMillis() - start) / 1000.0));

        System.out.println();
        System.out.print(report);
        try {
            Files.writeString(reportFile.toPath(), report.toString());
            System.out.println("[Adapter] Evaluation saved to " + reportFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Adapter] Failed to save " + reportFile + ": " + e.getMessage());
        }
        return report.toString();
    }

    private String describeBase() {
        return model.baseTree == null || model.baseTree.root == null ? "?"
                : model.baseTree.root.getNodeCount() + "-node tree";
    }

    private static void addRow(TextTable table, String opponent, String map, List<Game>[] games) {
        String[] cells = new String[11];
        cells[0] = opponent;
        cells[1] = map;
        int errored = 0;
        for (int v = 0; v < 2; v++) {
            List<Double> scores = new ArrayList<>();
            double cycles = 0;
            for (Game g : games[v]) {
                if (Double.isNaN(g.score)) {
                    errored++;
                } else {
                    scores.add(g.score);
                    cycles += g.cycles;
                }
            }
            double mean = scores.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double variance = scores.stream().mapToDouble(s -> (s - mean) * (s - mean)).average().orElse(Double.NaN);
            long wins = scores.stream().filter(s -> s == 1.0).count();
            long draws = scores.stream().filter(s -> s == 0.5).count();
            cells[2 + 4 * v] = String.format(Locale.ROOT, "%.3f", mean);
            cells[3 + 4 * v] = String.format(Locale.ROOT, "%.3f", Math.sqrt(variance));
            cells[4 + 4 * v] = wins + "/" + draws + "/" + (scores.size() - wins - draws);
            cells[5 + 4 * v] = scores.isEmpty() ? "-" : String.format(Locale.ROOT, "%.0f", cycles / scores.size());
        }
        cells[10] = String.valueOf(errored);
        table.row(cells);
    }

    static String mapName(String mapPath) {
        String name = new File(mapPath).getName();
        return name.endsWith(".xml") ? name.substring(0, name.length() - 4) : name;
    }

    private Game playGame(String map, AI opponentTemplate, long seed, boolean useAdapter) {
        try {
            GameState gs = new GameState(PhysicalGameState.load(map, utt), utt);
            AdapterBot bot = new AdapterBot(model, utt, useAdapter, false, false, seed);
            AI opponent = GPOpponentLoader.freshInstance(opponentTemplate, utt);
            if (opponent instanceof AIWithComputationBudget) {
                ((AIWithComputationBudget) opponent).setTimeBudget(opponentTimeBudget);
                ((AIWithComputationBudget) opponent).setIterationsBudget(-1);
            }
            if (opponent instanceof InterruptibleAI) {
                opponent = new ContinuingAI(opponent);
            }
            bot.reset();
            opponent.reset();
            boolean gameover = false;
            while (!gameover && gs.getTime() < maxCycles) {
                PlayerAction ours = bot.getAction(player, gs);
                PlayerAction theirs = opponent.getAction(1 - player, gs);
                gs.issueSafe(ours);
                gs.issueSafe(theirs);
                gameover = gs.cycle();
            }
            int winner = gs.winner();
            return new Game(winner == player ? 1.0 : (winner == -1 ? 0.5 : 0.0), gs.getTime());
        } catch (Exception e) {
            System.out.println("[Adapter] Evaluation game errored (" + new File(map).getName() + " vs "
                    + opponentTemplate.getClass().getSimpleName() + "): " + e);
            return new Game(Double.NaN, 0);
        }
    }
}
