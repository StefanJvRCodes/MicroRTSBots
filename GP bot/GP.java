import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import ai.custom.GPTournamentEvaluator;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Vector;
import rts.GameState;
import rts.PlayerAction;
import rts.UnitAction;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;



public class GP {
    private static final String[] DEFAULT_FUNCTIONS = {"if>0", "+", "-", "*", "/"};
    private static final int[] DEFAULT_FEATURE_INDICES = {
        Node.FEATURE_ENEMY_FAR_AWAY,
        Node.FEATURE_ENEMY_IN_RANGE,
        Node.FEATURE_NUM_ENEMIES,
        Node.FEATURE_ENEMY_TYPE,
        Node.FEATURE_NUM_ALLIES_NEAR,
        Node.FEATURE_MY_UNIT_TYPE,
        Node.FEATURE_NUM_RESOURCES
    };
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
    public String[] functions = DEFAULT_FUNCTIONS;
    public String[] terminals = new String[0];
    public int[] featureIndices = DEFAULT_FEATURE_INDICES;
    public UnitTypeTable utt = new UnitTypeTable();


    //outputs
    public Tree[] population;
    public double[] winRates;
    public Vector<Double>[] actionDistributions;




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
            Tree tree = new Tree(random, functions, terminals, featureIndices, modiRate, numOutputCells,
                    functionProbability, terminalProbability, maxDepth, fullProbability, growProbability);
            tree.full();
            population[filled++] = tree;
        }
        for (int i = 0; i < growCount && filled < populationSize; i++) {
            Tree tree = new Tree(random, functions, terminals, featureIndices, modiRate, numOutputCells,
                    functionProbability, terminalProbability, maxDepth, fullProbability, growProbability);
            tree.grow();
            population[filled++] = tree;
        }

        while (filled < populationSize) {
            Tree tree = new Tree(random, functions, terminals, featureIndices, modiRate, numOutputCells,
                    functionProbability, terminalProbability, maxDepth, fullProbability, growProbability);
            if (random.nextBoolean()) {
                tree.full();
            } else {
                tree.grow();
            }
            population[filled++] = tree;
        }
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
            Tree newTree = new Tree();
            newTree.root = parent.root.deepCopy();
            newPopulation[filled++] = newTree;
        }

        while (filled < populationSize) {
            Tree parent = population[selectTournamentWinnerIndex()];
            Tree newTree = new Tree();
            newTree.root = parent.root.deepCopy();
            newPopulation[filled++] = newTree;
        }

        population = newPopulation;
    }



    public Tree crossover(Tree parent1, Tree parent2) {
    
        if (parent1 == null || parent2 == null) {
            throw new IllegalArgumentException("Parents cannot be null");
        }

        Node child = parent1.root.deepCopy();
        if (child == null || parent2.root == null) {
            return new Tree(child);
        }

    
        Node targetInChild = child.getRandomNode(random);
        boolean isRoot = (targetInChild == child);

        if (!isRoot) {
            Node donor = parent2.root.getRandomNode(random);
            Node donorCopy = donor.deepCopy();
            Node parentOfTarget = child.findParentOf(targetInChild);
            parentOfTarget.replaceChild(targetInChild, donorCopy);
            return new Tree(child);
        }

        for (int attempt = 0; attempt < 10; attempt++) {
            Node donor = parent2.root.getRandomNode(random);
            if (donor.type == Node.NodeType.FUNCTION) {
                Node donorCopy = donor.deepCopy();
                if (donorCopy.isModi){
                    return new Tree(donorCopy);
                }
                donorCopy.isModi = true;
                if (donorCopy.outputCellIndex < 0) {
                    donorCopy.outputCellIndex = random.nextInt(numOutputCells);
                }
                return new Tree(donorCopy);
            }
        }
        return new Tree(child);
    }


    //TODO: double check mutation
    public Tree mutate(Tree parent) {
        if (parent == null) {
            throw new IllegalArgumentException("Parent cannot be null");
        }

        Node child = parent.root.deepCopy();
        Node target = child.getRandomNode(random);
        boolean isRoot = (target == child);

        int remainingDepth = Math.max(1, maxDepth - target.getDepth() + 1);

        if (isRoot) {
            // Generate a completely new tree
            Node replacementRoot = generateSubtree(maxDepth);
            return new Tree(replacementRoot);
        }

        // Replace non-root node with a randomly generated subtree
        Node replacementNode = generateSubtree(remainingDepth);
        Node parentOfTarget = child.findParentOf(target);
        
        if (parentOfTarget != null) {
            parentOfTarget.replaceChild(target, replacementNode);
        }
        
        return new Tree(child);
    }

    /**
     * Generates a random subtree with the specified maximum depth.
     * The tree can be more shallow depending on the functionProbability.
     * Arity is automatically determined by Node.FUNCTION_ARITY.
     */
    private Node generateSubtree(int maxDepth) {
        if (maxDepth <= 0) {
            return createTerminalNode();
        }
        
        boolean chooseFunction = random.nextInt(100) < functionProbability;
        
        if (!chooseFunction) {
            return createTerminalNode();
        }
        
        // Create a function node - constructor handles arity based on chosen function
        String[] availableFunctions = {"if>0", "+", "-", "*", "/"};
        Node node = new Node(random, availableFunctions, new String[]{}, new int[]{0,1,2,3,4,5,6,7}, Node.NodeType.FUNCTION, modiRate, numOutputCells);
        
        // Recursively generate children to fill the children array
        // The constructor has already set node.children array with correct arity
        for (int i = 0; i < node.children.length; i++) {
            node.setChild(i, generateSubtree(maxDepth - 1));
        }
        
        return node;
    }
    
    /**
     * Creates a terminal node (leaf node with constant or feature).
     */
    private Node createTerminalNode() {
        return new Node(random, new String[]{}, new String[]{}, new int[]{0,1,2,3,4,5,6,7}, Node.NodeType.TERMINAL, modiRate, numOutputCells);
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
            writer.printf("%.8f%n", highestWinrate);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to append to " + HIGHEST_WINRATE_CSV_FILE, e);
        }
    }

    public void appendAverageWinrateCsv(double averageWinrate) {
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(AVERAGE_WINRATE_CSV_FILE, true))) {
            writer.printf("%.8f%n", averageWinrate);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to append to " + AVERAGE_WINRATE_CSV_FILE, e);
        }
    }

    public void appendStdWinrateCsv(double stdWinrate) {
        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(STD_WINRATE_CSV_FILE, true))) {
            writer.printf("%.8f%n", stdWinrate);
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




    public double startTournament(Tree individual) {
        if (population == null || population.length == 0) {
            return Double.NaN;
        }

        try {
            List<AI> bots = new ArrayList<>();
            for (Tree tree : population) {
                bots.add(new TreeBotAI(tree, utt));
            }

            double[] evaluatedWinRates = GPTournamentEvaluator.evaluateRoundRobinWinRates(
                    bots,
                    DEFAULT_TOURNAMENT_MAPS,
                    utt,
                    1,
                    2000,
                    100,
                    100);

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

        private TreeBotAI(Tree tree, UnitTypeTable utt) {
            super(-1, -1);
            this.tree = tree;
            this.utt = utt;
        }

        @Override
        public void reset() {
        }

        @Override
        public AI clone() {
            return new TreeBotAI(tree == null || tree.root == null ? null : new Tree(tree.root.deepCopy()), utt);
        }

        @Override
        public PlayerAction getAction(int player, GameState gs) {
            PlayerAction pa = new PlayerAction();
            for (Unit unit : gs.getUnits()) {
                if (unit.getPlayer() != player) {
                    continue;
                }
                if (gs.getUnitAction(unit) != null) {
                    continue;
                }
                UnitAction chosen = chooseAction(unit, gs, player);
                if (chosen != null) {
                    pa.addUnitAction(unit, chosen);
                }
            }
            pa.fillWithNones(gs, player, 10);
            return pa;
        }

        private UnitAction chooseAction(Unit unit, GameState gs, int player) {
            List<UnitAction> legalActions = unit.getUnitActions(gs);
            if (legalActions.isEmpty() || tree == null || tree.root == null) {
                return legalActions.isEmpty() ? null : legalActions.get(0);
            }

            tree.evaluate(extractFeatures(unit, gs, player));
            int bestIndex = 0;
            for (int i = 1; i < tree.outputVector.length; i++) {
                if (tree.outputVector[i] > tree.outputVector[bestIndex]) {
                    bestIndex = i;
                }
            }

            UnitAction action = mapOutputToAction(bestIndex, unit, gs, legalActions);
            return action != null ? action : legalActions.get(0);
        }

        private UnitAction mapOutputToAction(int outputIndex, Unit unit, GameState gs, List<UnitAction> legalActions) {
            switch (outputIndex) {
                case 0:
                    return chooseMoveTowardTarget(unit, legalActions, findClosestEnemy(unit, gs));
                case 1:
                    return chooseAttackAction(unit, legalActions, findClosestEnemy(unit, gs));
                case 2:
                    return chooseMoveTowardTarget(unit, legalActions, findClosestResource(unit, gs));
                case 3:
                    return chooseByType(legalActions, UnitAction.TYPE_HARVEST);
                case 4:
                    return chooseByType(legalActions, UnitAction.TYPE_RETURN);
                case 5:
                    return chooseProduceAction(unit, gs, legalActions, true);
                case 6:
                    return chooseProduceAction(unit, gs, legalActions, false);
                default:
                    return null;
            }
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

        private UnitAction chooseMoveTowardTarget(Unit unit, List<UnitAction> legalActions, Unit target) {
            if (target == null) {
                return chooseByType(legalActions, UnitAction.TYPE_MOVE);
            }

            UnitAction bestMove = null;
            int bestDistance = Integer.MAX_VALUE;
            for (UnitAction action : legalActions) {
                if (action.getType() != UnitAction.TYPE_MOVE) {
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

        private UnitAction chooseProduceAction(Unit unit, GameState gs, List<UnitAction> legalActions, boolean wantWorker) {
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
                if (action.getType() == UnitAction.TYPE_PRODUCE && matchesProduceGoal(action.getUnitType(), wantWorker)) {
                    return action;
                }
            }
            return null;
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
