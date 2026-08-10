package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPNodeFactory;
import ai.evolution.gp.nodes.GPSExpression;
import ai.evolution.gp.nodes.functions.IfThenElse;
import ai.evolution.gp.nodes.terminals.actions.HarvestResources;
import ai.evolution.gp.nodes.terminals.conditions.CanHarvest;
import rts.PhysicalGameState;
import rts.units.UnitTypeTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class GPPopulation implements AutoCloseable {
    private final GPConfig cfg;
    private final UnitTypeTable utt;
    private final Random rnd;
    private final GPNodeFactory factory;
    private final GPEloTable eloTable;
    private final GPHardCaseArchive hardCaseArchive;
    private final ExecutorService evaluationPool;
    private final List<double[]> noveltyArchive = new ArrayList<>();
    private List<GPIndividual> individuals = new ArrayList<>();
    private int generation = 0;
    private boolean curriculumCompleted = false;
    private GPIndividual generalistChampion;
    private GPIndividual curriculumChampion;
    private final List<GPIndividual> seededSpecialists = new ArrayList<>();

    public GPPopulation(GPConfig cfg, UnitTypeTable utt, Random rnd) {
        this.cfg = cfg;
        this.utt = utt;
        this.rnd = rnd;
        this.factory = new GPNodeFactory(cfg.terminalProbability);
        this.eloTable = new GPEloTable(cfg);
        this.hardCaseArchive = new GPHardCaseArchive(cfg.hardCaseArchiveMax);
        this.evaluationPool = Executors.newFixedThreadPool(Math.max(1, cfg.threads));
    }

    public void initialize() {
        individuals = new ArrayList<>();
        for (int i = 0; i < cfg.populationSize; i++) {
            boolean full = (i % 2 == 0);
            int depth = cfg.minInitDepth + rnd.nextInt(cfg.maxInitDepth - cfg.minInitDepth + 1);
            ActionNode root = rnd.nextDouble() < cfg.harvestSeedFraction
                    ? seedHarvester(depth, rnd, full)
                    : factory.randomAction(depth, rnd, full);
            individuals.add(new GPIndividual(root));
        }
    }

    private ActionNode seedHarvester(int depth, Random rnd, boolean full) {
        ActionNode fallback = factory.randomAction(Math.max(1, depth - 1), rnd, full);
        return new IfThenElse(new CanHarvest(), new HarvestResources(), fallback);
    }

    public void evaluate(List<PhysicalGameState> maps, List<String> opponentNames) throws Exception {
        List<GPMatch.EvaluationCase> cases = new ArrayList<>();
        for (int mapIndex = 0; mapIndex < maps.size(); mapIndex++) {
            for (String opponentName : opponentNames) cases.add(new GPMatch.EvaluationCase(mapIndex, opponentName));
        }
        evaluateCases(maps, cases, true);
    }

    public void evaluateCases(List<PhysicalGameState> maps, List<GPMatch.EvaluationCase> cases,
                              boolean allowNovelty) throws Exception {
        List<GPHardCaseArchive.Case> archiveCases = cfg.useHardCaseArchive ? hardCaseArchive.cases() : Collections.emptyList();
        boolean collectBehavior = cfg.useNoveltyBonus && allowNovelty;
        List<Future<?>> futures = new ArrayList<>();
        for (GPIndividual ind : individuals) {
            futures.add(evaluationPool.submit(() -> {
                    try {
                        GPMatch.EvalResult result = GPMatch.evaluateCases(ind, utt, maps, cases, archiveCases,
                                cfg.maxCycles, cfg.maxInactiveCycles, collectBehavior,
                                cfg.evaluationSeed, cfg.drawMarginWeight, cfg.lossMarginWeight);
                        ind.matchupResults = result.matchups;
                        ind.margin = result.meanMargin;
                        ind.behaviorVector = result.behaviorVector;
                        ind.combatScore = computeCombatScore(result.matchups);
                        ind.winRate = rawWinRate(result.matchups);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                throw new Exception(cause != null ? cause : e);
            }
        }

        updateEloRatings();

        if (collectBehavior) {
            computeNoveltyScores();
        }
        for (GPIndividual ind : individuals) ind.fitness = ind.combatScore;

        if (cfg.useHardCaseArchive) detectHardCases();
    }

    static double rawWinRate(List<GPMatch.MatchupResult> matchups) {
        double sum = 0;
        int n = 0;
        for (GPMatch.MatchupResult m : matchups) {
            if (m.archiveCase) continue;
            sum += m.rawScore;
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    double computeCombatScore(List<GPMatch.MatchupResult> matchups) {
        return weightedHarmonicScore(matchups, cfg, eloTable);
    }

    static double weightedHarmonicScore(List<GPMatch.MatchupResult> matchups, GPConfig cfg,
                                        GPEloTable eloTable) {
        double weightSum = 0, weightOverScoreSum = 0;
        for (GPMatch.MatchupResult m : matchups) {
            double w = (m.archiveCase ? cfg.hardCaseWeight : 1.0)
                    * eloTable.rewardMultiplier(m.opponentKey());
            weightSum += w;
            weightOverScoreSum += w / (m.score + cfg.harmonicMeanEpsilon);
        }
        return weightOverScoreSum == 0 ? 0 : weightSum / weightOverScoreSum;
    }

    void updateEloRatings() {
        Map<String, double[]> aggregate = new HashMap<>();
        for (GPIndividual ind : individuals) {
            for (GPMatch.MatchupResult m : ind.matchupResults) {
                if (m.archiveCase) continue;
                double[] sumAndCount = aggregate.computeIfAbsent(m.opponentKey(), k -> new double[2]);
                sumAndCount[0] += m.rawScore;
                sumAndCount[1] += 1;
            }
        }
        for (Map.Entry<String, double[]> e : aggregate.entrySet()) {
            double[] sumAndCount = e.getValue();
            double meanScore = sumAndCount[1] == 0 ? 0.5 : sumAndCount[0] / sumAndCount[1];
            eloTable.update(e.getKey(), meanScore);
        }
    }

    private void computeNoveltyScores() {
        List<double[]> comparisonPool = new ArrayList<>();
        for (GPIndividual ind : individuals) if (ind.behaviorVector != null) comparisonPool.add(ind.behaviorVector);
        comparisonPool.addAll(noveltyArchive);

        for (GPIndividual ind : individuals) {
            ind.noveltyScore = ind.behaviorVector == null ? 0 : knnDistance(ind.behaviorVector, comparisonPool);
        }

        individuals.stream()
                .filter(i -> i.behaviorVector != null)
                .max(Comparator.comparingDouble(i -> i.noveltyScore))
                .ifPresent(i -> {
                    noveltyArchive.add(i.behaviorVector);
                    if (noveltyArchive.size() > cfg.noveltyArchiveSize) noveltyArchive.remove(0);
                });
    }

    private double knnDistance(double[] vector, List<double[]> pool) {
        List<Double> distances = new ArrayList<>(pool.size());
        for (double[] other : pool) {
            if (other == vector) continue;
            distances.add(GPBehaviorVector.distance(vector, other));
        }
        if (distances.isEmpty()) return 0;
        distances.sort(Double::compareTo);
        int k = Math.min(cfg.noveltyNeighbors, distances.size());
        double sum = 0;
        for (int i = 0; i < k; i++) sum += distances.get(i);
        return sum / k;
    }

    private void detectHardCases() {
        GPIndividual best = getBest();
        if (best == null || best.matchupResults == null || best.combatScore < cfg.hardCaseFitnessThreshold) return;
        for (GPMatch.MatchupResult m : best.matchupResults) {
            if (m.rawScore < cfg.hardCaseWeaknessThreshold) {
                hardCaseArchive.add(m.mapIndex, m.opponentName);
            }
        }
    }

    public int getHardCaseArchiveSize() { return hardCaseArchive.size(); }

    public boolean removeHardCase(int mapIndex, String opponentName) {
        return hardCaseArchive.remove(mapIndex, opponentName);
    }

    public int addFullEvaluationHardCases(List<GPMatch.MatchupResult> matchups, int maxAdditions) {
        if (!cfg.useHardCaseArchive || maxAdditions <= 0) return 0;
        List<GPMatch.MatchupResult> sorted = new ArrayList<>(matchups);
        sorted.sort(Comparator.comparingDouble(m -> m.rawScore));
        int added = 0;
        for (GPMatch.MatchupResult matchup : sorted) {
            if (matchup.archiveCase || matchup.rawScore >= cfg.hardCaseWeaknessThreshold) continue;
            if (hardCaseArchive.add(matchup.mapIndex, matchup.opponentName)) {
                added++;
                if (added >= maxAdditions) break;
            }
        }
        return added;
    }

    public int pinHardCases(List<GPMatch.EvaluationCase> cases) {
        if (!cfg.useHardCaseArchive) return 0;
        int added = 0;
        for (GPMatch.EvaluationCase evaluationCase : cases) {
            if (hardCaseArchive.addPinned(evaluationCase.mapIndex, evaluationCase.opponentName)) added++;
        }
        return added;
    }

    public void nextGeneration() {
        List<GPIndividual> sorted = new ArrayList<>(individuals);
        sorted.sort(this::compareIndividuals);
        Collections.reverse(sorted);

        List<GPIndividual> next = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (generalistChampion != null) {
            GPIndividual protectedCopy = generalistChampion.copy();
            next.add(protectedCopy);
            seen.add(GPSExpression.write(protectedCopy.root));
        }
        if (curriculumChampion != null && next.size() < cfg.eliteSize) {
            GPIndividual protectedCopy = curriculumChampion.copy();
            String key = GPSExpression.write(protectedCopy.root);
            if (seen.add(key)) next.add(protectedCopy);
        }
        for (GPIndividual specialist : seededSpecialists) {
            if (next.size() >= cfg.eliteSize) break;
            GPIndividual protectedCopy = specialist.copy();
            String key = GPSExpression.write(protectedCopy.root);
            if (seen.add(key)) next.add(protectedCopy);
        }
        for (int i = 0; i < sorted.size() && next.size() < cfg.eliteSize; i++) {
            GPIndividual elite = sorted.get(i).copy();
            String key = GPSExpression.write(elite.root);
            if (!seen.add(key)) continue;
            next.add(elite);
        }

        while (next.size() < cfg.populationSize) {
            GPIndividual offspring = null;
            for (int attempt = 0; attempt <= cfg.maxDuplicateRetries; attempt++) {
                offspring = produceOffspring(sorted);
                String key = GPSExpression.write(offspring.root);
                if (seen.add(key) || attempt == cfg.maxDuplicateRetries) break;
            }
            next.add(offspring);
        }
        individuals = next;
        generation++;
    }

    private GPIndividual produceOffspring(List<GPIndividual> sorted) {
        double r = rnd.nextDouble();
        if (r < cfg.crossoverRate) {
            GPIndividual p1 = tournamentSelect(sorted);
            GPIndividual p2 = !seededSpecialists.isEmpty()
                    && rnd.nextDouble() < cfg.specialistCrossoverRate
                    ? seededSpecialists.get(rnd.nextInt(seededSpecialists.size()))
                    : tournamentSelect(sorted);
            ActionNode childRoot = GPTreeOps.crossover(p1.root, p2.root, rnd, cfg.maxDepth);
            return new GPIndividual(childRoot);
        } else if (r < cfg.crossoverRate + cfg.mutationRate) {
            GPIndividual p = tournamentSelect(sorted);
            return new GPIndividual(GPTreeOps.mutate(p.root, rnd, factory, cfg.maxDepth, cfg.ercPerturbRate));
        } else {
            GPIndividual p = tournamentSelect(sorted);
            return p.copy();
        }
    }

    private GPIndividual tournamentSelect(List<GPIndividual> pool) {
        GPIndividual best = null;
        for (int i = 0; i < cfg.tournamentSize; i++) {
            GPIndividual candidate = pool.get(rnd.nextInt(pool.size()));
            if (best == null || compareIndividuals(candidate, best) > 0) best = candidate;
        }
        return best;
    }

    int compareIndividuals(GPIndividual a, GPIndividual b) {
        boolean aMet = a.winRate >= cfg.targetFitness;
        boolean bMet = b.winRate >= cfg.targetFitness;
        if (aMet != bMet) return aMet ? 1 : -1;
        if (aMet) {
            int sizeCmp = Integer.compare(b.size(), a.size());
            if (sizeCmp != 0) return sizeCmp;
        }
        double epsilon = Math.max(1e-9, cfg.combatTieEpsilon);
        long aBand = Math.round(a.combatScore / epsilon);
        long bBand = Math.round(b.combatScore / epsilon);
        int bandCmp = Long.compare(aBand, bBand);
        if (bandCmp != 0) return bandCmp;
        if (!aMet) {
            if (cfg.capabilityParsimonyGate) {
                int capabilityCmp = Integer.compare(cappedCapability(a), cappedCapability(b));
                if (capabilityCmp != 0) return capabilityCmp;
            }
            int sizeCmp = Integer.compare(b.size(), a.size());
            if (sizeCmp != 0) return sizeCmp;
        }
        if (cfg.useNoveltyBonus) {
            int noveltyCmp = Double.compare(a.noveltyScore, b.noveltyScore);
            if (noveltyCmp != 0) return noveltyCmp;
        }
        int combatCmp = Double.compare(a.combatScore, b.combatScore);
        if (combatCmp != 0) return combatCmp;
        return Double.compare(a.margin, b.margin);
    }

    private int cappedCapability(GPIndividual individual) {
        return Math.min(individual.distinctActionCount(), Math.max(1, cfg.capabilityGateCeiling));
    }

    public GPIndividual getBest() {
        return individuals.stream().max(this::compareIndividuals).orElse(null);
    }

    public List<GPIndividual> getTop(int count) {
        List<GPIndividual> sorted = new ArrayList<>(individuals);
        sorted.sort(this::compareIndividuals);
        Collections.reverse(sorted);
        return new ArrayList<>(sorted.subList(0, Math.min(Math.max(0, count), sorted.size())));
    }

    public double averageFitness() {
        return individuals.stream().mapToDouble(i -> i.fitness).average().orElse(0);
    }

    public int getGeneration() { return generation; }

    public boolean isCurriculumCompleted() { return curriculumCompleted; }

    public void completeCurriculum() { curriculumCompleted = true; }

    public GPIndividual getGeneralistChampion() {
        return generalistChampion == null ? null : generalistChampion.copy();
    }

    public void setGeneralistChampion(GPIndividual champion) {
        generalistChampion = champion == null ? null : champion.copy();
    }

    public GPIndividual getCurriculumChampion() {
        return curriculumChampion == null ? null : curriculumChampion.copy();
    }

    public void setCurriculumChampion(GPIndividual champion) {
        curriculumChampion = champion == null ? null : champion.copy();
    }

    public void addSeededSpecialist(GPIndividual specialist, int copies) {
        if (specialist == null) return;
        String expression = specialist.toSExpression();
        for (GPIndividual existing : seededSpecialists) {
            if (existing.toSExpression().equals(expression)) return;
        }
        seededSpecialists.add(specialist.copy());
        int count = Math.min(Math.max(0, copies), individuals.size());
        for (int i = 0; i < count; i++) {
            GPIndividual seeded;
            if (i == 0) {
                seeded = specialist.copy();
            } else if (generalistChampion != null && i % 3 == 1) {
                seeded = new GPIndividual(GPTreeOps.crossover(
                        generalistChampion.root, specialist.root, rnd, cfg.maxDepth));
            } else if (generalistChampion != null && i % 3 == 2) {
                seeded = new GPIndividual(GPTreeOps.crossover(
                        specialist.root, generalistChampion.root, rnd, cfg.maxDepth));
            } else {
                seeded = new GPIndividual(GPTreeOps.mutate(specialist.root, rnd, factory,
                        cfg.maxDepth, cfg.ercPerturbRate));
            }
            individuals.set(individuals.size() - 1 - i, seeded);
        }
    }

    public List<GPIndividual> getSeededSpecialists() {
        List<GPIndividual> copy = new ArrayList<>();
        for (GPIndividual specialist : seededSpecialists) copy.add(specialist.copy());
        return copy;
    }

    public List<GPIndividual> getIndividuals() { return individuals; }

    Random getRandom() { return rnd; }

    GPConfig getConfig() { return cfg; }

    Map<String, Double> eloSnapshot() { return eloTable.snapshot(); }

    List<GPHardCaseArchive.Case> hardCaseSnapshot() {
        return new ArrayList<>(hardCaseArchive.cases());
    }

    List<double[]> noveltySnapshot() {
        List<double[]> copy = new ArrayList<>();
        for (double[] vector : noveltyArchive) copy.add(vector.clone());
        return copy;
    }

    void restoreState(List<GPIndividual> restoredIndividuals, int restoredGeneration,
                      Map<String, Double> eloRatings, List<GPHardCaseArchive.Case> hardCases,
                      List<double[]> noveltyVectors, boolean restoredCurriculumCompleted) {
        individuals = restoredIndividuals;
        generation = restoredGeneration;
        curriculumCompleted = restoredCurriculumCompleted;
        eloTable.restore(eloRatings);
        hardCaseArchive.restore(hardCases);
        noveltyArchive.clear();
        for (double[] vector : noveltyVectors) noveltyArchive.add(vector.clone());
    }

    @Override
    public void close() {
        evaluationPool.shutdown();
    }
}
