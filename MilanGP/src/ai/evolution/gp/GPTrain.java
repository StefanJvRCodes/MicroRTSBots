package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPSExpression;
import rts.PhysicalGameState;
import rts.units.UnitTypeTable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

public class GPTrain {

    public static class PhaseResult {
        public final String bestExpression;
        public final GPIndividual bestIndividual;
        public final List<GPMatch.EvaluationCase> activeCases;
        public final List<GPMatch.EvaluationCase> ejectedCases;
        public final double winRate;
        public final double worstCase;
        public final int requestedCaseCount;

        PhaseResult(String bestExpression, GPIndividual bestIndividual,
                    List<GPMatch.EvaluationCase> activeCases,
                    List<GPMatch.EvaluationCase> ejectedCases, double winRate, double worstCase,
                    int requestedCaseCount) {
            this.bestExpression = bestExpression;
            this.bestIndividual = bestIndividual;
            this.activeCases = activeCases;
            this.ejectedCases = ejectedCases;
            this.winRate = winRate;
            this.worstCase = worstCase;
            this.requestedCaseCount = requestedCaseCount;
        }
    }

    public static void main(String[] args) throws Exception {
        GPConfig cfg = GPConfig.fromArgs(args);
        UnitTypeTable utt = new UnitTypeTable(cfg.unitTypeTableVersion, cfg.conflictPolicy);
        Path runDirectory = Paths.get(cfg.outputDirectory, cfg.runId);
        Files.createDirectories(runDirectory);
        List<PhysicalGameState> maps = loadMaps(cfg, utt);
        List<GPMatch.EvaluationCase> fullCases =
                GPMatchupSampler.allCases(cfg.maps, cfg.maps, cfg.opponents);
        List<GPMatch.EvaluationCase> curriculumCases =
                GPMatchupSampler.allCases(cfg.maps, cfg.curriculumMaps, cfg.curriculumOpponents);
        runPhase(cfg, utt, maps, fullCases, curriculumCases,
                readSeedExpressions(cfg.seedBotFiles), runDirectory, cfg.runId);
    }

    public static List<PhysicalGameState> loadMaps(GPConfig cfg, UnitTypeTable utt) throws Exception {
        List<PhysicalGameState> maps = new ArrayList<>();
        for (String mapPath : cfg.maps) maps.add(PhysicalGameState.load(mapPath, utt));
        return maps;
    }

    public static List<String> readSeedExpressions(String[] seedBotFiles) throws IOException {
        List<String> expressions = new ArrayList<>();
        for (String seedBotFile : seedBotFiles) {
            expressions.add(new String(Files.readAllBytes(Paths.get(seedBotFile)),
                    StandardCharsets.UTF_8).trim());
        }
        return expressions;
    }

    public static PhaseResult runPhase(GPConfig cfg, UnitTypeTable utt, List<PhysicalGameState> maps,
                                       List<GPMatch.EvaluationCase> requestedCases,
                                       List<GPMatch.EvaluationCase> curriculumCases,
                                       List<String> seedExpressions,
                                       Path runDirectory, String phaseLabel) throws Exception {
        Files.createDirectories(runDirectory);
        writeManifest(runDirectory.resolve("manifest.properties"), cfg, phaseLabel, requestedCases);
        Path metricsPath = runDirectory.resolve("metrics.jsonl");

        List<GPMatch.EvaluationCase> activeCases = new ArrayList<>(requestedCases);
        List<GPMatch.EvaluationCase> ejectedCases = new ArrayList<>();
        if (activeCases.isEmpty()) throw new IllegalArgumentException("Phase needs at least one case");

        GPPopulation population;
        if (cfg.resumeCheckpoint.isEmpty()) {
            population = new GPPopulation(cfg, utt, new Random(cfg.randomSeed));
            population.initialize();
        } else {
            population = GPCheckpoint.load(Paths.get(cfg.resumeCheckpoint), cfg, utt);
            if (population.getIndividuals().size() != cfg.populationSize) {
                throw new IllegalArgumentException("Checkpoint population size does not match --population");
            }
        }
        for (String expression : seedExpressions) {
            GPIndividual specialist = new GPIndividual(GPSExpression.parseAction(expression));
            population.addSeededSpecialist(specialist, cfg.seedCopies);
            System.out.println("Seeded protected specialist with " + cfg.seedCopies + " population copies");
        }
        boolean curriculumConfigured = !curriculumCases.isEmpty();
        if (curriculumConfigured && population.isCurriculumCompleted()) {
            population.pinHardCases(curriculumCases);
            if (population.getCurriculumChampion() == null) {
                ChampionMetrics recovered = evaluateElites(population.getIndividuals(), utt, maps,
                        curriculumCases, cfg);
                population.setCurriculumChampion(recovered.individual);
                System.out.println("Recovered curriculum specialist from checkpoint population"
                        + " | win rate = " + recovered.winRate
                        + " | worst case = " + recovered.worstCase);
            }
        }

        Map<String, Double> bestPerCase = new HashMap<>();
        Map<String, Integer> caseLastImprovement = new HashMap<>();
        int startGeneration = population.getGeneration();
        for (GPMatch.EvaluationCase c : activeCases) {
            bestPerCase.put(c.key(), Double.NEGATIVE_INFINITY);
            caseLastImprovement.put(c.key(), startGeneration);
        }

        boolean targetAnnounced = false;
        Integer bestSizeAtTarget = null;
        Integer lastSizeImprovementGeneration = null;
        double bestCombatSoFar = Double.NEGATIVE_INFINITY;
        int lastCombatImprovementGeneration = 0;
        boolean evaluatedThisInvocation = false;
        try {
            for (int gen = population.getGeneration(); gen < cfg.generations; gen++) {
                boolean inCurriculum = curriculumConfigured && gen < cfg.curriculumGenerations
                        && !population.isCurriculumCompleted();
                List<GPMatch.EvaluationCase> activePool = inCurriculum ? curriculumCases : activeCases;
                List<GPMatch.EvaluationCase> sampled = GPMatchupSampler.sample(activePool,
                        cfg.sampledMatchupsPerGeneration, gen, cfg.randomSeed, cfg.maps);
                population.evaluateCases(maps, sampled, !inCurriculum);
                evaluatedThisInvocation = true;
                GPIndividual best = population.getBest();

                boolean runFullEvaluation = inCurriculum || cfg.fullEvaluationInterval > 0
                        && gen % cfg.fullEvaluationInterval == 0;
                ChampionMetrics champion = runFullEvaluation
                        ? evaluateElites(fullEvaluationCandidates(population, cfg.fullEvaluationEliteCount),
                                utt, maps, activePool, cfg) : null;
                if (champion != null) {
                    population.setGeneralistChampion(champion.individual);
                    if (!inCurriculum && champion.winRate >= cfg.hardCaseFitnessThreshold) {
                        population.addFullEvaluationHardCases(champion.matchups,
                                cfg.fullEvaluationHardCasesToAdd);
                    }
                }
                String archiveInfo = cfg.useHardCaseArchive
                        ? " | hard cases = " + population.getHardCaseArchiveSize() : "";
                System.out.println("Generation " + gen +
                        (inCurriculum ? " [curriculum]" : "") +
                        " | best combat = " + best.combatScore +
                        " | sampled win rate = " + best.winRate +
                        (champion == null ? "" : " | full win rate = " + champion.winRate
                                + " | worst case = " + champion.worstCase) +
                        " | avg fitness = " + population.averageFitness() +
                        " | best size = " + best.size() +
                        archiveInfo);
                if (champion != null) {
                    System.out.println("  weakest full cases: "
                            + weakestCases(champion.matchups, cfg.maps, cfg.weakestCasesToLog));
                }
                appendMetrics(metricsPath, gen, inCurriculum, best, population, champion,
                        sampled.size(), cfg.maps);

                if (inCurriculum && champion != null
                        && champion.worstCase >= cfg.curriculumWorstCaseTarget) {
                    System.out.println("Curriculum worst-case target reached at generation " + gen
                            + ", switching to the full training pool.");
                    population.setCurriculumChampion(champion.individual);
                    population.pinHardCases(curriculumCases);
                    population.completeCurriculum();
                    bestCombatSoFar = Double.NEGATIVE_INFINITY;
                    lastCombatImprovementGeneration = gen;
                }
                if (!inCurriculum && champion != null) {
                    for (GPMatch.MatchupResult m : champion.matchups) {
                        if (m.archiveCase) continue;
                        Double previous = bestPerCase.get(m.opponentKey());
                        if (previous == null) continue;
                        if (m.rawScore > previous + cfg.caseStagnationImprovementThreshold) {
                            bestPerCase.put(m.opponentKey(), m.rawScore);
                            caseLastImprovement.put(m.opponentKey(), gen);
                        }
                    }
                    List<GPMatch.EvaluationCase> stagnant = new ArrayList<>();
                    if (cfg.caseStagnationPatience > 0 && activeCases.size() > 1) {
                        for (GPMatch.EvaluationCase c : activeCases) {
                            if (bestPerCase.get(c.key()) >= cfg.hardCaseWeaknessThreshold) continue;
                            if (gen - caseLastImprovement.get(c.key()) >= cfg.caseStagnationPatience) {
                                stagnant.add(c);
                            }
                        }
                    }
                    if (!stagnant.isEmpty()) {
                        boolean allStalled = stagnant.size() == activeCases.size();
                        for (GPMatch.EvaluationCase c : stagnant) {
                            System.out.println("Case " + describeCase(c, cfg.maps) + " stalled at "
                                    + bestPerCase.get(c.key()) + " for " + cfg.caseStagnationPatience
                                    + " generations, ejecting from the active pool.");
                            population.removeHardCase(c.mapIndex, c.opponentName);
                        }
                        ejectedCases.addAll(stagnant);
                        if (allStalled) {
                            System.out.println("Every active case has stalled, stopping.");
                            break;
                        }
                        activeCases.removeAll(stagnant);
                        bestCombatSoFar = Double.NEGATIVE_INFINITY;
                        lastCombatImprovementGeneration = gen;
                    }
                }
                if (!inCurriculum && !targetAnnounced) {
                    if (best.combatScore > bestCombatSoFar + cfg.stagnationImprovementThreshold) {
                        bestCombatSoFar = best.combatScore;
                        lastCombatImprovementGeneration = gen;
                    }
                    if (cfg.stagnationPatience > 0
                            && gen - lastCombatImprovementGeneration >= cfg.stagnationPatience) {
                        System.out.println("No combat-score improvement of at least "
                                + cfg.stagnationImprovementThreshold + " in " + cfg.stagnationPatience
                                + " generations, stopping.");
                        break;
                    }
                }
                if (!inCurriculum && champion != null && champion.winRate >= cfg.targetFitness) {
                    if (!targetAnnounced) {
                        System.out.println("Target win rate " + cfg.targetFitness + " reached; optimizing tree size.");
                        targetAnnounced = true;
                    }
                    int championSize = champion.individual.size();
                    if (bestSizeAtTarget == null || championSize < bestSizeAtTarget) {
                        bestSizeAtTarget = championSize;
                        lastSizeImprovementGeneration = gen;
                    }
                }
                if (targetAnnounced && lastSizeImprovementGeneration != null
                        && gen - lastSizeImprovementGeneration >= cfg.sizeShrinkPatience) {
                    System.out.println("No further size reduction in " + cfg.sizeShrinkPatience
                            + " generations, stopping.");
                    break;
                }

                population.nextGeneration();
                if (cfg.checkpointInterval > 0
                        && population.getGeneration() % cfg.checkpointInterval == 0) {
                    GPCheckpoint.save(runDirectory.resolve("checkpoint-"
                            + population.getGeneration() + ".properties"), population);
                }
            }

            if (!evaluatedThisInvocation) {
                List<GPMatch.EvaluationCase> sampled = GPMatchupSampler.sample(activeCases,
                        cfg.sampledMatchupsPerGeneration, population.getGeneration(), cfg.randomSeed, cfg.maps);
                population.evaluateCases(maps, sampled, true);
            }
            ChampionMetrics finalChampion = evaluateElites(
                    fullEvaluationCandidates(population, cfg.finalEvaluationEliteCount),
                    utt, maps, activeCases, cfg);
            GPIndividual best = finalChampion.individual;
            ActionNode reduced = GPTreeOps.reduce(best.root);
            String expression = GPSExpression.write(reduced);
            System.out.println("Final full-pool win rate: " + finalChampion.winRate
                    + " (worst case " + finalChampion.worstCase + ")");
            System.out.println("Size: " + best.size() + " -> " + GPTreeOps.size(reduced) + " after reduce()");
            System.out.println(expression);

            Path bestPath = runDirectory.resolve("best.txt");
            Files.write(bestPath, expression.getBytes(StandardCharsets.UTF_8));
            if (!ejectedCases.isEmpty()) {
                writeCaseList(runDirectory.resolve("ejected.txt"), ejectedCases, cfg.maps);
                System.out.println("Ejected " + ejectedCases.size() + " case(s): "
                        + describeCases(ejectedCases, cfg.maps));
            }
            if (!cfg.publishBotFile.isEmpty()) {
                Files.copy(bestPath, Paths.get(cfg.publishBotFile),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            GPCheckpoint.save(runDirectory.resolve("checkpoint-final.properties"), population);
            return new PhaseResult(expression, new GPIndividual(reduced), activeCases, ejectedCases,
                    finalChampion.winRate, finalChampion.worstCase, requestedCases.size());
        } finally {
            population.close();
        }
    }

    static String describeCase(GPMatch.EvaluationCase c, String[] mapPaths) {
        String map = c.mapIndex >= 0 && c.mapIndex < mapPaths.length
                ? mapPaths[c.mapIndex] : "map" + c.mapIndex;
        return c.opponentName + "@" + map;
    }

    static String describeCases(List<GPMatch.EvaluationCase> cases, String[] mapPaths) {
        List<String> parts = new ArrayList<>();
        for (GPMatch.EvaluationCase c : cases) parts.add(describeCase(c, mapPaths));
        return String.join(", ", parts);
    }

    private static void writeCaseList(Path path, List<GPMatch.EvaluationCase> cases, String[] mapPaths)
            throws IOException {
        List<String> lines = new ArrayList<>();
        for (GPMatch.EvaluationCase c : cases) lines.add(describeCase(c, mapPaths));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    public static PhaseResult pickBest(List<String> expressions, GPConfig cfg, UnitTypeTable utt,
                                       List<PhysicalGameState> maps,
                                       List<GPMatch.EvaluationCase> cases) throws Exception {
        List<GPIndividual> candidates = new ArrayList<>();
        for (String expression : expressions) {
            candidates.add(new GPIndividual(GPSExpression.parseAction(expression)));
        }
        ChampionMetrics best = evaluateElites(candidates, utt, maps, cases, cfg);
        return new PhaseResult(best.individual.toSExpression(), best.individual, cases,
                Collections.emptyList(), best.winRate, best.worstCase, cases.size());
    }

    private static ChampionMetrics evaluateElites(List<GPIndividual> elites, UnitTypeTable utt,
                                                   List<PhysicalGameState> maps,
                                                   List<GPMatch.EvaluationCase> cases,
                                                   GPConfig cfg) throws Exception {
        ChampionMetrics best = null;
        for (GPIndividual individual : elites) {
            List<GPMatch.EvalResult> repeatedResults = new ArrayList<>();
            int repeats = Math.max(1, cfg.fullEvaluationRepeats);
            for (int repeat = 0; repeat < repeats; repeat++) {
                long seed = cfg.evaluationSeed + repeat * 0x9E3779B97F4A7C15L;
                repeatedResults.add(GPMatch.evaluateCases(individual, utt, maps, cases,
                        Collections.emptyList(), cfg.maxCycles, cfg.maxInactiveCycles, false,
                        seed, cfg.drawMarginWeight, cfg.lossMarginWeight));
            }
            GPMatch.EvalResult result = aggregateEvaluationRepeats(repeatedResults);
            double worst = 1.0;
            for (GPMatch.MatchupResult matchup : result.matchups) {
                worst = Math.min(worst, matchup.rawScore);
            }
            ChampionMetrics candidate = new ChampionMetrics(individual,
                    GPPopulation.rawWinRate(result.matchups), worst, result.meanMargin,
                    result.matchups);
            if (best == null || candidate.compareTo(best, cfg.targetFitness) > 0) best = candidate;
        }
        return best;
    }

    static GPMatch.EvalResult aggregateEvaluationRepeats(List<GPMatch.EvalResult> results) {
        if (results.isEmpty()) throw new IllegalArgumentException("At least one evaluation is required");
        List<GPMatch.MatchupResult> template = results.get(0).matchups;
        double[] scoreSums = new double[template.size()];
        double[] rawScoreSums = new double[template.size()];
        double[] marginSums = new double[template.size()];
        double[] player0ScoreSums = new double[template.size()];
        double[] player1ScoreSums = new double[template.size()];
        int[] limitedGameSums = new int[template.size()];
        double meanMarginSum = 0;
        for (GPMatch.EvalResult result : results) {
            if (result.matchups.size() != template.size()) {
                throw new IllegalArgumentException("Repeated evaluations have different matchup counts");
            }
            meanMarginSum += result.meanMargin;
            for (int i = 0; i < template.size(); i++) {
                GPMatch.MatchupResult expected = template.get(i);
                GPMatch.MatchupResult actual = result.matchups.get(i);
                if (expected.mapIndex != actual.mapIndex
                        || !expected.opponentName.equals(actual.opponentName)
                        || expected.archiveCase != actual.archiveCase) {
                    throw new IllegalArgumentException("Repeated evaluations have different matchup order");
                }
                scoreSums[i] += actual.score;
                rawScoreSums[i] += actual.rawScore;
                marginSums[i] += actual.margin;
                player0ScoreSums[i] += actual.candidateAsPlayer0Score;
                player1ScoreSums[i] += actual.candidateAsPlayer1Score;
                limitedGameSums[i] += actual.limitedGames;
            }
        }
        List<GPMatch.MatchupResult> averaged = new ArrayList<>();
        for (int i = 0; i < template.size(); i++) {
            GPMatch.MatchupResult matchup = template.get(i);
            averaged.add(new GPMatch.MatchupResult(matchup.opponentName, matchup.mapIndex,
                    scoreSums[i] / results.size(), rawScoreSums[i] / results.size(),
                    marginSums[i] / results.size(), matchup.archiveCase,
                    player0ScoreSums[i] / results.size(),
                    player1ScoreSums[i] / results.size(), limitedGameSums[i]));
        }
        return new GPMatch.EvalResult(averaged, meanMarginSum / results.size(), null);
    }

    private static List<GPIndividual> fullEvaluationCandidates(GPPopulation population, int count) {
        List<GPIndividual> candidates = population.getTop(count);
        Set<String> expressions = new HashSet<>();
        for (GPIndividual candidate : candidates) expressions.add(candidate.toSExpression());
        GPIndividual protectedChampion = population.getGeneralistChampion();
        if (protectedChampion != null && expressions.add(protectedChampion.toSExpression())) {
            candidates.add(protectedChampion);
        }
        GPIndividual curriculumChampion = population.getCurriculumChampion();
        if (curriculumChampion != null && expressions.add(curriculumChampion.toSExpression())) {
            candidates.add(curriculumChampion);
        }
        for (GPIndividual specialist : population.getSeededSpecialists()) {
            if (expressions.add(specialist.toSExpression())) candidates.add(specialist);
        }
        return candidates;
    }

    private static String weakestCases(List<GPMatch.MatchupResult> matchups, String[] mapPaths, int count) {
        List<GPMatch.MatchupResult> sorted = new ArrayList<>(matchups);
        sorted.sort(Comparator.comparingDouble(m -> m.rawScore));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(Math.max(0, count), sorted.size()); i++) {
            if (i > 0) out.append(", ");
            GPMatch.MatchupResult matchup = sorted.get(i);
            String map = matchup.mapIndex >= 0 && matchup.mapIndex < mapPaths.length
                    ? mapPaths[matchup.mapIndex] : "map" + matchup.mapIndex;
            out.append(matchup.opponentName).append('@').append(map)
                    .append('=').append(matchup.rawScore)
                    .append(" [p0=").append(matchup.candidateAsPlayer0Score)
                    .append(", p1=").append(matchup.candidateAsPlayer1Score)
                    .append(", shaped=").append(matchup.score)
                    .append(", limited=").append(matchup.limitedGames).append(']');
        }
        return out.toString();
    }

    private static void appendMetrics(Path path, int generation, boolean curriculum, GPIndividual best,
                                      GPPopulation population, ChampionMetrics champion,
                                      int sampledCases, String[] mapPaths) throws IOException {
        String json = "{\"generation\":" + generation
                + ",\"curriculum\":" + curriculum
                + ",\"bestCombat\":" + best.combatScore
                + ",\"sampledWinRate\":" + best.winRate
                + ",\"averageFitness\":" + population.averageFitness()
                + ",\"bestSize\":" + best.size()
                + ",\"sampledCases\":" + sampledCases
                + ",\"hardCases\":" + population.getHardCaseArchiveSize()
                + (champion == null ? "" : ",\"fullWinRate\":" + champion.winRate
                        + ",\"worstCase\":" + champion.worstCase
                        + ",\"weakestCases\":\""
                        + jsonEscape(weakestCases(champion.matchups, mapPaths, 5)) + "\"")
                + "}\n";
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(json);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeManifest(Path path, GPConfig cfg, String phaseLabel,
                                      List<GPMatch.EvaluationCase> cases) throws IOException {
        Properties p = new Properties();
        p.setProperty("runId", cfg.runId);
        p.setProperty("phaseLabel", phaseLabel);
        p.setProperty("cases", describeCases(cases, cfg.maps));
        p.setProperty("caseStagnationPatience", Integer.toString(cfg.caseStagnationPatience));
        p.setProperty("caseStagnationImprovementThreshold",
                Double.toString(cfg.caseStagnationImprovementThreshold));
        p.setProperty("randomSeed", Long.toString(cfg.randomSeed));
        p.setProperty("evaluationSeed", Long.toString(cfg.evaluationSeed));
        p.setProperty("populationSize", Integer.toString(cfg.populationSize));
        p.setProperty("generations", Integer.toString(cfg.generations));
        p.setProperty("threads", Integer.toString(cfg.threads));
        p.setProperty("maps", String.join(",", cfg.maps));
        p.setProperty("opponents", String.join(",", cfg.opponents));
        p.setProperty("sampledMatchupsPerGeneration", Integer.toString(cfg.sampledMatchupsPerGeneration));
        p.setProperty("fullEvaluationRepeats", Integer.toString(cfg.fullEvaluationRepeats));
        p.setProperty("drawMarginWeight", Double.toString(cfg.drawMarginWeight));
        p.setProperty("lossMarginWeight", Double.toString(cfg.lossMarginWeight));
        p.setProperty("capabilityParsimonyGate", Boolean.toString(cfg.capabilityParsimonyGate));
        p.setProperty("capabilityGateCeiling", Integer.toString(cfg.capabilityGateCeiling));
        p.setProperty("stagnationPatience", Integer.toString(cfg.stagnationPatience));
        p.setProperty("stagnationImprovementThreshold", Double.toString(cfg.stagnationImprovementThreshold));
        p.setProperty("seedBotFiles", String.join(",", cfg.seedBotFiles));
        p.setProperty("seedCopies", Integer.toString(cfg.seedCopies));
        p.setProperty("specialistCrossoverRate", Double.toString(cfg.specialistCrossoverRate));
        p.setProperty("useNoveltyBonus", Boolean.toString(cfg.useNoveltyBonus));
        p.setProperty("useHardCaseArchive", Boolean.toString(cfg.useHardCaseArchive));
        p.setProperty("unitTypeTableVersion", Integer.toString(cfg.unitTypeTableVersion));
        p.setProperty("conflictPolicy", Integer.toString(cfg.conflictPolicy));
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            p.store(writer, "microRTS GP run manifest");
        }
    }

    private static class ChampionMetrics {
        final GPIndividual individual;
        final double winRate;
        final double worstCase;
        final double margin;
        final List<GPMatch.MatchupResult> matchups;

        ChampionMetrics(GPIndividual individual, double winRate, double worstCase, double margin,
                        List<GPMatch.MatchupResult> matchups) {
            this.individual = individual;
            this.winRate = winRate;
            this.worstCase = worstCase;
            this.margin = margin;
            this.matchups = matchups;
        }

        int compareTo(ChampionMetrics other, double targetFitness) {
            int worstCmp = Double.compare(worstCase, other.worstCase);
            if (worstCmp != 0) return worstCmp;
            int rateCmp = Double.compare(winRate, other.winRate);
            if (rateCmp != 0) return rateCmp;
            if (winRate >= targetFitness && other.winRate >= targetFitness) {
                int sizeCmp = Integer.compare(other.individual.size(), individual.size());
                if (sizeCmp != 0) return sizeCmp;
            }
            return Double.compare(margin, other.margin);
        }
    }
}
