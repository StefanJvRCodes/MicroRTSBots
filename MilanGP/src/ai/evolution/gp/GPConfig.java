package ai.evolution.gp;

import java.util.ArrayList;
import java.util.List;

public class GPConfig {
    public int populationSize = 1000;
    public int generations = 200;
    public double targetFitness = 1.0;
    public int tournamentSize = 3;
    public int eliteSize = 5;
    public double crossoverRate = 0.6;
    public double mutationRate = 0.3;
    public double ercPerturbRate = 0.5;
    public int maxDuplicateRetries = 5;
    public int minInitDepth = 2;
    public int maxInitDepth = 6;
    public double harvestSeedFraction = 0.5;
    public int maxDepth = 10;
    public double terminalProbability = 0.35;
    public int maxCycles = 10000;
    public int maxInactiveCycles = 300;
    public double drawMarginWeight = 0.25;
    public double lossMarginWeight = 0.2;
    public long randomSeed = 42;
    public long evaluationSeed = 4242;
    public int threads = Runtime.getRuntime().availableProcessors();
    public String[] opponents = {
            "WorkerRush",
            "WorkerRushPlusPlus",
            "LightRush",
            "HeavyRush",
            "RangedRush",
            "SimpleEconomyRush",
            "EconomyRush",
            "EconomyRushBurster",
            "EconomyMilitaryRush",
            "EMRDeterministico",
            "WorkerDefense",
            "LightDefense",
            "HeavyDefense",
            "RangedDefense",
            "mayariBot",
    };
    public String[] curriculumOpponents = {
            "WorkerRush",
    };
    public int curriculumGenerations = 50;
    public double curriculumWorstCaseTarget = 0.75;
    public int sizeShrinkPatience = 50;
    public int stagnationPatience = 100;
    public double stagnationImprovementThreshold = 0.005;
    public int caseStagnationPatience = 80;
    public double caseStagnationImprovementThreshold = 0.01;
    public int autopilotMaxDepth = 4;
    public int autopilotMaxAttemptsPerCaseSet = 2;
    public String[] maps = {
            "maps/4x4/basesWorkers4x4.xml",
            "maps/8x8/basesWorkers8x8.xml",
            "maps/16x16/basesWorkers16x16.xml",
            "maps/8x8/TwoBasesWorkers8x8.xml",
            "maps/8x8/basesWorkers8x8Obstacle.xml",
    };
    public String[] curriculumMaps = {
            "maps/16x16/basesWorkers16x16.xml",
    };
    public int sampledMatchupsPerGeneration = 20;
    public int fullEvaluationInterval = 5;
    public int fullEvaluationEliteCount = 10;
    public int fullEvaluationRepeats = 5;
    public int finalEvaluationEliteCount = 20;
    public String[] seedBotFiles = {};
    public int seedCopies = 20;
    public double specialistCrossoverRate = 0.1;

    public String playBotFile = "./models/baseline.txt";
    public String[] playOpponents = {
            "WorkerRush", "LightRush", "HeavyRush", "RangedRush", "WorkerRushPlusPlus",
            "EconomyRush", "EconomyRushBurster", "EconomyMilitaryRush", "EMRDeterministico", "SimpleEconomyRush",
            "LightDefense", "HeavyDefense", "RangedDefense", "WorkerDefense",
            "mayariBot",
            "RandomAI", "RandomBiasedAI", "RandomBiasedSingleUnitAI", "PassiveAI"
    };
    public String playMap = "maps/16x16/basesWorkers16x16.xml";
    public String[] holdoutMaps = {
            "maps/12x12/melee12x12Mixed12.xml",
            "maps/8x8/melee8x8Mixed6.xml",
            "maps/16x16/basesWorkers16x16noResources.xml",
            "maps/8x8/basesWorkersBarracks8x8.xml",
            "maps/24x24/basesWorkers24x24.xml",
            "maps/16x16/basesWorkers16x16C.xml",
            "maps/BroodWar/(4)BloodBath.scmA.xml",
    };
    public String[] holdoutOpponents = {
            "RandomAI", "RandomBiasedAI", "RandomBiasedSingleUnitAI",
    };
    public int playIterations = 10;
    public boolean playVisualize = false;
    public int playVisualDelayMillis = 50;
    public boolean playHoldout = false;

    public double harmonicMeanEpsilon = 0.1;

    public double eloInitialRating = 1000;

    public double eloK = 16;

    public double eloScale = 400;

    public double eloMaxMultiplier = 3.0;
    public double eloMinMultiplier = 0.5;

    public boolean useNoveltyBonus = true;
    public double combatTieEpsilon = 0.01;

    public boolean capabilityParsimonyGate = true;

    public int capabilityGateCeiling = 8;
    public int noveltyNeighbors = 15;
    public int noveltyArchiveSize = 200;

    public boolean useHardCaseArchive = true;
    public int hardCaseArchiveMax = 12;
    public int fullEvaluationHardCasesToAdd = 1;
    public int weakestCasesToLog = 5;
    public double hardCaseWeight = 2.0;
    public double hardCaseFitnessThreshold = 0.45;
    public double hardCaseWeaknessThreshold = 0.5;

    public String runId = "gp-" + System.currentTimeMillis();
    public String outputDirectory = "runs";
    public String publishBotFile = "";
    public int checkpointInterval = 10;
    public String resumeCheckpoint = "";
    public int unitTypeTableVersion = 2;
    public int conflictPolicy = 1;

    public GPConfig copy() {
        GPConfig copy = new GPConfig();
        try {
            for (java.lang.reflect.Field field : GPConfig.class.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.set(copy, field.get(this));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("GPConfig fields must stay public", e);
        }
        return copy;
    }

    public static GPConfig fromArgs(String[] args) {
        GPConfig cfg = new GPConfig();
        for (String arg : args) {
            if (!arg.startsWith("--") || arg.indexOf('=') < 0) {
                throw new IllegalArgumentException("Expected --key=value, got: " + arg);
            }
            String key = arg.substring(2, arg.indexOf('='));
            String value = arg.substring(arg.indexOf('=') + 1);
            if ("seed".equals(key)) cfg.randomSeed = Long.parseLong(value);
            else if ("evaluation-seed".equals(key)) cfg.evaluationSeed = Long.parseLong(value);
            else if ("threads".equals(key)) cfg.threads = Integer.parseInt(value);
            else if ("population".equals(key)) cfg.populationSize = Integer.parseInt(value);
            else if ("generations".equals(key)) cfg.generations = Integer.parseInt(value);
            else if ("run-id".equals(key)) cfg.runId = value;
            else if ("output-dir".equals(key)) cfg.outputDirectory = value;
            else if ("publish".equals(key)) cfg.publishBotFile = value;
            else if ("checkpoint-interval".equals(key)) cfg.checkpointInterval = Integer.parseInt(value);
            else if ("resume".equals(key)) cfg.resumeCheckpoint = value;
            else if ("sampled-matchups".equals(key)) cfg.sampledMatchupsPerGeneration = Integer.parseInt(value);
            else if ("full-evaluation-repeats".equals(key)) cfg.fullEvaluationRepeats = Integer.parseInt(value);
            else if ("seed-bots".equals(key)) cfg.seedBotFiles = splitList(value);
            else if ("seed-copies".equals(key)) cfg.seedCopies = Integer.parseInt(value);
            else if ("specialist-crossover-rate".equals(key)) cfg.specialistCrossoverRate = Double.parseDouble(value);
            else if ("curriculum-generations".equals(key)) cfg.curriculumGenerations = Integer.parseInt(value);
            else if ("curriculum-maps".equals(key)) cfg.curriculumMaps = splitList(value);
            else if ("curriculum-opponents".equals(key)) cfg.curriculumOpponents = splitList(value);
            else if ("max-cycles".equals(key)) cfg.maxCycles = Integer.parseInt(value);
            else if ("max-inactive-cycles".equals(key)) cfg.maxInactiveCycles = Integer.parseInt(value);
            else if ("draw-margin-weight".equals(key)) cfg.drawMarginWeight = Double.parseDouble(value);
            else if ("loss-margin-weight".equals(key)) cfg.lossMarginWeight = Double.parseDouble(value);
            else if ("capability-parsimony-gate".equals(key)) cfg.capabilityParsimonyGate = Boolean.parseBoolean(value);
            else if ("capability-gate-ceiling".equals(key)) cfg.capabilityGateCeiling = Integer.parseInt(value);
            else if ("stagnation-patience".equals(key)) cfg.stagnationPatience = Integer.parseInt(value);
            else if ("stagnation-improvement-threshold".equals(key)) cfg.stagnationImprovementThreshold = Double.parseDouble(value);
            else if ("full-evaluation-interval".equals(key)) cfg.fullEvaluationInterval = Integer.parseInt(value);
            else if ("case-stagnation-patience".equals(key)) cfg.caseStagnationPatience = Integer.parseInt(value);
            else if ("case-stagnation-improvement-threshold".equals(key)) cfg.caseStagnationImprovementThreshold = Double.parseDouble(value);
            else if ("autopilot-max-depth".equals(key)) cfg.autopilotMaxDepth = Integer.parseInt(value);
            else if ("autopilot-max-attempts".equals(key)) cfg.autopilotMaxAttemptsPerCaseSet = Integer.parseInt(value);
            else if ("maps".equals(key)) cfg.maps = splitList(value);
            else if ("opponents".equals(key)) cfg.opponents = splitList(value);
            else if ("novelty".equals(key)) cfg.useNoveltyBonus = Boolean.parseBoolean(value);
            else if ("hard-cases".equals(key)) cfg.useHardCaseArchive = Boolean.parseBoolean(value);
            else if ("play-bot".equals(key)) cfg.playBotFile = value;
            else if ("play-iterations".equals(key)) cfg.playIterations = Integer.parseInt(value);
            else if ("play-map".equals(key)) cfg.playMap = value;
            else if ("play-opponents".equals(key)) cfg.playOpponents = splitList(value);
            else if ("holdout".equals(key)) cfg.playHoldout = Boolean.parseBoolean(value);
            else if ("visualize".equals(key)) cfg.playVisualize = Boolean.parseBoolean(value);
            else if ("visual-delay".equals(key)) cfg.playVisualDelayMillis = Integer.parseInt(value);
            else throw new IllegalArgumentException("Unknown option: --" + key);
        }
        return cfg;
    }

    private static String[] splitList(String value) {
        String[] raw = value.split(",");
        List<String> cleaned = new ArrayList<>();
        for (String item : raw) if (!item.trim().isEmpty()) cleaned.add(item.trim());
        return cleaned.toArray(new String[cleaned.size()]);
    }
}
