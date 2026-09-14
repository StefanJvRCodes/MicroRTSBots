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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The individuals of one run: initialisation, parallel evaluation, ranking and reproduction. */
public class GPPopulation implements AutoCloseable {
    private final GPConfig cfg;
    private final UnitTypeTable utt;
    private final Random rnd;
    private final ExecutorService pool;
    private List<GPIndividual> individuals = new ArrayList<>();
    private int generation = 0;

    public GPPopulation(GPConfig cfg, UnitTypeTable utt, Random rnd) {
        this.cfg = cfg;
        this.utt = utt;
        this.rnd = rnd;
        this.pool = Executors.newFixedThreadPool(Math.max(1, cfg.threads));
    }

    /**
     * Ramped half-and-half: alternate full and grow trees over a range of depths. A share of the
     * population is wrapped so its workers harvest from the start, otherwise random search has to
     * rediscover an economy before anything else can be selected for.
     */
    public void initialize() {
        individuals = new ArrayList<>();
        for (int i = 0; i < cfg.populationSize; i++) {
            boolean full = i % 2 == 0;
            int depth = cfg.minInitDepth + rnd.nextInt(cfg.maxInitDepth - cfg.minInitDepth + 1);
            ActionNode root = GPTreeOps.grow(depth, full, cfg.terminalProbability, rnd);
            if (rnd.nextDouble() < cfg.harvestSeedFraction) {
                root = new IfThenElse(new CanHarvest(), new HarvestResources(), root);
            }
            individuals.add(new GPIndividual(root));
        }
    }

    /** Plays every individual against every case, in parallel, and fills in its scores. */
    public void evaluate(List<PhysicalGameState> maps, List<GPMatch.EvaluationCase> cases) throws Exception {
        List<Future<?>> futures = new ArrayList<>();
        for (GPIndividual ind : individuals) {
            futures.add(pool.submit(() -> {
                ind.matchups = GPMatch.evaluate(ind, utt, maps, cases, cfg);
                score(ind);
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
    }

    /**
     * Combat score is the harmonic mean of the shaped matchup scores, so the worst matchup
     * dominates and a weakness cannot be averaged away by strength elsewhere.
     */
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

    /** Higher combat score wins; among equals the smaller tree, then the better material margin. */
    static final Comparator<GPIndividual> RANKING = Comparator
            .comparingDouble((GPIndividual i) -> i.combatScore)
            .thenComparing(Comparator.comparingInt(GPIndividual::size).reversed())
            .thenComparingDouble(i -> i.margin);

    public GPIndividual getBest() {
        return individuals.stream().max(RANKING).orElseThrow();
    }

    public double meanCombatScore() {
        return individuals.stream().mapToDouble(i -> i.combatScore).average().orElse(0);
    }

    /** Elites are copied through unchanged; every other slot is filled by one genetic operator. */
    public void nextGeneration() {
        List<GPIndividual> ranked = new ArrayList<>(individuals);
        ranked.sort(RANKING.reversed());

        List<GPIndividual> next = new ArrayList<>(cfg.populationSize);
        Set<String> seen = new HashSet<>();
        for (GPIndividual elite : ranked) {
            if (next.size() >= cfg.eliteSize) break;
            if (seen.add(elite.toSExpression())) next.add(elite.copy());
        }
        while (next.size() < cfg.populationSize) {
            GPIndividual offspring = produceOffspring();
            for (int retry = 0; retry < cfg.maxDuplicateRetries && !seen.add(offspring.toSExpression()); retry++) {
                offspring = produceOffspring();
            }
            next.add(offspring);
        }
        individuals = next;
        generation++;
    }

    private GPIndividual produceOffspring() {
        double r = rnd.nextDouble();
        GPIndividual parent = tournamentSelect();
        if (r < cfg.crossoverRate) {
            return new GPIndividual(GPTreeOps.crossover(parent.root, tournamentSelect().root, rnd, cfg.maxDepth));
        }
        if (r < cfg.crossoverRate + cfg.mutationRate) {
            return new GPIndividual(GPTreeOps.mutate(parent.root, rnd, cfg.maxDepth,
                    cfg.terminalProbability, cfg.ercPerturbRate));
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

    // ---- state access for GPTrain and GPCheckpoint

    public List<GPIndividual> getIndividuals() { return individuals; }

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
