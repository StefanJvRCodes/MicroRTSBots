package ai.custom.GP_bot;

import ai.core.AI;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import rts.units.UnitTypeTable;



public class GP {
    /** Controls which matches contribute to fitness each generation. */
    public enum TournamentMode {
        /** Population plays only itself, round-robin (the original behavior). */
        SELF,
        /** Population plays only the fixed roster loaded from {@code opponentsFolder}. */
        OPPONENTS,
        /** Population plays both; scores are blended by {@code fixedOpponentWeight}. */
        BOTH,
        /**
         * Two-phase transfer: evolve {@code selfPlayGenerations} generations
         * with SELF fitness, then carry that final population over as the
         * starting population for {@code generations} more generations under
         * {@code transferTargetMode} (OPPONENTS or BOTH). Self-play gives the
         * population a usable fitness gradient while it still can't beat any
         * fixed opponent (where every individual scoring 0 makes selection
         * random).
         */
        TRANSFER
    }

    public static final String HIGHEST_WINRATE_CSV_FILE = "highest_winrate_per_generation.csv";
    public static final String AVERAGE_WINRATE_CSV_FILE = "average_winrate_per_generation.csv";
    public static final String STD_WINRATE_CSV_FILE = "std_winrate_per_generation.csv";
    /** Prefix for the CSVs written during the self-play phase of TRANSFER mode, so the two phases plot separately. */
    public static final String SELF_PLAY_CSV_PREFIX = "selfplay_";

    //GP parameters
    public int seed = -1;
    public int populationSize = -1;
    public int maxDepth = -1;
    public int functionProbability = -1;
    public int terminalProbability = -1;
    public int tournamentSize = -1;
    public int mutationRate = -1;
    public int crossoverRate = -1;
    public int reproductionRate = -1;
    public int generations = -1;
    public double modiRate = -1.0;
    public int numOutputCells = 7;
    public int currSeed;
    public Random random;
    public int growProbability = -1;
    public int fullProbability = -1;
    public String[] functions = Node.DEFAULT_FUNCTIONS;
    public String[] terminals = new String[0];
    public int[] featureIndices = Node.DEFAULT_FEATURE_INDICES;
    public UnitTypeTable utt = new UnitTypeTable();

    /** How each unit's action is picked from the tree's output cells during fitness games (see {@link TreePolicy.Mode}). */
    public TreePolicy.Mode actionSelection = TreePolicy.Mode.ARGMAX;
    /** Softmax temperature for SOFTMAX and ONEHOT selection; ignored by ARGMAX. */
    public double temperature = 1.0;
    /**
     * Seeds each bot's action-sampling RNG. Kept apart from {@link #random}
     * so the selection mode doesn't change the breeding sequence.
     */
    private final Random policySeeds = new Random();
    /** Every .xml map in this folder is played, by self-play and against every opponent. */
    public String trainingMapsFolder = "training_maps";
    private List<String> trainingMaps;
    public String opponentsFolder = "opponents";
    /** Project bots (by alias or class name) added to the JAR opponents - e.g. weak bots so early generations can win some games. */
    public List<String> builtinOpponents = new ArrayList<>();
    /** Which classes to use from the opponent JARs (simple or full names); empty = every AI class found. */
    public List<String> opponentClasses = new ArrayList<>();
    public double fixedOpponentWeight = 0.5;
    public TournamentMode tournamentMode = TournamentMode.BOTH;

    // TRANSFER mode settings.
    public int selfPlayGenerations = -1;
    /**
     * Population size during the self-play phase; 0 = same as
     * {@link #populationSize}. If smaller, the final self-play population is
     * kept and topped up to {@code populationSize} with offspring bred from
     * it (see {@link #expandPopulation}) before the opponent phase starts.
     */
    public int selfPlayPopulationSize = 0;
    /** Mode used for the second phase of TRANSFER: OPPONENTS or BOTH. */
    public TournamentMode transferTargetMode = TournamentMode.OPPONENTS;
    /** Where the final self-play population is saved, so phase 2 can be rerun without redoing phase 1. */
    public String selfPlayPopulationFile = "gp_populations/self_play_final.ser";
    /** If true and {@code selfPlayPopulationFile} exists, load it instead of running phase 1. */
    public boolean skipSelfPlay = false;
    /** Hand-built tree to seed the initial population with: "none" or "stefan" (see {@link HandcraftedTrees}). */
    public String seedTree = "none";
    /** Fraction of the initial population taken by the seed tree: one exact copy, the rest mutants of it. */
    public double seedFraction = 0.5;
    /** Opponents the final generation is evaluated against after training ("none" in parameters.txt = skip). */
    public List<String> finalEvaluationOpponents = new ArrayList<>(java.util.Arrays.asList("Coac", "Rojo", "mayari"));
    /** Games per individual, per opponent, per map in the final evaluation. */
    public int finalEvaluationGames = 1;
    /**
     * Written after every evaluated generation, so a run stopped with Ctrl+C
     * can continue with "resume: true" from the last finished generation.
     */
    public String checkpointFile = "gp_populations/checkpoint.ser";
    /** If true and {@code checkpointFile} exists, continue that run instead of starting a new one. */
    public boolean resume = false;
    /** Prepended to every winrate CSV filename; switched to {@link #SELF_PLAY_CSV_PREFIX} during self-play. */
    private String csvPrefix = "";
    // How many games run at once, across self-play and every opponent. Game
    // simulation is CPU-bound, and search-based opponents spend their whole
    // time budget computing each decision, so more threads than cores just
    // gives each one less CPU per decision (weaker play) rather than more
    // throughput - default to the core count; "game threads" overrides it.
    public int gameThreads = Runtime.getRuntime().availableProcessors();
    /**
     * Milliseconds per decision for the fixed opponents. Search-based bots
     * (Droplet, Izanagi, Tiamat, ImprovedTiamat) use all of it every cycle,
     * so it sets how long their games take - and how strong they play.
     * Tournaments use 100.
     */
    public int opponentTimeBudget = 100;
    /**
     * Self-play opponents per individual per generation, drawn at random;
     * 0 = full round robin (every pair, n(n-1)/2 of them).
     */
    public int selfPlayOpponents = 0;
    /**
     * Save a replay zip of every game under gp_traces/ (one subfolder per
     * task) - diagnostic only, and thousands of files per generation at
     * normal population sizes, so off unless "save traces: true".
     */
    public boolean saveTraces = false;
    private ExecutorService gamePool;
    private List<AI> fixedOpponents;
    private boolean fixedOpponentsLoaded = false;


    //outputs
    public Tree[] population;
    public double[] winRates;
    public Vector<Double>[] actionDistributions;

    private Tree treeFactory;

    private Tree treeFactory() {
        if (treeFactory == null) {
            treeFactory = new Tree(random, modiRate, functionProbability, terminalProbability,
                    maxDepth, fullProbability, growProbability, functions, terminals, featureIndices, numOutputCells);
        }
        return treeFactory;
    }




    GP(){
        String fileName = "parameters.txt";

        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    String parameter = parts[0].trim();
                    String value = parts[1].trim();
                    parameter = parameter.toLowerCase();

                    switch (parameter) {
                        case "seed":
                            seed = Integer.parseInt(value);
                            break;
                        case "population size":
                            populationSize = Integer.parseInt(value);
                            break;
                        case "max depth":
                            maxDepth = Integer.parseInt(value);
                            break;
                        case "function probability":
                            functionProbability = Integer.parseInt(value);
                            break;
                        case "terminal probability":
                            terminalProbability = Integer.parseInt(value);
                            break;
                        case "tournament size":
                            tournamentSize = Integer.parseInt(value);
                            break;
                        case "mutation rate":
                            mutationRate = Integer.parseInt(value);
                            break;
                        case "crossover rate":
                            crossoverRate = Integer.parseInt(value);
                            break;
                        case "reproduction rate":
                            reproductionRate = Integer.parseInt(value);
                            break;
                        case "generations":
                            generations = Integer.parseInt(value);
                            break;
                        case "modi rate":
                            modiRate = Double.parseDouble(value);
                            break;
                        case "grow probability":
                            growProbability = Integer.parseInt(value);
                            break;
                        case "full probability":
                            fullProbability = Integer.parseInt(value);
                            break;
                        case "action selection":
                            actionSelection = TreePolicy.Mode.parse(value);
                            break;
                        case "temperature":
                            temperature = Double.parseDouble(value);
                            break;
                        case "training maps folder":
                            trainingMapsFolder = value;
                            break;
                        case "opponents folder":
                            opponentsFolder = value;
                            break;
                        case "builtin opponents":
                            builtinOpponents = parseNameList(value);
                            break;
                        case "opponent classes":
                            opponentClasses = parseNameList(value);
                            break;
                        case "fixed opponent weight":
                            fixedOpponentWeight = Double.parseDouble(value);
                            break;
                        case "max opponent threads":
                        case "game threads":
                            gameThreads = Integer.parseInt(value);
                            break;
                        case "opponent time budget":
                            opponentTimeBudget = Integer.parseInt(value);
                            break;
                        case "self play opponents":
                            selfPlayOpponents = value.equalsIgnoreCase("all") ? 0 : Integer.parseInt(value);
                            break;
                        case "save traces":
                            saveTraces = Boolean.parseBoolean(value);
                            break;
                        case "tournament mode":
                            tournamentMode = parseTournamentMode(value);
                            break;
                        case "self play generations":
                            selfPlayGenerations = Integer.parseInt(value);
                            break;
                        case "transfer target mode":
                            transferTargetMode = parseTournamentMode(value);
                            if (transferTargetMode != TournamentMode.OPPONENTS && transferTargetMode != TournamentMode.BOTH) {
                                throw new IllegalArgumentException("transfer target mode must be opponents or both.");
                            }
                            break;
                        case "self play population size":
                            selfPlayPopulationSize = value.equalsIgnoreCase("same") ? 0 : Integer.parseInt(value);
                            break;
                        case "self play population file":
                            selfPlayPopulationFile = value;
                            break;
                        case "skip self play":
                            skipSelfPlay = Boolean.parseBoolean(value);
                            break;
                        case "seed tree":
                            seedTree = value.toLowerCase();
                            if (!seedTree.equals("none") && !seedTree.equals("stefan")) {
                                throw new IllegalArgumentException("seed tree must be none or stefan.");
                            }
                            break;
                        case "seed fraction":
                            seedFraction = Double.parseDouble(value);
                            break;
                        case "final evaluation opponents":
                            finalEvaluationOpponents = value.equalsIgnoreCase("none")
                                    ? new ArrayList<>() : parseNameList(value);
                            break;
                        case "final evaluation games":
                            finalEvaluationGames = Integer.parseInt(value);
                            break;
                        case "checkpoint file":
                            checkpointFile = value;
                            break;
                        case "resume":
                            resume = Boolean.parseBoolean(value);
                            break;
                        default:
                            System.out.println("Unknown parameter: " + parameter);
                    }
                }
            }
            if (mutationRate + crossoverRate + reproductionRate != 100) {
                throw new IllegalArgumentException("Mutation, crossover, and reproduction rates must sum to 100.");
            }
            if (functionProbability + terminalProbability != 100) {
                throw new IllegalArgumentException("Function and terminal probabilities must sum to 100.");
            }
            if (actionSelection != TreePolicy.Mode.ARGMAX && !(temperature > 0)) {
                throw new IllegalArgumentException("temperature must be > 0 for " + actionSelection + " action selection.");
            }
            if (growProbability + fullProbability != 100) {
                throw new IllegalArgumentException("Grow and full probabilities must sum to 100.");
            }
            if (seed == -1 || populationSize == -1 || maxDepth == -1 || functionProbability == -1 || terminalProbability == -1 || tournamentSize == -1 || mutationRate == -1 || crossoverRate == -1 || reproductionRate == -1 || generations == -1) {
                throw new IllegalArgumentException("One or more parameters are missing in the parameters file.");
            }
            if (tournamentMode == TournamentMode.TRANSFER && selfPlayGenerations <= 0
                    && !(skipSelfPlay && new File(selfPlayPopulationFile).exists())) {
                throw new IllegalArgumentException("tournament mode transfer needs \"self play generations\" > 0 "
                        + "(or \"skip self play: true\" with an existing self play population file).");
            }


            random = new Random(seed);




        }
        catch (Exception e) {
            System.out.println("Error reading parameters file: " + e.getMessage());
        }

    }


    public void run(){
        if (random == null) {
            random = new Random(seed);
        }
        System.out.println("[GP] Action selection: " + actionSelection
                + (actionSelection == TreePolicy.Mode.ARGMAX ? "" : " (temperature " + temperature + ")"));
        System.out.println("[GP] Opponent time budget: " + opponentTimeBudget + " ms; self-play opponents: "
                + (selfPlayOpponents > 0 ? selfPlayOpponents + " per individual" : "all (round robin)"));

        Checkpoint checkpoint = resume ? loadCheckpoint() : null;
        if (checkpoint == null) {
            resetHighestWinrateCsv();
            resetAverageWinrateCsv();
            resetStdWinrateCsv();
        }

        if (tournamentMode == TournamentMode.TRANSFER) {
            runTransfer(checkpoint);
        } else {
            int firstGeneration = 0;
            if (checkpoint != null) {
                firstGeneration = restoreCheckpoint(checkpoint);
            } else {
                initializePopulation();
            }
            evolve(tournamentMode, generations, "", firstGeneration, false);
        }

        evaluateFinalGeneration();
        exportFinalBestBot();
    }

    /** Where {@link #evaluateFinalGeneration} writes its report and per-individual results. */
    public static final String FINAL_EVALUATION_REPORT_FILE = "final_evaluation.txt";
    public static final String FINAL_EVALUATION_CSV_FILE = "final_evaluation_population.csv";

    /**
     * Plays every individual of the final generation against each of
     * {@link #finalEvaluationOpponents} on every training map
     * ({@link #finalEvaluationGames} games each) and reports, per opponent
     * and map, the population's average win rate, its standard deviation
     * across individuals and the best individual's win rate - then the same
     * per opponent over all maps and overall. Win rate = (wins + 0.5 x
     * draws) / games, as in fitness. Printed, saved to
     * {@value #FINAL_EVALUATION_REPORT_FILE}, with every individual's numbers
     * in {@value #FINAL_EVALUATION_CSV_FILE}.
     */
    private void evaluateFinalGeneration() {
        if (finalEvaluationOpponents.isEmpty() || population == null || population.length == 0) {
            return;
        }
        List<String> maps = trainingMaps();
        List<AI> opponents = GPOpponentLoader.loadNamedOpponents(finalEvaluationOpponents, opponentsFolder, utt, maps);
        if (opponents.isEmpty()) {
            System.out.println("[GP] Final evaluation skipped - none of " + finalEvaluationOpponents + " could be loaded.");
            return;
        }
        int n = population.length;
        List<AI> bots = new ArrayList<>();
        for (Tree tree : population) {
            bots.add(new GPEvolvedBot(tree, utt, actionSelection, temperature, policySeeds.nextLong()));
        }

        System.out.printf("[GP] Final evaluation: %d individuals vs %d opponents on %d maps, %d game(s) each.%n",
                n, opponents.size(), maps.size(), finalEvaluationGames);
        long start = System.currentTimeMillis();
        double[][][] rates = new double[opponents.size()][maps.size()][];  // [opponent][map][individual]
        try {
            for (int o = 0; o < opponents.size(); o++) {
                for (int m = 0; m < maps.size(); m++) {
                    rates[o][m] = GPTournamentEvaluator.evaluateFixedOpponentWinRatesParallel(
                            bots, opponents.get(o), java.util.Collections.singletonList(maps.get(m)), utt,
                            finalEvaluationGames, 2000, opponentTimeBudget, 100,
                            "gp_tournament_rw/final_evaluation/opp" + o + "_map" + m, null, gamePool());
                }
            }
        } catch (Exception e) {
            System.err.println("[GP] Final evaluation failed: " + e);
            return;
        }

        // Each individual's mean per opponent (over maps) and overall (over every opponent and map).
        double[][] perOpponent = new double[opponents.size()][n];
        double[] overall = new double[n];
        for (int i = 0; i < n; i++) {
            double[] all = new double[opponents.size() * maps.size()];
            for (int o = 0; o < opponents.size(); o++) {
                double[] cells = new double[maps.size()];
                for (int m = 0; m < maps.size(); m++) {
                    cells[m] = rates[o][m][i];
                    all[o * maps.size() + m] = cells[m];
                }
                perOpponent[o][i] = getAverage(cells);
            }
            overall[i] = getAverage(all);
        }
        int best = 0;
        for (int i = 1; i < n; i++) {
            if (isBetterEvaluation(overall[i], overall[best])) {
                best = i;
            }
        }

        TextTable table = new TextTable("Opponent", "Map", "Average win rate", "Std (population)", "Best win rate",
                "Top individual");
        for (int o = 0; o < opponents.size(); o++) {
            String opponent = opponents.get(o).getClass().getSimpleName();
            for (int m = 0; m < maps.size(); m++) {
                addFinalEvaluationRow(table, opponent, mapName(maps.get(m)), rates[o][m], best);
            }
            if (maps.size() > 1) {
                addFinalEvaluationRow(table, opponent, "all maps", perOpponent[o], best);
            }
            table.separator();
        }
        addFinalEvaluationRow(table, "All opponents", "all maps", overall, best);

        StringBuilder report = new StringBuilder();
        String nl = System.lineSeparator();
        report.append(String.format(Locale.ROOT, "Final evaluation - final generation (%d individuals), %s%n", n,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
        report.append(String.format(Locale.ROOT, "%d game(s) per individual per opponent per map, max 2000 cycles, "
                + "opponent budget %d ms.%n", finalEvaluationGames, opponentTimeBudget));
        report.append("Win rate = (wins + 0.5 x draws) / games. Average and Std are over the population's individuals;"
                + nl + "Best = highest individual win rate in that row; Top individual = the individual with the best "
                + "overall win rate." + nl + nl);
        report.append(table.render()).append(nl);
        report.append(String.format(Locale.ROOT, "Best individual overall: #%d, win rate %.3f%n", best, overall[best]));
        for (int o = 0; o < opponents.size(); o++) {
            report.append(String.format(Locale.ROOT, "  vs %-14s", opponents.get(o).getClass().getSimpleName()));
            for (int m = 0; m < maps.size(); m++) {
                report.append(String.format(Locale.ROOT, "  %s %.3f", mapName(maps.get(m)), rates[o][m][best]));
            }
            report.append(nl);
        }
        report.append(String.format(Locale.ROOT, "(%.0f s)%n", (System.currentTimeMillis() - start) / 1000.0));

        System.out.println();
        System.out.print(report);
        try {
            Files.writeString(new File(FINAL_EVALUATION_REPORT_FILE).toPath(), report.toString(), StandardCharsets.UTF_8);
            StringBuilder csv = new StringBuilder("individual,training_win_rate");
            for (AI opponent : opponents) {
                for (String map : maps) {
                    csv.append(',').append(opponent.getClass().getSimpleName()).append('_').append(mapName(map));
                }
            }
            csv.append(",overall").append(nl);
            for (int i = 0; i < n; i++) {
                csv.append(i).append(',').append(String.format(Locale.ROOT, "%.4f", population[i].winRate));
                for (int o = 0; o < opponents.size(); o++) {
                    for (int m = 0; m < maps.size(); m++) {
                        csv.append(String.format(Locale.ROOT, ",%.4f", rates[o][m][i]));
                    }
                }
                csv.append(String.format(Locale.ROOT, ",%.4f", overall[i])).append(nl);
            }
            Files.writeString(new File(FINAL_EVALUATION_CSV_FILE).toPath(), csv.toString(), StandardCharsets.UTF_8);
            System.out.println("[GP] Final evaluation saved to " + new File(FINAL_EVALUATION_REPORT_FILE).getAbsolutePath()
                    + " and " + FINAL_EVALUATION_CSV_FILE);
        } catch (IOException e) {
            System.err.println("[GP] Failed to save final evaluation: " + e.getMessage());
        }
    }

    private void addFinalEvaluationRow(TextTable table, String opponent, String map, double[] winRates, int best) {
        table.row(opponent, map,
                String.format(Locale.ROOT, "%.3f", getAverage(winRates)),
                String.format(Locale.ROOT, "%.3f", getStandardDeviation(winRates)),
                String.format(Locale.ROOT, "%.3f", getHighestWinrate(winRates)),
                String.format(Locale.ROOT, "%.3f", winRates[best]));
    }

    private static String mapName(String mapPath) {
        String name = new File(mapPath).getName();
        return name.endsWith(".xml") ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * Runs generations {@code firstGeneration}..{@code numGenerations-1} on
     * the current population. Each generation but the first is bred from the
     * previous one's win rates, then evaluated and checkpointed - so on
     * return every tree's winRate reflects the population as it now stands,
     * and a resumed run (firstGeneration > 0) starts by breeding the
     * checkpointed, already-evaluated population.
     */
    private void evolve(TournamentMode mode, int numGenerations, String label, int firstGeneration,
                        boolean selfPlayPhase) {
        for (int currGeneration = firstGeneration; currGeneration < numGenerations; currGeneration++) {
            if (currGeneration > 0) {
                generateNewPopulation();
            }
            System.out.printf("%sGeneration %d/%d%n", label, currGeneration + 1, numGenerations);
            System.out.println(population[0].root.toString());
            long start = System.currentTimeMillis();
            startTournament(population[0], mode);
            System.out.printf(Locale.ROOT, "%sGeneration %d evaluated in %.1fs%n",
                    label, currGeneration + 1, (System.currentTimeMillis() - start) / 1000.0);
            saveCheckpoint(new Checkpoint(tournamentMode, selfPlayPhase, currGeneration, population, random));
        }
    }

    /**
     * Phase 1: self-play from a random population (or load a saved one).
     * Phase 2: the final self-play generation becomes the starting
     * population against the fixed opponents.
     */
    private void runTransfer(Checkpoint checkpoint) {
        if (checkpoint != null && !checkpoint.selfPlayPhase) {
            int firstGeneration = restoreCheckpoint(checkpoint);
            evolve(transferTargetMode, generations, "[Vs opponents] ", firstGeneration, false);
            return;
        }

        // The configured size, before a restored or loaded self-play population changes populationSize.
        int opponentPhaseSize = populationSize;
        File populationFile = new File(selfPlayPopulationFile);
        if (checkpoint == null && skipSelfPlay && populationFile.exists()) {
            population = loadPopulation(populationFile);
            System.out.printf("[GP] Loaded %d-tree self-play population from %s - skipping self-play phase.%n",
                    population.length, populationFile.getAbsolutePath());
        } else {
            int firstGeneration = 0;
            if (checkpoint != null) {
                firstGeneration = restoreCheckpoint(checkpoint);
            } else {
                if (selfPlayPopulationSize > 0) {
                    populationSize = selfPlayPopulationSize;
                }
                initializePopulation();
            }
            csvPrefix = SELF_PLAY_CSV_PREFIX;
            if (checkpoint == null) {
                resetHighestWinrateCsv();
                resetAverageWinrateCsv();
                resetStdWinrateCsv();
            }
            evolve(TournamentMode.SELF, selfPlayGenerations, "[Self-play] ", firstGeneration, true);
            csvPrefix = "";
            savePopulation(populationFile);
        }

        expandPopulation(opponentPhaseSize);
        System.out.println("[GP] Transferring self-play population to " + transferTargetMode + " phase.");
        evolve(transferTargetMode, generations, "[Vs opponents] ", 0, false);
    }

    /**
     * Grows an evaluated population to {@code targetSize}: every current
     * tree is kept, and the rest are offspring of it - parents picked by
     * tournament selection on their (self-play) win rates, each child made
     * by crossover, mutation or reproduction in the configured proportions.
     * No-op if the population is already that big.
     */
    private void expandPopulation(int targetSize) {
        int parents = population.length;
        if (parents >= targetSize) {
            return;
        }
        populationSize = parents;  // selection draws from the current population
        Tree[] expanded = java.util.Arrays.copyOf(population, targetSize);
        int crossovers = 0;
        int mutations = 0;
        for (int i = parents; i < targetSize; i++) {
            int roll = random.nextInt(100);
            if (roll < crossoverRate) {
                expanded[i] = crossover(population[selectTournamentWinnerIndex()],
                        population[selectTournamentWinnerIndex()]);
                crossovers++;
            } else if (roll < crossoverRate + mutationRate) {
                expanded[i] = mutate(population[selectTournamentWinnerIndex()]);
                mutations++;
            } else {
                expanded[i] = new Tree(population[selectTournamentWinnerIndex()].root.deepCopy(), numOutputCells);
            }
        }
        population = expanded;
        populationSize = targetSize;
        System.out.printf("[GP] Expanded the population from %d to %d trees for the opponent phase: kept all %d, "
                        + "bred %d (%d crossover, %d mutation, %d copies).%n", parents, targetSize, parents,
                targetSize - parents, crossovers, mutations, targetSize - parents - crossovers - mutations);
    }

    /**
     * A tree to use as a fixed base agent: "stefan" for the hand-built
     * StefanFirstBot tree, or a .ser file holding one Tree (e.g. an exported
     * bestTree.ser), a Tree[] population (e.g. self_play_final.ser) or a GP
     * checkpoint - from a population, the tree with the highest win rate.
     */
    public static Tree loadBaseTree(String spec) {
        if ("stefan".equalsIgnoreCase(spec.trim())) {
            return HandcraftedTrees.stefanFirstBot(TreePolicy.NUM_OUTPUT_CELLS);
        }
        File file = new File(spec.trim());
        Object loaded;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            loaded = ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to load base tree from " + file.getAbsolutePath(), e);
        }
        Tree[] population;
        if (loaded instanceof Tree) {
            return (Tree) loaded;
        } else if (loaded instanceof Tree[]) {
            population = (Tree[]) loaded;
        } else if (loaded instanceof Checkpoint) {
            population = ((Checkpoint) loaded).population;
        } else {
            throw new IllegalArgumentException(file.getAbsolutePath() + " holds a " + loaded.getClass().getName()
                    + ", not a Tree, Tree[] or GP checkpoint.");
        }
        Tree best = null;
        for (Tree tree : population) {
            if (tree != null && tree.root != null && (best == null
                    || (Double.isFinite(tree.winRate) && !(tree.winRate <= best.winRate)))) {
                best = tree;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("No usable tree in " + file.getAbsolutePath());
        }
        System.out.printf(Locale.ROOT, "[GP] Base tree from %s: best of %d, win rate %.4f%n",
                file.getAbsolutePath(), population.length, best.winRate);
        return best;
    }

    /** Everything needed to continue a run after its last evaluated generation. */
    private static final class Checkpoint implements Serializable {
        private static final long serialVersionUID = 1L;

        final TournamentMode tournamentMode;
        /** True while in phase 1 (self-play) of a TRANSFER run. */
        final boolean selfPlayPhase;
        /** Index (0-based) of the last generation that was evaluated. */
        final int generation;
        /** That generation's population, with its win rates. */
        final Tree[] population;
        /**
         * The GP's RNG, written in the same stream as the population so the
         * trees' shared references to it stay shared after loading, and a
         * resumed run breeds exactly as the uninterrupted one would have.
         */
        final Random random;

        Checkpoint(TournamentMode tournamentMode, boolean selfPlayPhase, int generation, Tree[] population,
                   Random random) {
            this.tournamentMode = tournamentMode;
            this.selfPlayPhase = selfPlayPhase;
            this.generation = generation;
            this.population = population;
            this.random = random;
        }
    }

    /** Written to a temp file and moved into place, so Ctrl+C mid-write never leaves a half-written checkpoint. */
    private void saveCheckpoint(Checkpoint checkpoint) {
        File file = new File(checkpointFile);
        File temp = new File(checkpointFile + ".tmp");
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create " + parent.getAbsolutePath());
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(temp))) {
                oos.writeObject(checkpoint);
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Not fatal: training continues, it just can't be resumed from this generation.
            System.err.println("[GP] Failed to save checkpoint: " + e.getMessage());
        }
    }

    /** Returns null (start a new run) when there is no checkpoint to resume from. */
    private Checkpoint loadCheckpoint() {
        File file = new File(checkpointFile);
        if (!file.exists()) {
            System.out.println("[GP] resume is on but no checkpoint at " + file.getAbsolutePath()
                    + " - starting a new run.");
            return null;
        }
        Checkpoint checkpoint;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            checkpoint = (Checkpoint) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to load checkpoint from " + file.getAbsolutePath(), e);
        }
        if (checkpoint.tournamentMode != tournamentMode) {
            throw new IllegalStateException("Checkpoint " + file.getAbsolutePath() + " is from a "
                    + checkpoint.tournamentMode + " run but tournament mode is " + tournamentMode
                    + " - set the tournament mode back, or \"resume: false\" to start over.");
        }
        return checkpoint;
    }

    /**
     * Installs the checkpoint's population and RNG, trims the phase's CSVs
     * back to the checkpointed generation (a run stopped between writing the
     * CSVs and the checkpoint would otherwise log that generation twice), and
     * returns the generation to continue from.
     */
    private int restoreCheckpoint(Checkpoint checkpoint) {
        population = checkpoint.population;
        populationSize = population.length;
        random = checkpoint.random;
        treeFactory = null;

        int keptRows = checkpoint.generation + 1;
        csvPrefix = checkpoint.selfPlayPhase ? SELF_PLAY_CSV_PREFIX : "";
        truncateCsv(HIGHEST_WINRATE_CSV_FILE, keptRows);
        truncateCsv(AVERAGE_WINRATE_CSV_FILE, keptRows);
        truncateCsv(STD_WINRATE_CSV_FILE, keptRows);
        if (checkpoint.selfPlayPhase) {
            // Phase 2 hasn't started yet, so its CSVs hold the previous run's rows.
            csvPrefix = "";
            resetHighestWinrateCsv();
            resetAverageWinrateCsv();
            resetStdWinrateCsv();
        }
        csvPrefix = "";

        System.out.printf("[GP] Resuming %s%s run from %s: generation %d done, %d trees.%n",
                tournamentMode, checkpoint.selfPlayPhase ? " (self-play phase)" : "",
                new File(checkpointFile).getAbsolutePath(), keptRows, populationSize);
        return keptRows;
    }

    /** Keeps only the first {@code rows} lines of {@code csvPrefix + baseName}. */
    private void truncateCsv(String baseName, int rows) {
        File file = new File(csvPrefix + baseName);
        if (!file.exists()) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.size() > rows) {
                Files.write(file.toPath(), lines.subList(0, rows), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to trim " + file, e);
        }
    }

    private void savePopulation(File file) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create " + parent.getAbsolutePath());
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                oos.writeObject(population);
            }
            System.out.println("[GP] Saved self-play population to " + file.getAbsolutePath());
        } catch (IOException e) {
            // Not fatal: phase 2 can still use the in-memory population.
            System.err.println("[GP] Failed to save self-play population: " + e.getMessage());
        }
    }

    private Tree[] loadPopulation(File file) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            Tree[] loaded = (Tree[]) ois.readObject();
            if (loaded.length != populationSize) {
                // Selection and breeding index by populationSize, so it must match the loaded array.
                System.out.printf("[GP] Saved population has %d trees, population size is %d%s.%n",
                        loaded.length, populationSize, loaded.length < populationSize
                                ? " - it will be topped up with offspring" : " - using all " + loaded.length);
                populationSize = loaded.length;
            }
            return loaded;
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to load self-play population from " + file.getAbsolutePath(), e);
        }
    }

    private static List<String> parseNameList(String value) {
        List<String> names = new ArrayList<>();
        for (String name : value.split(",")) {
            if (!name.trim().isEmpty()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    private static TournamentMode parseTournamentMode(String value) {
        switch (value.toLowerCase()) {
            case "self":
                return TournamentMode.SELF;
            case "opponents":
                return TournamentMode.OPPONENTS;
            case "both":
                return TournamentMode.BOTH;
            case "transfer":
                return TournamentMode.TRANSFER;
            default:
                throw new IllegalArgumentException(
                        "Unknown tournament mode \"" + value + "\" - expected self, opponents, both, or transfer.");
        }
    }

    public void resetHighestWinrateCsv() {
        writeCsv(HIGHEST_WINRATE_CSV_FILE, null);
    }

    public void resetAverageWinrateCsv() {
        writeCsv(AVERAGE_WINRATE_CSV_FILE, null);
    }

    public void resetStdWinrateCsv() {
        writeCsv(STD_WINRATE_CSV_FILE, null);
    }

    public void appendHighestWinrateCsv(double highestWinrate) {
        writeCsv(HIGHEST_WINRATE_CSV_FILE, highestWinrate);
    }

    public void appendAverageWinrateCsv(double averageWinrate) {
        writeCsv(AVERAGE_WINRATE_CSV_FILE, averageWinrate);
    }

    public void appendStdWinrateCsv(double stdWinrate) {
        writeCsv(STD_WINRATE_CSV_FILE, stdWinrate);
    }

    /** Truncates {@code csvPrefix + baseName} when {@code value} is null, otherwise appends {@code value} as a line. */
    private void writeCsv(String baseName, Double value) {
        String fileName = csvPrefix + baseName;
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(fileName, value != null))) {
            if (value == null) {
                writer.print("");
            } else {
                writer.printf(Locale.ROOT, "%.8f%n", value);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write " + fileName, e);
        }
    }

    private void initializePopulation() {
        population = new Tree[populationSize];

        int fullCount = (int) Math.round((fullProbability / 100.0) * populationSize);
        fullCount = Math.max(0, Math.min(populationSize, fullCount));
        int growCount = populationSize - fullCount;

        int filled = 0;
        for (int i = 0; i < fullCount && filled < populationSize; i++) {
            Tree tree = newConfiguredTree();
            tree.full();
            tree.enforceRootModi();
            population[filled++] = tree;
        }
        for (int i = 0; i < growCount && filled < populationSize; i++) {
            Tree tree = newConfiguredTree();
            tree.grow();
            tree.enforceRootModi();
            population[filled++] = tree;
        }

        while (filled < populationSize) {
            Tree tree = newConfiguredTree();
            if (random.nextBoolean()) {
                tree.full();
                tree.enforceRootModi();
            } else {
                tree.grow();
                tree.enforceRootModi();
            }
            population[filled++] = tree;
        }

        seedHandcraftedTrees();
    }

    /** Overwrites the first {@code seedFraction} of the population with the seed tree and mutants of it. */
    private void seedHandcraftedTrees() {
        if (!"stefan".equals(seedTree)) {
            return;
        }
        Tree seed = HandcraftedTrees.stefanFirstBot(numOutputCells);
        int count = (int) Math.round(Math.max(0.0, Math.min(1.0, seedFraction)) * populationSize);
        count = Math.max(1, Math.min(populationSize, count));
        population[0] = seed;
        for (int i = 1; i < count; i++) {
            population[i] = mutate(seed);
        }
        System.out.printf("[GP] Seeded %d/%d trees from StefanFirstBot tree: %s%n", count, populationSize, seed.root);
    }

    private Tree newConfiguredTree() {
        return new Tree(random, modiRate, functionProbability, terminalProbability,
                maxDepth, fullProbability, growProbability,
                functions, terminals, featureIndices, numOutputCells);
    }

    public void generateNewPopulation() {
        if (population == null || population.length == 0) {
            return;
        }

        Tree[] newPopulation = new Tree[populationSize];
        int filled = 0;

        int crossoverChildren = (int) ((double) crossoverRate / 100 * populationSize);
        int mutationChildren = (int) ((double) mutationRate / 100 * populationSize);
        int reproductionChildren = (int) ((double) reproductionRate / 100 * populationSize);

        for (int K = 0; K < crossoverChildren && filled < populationSize; K++) {
            Tree parent1 = population[selectTournamentWinnerIndex()];
            Tree parent2 = population[selectTournamentWinnerIndex()];
            Tree newTree = crossover(parent1, parent2);
            newPopulation[filled++] = newTree;
        }

        for (int K = 0; K < mutationChildren && filled < populationSize; K++) {
            Tree parent = population[selectTournamentWinnerIndex()];
            Tree newTree = mutate(parent);
            newPopulation[filled++] = newTree;
        }

        for (int K = 0; K < reproductionChildren && filled < populationSize; K++) {
            Tree parent = population[selectTournamentWinnerIndex()];
            newPopulation[filled++] = new Tree(parent.root.deepCopy(), numOutputCells);
        }

        while (filled < populationSize) {
            Tree parent = population[selectTournamentWinnerIndex()];
            newPopulation[filled++] = new Tree(parent.root.deepCopy(), numOutputCells);
        }

        population = newPopulation;
    }

    public Tree crossover(Tree parent1, Tree parent2) {
        if (parent1 == null || parent2 == null) {
            throw new IllegalArgumentException("Parents cannot be null");
        }

        Node child = parent1.root.deepCopy();
        if (child == null || parent2.root == null) {
            return new Tree(child, numOutputCells);
        }

        Node targetInChild = child.getRandomNode(random);
        boolean isRoot = (targetInChild == child);

        if (!isRoot) {
            Node donor = parent2.root.getRandomNode(random);
            Node donorCopy = donor.deepCopy();
            Node parentOfTarget = child.findParentOf(targetInChild);
            parentOfTarget.replaceChild(targetInChild, donorCopy);
            return new Tree(child, numOutputCells);
        }

        for (int attempt = 0; attempt < 10; attempt++) {
            Node donor = parent2.root.getRandomNode(random);
            if (donor.type == Node.NodeType.FUNCTION) {
                Node donorCopy = donor.deepCopy();
                if (donorCopy.isModi) {
                    return new Tree(donorCopy, numOutputCells);
                }
                donorCopy.isModi = true;
                if (donorCopy.outputCellIndex < 0) {
                    donorCopy.outputCellIndex = random.nextInt(numOutputCells);
                }
                return new Tree(donorCopy, numOutputCells);
            }
        }
        return new Tree(child, numOutputCells);
    }

    public Tree mutate(Tree parent) {
        if (parent == null) {
            throw new IllegalArgumentException("Parent cannot be null");
        }

        Node child = parent.root.deepCopy();
        Node target = child.getRandomNode(random);
        boolean isRoot = (target == child);

        int remainingDepth = Math.max(1, maxDepth - target.getDepth() + 1);

        if (isRoot) {
            Node replacementRoot = treeFactory().recursiveGrow(maxDepth);
            treeFactory().configureModiRecursive(replacementRoot);
            return new Tree(replacementRoot, numOutputCells);
        }

        Node replacementNode = treeFactory().recursiveGrow(remainingDepth);
        treeFactory().configureModiRecursive(replacementNode);
        Node parentOfTarget = child.findParentOf(target);

        if (parentOfTarget != null) {
            parentOfTarget.replaceChild(target, replacementNode);
            return new Tree(child, numOutputCells);
        }

        return new Tree(parent.root.deepCopy(), numOutputCells);
    }

    public int selectTournamentWinnerIndex() {
        int drawCount = Math.max(1, Math.min(tournamentSize, populationSize));
        int bestIndex = random.nextInt(populationSize);
        double bestWinRate = population[bestIndex].winRate;

        for (int i = 1; i < drawCount; i++) {
            int candidateIndex = random.nextInt(populationSize);
            double candidateWinRate = population[candidateIndex].winRate;
            if (isBetterEvaluation(candidateWinRate, bestWinRate)) {
                bestWinRate = candidateWinRate;
                bestIndex = candidateIndex;
            }
        }
        return bestIndex;
    }

    public boolean isBetterEvaluation(double candidate, double currentBest) {
        boolean candidateFinite = Double.isFinite(candidate);
        boolean currentFinite = Double.isFinite(currentBest);

        if (candidateFinite && !currentFinite) {
            return true;
        }
        if (!candidateFinite && currentFinite) {
            return false;
        }
        return candidate > currentBest;
    }

    private Tree selectBestTree() {
        Tree best = null;
        if (population == null) {
            return null;
        }

        for (Tree candidate : population) {
            if (candidate == null) {
                continue;
            }
            if (best == null) {
                best = candidate;
                continue;
            }
            if (isBetterEvaluation(candidate.winRate, best.winRate)) {
                best = candidate;
            }
        }
        return best;
    }

    private void exportFinalBestBot() {
        if (population == null || population.length == 0) {
            System.out.println("[GP] No population available to export.");
            return;
        }

        Tree best = selectBestTree();
        if (best == null || best.root == null) {
            System.out.println("[GP] No valid best tree available to export.");
            return;
        }

        try {
            File exportDir = new File("best_bot");
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                throw new IOException("Failed to create export directory: " + exportDir.getAbsolutePath());
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File botDir = new File(exportDir, "final_best_tree_" + timestamp);
            if (!botDir.exists() && !botDir.mkdirs()) {
                throw new IOException("Failed to create temp bot directory: " + botDir.getAbsolutePath());
            }

            File treeFile = new File(botDir, "bestTree.ser");
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(treeFile))) {
                oos.writeObject(best);
            }

            File sourceFile = new File(botDir, "ExportedGPEvolvedBot.java");
            String source = generateExportedBotSource();
            Files.writeString(sourceFile.toPath(), source, StandardCharsets.UTF_8);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IllegalStateException("No Java compiler available in this JDK. Use a JDK, not just a JRE.");
            }

            String projectClasspath = buildProjectClasspath();
            int compileResult = compiler.run(null, null, null,
                    "-classpath", projectClasspath,
                    "-d", botDir.getAbsolutePath(),
                    sourceFile.getAbsolutePath());
            if (compileResult != 0) {
                throw new IllegalStateException("Failed to compile exported best bot wrapper class.");
            }

            File jarFile = new File(exportDir, "final_best_tree_" + timestamp + ".jar");
            createBotJar(botDir, jarFile);

            System.out.printf("[GP] Final best bot exported to %s%n", jarFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[GP] Failed to export final best bot: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private String buildProjectClasspath() {
        StringBuilder classpath = new StringBuilder();

        File binDir = new File("bin");
        if (binDir.exists()) {
            classpath.append(binDir.getAbsolutePath());
        }

        File libDir = new File("lib");
        if (libDir.exists()) {
            File[] jars = libDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars != null) {
                for (File jar : jars) {
                    if (classpath.length() > 0) {
                        classpath.append(File.pathSeparator);
                    }
                    classpath.append(jar.getAbsolutePath());
                }
            }
        }

        return classpath.length() == 0 ? "" : classpath.toString();
    }

    private String generateExportedBotSource() {
        return "package ai.custom.GP_bot;\n\n"
                + "import ai.core.AI;\n"
                + "import ai.core.AIWithComputationBudget;\n"
                + "import ai.core.ParameterSpecification;\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.List;\n"
                + "import rts.GameState;\n"
                + "import rts.PlayerAction;\n\n"
                + "public class ExportedGPEvolvedBot extends AIWithComputationBudget {\n"
                + "    private static final long serialVersionUID = 1L;\n\n"
                + "    public ExportedGPEvolvedBot() {\n"
                + "        super(-1, -1);\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public void reset() {\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public AI clone() {\n"
                + "        return new ExportedGPEvolvedBot();\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public PlayerAction getAction(int player, GameState gs) throws Exception {\n"
                + "        return new PlayerAction();\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public List<ParameterSpecification> getParameters() {\n"
                + "        return new ArrayList<>();\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public String toString() {\n"
                + "        return \"ExportedGPEvolvedBot\";\n"
                + "    }\n"
                + "}\n";
    }

    private void createBotJar(File botDir, File jarFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(jarFile);
             JarOutputStream jos = new JarOutputStream(fos, new Manifest())) {
            addJarEntry(jos, botDir, "ai/custom/GP_bot/ExportedGPEvolvedBot.class");

            JarEntry readme = new JarEntry("README.txt");
            jos.putNextEntry(readme);
            jos.write("This jar contains an exported GP-evolved bot.\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }

    private void addJarEntry(JarOutputStream jos, File rootDir, String relativePath) throws IOException {
        File source = new File(rootDir, relativePath.replace('/', File.separatorChar));
        if (!source.exists()) {
            return;
        }

        JarEntry entry = new JarEntry(relativePath);
        jos.putNextEntry(entry);
        byte[] bytes = Files.readAllBytes(source.toPath());
        jos.write(bytes);
        jos.closeEntry();
    }

    /** Averages only the finite entries of {@code values}, ignoring any NaN
     * (undefined win rate - e.g. an individual that never got a usable
     * result against a given opponent). Returns NaN only if every entry is
     * NaN - "no individual has any usable data this generation" is a real,
     * if unusual, state worth reporting as such rather than masking.
     * Without this, a single NaN anywhere in the array would poison the
     * average for the whole population via plain summation (NaN + x = NaN
     * for any x), even when most individuals have perfectly good data.
     */
    public double getAverage(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double total = 0.0;
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            if (Double.isFinite(values[i])) {
                total += values[i];
                count++;
            }
        }
        return count == 0 ? Double.NaN : total / count;
    }

    /** Same NaN-skipping treatment as {@link #getAverage}, over the same finite subset. */
    public double getStandardDeviation(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double average = getAverage(values);
        if (!Double.isFinite(average)) {
            return Double.NaN;
        }
        double sumSquaredDifferences = 0.0;
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            if (Double.isFinite(values[i])) {
                sumSquaredDifferences += Math.pow(values[i] - average, 2);
                count++;
            }
        }
        return Math.sqrt(sumSquaredDifferences / count);
    }

    /**
     * Highest finite value in {@code values}, ignoring NaN entries. Returns
     * NaN only if every entry is NaN. The previous implementation seeded
     * "highest" from values[0] unconditionally: if that one specific
     * individual had a NaN win rate, every comparison against it
     * (`values[i] > highest`) was `x > NaN`, which Java always evaluates to
     * false - so "highest" could never be replaced, poisoning this stat for
     * the whole generation regardless of how well everyone else did.
     */
    public double getHighestWinrate(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double highest = Double.NaN;
        for (int i = 0; i < values.length; i++) {
            if (Double.isFinite(values[i]) && (Double.isNaN(highest) || values[i] > highest)) {
                highest = values[i];
            }
        }
        return highest;
    }



    /** The .xml maps in {@link #trainingMapsFolder}, sorted by name; read once per run. */
    private synchronized List<String> trainingMaps() {
        if (trainingMaps == null) {
            File folder = new File(trainingMapsFolder);
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".xml"));
            if (files == null || files.length == 0) {
                throw new IllegalStateException("No .xml maps found in training maps folder \""
                        + folder.getAbsolutePath() + "\".");
            }
            java.util.Arrays.sort(files);
            trainingMaps = new ArrayList<>();
            for (File file : files) {
                trainingMaps.add(file.getPath());
            }
            System.out.println("[GP] Training maps (" + trainingMaps.size() + "): " + trainingMaps);
        }
        return trainingMaps;
    }

    private List<AI> loadFixedOpponentsIfNeeded() {
        if (!fixedOpponentsLoaded) {
            fixedOpponents = new ArrayList<>(
                    GPOpponentLoader.loadBuiltinOpponents(builtinOpponents, utt, trainingMaps()));
            for (AI ai : GPOpponentLoader.loadOpponentsFromFolder(opponentsFolder, utt, trainingMaps(), opponentClasses)) {
                // A bot can be both a built-in and found in a JAR (e.g. CoacAI: lib/Coac.jar and opponents/Coac.jar).
                if (fixedOpponents.stream().noneMatch(existing -> existing.getClass() == ai.getClass())) {
                    fixedOpponents.add(ai);
                }
            }
            fixedOpponentsLoaded = true;
            System.out.println("[GP] Opponent roster (" + fixedOpponents.size() + "): " + fixedOpponents);
        }
        return fixedOpponents;
    }

    private double[] blendWinRates(double[] selfPlay, double[] fixedOpponent, double weight) {
        double[] blended = new double[selfPlay.length];
        for (int i = 0; i < selfPlay.length; i++) {
            boolean selfFinite = Double.isFinite(selfPlay[i]);
            boolean fixedFinite = Double.isFinite(fixedOpponent[i]);
            if (selfFinite && fixedFinite) {
                blended[i] = weight * fixedOpponent[i] + (1.0 - weight) * selfPlay[i];
            } else if (fixedFinite) {
                blended[i] = fixedOpponent[i];
            } else {
                blended[i] = selfPlay[i];
            }
        }
        return blended;
    }

    /**
     * Small immutable result of one opponent's evaluation task, so results
     * can be collected from Futures and aggregated single-threaded after
     * every task has finished, rather than needing any of the actual
     * aggregation logic (crash bookkeeping, summing win rates) to be
     * synchronized itself.
     */
    private static final class OpponentEvaluation {
        final AI opponent;
        final double[] winRates;
        final Exception error;

        OpponentEvaluation(AI opponent, double[] winRates, Exception error) {
            this.opponent = opponent;
            this.winRates = winRates;
            this.error = error;
        }
    }

    /**
     * Plays the population against each currently-loaded fixed opponent
     * concurrently - one opponent's whole mini-tournament per thread - since
     * these evaluations are fully independent of one another: each
     * FixedOpponentsTournament call clones every AI before playing a game
     * (confirmed in Tournament.playSingleGame), so different opponents'
     * concurrent evaluations never touch the same live bot instance, only
     * independent clones of it. If a given opponent's mini-tournament
     * throws, it's logged and permanently removed from
     * {@link #fixedOpponents} (the same cached list {@link
     * #loadFixedOpponentsIfNeeded()} reuses every generation - once removed,
     * it's gone for the rest of the run, not just this generation).
     * Opponents that complete successfully have their win-rate arrays
     * averaged together, same as if it'd been one combined round robin.
     *
     * <p>Returns an all-NaN array (same convention as
     * {@link GPTournamentEvaluator#evaluateFixedOpponentsWinRates} with an
     * empty opponents list) if every opponent crashed this generation, or
     * none were loaded to begin with - callers already handle that the same
     * way they'd handle "no opponents configured".
     */
    /** Shared pool every game task runs on, so self-play and all opponents together use exactly {@link #gameThreads} cores. */
    private synchronized ExecutorService gamePool() {
        if (gamePool == null) {
            gamePool = Executors.newFixedThreadPool(Math.max(1, gameThreads), r -> {
                Thread t = new Thread(r, "gp-game");
                t.setDaemon(true);
                return t;
            });
        }
        return gamePool;
    }

    private String traceFolder(String path) {
        return saveTraces ? path : null;
    }

    private double[] evaluateSelfPlay(List<AI> bots) throws Exception {
        int n = bots.size();
        if (selfPlayOpponents <= 0 || selfPlayOpponents >= n - 1) {
            return GPTournamentEvaluator.evaluateRoundRobinWinRatesParallel(
                    bots, trainingMaps(), utt, 1, 2000, 100, 100,
                    traceFolder("gp_traces/self_play"), gamePool());
        }
        return GPTournamentEvaluator.evaluatePairsWinRatesParallel(
                bots, sampleSelfPlayPairs(n, selfPlayOpponents), trainingMaps(), utt, 1, 2000, 100, 100,
                traceFolder("gp_traces/self_play"), gamePool());
    }

    /**
     * {@code rounds} random pairings of the population: each round shuffles
     * the individuals and pairs them off, so everyone plays {@code rounds}
     * opponents (one fewer for the odd one out of a round, or when a pair
     * repeats) - n*rounds/2 pairs instead of the round robin's n(n-1)/2.
     */
    private List<int[]> sampleSelfPlayPairs(int n, int rounds) {
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        List<int[]> pairs = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        for (int r = 0; r < rounds; r++) {
            java.util.Collections.shuffle(order, random);
            for (int k = 0; k + 1 < n; k += 2) {
                int i = Math.min(order.get(k), order.get(k + 1));
                int j = Math.max(order.get(k), order.get(k + 1));
                if (seen.add(((long) i << 32) | j)) {
                    pairs.add(new int[] {i, j});
                }
            }
        }
        return pairs;
    }

    private double[] evaluateFixedOpponentsWithRecovery(List<AI> bots) throws Exception {
        List<AI> opponents = loadFixedOpponentsIfNeeded();
        List<AI> snapshot = new ArrayList<>(opponents);

        if (snapshot.isEmpty()) {
            double[] undefined = new double[bots.size()];
            java.util.Arrays.fill(undefined, Double.NaN);
            return undefined;
        }

        // One coordinator thread per opponent: each only submits that
        // opponent's per-individual game tasks to the shared game pool and
        // waits for them, so these threads are idle and needn't be capped
        // (and must not be pool threads themselves, or waiting on the pool
        // could deadlock it).
        ExecutorService executor = Executors.newFixedThreadPool(snapshot.size(), r -> {
            Thread t = new Thread(r, "gp-opponent-eval");
            t.setDaemon(true);
            return t;
        });

        List<Future<OpponentEvaluation>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < snapshot.size(); i++) {
                final int oppIndex = i;  // Capture for lambda
                AI opponent = snapshot.get(i);
                // Unique scratch folder per concurrent evaluation: every one
                // of these calls always has exactly one opponent, at local
                // index 0, so without this, concurrently-running evaluations
                // for different opponents would all read/write the same
                // "opponentAI0readWriteFolder" path at the same time.
                String readWriteFolder = GPTournamentEvaluator.FIXED_OPPONENTS_READ_WRITE_FOLDER_BASE + "/opp" + oppIndex;
                futures.add(executor.submit(() -> {
                    try {
                        double[] result = GPTournamentEvaluator.evaluateFixedOpponentWinRatesParallel(
                                bots, opponent, trainingMaps(), utt, 1, 2000, opponentTimeBudget, 100, readWriteFolder,
                                traceFolder("gp_traces/fixed_opponents/opp" + oppIndex), gamePool());
                        return new OpponentEvaluation(opponent, result, null);
                    } catch (Exception e) {
                        return new OpponentEvaluation(opponent, null, e);
                    }
                }));
            }

            // Tracked per individual, not just per opponent: an opponent's
            // whole evaluation can succeed (no exception) while still
            // containing a NaN for one specific individual - e.g. that
            // individual hit an undefined/0-games pairing against just that
            // one opponent. Summing blindly would let that single NaN poison
            // this individual's total across every opponent, including ones
            // it has perfectly good results against, so each individual's
            // average is taken only over the opponents that actually gave it
            // a finite result.
            double[] sum = new double[bots.size()];
            int[] finiteCountPerIndividual = new int[bots.size()];
            int succeededOpponents = 0;
            List<AI> crashed = new ArrayList<>();

            for (Future<OpponentEvaluation> future : futures) {
                OpponentEvaluation eval = future.get();
                if (eval.error != null) {
                    System.out.println("[GP] Fixed opponent \"" + eval.opponent + "\" crashed mid-tournament - "
                            + "permanently removing it from the opponent roster: " + eval.error);
                    crashed.add(eval.opponent);
                    continue;
                }
                succeededOpponents++;
                for (int i = 0; i < sum.length; i++) {
                    if (Double.isFinite(eval.winRates[i])) {
                        sum[i] += eval.winRates[i];
                        finiteCountPerIndividual[i]++;
                    }
                }
            }

            opponents.removeAll(crashed);

            if (succeededOpponents == 0) {
                double[] undefined = new double[bots.size()];
                java.util.Arrays.fill(undefined, Double.NaN);
                return undefined;
            }

            double[] averaged = new double[sum.length];
            for (int i = 0; i < sum.length; i++) {
                // NaN here means this individual had no finite result
                // against any opponent that completed - a real "no usable
                // data for this one individual" case, not a bug to hide.
                averaged[i] = finiteCountPerIndividual[i] == 0 ? Double.NaN : sum[i] / finiteCountPerIndividual[i];
            }
            return averaged;
        } finally {
            executor.shutdown();
        }
    }

    public double startTournament(Tree individual) {
        return startTournament(individual, tournamentMode);
    }

    public double startTournament(Tree individual, TournamentMode mode) {
        if (population == null || population.length == 0) {
            return Double.NaN;
        }

        try {
            List<AI> bots = new ArrayList<>();
            for (Tree tree : population) {
                bots.add(new GPEvolvedBot(tree, utt, actionSelection, temperature, policySeeds.nextLong()));
            }

            double[] evaluatedWinRates;
            switch (mode) {
                case TRANSFER:
                    throw new IllegalArgumentException("TRANSFER is a two-phase run, not a single-generation evaluation mode.");
                case SELF: {
                    evaluatedWinRates = evaluateSelfPlay(bots);
                    break;
                }
                case OPPONENTS: {
                    List<AI> opponents = loadFixedOpponentsIfNeeded();
                    if (opponents.isEmpty()) {
                        throw new IllegalStateException("tournamentMode is OPPONENTS but no opponents were loaded from \""
                                + opponentsFolder + "\" - add bot JARs to that folder, or switch tournamentMode to SELF/BOTH.");
                    }
                    evaluatedWinRates = evaluateFixedOpponentsWithRecovery(bots);
                    if (opponents.isEmpty()) {
                        throw new IllegalStateException("tournamentMode is OPPONENTS but every fixed opponent has now "
                                + "crashed and been removed - add different/working bot JARs to \"" + opponentsFolder
                                + "\", or switch tournamentMode to SELF/BOTH.");
                    }
                    break;
                }
                case BOTH:
                default: {
                    List<AI> opponents = loadFixedOpponentsIfNeeded();
                    if (opponents.isEmpty()) {
                        evaluatedWinRates = evaluateSelfPlay(bots);
                    } else {
                        // Self-play and the fixed-opponent batch don't depend
                        // on each other's results, so run them concurrently
                        // rather than one after the other.
                        ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
                            Thread t = new Thread(r, "gp-selfplay-vs-fixed");
                            t.setDaemon(true);
                            return t;
                        });
                        try {
                            Future<double[]> selfPlayFuture = executor.submit(() ->
                                    evaluateSelfPlay(bots));
                            Future<double[]> fixedOpponentFuture = executor.submit(() ->
                                    evaluateFixedOpponentsWithRecovery(bots));

                            double[] selfPlayWinRates = selfPlayFuture.get();
                            double[] fixedOpponentWinRates = fixedOpponentFuture.get();
                            evaluatedWinRates = blendWinRates(selfPlayWinRates, fixedOpponentWinRates, fixedOpponentWeight);
                        } finally {
                            executor.shutdown();
                        }
                    }
                    break;
                }
            }

            winRates = evaluatedWinRates;
            for (int i = 0; i < population.length && i < evaluatedWinRates.length; i++) {
                population[i].winRate = evaluatedWinRates[i];
            }

            double highestWinrate = getHighestWinrate(evaluatedWinRates);
            double averageWinrate = getAverage(evaluatedWinRates);
            double stdWinrate = getStandardDeviation(evaluatedWinRates);
            appendHighestWinrateCsv(highestWinrate);
            appendAverageWinrateCsv(averageWinrate);
            appendStdWinrateCsv(stdWinrate);
            printWinrateStats(highestWinrate, averageWinrate, stdWinrate);

            if (individual == null) {
                return Double.NaN;
            }
            for (int i = 0; i < population.length; i++) {
                if (population[i] == individual) {
                    individual.winRate = evaluatedWinRates[i];
                    return evaluatedWinRates[i];
                }
            }
            return Double.NaN;
        } catch (Exception e) {
            throw new RuntimeException("Tournament evaluation failed", e);
        }
    }

    private void printWinrateStats(double highestWinrate, double averageWinrate, double stdWinrate) {
        System.out.printf(Locale.ROOT, "Winrate stats - highest: %.4f, average: %.4f, std: %.4f%n",
                highestWinrate, averageWinrate, stdWinrate);
    }
}
