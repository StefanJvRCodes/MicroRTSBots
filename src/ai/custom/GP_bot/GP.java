package ai.custom.GP_bot;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.Vector;
import rts.GameState;
import rts.PlayerAction;
import rts.UnitAction;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;




public class GP {
    /** Controls which matches contribute to fitness each generation. */
    public enum TournamentMode {
        /** Population plays only itself, round-robin (the original behavior). */
        SELF,
        /** Population plays only the fixed roster loaded from {@code opponentsFolder}. */
        OPPONENTS,
        /** Population plays both; scores are blended by {@code fixedOpponentWeight}. */
        BOTH
    }

    private static final List<String> DEFAULT_TOURNAMENT_MAPS = java.util.Arrays.asList(
        "maps/basesWorkers32x32A.xml",
        "maps/NoWhereToRun9x8.xml"
    );
    public static final String HIGHEST_WINRATE_CSV_FILE = "highest_winrate_per_generation.csv";
    public static final String AVERAGE_WINRATE_CSV_FILE = "average_winrate_per_generation.csv";
    public static final String STD_WINRATE_CSV_FILE = "std_winrate_per_generation.csv";

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
    // Node is the single source of truth for the default function set and
    // default feature indices, so both Tree and GP build against the same
    // values instead of maintaining their own copies.
    public String[] functions = Node.DEFAULT_FUNCTIONS;
    public String[] terminals = new String[0];
    public int[] featureIndices = Node.DEFAULT_FEATURE_INDICES;
    public UnitTypeTable utt = new UnitTypeTable();

    // Fixed opponents (e.g. past microRTS competition winners) loaded from
    // JARs in this folder, in addition to self-play. Both optional: if the
    // folder doesn't exist or has no usable bots in it, GP just falls back
    // to self-play only - see GPOpponentLoader for how bots are discovered.
    public String opponentsFolder = "opponents";
    // How much weight fixed-opponent win rate gets versus self-play win
    // rate when both are available: 0.0 = self-play only, 1.0 = fixed
    // opponents only, 0.5 = equal blend. Only consulted when tournamentMode
    // is BOTH.
    public double fixedOpponentWeight = 0.5;
    // Which matches count toward fitness. Defaults to BOTH, matching the
    // original self-play-plus-opponents-if-present behavior.
    public TournamentMode tournamentMode = TournamentMode.BOTH;
    private List<AI> fixedOpponents;
    private boolean fixedOpponentsLoaded = false;


    //outputs
    public Tree[] population;
    public double[] winRates;
    public Vector<Double>[] actionDistributions;

    /**
     * A Tree configured with this GP run's parameters but with no root of
     * its own. Mutation delegates subtree generation to it (recursiveGrow /
     * configureModiRecursive) instead of GP reimplementing tree-building
     * logic that Tree already owns.
     */
    private Tree treeFactory;

    private Tree treeFactory() {
        if (treeFactory == null) {
            treeFactory = new Tree(random, modiRate, functionProbability, terminalProbability,
                    maxDepth, fullProbability, growProbability, functions, terminals, featureIndices, numOutputCells);
        }
        return treeFactory;
    }




    GP(){
        resetHighestWinrateCsv();
        resetAverageWinrateCsv();
        resetStdWinrateCsv();
        
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
                        case "opponents folder":
                            opponentsFolder = value;
                            break;
                        case "fixed opponent weight":
                            fixedOpponentWeight = Double.parseDouble(value);
                            break;
                        case "tournament mode":
                            switch (value.toLowerCase()) {
                                case "self":
                                    tournamentMode = TournamentMode.SELF;
                                    break;
                                case "opponents":
                                    tournamentMode = TournamentMode.OPPONENTS;
                                    break;
                                case "both":
                                    tournamentMode = TournamentMode.BOTH;
                                    break;
                                default:
                                    throw new IllegalArgumentException(
                                            "Unknown tournament mode \"" + value + "\" - expected self, opponents, or both.");
                            }
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
            if (growProbability + fullProbability != 100) {
                throw new IllegalArgumentException("Grow and full probabilities must sum to 100.");
            }
            if (seed == -1 || populationSize == -1 || maxDepth == -1 || functionProbability == -1 || terminalProbability == -1 || tournamentSize == -1 || mutationRate == -1 || crossoverRate == -1 || reproductionRate == -1 || generations == -1) {
                throw new IllegalArgumentException("One or more parameters are missing in the parameters file.");
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

        initializePopulation();

        for (int currGeneration = 0; currGeneration < generations; currGeneration++) {
            System.out.printf("Generation %d/%d%n", currGeneration + 1, generations);
            startTournament(population[0]);
            if (currGeneration < generations - 1) {
                generateNewPopulation();
            }
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
            population[filled++] = tree;
        }
        for (int i = 0; i < growCount && filled < populationSize; i++) {
            Tree tree = newConfiguredTree();
            tree.grow();
            population[filled++] = tree;
        }

        while (filled < populationSize) {
            Tree tree = newConfiguredTree();
            if (random.nextBoolean()) {
                tree.full();
            } else {
                tree.grow();
            }
            population[filled++] = tree;
        }
    }

    /** Builds a fresh Tree configured with this run's parameters (no root yet). */
    private Tree newConfiguredTree() {
        return new Tree(random, modiRate, functionProbability, terminalProbability,
                maxDepth, fullProbability, growProbability, functions, terminals, featureIndices, numOutputCells);
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
                if (donorCopy.isModi){
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
            // Generate a completely new tree, delegating to Tree's own
            // subtree-building logic instead of reimplementing it here.
            Node replacementRoot = treeFactory().recursiveGrow(maxDepth);
            treeFactory().configureModiRecursive(replacementRoot);
            return new Tree(replacementRoot, numOutputCells);
        }

        // Replace non-root node with a randomly generated subtree.
        Node replacementNode = treeFactory().recursiveGrow(remainingDepth);
        treeFactory().configureModiRecursive(replacementNode);
        Node parentOfTarget = child.findParentOf(target);

        if (parentOfTarget != null) {
            parentOfTarget.replaceChild(target, replacementNode);
        }

        return new Tree(child, numOutputCells);
    }


    /*evaluation
        //start tournament for first individual
        //for every player action, set feature vector
        //get action probability distribution and pick best action
        //after tournament, store win rate


        set feature vector
        //public double evaluate(double[] features, OutputVector output)

        features[0] = Math.min(enemyCount, 10) / 10.0; // Cap at 10 enemies
        features[1] = encodeEnemyType(nearestEnemyType); // 0.0-1.0
        features[2] = Math.min(allyCount, 5) / 5.0; // Cap at 5 allies
        features[3] = encodeUnitType(myType); // 0.0-1.0
        features[4] = Math.min(resources, 200) / 200.0; // Cap resources
        features[5] = myHealth / maxHealth;  // Should I fight or flee?
        features[6] = myDamage / maxEnemyHealth;  // Can I kill them quickly?
        features[7] = alliesNearResource / enemiesNearResource;
    
    */

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

    public void resetHighestWinrateCsv() {
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(HIGHEST_WINRATE_CSV_FILE, false))) {
            writer.print("");
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to initialize " + HIGHEST_WINRATE_CSV_FILE, e);
        }
    }

    public void resetAverageWinrateCsv() {
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(AVERAGE_WINRATE_CSV_FILE, false))) {
            writer.print("");
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to initialize " + AVERAGE_WINRATE_CSV_FILE, e);
        }
    }

    public void resetStdWinrateCsv() {
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(STD_WINRATE_CSV_FILE, false))) {
            writer.print("");
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to initialize " + STD_WINRATE_CSV_FILE, e);
        }
    }

    public void appendHighestWinrateCsv(double highestWinrate) {
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(HIGHEST_WINRATE_CSV_FILE, true))) {
            writer.printf(Locale.ROOT, "%.8f%n", highestWinrate);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to append to " + HIGHEST_WINRATE_CSV_FILE, e);
        }
    }

    public void appendAverageWinrateCsv(double averageWinrate) {
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(AVERAGE_WINRATE_CSV_FILE, true))) {
            writer.printf(Locale.ROOT, "%.8f%n", averageWinrate);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to append to " + AVERAGE_WINRATE_CSV_FILE, e);
        }
    }

    public void appendStdWinrateCsv(double stdWinrate) {
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(STD_WINRATE_CSV_FILE, true))) {
            writer.printf(Locale.ROOT, "%.8f%n", stdWinrate);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to append to " + STD_WINRATE_CSV_FILE, e);
        }
    }

    public double getAverage(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < values.length; i++) {
            total += values[i];
        }
        return total / values.length;
    }

    public double getStandardDeviation(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double average = getAverage(values);
        double sumSquaredDifferences = 0.0;
        for (int i = 0; i < values.length; i++) {
            sumSquaredDifferences += Math.pow(values[i] - average, 2);
        }
        return Math.sqrt(sumSquaredDifferences / values.length);
    }

    public double getHighestWinrate(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double highest = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i] > highest) {
                highest = values[i];
            }
        }
        return highest;
    }




    /**
     * Loads the fixed-opponent roster from {@link #opponentsFolder} the
     * first time it's needed and caches it for the rest of the run (the
     * folder is scanned and every bot instantiated once, not every
     * generation). Returns an empty list - never null - if the folder is
     * missing or empty, so callers don't need a null check.
     */
    private List<AI> loadFixedOpponentsIfNeeded() {
        if (!fixedOpponentsLoaded) {
            fixedOpponents = GPOpponentLoader.loadOpponentsFromFolder(opponentsFolder, utt, DEFAULT_TOURNAMENT_MAPS);
            fixedOpponentsLoaded = true;
        }
        return fixedOpponents;
    }

    /**
     * Combines self-play and fixed-opponent win rates per individual. If
     * either side is NaN for a given individual (no games of that kind were
     * played), falls back entirely to the other side rather than producing
     * a NaN that would make that individual look artificially bad in
     * tournament selection.
     */
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
     * Plays the population against each currently-loaded fixed opponent one
     * at a time - rather than one combined round robin - so a bot that
     * passes {@link GPOpponentLoader}'s load-time smoke test but still
     * crashes mid-game (e.g. only later in a real game than the smoke test
     * reaches, or only on a specific map) can be isolated to exactly the
     * opponent responsible instead of taking down the whole generation.
     * That opponent is then permanently removed from {@link #fixedOpponents}
     * - this generation and every one after it - rather than letting the
     * crash propagate and kill the GP run. Win rates from opponents that did
     * complete successfully are averaged together.
     *
     * <p>Returns an all-NaN array (same convention as
     * {@link GPTournamentEvaluator#evaluateFixedOpponentsWinRates} with an
     * empty opponents list) if every opponent crashed this generation, or
     * none were loaded to begin with - callers already handle that the same
     * way they'd handle "no opponents configured".
     */
    private double[] evaluateFixedOpponentsWithRecovery(List<AI> bots) {
        List<AI> opponents = loadFixedOpponentsIfNeeded();

        double[] sum = null;
        int succeeded = 0;
        List<AI> crashed = new ArrayList<>();

        for (AI opponent : new ArrayList<>(opponents)) {
            double[] result;
            try {
                result = GPTournamentEvaluator.evaluateFixedOpponentsWinRates(
                        bots, java.util.Collections.singletonList(opponent),
                        DEFAULT_TOURNAMENT_MAPS, utt, 1, 2000, 100, 100);
            } catch (Exception e) {
                System.out.println("[GP] Fixed opponent \"" + opponent + "\" crashed mid-tournament - permanently "
                        + "removing it from the opponent roster: " + e);
                crashed.add(opponent);
                continue;
            }

            if (sum == null) {
                sum = result;
            } else {
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += result[i];
                }
            }
            succeeded++;
        }

        opponents.removeAll(crashed);

        if (succeeded == 0) {
            double[] undefined = new double[bots.size()];
            java.util.Arrays.fill(undefined, Double.NaN);
            return undefined;
        }

        double[] averaged = new double[sum.length];
        for (int i = 0; i < sum.length; i++) {
            averaged[i] = sum[i] / succeeded;
        }
        return averaged;
    }

    public double startTournament(Tree individual) {
        if (population == null || population.length == 0) {
            return Double.NaN;
        }

        try {
            List<AI> bots = new ArrayList<>();
            for (Tree tree : population) {
                bots.add(new TreeBotAI(tree, utt, numOutputCells));
            }

            double[] evaluatedWinRates;
            switch (tournamentMode) {
                case SELF: {
                    evaluatedWinRates = GPTournamentEvaluator.evaluateRoundRobinWinRates(
                            bots, DEFAULT_TOURNAMENT_MAPS, utt, 1, 2000, 100, 100);
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
                    double[] selfPlayWinRates = GPTournamentEvaluator.evaluateRoundRobinWinRates(
                            bots, DEFAULT_TOURNAMENT_MAPS, utt, 1, 2000, 100, 100);
                    List<AI> opponents = loadFixedOpponentsIfNeeded();
                    if (opponents.isEmpty()) {
                        evaluatedWinRates = selfPlayWinRates;
                    } else {
                        double[] fixedOpponentWinRates = evaluateFixedOpponentsWithRecovery(bots);
                        evaluatedWinRates = blendWinRates(selfPlayWinRates, fixedOpponentWinRates, fixedOpponentWeight);
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

    private static double[] extractFeatures(Unit unit, GameState gs, int player) {
        Unit closestEnemy = findClosestEnemy(unit, gs);
        int enemyDistance = closestEnemy == null ? Integer.MAX_VALUE : manhattanDistance(unit, closestEnemy);
        int attackRange = Math.max(1, unit.getType().attackRange);

        int enemyCount = countUnitsNear(unit, gs, player, true, unit.getType().sightRadius);
        int allyCount = countUnitsNear(unit, gs, player, false, unit.getType().sightRadius);
        int resources = gs.getPlayer(player).getResources();

        double enemyFarAway = (closestEnemy == null || enemyDistance > attackRange) ? 1.0 : 0.0;
        double enemyInRange = (closestEnemy != null && enemyDistance <= attackRange) ? 1.0 : 0.0;
        double numEnemies = Math.min(enemyCount, 10) / 10.0;
        double enemyType = encodeUnitType(closestEnemy == null ? null : closestEnemy.getType());
        double numAlliesNear = Math.min(allyCount, 5) / 5.0;
        double myUnitType = encodeUnitType(unit.getType());
        double numResources = Math.min(resources, 200) / 200.0;

        return new double[] {
                enemyFarAway,
                enemyInRange,
                numEnemies,
                enemyType,
                numAlliesNear,
                myUnitType,
                numResources
        };
    }

    private static Unit findClosestEnemy(Unit unit, GameState gs) {
        Unit closest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit other : gs.getUnits()) {
            if (other.getPlayer() < 0 || other.getPlayer() == unit.getPlayer()) {
                continue;
            }
            int d = manhattanDistance(unit, other);
            if (d < bestDistance) {
                bestDistance = d;
                closest = other;
            }
        }
        return closest;
    }

    private static Unit findClosestResource(Unit unit, GameState gs) {
        Unit closest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit other : gs.getUnits()) {
            if (!other.getType().isResource) {
                continue;
            }
            int d = manhattanDistance(unit, other);
            if (d < bestDistance) {
                bestDistance = d;
                closest = other;
            }
        }
        return closest;
    }

    private static int countUnitsNear(Unit unit, GameState gs, int player, boolean enemies, int radius) {
        int count = 0;
        for (Unit other : gs.getUnits()) {
            if (other == unit || other.getPlayer() < 0) {
                continue;
            }
            boolean isEnemy = other.getPlayer() != player;
            if (isEnemy != enemies) {
                continue;
            }
            if (manhattanDistance(unit, other) <= radius) {
                count++;
            }
        }
        return count;
    }

    private static int manhattanDistance(Unit a, Unit b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }

    private static double encodeUnitType(UnitType type) {
        if (type == null) {
            return 0.0;
        }
        if ("Worker".equals(type.name)) return 0.0;
        if ("Light".equals(type.name)) return 0.25;
        if ("Heavy".equals(type.name)) return 0.50;
        if ("Ranged".equals(type.name)) return 0.75;
        if ("Base".equals(type.name)) return 0.85;
        if ("Barracks".equals(type.name)) return 0.95;
        if ("Resource".equals(type.name)) return 1.0;
        return 0.5;
    }

    private static final class TreeBotAI extends AIWithComputationBudget implements Cloneable {
        private final Tree tree;
        private final UnitTypeTable utt;
        private final int numOutputCells;

        private TreeBotAI(Tree tree, UnitTypeTable utt, int numOutputCells) {
            super(-1, -1);
            this.tree = tree;
            this.utt = utt;
            this.numOutputCells = numOutputCells;
        }

        @Override
        public void reset() {
        }

        @Override
        public AI clone() {
            Tree clonedTree = (tree == null || tree.root == null) ? null : new Tree(tree.root.deepCopy(), numOutputCells);
            return new TreeBotAI(clonedTree, utt, numOutputCells);
        }

        @Override
        public PlayerAction getAction(int player, GameState gs) {
            PlayerAction pa = new PlayerAction();
            int remainingResources = gs.getPlayer(player).getResources() - reservedForInProgressProduce(gs, player);
            // Cells already claimed by ANY unit's (any player's) in-progress
            // MOVE or PRODUCE action, plus cells claimed by actions we choose
            // for our own units earlier in this same call. See
            // collectReservedPositions() for why this can't just be read off
            // gs.getUnitAt(...).
            Set<Long> reservedPositions = collectReservedPositions(gs);
            for (Unit unit : gs.getUnits()) {
                if (unit.getPlayer() != player) {
                    continue;
                }
                if (gs.getUnitAction(unit) != null) {
                    continue;
                }
                UnitAction chosen = chooseAction(unit, gs, player, remainingResources, reservedPositions);
                if (chosen != null) {
                    pa.addUnitAction(unit, chosen);
                    if (chosen.getType() == UnitAction.TYPE_PRODUCE && chosen.getUnitType() != null) {
                        remainingResources -= Math.max(0, chosen.getUnitType().cost);
                    }
                    if (chosen.getType() == UnitAction.TYPE_MOVE || chosen.getType() == UnitAction.TYPE_PRODUCE) {
                        reservedPositions.add(destinationKey(unit, chosen));
                    }
                }
            }
            pa.fillWithNones(gs, player, 10);
            return pa;
        }

        /**
         * Resources already committed to this player's units that are mid-way
         * through a PRODUCE action issued on a previous decision cycle. microRTS
         * only deducts a produce action's cost (and spawns the unit) when the
         * action completes, not when it's issued, so
         * gs.getPlayer(player).getResources() still counts those resources as
         * available while the build is in progress.
         */
        private int reservedForInProgressProduce(GameState gs, int player) {
            int reserved = 0;
            for (Unit u : gs.getUnits()) {
                if (u.getPlayer() != player) {
                    continue;
                }
                UnitAction inProgress = gs.getUnitAction(u);
                if (inProgress != null && inProgress.getType() == UnitAction.TYPE_PRODUCE && inProgress.getUnitType() != null) {
                    reserved += inProgress.getUnitType().cost;
                }
            }
            return reserved;
        }

        /**
         * Cells claimed by any unit's in-progress MOVE or PRODUCE action,
         * across all players. A PRODUCE action's target cell isn't reflected
         * in gs.getUnitAt(...) until the action completes and the new unit is
         * actually placed there, so unit.getUnitActions(gs) alone doesn't
         * reliably filter these out - a different unit can still be offered a
         * MOVE (or another PRODUCE) into that same cell as a "legal" action.
         * GameState.issueSafe enforces the collision globally when the action
         * is actually issued, throwing "Inconsistent actions were executed!"
         * if we don't avoid it ourselves first.
         */
        private Set<Long> collectReservedPositions(GameState gs) {
            Set<Long> reserved = new HashSet<>();
            for (Unit u : gs.getUnits()) {
                UnitAction inProgress = gs.getUnitAction(u);
                if (inProgress == null) {
                    continue;
                }
                int type = inProgress.getType();
                if (type == UnitAction.TYPE_MOVE || type == UnitAction.TYPE_PRODUCE) {
                    reserved.add(destinationKey(u, inProgress));
                }
            }
            return reserved;
        }

        private UnitAction chooseAction(Unit unit, GameState gs, int player, int remainingResources,
                                        Set<Long> reservedPositions) {
            List<UnitAction> legalActions = unit.getUnitActions(gs);
            if (legalActions.isEmpty() || tree == null || tree.root == null) {
                return chooseSafeFallbackAction(unit, legalActions, remainingResources, reservedPositions);
            }

            tree.evaluate(extractFeatures(unit, gs, player));
            int bestIndex = 0;
            for (int i = 1; i < tree.outputVector.length; i++) {
                if (tree.outputVector[i] > tree.outputVector[bestIndex]) {
                    bestIndex = i;
                }
            }

            UnitAction action = mapOutputToAction(bestIndex, unit, gs, legalActions, remainingResources, reservedPositions);
            return action != null ? action : chooseSafeFallbackAction(unit, legalActions, remainingResources, reservedPositions);
        }

        private UnitAction mapOutputToAction(int outputIndex, Unit unit, GameState gs, List<UnitAction> legalActions,
                                             int remainingResources, Set<Long> reservedPositions) {
            switch (outputIndex) {
                case 0:
                    return chooseMoveTowardTarget(unit, legalActions, findClosestEnemy(unit, gs), reservedPositions);
                case 1:
                    return chooseAttackAction(unit, legalActions, findClosestEnemy(unit, gs));
                case 2:
                    return chooseMoveTowardTarget(unit, legalActions, findClosestResource(unit, gs), reservedPositions);
                case 3:
                    return chooseByType(legalActions, UnitAction.TYPE_HARVEST);
                case 4:
                    return chooseByType(legalActions, UnitAction.TYPE_RETURN);
                case 5:
                    return chooseProduceAction(unit, gs, legalActions, true, remainingResources, reservedPositions);
                case 6:
                    return chooseProduceAction(unit, gs, legalActions, false, remainingResources, reservedPositions);
                default:
                    return null;
            }
        }

        private UnitAction chooseSafeFallbackAction(Unit unit, List<UnitAction> legalActions, int remainingResources,
                                                    Set<Long> reservedPositions) {
            for (UnitAction action : legalActions) {
                if (action.getType() != UnitAction.TYPE_PRODUCE
                        && (action.getType() != UnitAction.TYPE_MOVE
                            || !reservedPositions.contains(destinationKey(unit, action)))) {
                    return action;
                }
            }

            for (UnitAction action : legalActions) {
                if (action.getType() == UnitAction.TYPE_PRODUCE
                        && action.getUnitType() != null
                        && action.getUnitType().cost <= remainingResources
                        && !reservedPositions.contains(destinationKey(unit, action))) {
                    return action;
                }
            }

            for (UnitAction action : legalActions) {
                if (action.getType() == UnitAction.TYPE_MOVE
                        && !reservedPositions.contains(destinationKey(unit, action))) {
                    return action;
                }
            }

            return null;
        }

        private UnitAction chooseByType(List<UnitAction> legalActions, int type) {
            for (UnitAction action : legalActions) {
                if (action.getType() == type) {
                    return action;
                }
            }
            return null;
        }

        private UnitAction chooseAttackAction(Unit unit, List<UnitAction> legalActions, Unit closestEnemy) {
            if (closestEnemy != null) {
                for (UnitAction action : legalActions) {
                    if (action.getType() == UnitAction.TYPE_ATTACK_LOCATION
                            && action.getLocationX() == closestEnemy.getX()
                            && action.getLocationY() == closestEnemy.getY()) {
                        return action;
                    }
                }
            }
            return chooseByType(legalActions, UnitAction.TYPE_ATTACK_LOCATION);
        }

        private UnitAction chooseMoveTowardTarget(Unit unit, List<UnitAction> legalActions, Unit target,
                                                  Set<Long> reservedPositions) {
            if (target == null) {
                return chooseByType(legalActions, UnitAction.TYPE_MOVE, unit, reservedPositions);
            }

            UnitAction bestMove = null;
            int bestDistance = Integer.MAX_VALUE;
            for (UnitAction action : legalActions) {
                if (action.getType() != UnitAction.TYPE_MOVE) {
                    continue;
                }
                if (reservedPositions.contains(destinationKey(unit, action))) {
                    continue;
                }
                int nextX = unit.getX() + UnitAction.DIRECTION_OFFSET_X[action.getDirection()];
                int nextY = unit.getY() + UnitAction.DIRECTION_OFFSET_Y[action.getDirection()];
                int distance = Math.abs(nextX - target.getX()) + Math.abs(nextY - target.getY());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestMove = action;
                }
            }
            return bestMove;
        }

        private UnitAction chooseByType(List<UnitAction> legalActions, int type, Unit unit,
                                        Set<Long> reservedPositions) {
            for (UnitAction action : legalActions) {
                if (action.getType() != type) {
                    continue;
                }
                if (type == UnitAction.TYPE_MOVE && reservedPositions.contains(destinationKey(unit, action))) {
                    continue;
                }
                return action;
            }
            return null;
        }

        private UnitAction chooseProduceAction(Unit unit, GameState gs, List<UnitAction> legalActions,
                                               boolean wantWorker, int remainingResources, Set<Long> reservedPositions) {
            Unit target = wantWorker ? findClosestResource(unit, gs) : findClosestEnemy(unit, gs);
            UnitAction bestAction = null;
            int bestDistance = Integer.MAX_VALUE;

            for (UnitAction action : legalActions) {
                if (action.getType() != UnitAction.TYPE_PRODUCE) {
                    continue;
                }
                UnitType producedType = action.getUnitType();
                if (!matchesProduceGoal(producedType, wantWorker)) {
                    continue;
                }
                if (producedType.cost > remainingResources) {
                    continue;
                }
                if (reservedPositions.contains(destinationKey(unit, action))) {
                    continue;
                }
                if (target == null) {
                    return action;
                }
                int nextX = unit.getX() + UnitAction.DIRECTION_OFFSET_X[action.getDirection()];
                int nextY = unit.getY() + UnitAction.DIRECTION_OFFSET_Y[action.getDirection()];
                int distance = Math.abs(nextX - target.getX()) + Math.abs(nextY - target.getY());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestAction = action;
                }
            }

            if (bestAction != null) {
                return bestAction;
            }

            for (UnitAction action : legalActions) {
                if (action.getType() == UnitAction.TYPE_PRODUCE
                        && action.getUnitType() != null
                        && matchesProduceGoal(action.getUnitType(), wantWorker)
                        && action.getUnitType().cost <= remainingResources
                        && !reservedPositions.contains(destinationKey(unit, action))) {
                    return action;
                }
            }
            return null;
        }

        /** Unique key for the cell a MOVE or PRODUCE action targets, relative to the acting unit. */
        private long destinationKey(Unit unit, UnitAction action) {
            int nextX = unit.getX() + UnitAction.DIRECTION_OFFSET_X[action.getDirection()];
            int nextY = unit.getY() + UnitAction.DIRECTION_OFFSET_Y[action.getDirection()];
            return (((long) nextX) << 32) ^ (nextY & 0xffffffffL);
        }

        private boolean matchesProduceGoal(UnitType producedType, boolean wantWorker) {
            if (producedType == null) {
                return false;
            }
            if (wantWorker) {
                return "Worker".equals(producedType.name);
            }
            return !"Worker".equals(producedType.name) && !producedType.isResource && !producedType.isStockpile;
        }

        @Override
        public List<ParameterSpecification> getParameters() {
            return new ArrayList<>();
        }

        @Override
        public String toString() {
            return "TreeBotAI{" + tree + "}";
        }
    }
}