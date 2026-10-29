import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;
import java.util.Vector;

//Note: When user picks date to test, it cannot be lower that numPreviousDays*96 away from start of dataset
public class GP {
    public static final int DEFAULT_EVALUATION_BATCH_SIZE = 16;
    public static final double NON_FINITE_PENALTY = 1_000_000_000_000.0;
    public static final String LOWEST_MSE_CSV_FILE = "lowest_mse_per_generation.csv";
    public static final String AVERAGE_MSE_CSV_FILE = "average_mse_per_generation.csv";
    public static final String STD_MSE_CSV_FILE = "std_mse_per_generation.csv";

    public String selectionType = "";
    public int numPreviousDays = 0;
    public Random random;
    public Vector<double[]> xValues = new Vector<>();
    public Vector<Double> yValues = new Vector<>();
    public Vector<String> dates = new Vector<>();

    public Vector<TrainingCase> trainingCases = new Vector<>();
    public Vector<TrainingCase> testingCases = new Vector<>();

    public Vector<String[]> terminals = new Vector<>();
    public Vector<String> functions = new Vector<>();

    public int seed;
    public int populationSize = 0;
    public String initialPopulationGeneration = "";
    public int maxDepth = 0;
    public int functionProbability = 0;
    public int terminalProbability = 0;
    public int tournamentSize = 0;
    public int mutationRate = 0;
    public int crossoverRate = 0;
    public int reproductionRate = 0;
    public int runs = 0;
    public int evaluationBatchSize = DEFAULT_EVALUATION_BATCH_SIZE;

    public Tree[] population;
    public double[] evaluations;

    GP() {
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
                        case "mutationrate":
                            mutationRate = Integer.parseInt(value);
                            break;
                        case "crossoverrate":
                            crossoverRate = Integer.parseInt(value);
                            break;
                        case "reproductionrate":
                            reproductionRate = Integer.parseInt(value);
                            break;
                        case "populationsize":
                            populationSize = Integer.parseInt(value);
                            break;
                        case "maxdepth":
                            maxDepth = Integer.parseInt(value);
                            break;
                        case "selectiontype":
                            selectionType = value;
                            break;
                        case "seed":
                            if (value.equals("")) {
                                random = new Random();
                                break;
                            }
                            seed = Integer.parseInt(value);
                            random = new Random(seed);
                            break;
                        case "initialpopulationgeneration":
                            initialPopulationGeneration = value;
                            break;
                        case "functionprobability":
                            functionProbability = Integer.parseInt(value);
                            if (functionProbability <= 0 || functionProbability >= 100) {
                                System.out.println("Function probability must be between 1 and 99.");
                                throw new IllegalArgumentException("Function probability must be between 1 and 99.");
                            }
                            terminalProbability = 100 - functionProbability;
                            break;
                        case "tournamentsize":
                            tournamentSize = Integer.parseInt(value);
                            break;
                        case "runs":
                            runs = Integer.parseInt(value);
                            break;
                        case "evaluationbatchsize":
                            evaluationBatchSize = Integer.parseInt(value);
                            if (evaluationBatchSize <= 0) {
                                System.out.println("evaluationBatchSize must be > 0.");
                                throw new IllegalArgumentException("evaluationBatchSize must be > 0.");
                            }
                            break;
                        case "numpreviousdays":
                            numPreviousDays = Integer.parseInt(value);
                            setTerminals();
                            break;
                        default:
                            System.out.println("Unknown parameter: " + parameter);
                    }
                }
            }
            if (populationSize == 0 || maxDepth == 0 || selectionType.equals("")
                    || initialPopulationGeneration.equals("") || functionProbability == 0 || tournamentSize == 0
                    || mutationRate == 0 || crossoverRate == 0 || reproductionRate == 0 || runs == 0
                    || numPreviousDays == 0) {
                System.out.println(
                        "One or more parameters are missing or invalid. Please check the parameters.txt file.");
                throw new IllegalArgumentException(
                        "One or more parameters are missing or invalid. Please check the parameters.txt file.");
            }

            functions.add("ADD");
            functions.add("SUBTRACT");
            functions.add("MULTIPLY");
            functions.add("DIVIDE");
            // next step is to get training data into testing cases

            createXandYValues();
            int minIndex = numPreviousDays * 96;
            Vector<TrainingCase> allCases = new Vector<>();

            for (int start = minIndex; start < xValues.size(); start++) {
                allCases.add(buildLaggedTrainingCase(start));
            }

            int trainCutoff = (allCases.size() * 2) / 3;
            for (int i = 0; i < allCases.size(); i++) {
                if (i < trainCutoff) {
                    trainingCases.add(allCases.get(i));
                } else {
                    testingCases.add(allCases.get(i));
                }
            }

            if (trainingCases.isEmpty() && !testingCases.isEmpty()) {
                trainingCases.add(testingCases.remove(0));
            }
            if (testingCases.isEmpty() && !trainingCases.isEmpty()) {
                testingCases.add(trainingCases.get(trainingCases.size() - 1));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void run() {
        runTrain();
    }

    void runTrain() {
        resetLowestMseCsv();
        resetAverageMseCsv();
        resetStdMseCsv();

        if (initialPopulationGeneration.equals("grow")) {
            population = new Tree[populationSize];
            evaluations = new double[populationSize];
            for (int i = 0; i < populationSize; i++) {
                population[i] = new Tree(Tree.GenerationType.GROW, maxDepth, random, functions.toArray(new String[0]),
                        terminals.toArray(new String[0][]), functionProbability, terminalProbability);
            }
        } else if (initialPopulationGeneration.equals("full")) {
            population = new Tree[populationSize];
            evaluations = new double[populationSize];

            for (int i = 0; i < populationSize; i++) {
                population[i] = new Tree(Tree.GenerationType.FULL, maxDepth, random, functions.toArray(new String[0]),
                        terminals.toArray(new String[0][]), functionProbability, terminalProbability);
            }
        } else if (initialPopulationGeneration.equals("HalfAndHalf")) {
            population = new Tree[populationSize];
            evaluations = new double[populationSize];
            if (maxDepth < 2) {
                System.out.println("Max depth must be at least 2 for half and half generation.");
                throw new IllegalArgumentException("Max depth must be at least 2 for half and half generation.");
            }
            int depthLevels = maxDepth - 1;
            int half = populationSize / 2;

            for (int i = 0; i < half; i++) {
                int depth = 2 + (i % depthLevels);
                population[i] = new Tree(Tree.GenerationType.GROW, depth, random, functions.toArray(new String[0]),
                        terminals.toArray(new String[0][]), functionProbability, terminalProbability);
            }

            for (int i = half; i < populationSize; i++) {
                int depth = 2 + ((i - half) % depthLevels);
                population[i] = new Tree(Tree.GenerationType.FULL, depth, random, functions.toArray(new String[0]),
                        terminals.toArray(new String[0][]), functionProbability, terminalProbability);
            }
        } else {
            System.out.println("Invalid initial population generation method. Please check the parameters.txt file.");
            throw new IllegalArgumentException(
                    "Invalid initial population generation method. Please check the parameters.txt file.");
        }

        if (runs == 0) {
            int generation = 1;
            while (!terminationCriteriaMet()) {
                evaluatePopulation();
                printGenerationStats(generation, evaluations);
                generateNewPopulation();
                generation++;
            }
        } else {
            for (int i = 0; i < runs; i++) {
                evaluatePopulation();
                printGenerationStats(i + 1, evaluations);
                generateNewPopulation();
            }
        }

    }

    public void evaluatePopulation() {
        if (trainingCases.isEmpty()) {
            throw new IllegalStateException("No training cases available.");
        }
        int batchSize = Math.max(1, Math.min(evaluationBatchSize, trainingCases.size()));
        for (int i = 0; i < populationSize; i++) {
            double total = 0.0;
            for (int b = 0; b < batchSize; b++) {
                TrainingCase trainingCase = trainingCases.get(random.nextInt(trainingCases.size()));
                double rawFitness = population[i].meanSquaredError(trainingCase, terminals.toArray(new String[0][]));
                total += sanitizeFitness(rawFitness);
            }
            evaluations[i] = sanitizeFitness(total / batchSize);
        }
    }

    public double[] testEvaluations;

    public void evaluateTestPopulation() {
        if (testingCases.isEmpty()) {
            throw new IllegalStateException("No testing cases available.");
        }

        testEvaluations = new double[populationSize];
        for (int i = 0; i < populationSize; i++) {
            double total = 0.0;
            for (int j = 0; j < testingCases.size(); j++) {
                double rawFitness = population[i].meanSquaredError(testingCases.get(j),
                        terminals.toArray(new String[0][]));
                total += sanitizeFitness(rawFitness);
            }
            testEvaluations[i] = sanitizeFitness(total / testingCases.size());
        }
    }

    public void runTest() {
        evaluateTestPopulation();
    }

    public void predictForDateTime(String dateTime) {
        if (population == null || population.length == 0) {
            throw new IllegalStateException("Population has not been trained.");
        }

        int targetIndex = dates.indexOf(dateTime);
        if (targetIndex < 0) {
            System.out.println("Date/time not found in dataset: " + dateTime);
            return;
        }

        int minIndex = numPreviousDays * 96;
        if (targetIndex < minIndex) {
            System.out.println("Date/time found but not enough previous days to build input block for: " + dateTime);
            return;
        }

        if (testEvaluations == null || testEvaluations.length != population.length) {
            evaluateTestPopulation();
        }

        int bestIndex = getLowestErrorIndex(testEvaluations);
        if (bestIndex < 0 || bestIndex >= population.length) {
            System.out.println("Could not determine a best tree for prediction.");
            return;
        }

        TrainingCase predictionCase = buildLaggedTrainingCase(targetIndex);
        double actual = predictionCase.targetY;
        double predicted = population[bestIndex].evaluate(predictionCase, terminals.toArray(new String[0][]));
        double mse = Math.pow(predicted - actual, 2);

        System.out.printf("Prediction at %s | predicted=%.8f | actual=%.8f | mse=%.8f%n", dateTime, predicted, actual,
                mse);
    }

    public void printFinalResults(long runtimeMs) {
        int bestIndex = getLowestErrorIndex(testEvaluations);
        Tree bestTree = (bestIndex >= 0 && population != null && bestIndex < population.length) ? population[bestIndex]
                : null;
        double averageMse = getAverage(testEvaluations);
        double lowestMse = getLowestError(testEvaluations);
        double stdMse = getStandardDeviation(testEvaluations);

        System.out.printf(
                "avgMSE=%.8f | stdMSE=%.8f | lowestMSE=%.8f%n",
                averageMse, stdMse, lowestMse);
        System.out.printf("Runtime: %d ms%n", runtimeMs);
        System.out.println(getPredictionSummary(bestTree));
        System.out.println("Best tree:\n" + (bestTree == null ? "<none>" : bestTree.toString()));
    }

    public boolean terminationCriteriaMet() {
        // Todo
        return false;
    }

    double getAverage(double[] values) {
        if (values == null || values.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double totalFitness = 0.0;
        for (int i = 0; i < values.length; i++) {
            totalFitness += sanitizeFitness(values[i]);
        }
        return totalFitness / values.length;
    }

    double getStandardDeviation(double[] values) {
        if (values == null || values.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double average = getAverage(values);
        double sumSquaredDifferences = 0.0;
        for (int i = 0; i < values.length; i++) {
            double fitness = sanitizeFitness(values[i]);
            sumSquaredDifferences += Math.pow(fitness - average, 2);
        }
        return Math.sqrt(sumSquaredDifferences / values.length);
    }

    double getLowestError(double[] values) {
        int bestIndex = getLowestErrorIndex(values);
        return bestIndex < 0 ? Double.POSITIVE_INFINITY : values[bestIndex];
    }

    int getLowestErrorIndex(double[] values) {
        if (values == null || values.length == 0) {
            return -1;
        }

        int bestIndex = -1;
        for (int i = 0; i < values.length; i++) {
            if (isBetterEvaluation(values[i], bestIndex < 0 ? Double.POSITIVE_INFINITY : values[bestIndex])) {
                bestIndex = i;
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
        return candidate < currentBest;
    }

    void printGenerationStats(int generation, double[] generationEvaluations) {
        int bestIndex = getLowestErrorIndex(generationEvaluations);
        Tree bestTree = (bestIndex >= 0 && population != null && bestIndex < population.length) ? population[bestIndex]
                : null;
        double averageMse = getAverage(generationEvaluations);
        double stdMse = getStandardDeviation(generationEvaluations);
        double lowestMse = getLowestError(generationEvaluations);

        System.out.printf(
                "Generation %d | avgMSE=%.8f | stdMSE=%.8f | lowestMSE=%.8f%n",
                generation,
                averageMse,
            stdMse,
                lowestMse);
        appendAverageMseCsv(averageMse);
        appendStdMseCsv(stdMse);
        appendLowestMseCsv(lowestMse);
        System.out.println(getPredictionSummary(bestTree));
        System.out.println("Best tree:\n" + (bestTree == null ? "<none>" : bestTree.toString()));
    }

    public String getPredictionSummary(Tree tree) {
        if (tree == null) {
            return "Best prediction: <unavailable>";
        }

        TrainingCase referenceCase = getReferenceCaseForReporting();
        if (referenceCase == null) {
            return "Best prediction: <no reference case available>";
        }

        double predicted = tree.evaluate(referenceCase, terminals.toArray(new String[0][]));
        double actual = referenceCase.targetY;
        double mse = Math.pow(predicted - actual, 2);

        return String.format(
                "Best prediction | predicted=%.8f | actual=%.8f | mse=%.8f",
                predicted,
                actual,
                mse);
    }

    public TrainingCase getReferenceCaseForReporting() {
        if (!testingCases.isEmpty()) {
            return testingCases.get(0);
        }
        if (!trainingCases.isEmpty()) {
            return trainingCases.get(0);
        }
        return null;
    }

    public void resetLowestMseCsv() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(LOWEST_MSE_CSV_FILE, false))) {
            writer.print("");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize " + LOWEST_MSE_CSV_FILE, e);
        }
    }

    public void resetAverageMseCsv() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(AVERAGE_MSE_CSV_FILE, false))) {
            writer.print("");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize " + AVERAGE_MSE_CSV_FILE, e);
        }
    }

    public void resetStdMseCsv() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(STD_MSE_CSV_FILE, false))) {
            writer.print("");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize " + STD_MSE_CSV_FILE, e);
        }
    }

    public void appendLowestMseCsv(double lowestMse) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(LOWEST_MSE_CSV_FILE, true))) {
            writer.printf("%.8f%n", lowestMse);
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to " + LOWEST_MSE_CSV_FILE, e);
        }
    }

    public void appendAverageMseCsv(double averageMse) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(AVERAGE_MSE_CSV_FILE, true))) {
            writer.printf("%.8f%n", averageMse);
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to " + AVERAGE_MSE_CSV_FILE, e);
        }
    }

    public void appendStdMseCsv(double stdMse) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(STD_MSE_CSV_FILE, true))) {
            writer.printf("%.8f%n", stdMse);
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to " + STD_MSE_CSV_FILE, e);
        }
    }

    public TrainingCase buildLaggedTrainingCase(int targetIndex) {
        int minIndex = numPreviousDays * 96;
        if (targetIndex < minIndex) {
            throw new IllegalArgumentException("Target index does not have enough previous days: " + targetIndex);
        }

        Vector<double[]> laggedXValues = new Vector<>();
        Vector<String> laggedDates = new Vector<>();
        for (int i = 0; i < numPreviousDays; ++i) {
            int lagIndex = targetIndex - (i + 1) * 96;
            if (lagIndex >= targetIndex) {
                throw new IllegalStateException("Lagged input must come from before the target index.");
            }
            laggedXValues.add(xValues.get(lagIndex));
            laggedDates.add(dates.get(lagIndex));
        }

        return new TrainingCase(laggedXValues, yValues.get(targetIndex), laggedDates);
    }

    public void createXandYValues() {
        String fileName = "Assignment1Dataset.csv";
        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            xValues.clear();
            yValues.clear();
            dates.clear();
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                dates.add(parts[0]);
                double yValue = Double.parseDouble(parts[1]);
                yValues.add(yValue);

                double[] xRow = new double[8];
                xRow[2] = yValue;
                for (int i = 2; i <= 6; i++) {
                    xRow[i + 1] = Double.parseDouble(parts[i]);
                }
                String[] datetimeParts = parts[0].split(" ");
                int year = Integer.parseInt(datetimeParts[0].split("/")[2]);
                int month = Integer.parseInt(datetimeParts[0].split("/")[1]);
                int day = Integer.parseInt(datetimeParts[0].split("/")[0]);
                int dayOfWeek = zellersCongruence(day, month, year);
                xRow[0] = dayOfWeek;

                int hour = Integer.parseInt(datetimeParts[1].split(":")[0]);
                xRow[1] = hour;
                xValues.add(xRow);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setTerminals() {
        for (int i = 0; i < numPreviousDays; i++) {
            String[] terminalSet = new String[8];
            terminalSet[0] = "Day-" + (i + 1);
            terminalSet[1] = "Hour-" + (i + 1);
            terminalSet[2] = "Electricity_load-" + (i + 1);
            terminalSet[3] = "Residential_electricity_price-" + (i + 1);
            terminalSet[4] = "Residential_solar_generation-" + (i + 1);
            terminalSet[5] = "Residential_wind_generation-" + (i + 1);
            terminalSet[6] = "Temperature-" + (i + 1);
            terminalSet[7] = "Relative Humidity-" + (i + 1);
            terminals.add(terminalSet);
        }
    }


    void generateNewPopulation() {
        if (population == null || population.length == 0) {
            return;
        }

        Tree[] newPopulation = new Tree[populationSize];
        int filled = 0;

        int crossoverChildren = (int) ((double) crossoverRate / 100 * populationSize);
        int mutationChildren = (int) ((double) mutationRate / 100 * populationSize);
        int reproductionChildren = (int) ((double) reproductionRate / 100 * populationSize);

        for (int K = 0; K < crossoverChildren && filled < populationSize; K += 2) {
            Tree parent1 = population[selectTournamentWinnerIndex()];
            Tree parent2 = population[selectTournamentWinnerIndex()];

            newPopulation[filled++] = crossover(parent1, parent2);

            if (filled < populationSize) {
                newPopulation[filled++] = crossover(parent2, parent1);
            }
        }

        for (int K = 0; K < mutationChildren && filled < populationSize; K++) {
            Tree parent = population[selectTournamentWinnerIndex()];
            newPopulation[filled++] = mutate(parent);
        }

        for (int K = 0; K < reproductionChildren && filled < populationSize; K++) {
            Tree parent = population[selectTournamentWinnerIndex()];
            newPopulation[filled++] = cloneTree(parent);
        }

        while (filled < populationSize) {
            Tree parent = population[selectTournamentWinnerIndex()];
            newPopulation[filled++] = cloneTree(parent);
        }

        population = newPopulation;
    }

    public int selectTournamentWinnerIndex() {
        int drawCount = Math.max(1, Math.min(tournamentSize, populationSize));
        int bestIndex = random.nextInt(populationSize);
        double bestError = evaluations[bestIndex];

        for (int i = 1; i < drawCount; i++) {
            int candidateIndex = random.nextInt(populationSize);
            double candidateError = evaluations[candidateIndex];
            if (isBetterEvaluation(candidateError, bestError)) {
                bestError = candidateError;
                bestIndex = candidateIndex;
            }
        }
        return bestIndex;
    }

    public String selectOperatorByRate() {
        int mutationWeight = Math.max(0, mutationRate);
        int crossoverWeight = Math.max(0, crossoverRate);
        int reproductionWeight = Math.max(0, reproductionRate);
        int totalWeight = mutationWeight + crossoverWeight + reproductionWeight;

        if (totalWeight <= 0) {
            return "reproduction";
        }

        int roll = random.nextInt(totalWeight);
        if (roll < mutationWeight) {
            return "mutation";
        }
        if (roll < mutationWeight + crossoverWeight) {
            return "crossover";
        }
        return "reproduction";
    }

    private void simplifyZeroSubtrees(Tree tree) {
        if (tree == null || tree.head == null) {
            return;
        }
        tree.head.simplifyZeroSubtrees();
        tree.head = tree.head.getHead();
    }

    public double sanitizeFitness(double fitness) {
        if (!Double.isFinite(fitness) || fitness < 0.0) {
            return NON_FINITE_PENALTY;
        }
        return Math.min(fitness, NON_FINITE_PENALTY);
    }

    public Tree cloneTree(Tree source) {
        Tree clone = new Tree(
                Tree.GenerationType.GROW,
                1,
                random,
                functions.toArray(new String[0]),
                terminals.toArray(new String[0][]),
                functionProbability,
                terminalProbability);
        clone.head = source.head == null ? null : source.head.deepCopy();
        if (clone.head != null) {
            clone.head.parent = null;
        }
        simplifyZeroSubtrees(clone);
        return clone;
    }

    public Tree mutate(Tree parentTree) {
        Tree childTree = cloneTree(parentTree);
        if (childTree.head == null) {
            return childTree;
        }

        Node mutationPoint = childTree.head.getRandomNode(random);
        if (mutationPoint == null) {
            return childTree;
        }

        int remainingDepth = Math.max(1, maxDepth - mutationPoint.getDepthInTree() + 1);
        Node replacement = generateRandomSubtree(remainingDepth);

        if (mutationPoint == childTree.head) {
            childTree.head = replacement;
            if (childTree.head != null) {
                childTree.head.parent = null;
            }
            simplifyZeroSubtrees(childTree);
            return childTree;
        }

        childTree.head.replaceSubtree(mutationPoint, replacement);
        if (childTree.head != null) {
            childTree.head = childTree.head.getHead();
        }
        simplifyZeroSubtrees(childTree);
        return childTree;
    }

    public Tree crossover(Tree parentA, Tree parentB) {
        Tree child = cloneTree(parentA);
        if (child.head == null || parentB.head == null) {
            return child;
        }

        Node targetInChild = child.head.getRandomNode(random);
        Node donorInParentB = parentB.head.getRandomNode(random);
        if (targetInChild == null || donorInParentB == null) {
            return child;
        }

        Node donorCopy = donorInParentB.deepCopy();

        if (targetInChild == child.head) {
            child.head = donorCopy;
            child.head.parent = null;
            simplifyZeroSubtrees(child);
            return child;
        }

        child.head.replaceSubtree(targetInChild, donorCopy);
        child.head = child.head.getHead();
        simplifyZeroSubtrees(child);
        return child;
    }

    public Node generateRandomSubtree(int maxAllowedDepth) {
        if (maxAllowedDepth <= 1) {
            return new Node(random, terminals.toArray(new String[0][]));
        }

        int pick = random.nextInt(100);
        if (pick < functionProbability) {
            Node functionNode = new Node(random, functions.toArray(new String[0]));
            for (int i = 0; i < functionNode.numChildren; i++) {
                functionNode.setChild(i, generateRandomSubtree(maxAllowedDepth - 1));
            }
                functionNode.simplifyZeroSubtrees();
                return functionNode;
        }

        return new Node(random, terminals.toArray(new String[0][]));
    }

    public int zellersCongruence(int day, int month, int year) {
        if (month < 3) {
            month += 12;
            year -= 1;
        }
        int k = year % 100;
        int j = year / 100;
        int f = day + (13 * (month + 1)) / 5 + k + k / 4 + j / 4 - 2 * j;
        int dayOfWeek = ((f % 7) + 7) % 7;
        // return String.valueOf(dayOfWeek);//0 saturday, 1 sunday, 2 monday, 3 tuesday,
        // 4 wednesday, 5 thursday, 6 friday
        return dayOfWeek;
    }

    // public void selectDataSelectionType(){
    // Scanner scanner = new Scanner(System.in);
    // System.out.println("Select whether to train using n days' values at the same
    // specific time, or \r\n" + //
    // "train using m previous values, or\r\n" + //
    // "train using p previous same weekdays' values at the same specific time\r\n"
    // + //
    // "Enter 1 for n days' values at the same specific time\r\n" + //
    // "Enter 2 for m previous values\r\n" + //
    // "Enter 3 for p previous same weekdays' values at the same specific time");
    // int choice = scanner.nextInt();
    // switch (choice) {
    // case 1:
    // System.out.println("You selected n days' values at the same specific time");
    // System.out.println("How many values would you like to use for training?");
    // numTrainingValues = scanner.nextInt();
    // System.out.println("You selected " + numTrainingValues + " values for
    // training.");
    // break;
    // case 2:
    // System.out.println("You selected m previous values");
    // System.out.println("How many values would you like to use for training?");
    // numTrainingValues = scanner.nextInt();
    // System.out.println("You selected " + numTrainingValues + " values for
    // training.");
    // break;
    // case 3:
    // System.out.println("You selected p previous same weekdays' values at the same
    // specific time");
    // System.out.println("How many values would you like to use for training?");
    // numTrainingValues = scanner.nextInt();
    // System.out.println("You selected " + numTrainingValues + " values for
    // training.");
    // break;
    // default:
    // System.out.println("Invalid choice. Please select 1, 2, or 3.");
    // }
    // scanner.close();
    // }
}
