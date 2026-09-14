package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.functions.IfThenElse;
import ai.evolution.gp.nodes.terminals.actions.HarvestResources;
import ai.evolution.gp.nodes.terminals.conditions.CanHarvest;
import rts.PhysicalGameState;
import rts.units.UnitTypeTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class GPPopulation implements AutoCloseable {
    private final GPConfig cfg;
    private final UnitTypeTable utt;
    private final Random rnd;
    private final ExecutorService pool;
    private List<GPIndividual> individuals = new ArrayList<>();
    private int generation = 0;
    private GPStructure structure;

    /** Every genotype ever scored in this run, keyed by canonical form. Evaluation is deterministic, so a
     *  revisited tree costs a lookup instead of a full round of games. */
    private final Map<String, List<GPMatch.MatchupResult>> archive = new ConcurrentHashMap<>();
    private int reusedEvaluations;
    private GPIndividual bestEver;
    private int bestEverGeneration;

    public GPPopulation(GPConfig cfg, UnitTypeTable utt, Random rnd) {
        this.cfg = cfg;
        this.utt = utt;
        this.rnd = rnd;
        this.pool = Executors.newFixedThreadPool(Math.max(1, cfg.threads));
    }

    public void initialize() {
        individuals = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (individuals.size() < cfg.populationSize) {
            GPIndividual candidate = null;
            for (int attempt = 0; attempt < cfg.maxDuplicateRetries; attempt++) {
                candidate = randomIndividual(individuals.size() % 2 == 0);
                if (seen.add(candidate.canonical())) break;
            }
            individuals.add(candidate);
        }
    }

    private GPIndividual randomIndividual(boolean full) {
        int depth = cfg.minInitDepth + rnd.nextInt(cfg.maxInitDepth - cfg.minInitDepth + 1);
        ActionNode root = GPTreeOps.grow(depth, full, cfg.terminalProbability, rnd);
        if (rnd.nextDouble() < cfg.harvestSeedFraction) {
            root = new IfThenElse(new CanHarvest(), new HarvestResources(), root);
        }
        return new GPIndividual(root);
    }

    public void evaluate(List<PhysicalGameState> maps, List<GPMatch.EvaluationCase> cases) throws Exception {
        reusedEvaluations = 0;
        List<Future<?>> futures = new ArrayList<>();
        for (GPIndividual ind : individuals) {
            List<GPMatch.MatchupResult> known = archive.get(ind.canonical());
            if (known != null) {
                ind.matchups = known;
                score(ind);
                reusedEvaluations++;
                continue;
            }
            futures.add(pool.submit(() -> {
                ind.matchups = GPMatch.evaluate(ind, utt, maps, cases, cfg);
                score(ind);
                archive.put(ind.canonical(), List.copyOf(ind.matchups));
                return null;
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }
        GPIndividual best = getBest();
        if (bestEver == null || RANKING.compare(best, bestEver) > 0) {
            bestEver = best;
            bestEverGeneration = generation;
        }
    }

    private void score(GPIndividual ind) {
        double inverseSum = 0, rawSum = 0, marginSum = 0, worst = 1.0;
        for (GPMatch.MatchupResult m : ind.matchups) {
            inverseSum += 1.0 / (m.score() + cfg.harmonicMeanEpsilon);
            rawSum += m.rawScore();
            marginSum += m.margin();
            worst = Math.min(worst, m.rawScore());
        }
        int n = ind.matchups.size();
        ind.combatScore = n / inverseSum;
        ind.winRate = rawSum / n;
        ind.worstCase = worst;
        ind.margin = marginSum / n;
    }

    static final Comparator<GPIndividual> RANKING = Comparator
            .comparingDouble((GPIndividual i) -> i.combatScore)
            .thenComparing(Comparator.comparingInt(GPIndividual::size).reversed())
            .thenComparingDouble(i -> i.margin);

    public GPIndividual getBest() {
        return individuals.stream().max(RANKING).orElseThrow();
    }

    /** The best individual of any generation, which is not always in the current population: the structure
     *  search retires whole areas, and an elite is dropped when a retired area no longer accepts it. */
    public GPIndividual getBestEver() {
        return bestEver == null ? getBest() : bestEver;
    }

    public int getBestEverGeneration() { return bestEverGeneration; }

    public int reusedEvaluations() { return reusedEvaluations; }

    public int archiveSize() { return archive.size(); }

    public int distinctIndividuals() {
        Set<String> distinct = new HashSet<>();
        for (GPIndividual ind : individuals) distinct.add(ind.canonical());
        return distinct.size();
    }

    public double meanCombatScore() {
        return individuals.stream().mapToDouble(i -> i.combatScore).average().orElse(0);
    }

    public void nextGeneration() {
        List<GPIndividual> ranked = new ArrayList<>(individuals);
        ranked.sort(RANKING.reversed());
        GPIndividual best = ranked.getFirst();
        boolean seeding = structure != null && structure.seeding();

        List<GPIndividual> next = new ArrayList<>(cfg.populationSize);
        Set<String> seen = new HashSet<>();
        int eliteBudget = seeding ? 1 : cfg.eliteSize;
        for (GPIndividual elite : seeding ? List.of(best) : ranked) {
            if (next.size() >= eliteBudget) break;
            if (accepted(elite) && seen.add(elite.canonical())) next.add(elite.copy());
        }
        while (next.size() < cfg.populationSize) {
            next.add(offspring(best, seen));
        }
        individuals = next;
        generation++;
    }

    /**
     * A unique, structurally acceptable offspring, or the closest miss once retries run out. A duplicate that
     * respects the structure constraint beats a novel tree that breaks it, and the miss is still registered so
     * it cannot be handed out twice.
     */
    private GPIndividual offspring(GPIndividual best, Set<String> seen) {
        GPIndividual fallback = null;
        for (int attempt = 0; attempt < cfg.maxDuplicateRetries; attempt++) {
            GPIndividual candidate = produceOffspring(best);
            boolean valid = accepted(candidate);
            if (valid && seen.add(candidate.canonical())) return candidate;
            if (valid || fallback == null) fallback = candidate;
        }
        seen.add(fallback.canonical());
        return fallback;
    }

    private boolean accepted(GPIndividual individual) {
        return structure == null || structure.accepts(individual.root);
    }

    private GPIndividual produceOffspring(GPIndividual best) {
        double r = rnd.nextDouble();
        boolean seeding = structure != null && structure.seeding();
        GPIndividual parent = seeding ? best : tournamentSelect();
        int minLevel = structure == null ? 0 : structure.minLevel();
        int maxLevel = structure == null ? Integer.MAX_VALUE : structure.maxLevel();
        if (r < cfg.crossoverRate) {
            return new GPIndividual(GPTreeOps.crossover(parent.root, tournamentSelect().root, rnd,
                    cfg.maxDepth, minLevel, maxLevel));
        }
        if (r < cfg.crossoverRate + cfg.mutationRate) {
            return new GPIndividual(GPTreeOps.mutate(parent.root, rnd, cfg.maxDepth,
                    cfg.terminalProbability, cfg.ercPerturbRate, minLevel, maxLevel));
        }
        return parent.copy();
    }

    private GPIndividual tournamentSelect() {
        GPIndividual best = null;
        for (int i = 0; i < cfg.tournamentSize; i++) {
            GPIndividual candidate = individuals.get(rnd.nextInt(individuals.size()));
            if (best == null || RANKING.compare(candidate, best) > 0) best = candidate;
        }
        return best;
    }

    public List<GPIndividual> getIndividuals() { return individuals; }

    public void useStructureSearch(GPStructure search) { structure = search; }

    public GPStructure getStructureSearch() { return structure; }

    public int getGeneration() { return generation; }

    Random getRandom() { return rnd; }

    void restore(List<GPIndividual> restored, int restoredGeneration) {
        individuals = restored;
        generation = restoredGeneration;
    }

    @Override
    public void close() {
        pool.shutdown();
    }
}
