package ai.evolution.gp;

import ai.evolution.gp.nodes.terminals.actions.Idle;
import ai.evolution.gp.nodes.terminals.actions.HarvestResources;
import ai.evolution.gp.nodes.terminals.actions.TrainWorker;
import ai.evolution.gp.nodes.GPSExpression;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.terminals.conditions.EnemyHasBarracks;
import ai.evolution.gp.nodes.terminals.conditions.GameTimeAtLeast;
import ai.evolution.gp.nodes.terminals.conditions.IsCarryingResources;
import ai.evolution.gp.nodes.terminals.conditions.OwnHasBarracks;
import ai.evolution.gp.nodes.terminals.conditions.OwnWorkersAtLeast;
import ai.evolution.gp.nodes.terminals.conditions.WorkerAttackRankAtMost;
import org.junit.Test;
import rts.GameState;
import rts.Player;
import rts.PhysicalGameState;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GPTrainingTest {
    @Test
    public void mayariBotCanBeSelectedAsTrainingOpponent() {
        UnitTypeTable utt = new UnitTypeTable();
        assertEquals("mayari", GPOpponents.build("mayariBot", utt).getClass().getSimpleName());

        GPConfig cfg = GPConfig.fromArgs(new String[]{
                "--maps=maps/16x16/basesWorkers16x16.xml",
                "--opponents=mayariBot",
                "--curriculum-generations=0"
        });
        assertArrayEquals(new String[]{"mayariBot"}, cfg.opponents);
        assertEquals(0, cfg.curriculumGenerations);
    }

    @Test
    public void timeoutDrawUsesMaterialMarginForFitnessOnly() {
        GPMatch.Result advantagedDraw = new GPMatch.Result(0.5, 0.8, null, 1000, true);
        GPMatch.Result completedDraw = new GPMatch.Result(0.5, 0.8, null, 1000, false);
        assertEquals(0.7, GPMatch.shapeTimeoutDraw(advantagedDraw, 0.25), 1e-9);
        assertEquals(0.5, GPMatch.shapeTimeoutDraw(completedDraw, 0.25), 1e-9);
    }

    @Test
    public void defeatIsShapedByRetainedMaterialButNeverReordersOutcomes() {
        GPMatch.Result nearLoss = new GPMatch.Result(0.0, -0.2, null, 900, false);
        GPMatch.Result wipeout = new GPMatch.Result(0.0, -1.0, null, 900, false);
        GPMatch.Result worstDraw = new GPMatch.Result(0.5, -1.0, null, 1000, true);

        double shapedNearLoss = GPMatch.shapeOutcome(nearLoss, 0.25, 0.2);
        assertEquals(0.08, shapedNearLoss, 1e-9);
        assertEquals(0.0, GPMatch.shapeOutcome(wipeout, 0.25, 0.2), 1e-9);
        assertTrue("A shaped defeat must stay below the worst shaped draw",
                shapedNearLoss < GPMatch.shapeOutcome(worstDraw, 0.25, 0.2));

        // The cap holds even when the weight is configured absurdly high: a defeat may tie the
        // worst shaped draw at the extreme, but it can never outrank one.
        assertTrue(GPMatch.shapeOutcome(new GPMatch.Result(0.0, 1.0, null, 900, false), 0.25, 5.0)
                <= GPMatch.shapeOutcome(worstDraw, 0.25, 5.0));
        assertEquals(0.0, GPMatch.shapeOutcome(nearLoss, 0.25, 0), 1e-9);
    }

    @Test
    public void crashLossIsNeverMarginShaped() {
        GPMatch.Result crashed = new GPMatch.Result(0.0, 0.9, null, 40, false, true);
        assertEquals(0.0, GPMatch.shapeOutcome(crashed, 0.25, 0.2), 1e-9);
    }

    @Test
    public void repeatedFullEvaluationsAverageEachMatchup() {
        GPMatch.EvalResult first = new GPMatch.EvalResult(Arrays.asList(
                new GPMatch.MatchupResult("WorkerRush", 0, 1.0, 10, false),
                new GPMatch.MatchupResult("mayariBot", 0, 0.0, -10, false)), 0, null);
        GPMatch.EvalResult second = new GPMatch.EvalResult(Arrays.asList(
                new GPMatch.MatchupResult("WorkerRush", 0, 0.5, 4, false),
                new GPMatch.MatchupResult("mayariBot", 0, 0.5, -2, false)), 1, null);

        GPMatch.EvalResult averaged =
                GPTrain.aggregateEvaluationRepeats(Arrays.asList(first, second));

        assertEquals(0.75, averaged.matchups.get(0).score, 1e-9);
        assertEquals(0.25, averaged.matchups.get(1).score, 1e-9);
        assertEquals(7.0, averaged.matchups.get(0).margin, 1e-9);
        assertEquals(0.5, averaged.meanMargin, 1e-9);
    }

    @Test
    public void parallelUnitCreationUsesUniqueIds() throws Exception {
        UnitTypeTable utt = new UnitTypeTable();
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < 8; thread++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 2000; i++) {
                        assertTrue(ids.add(new Unit(0, utt.getUnitType("Worker"), 0, 0).getID()));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get();
            assertEquals(16000, ids.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void eloDifficultyActsAsWeightNotScoreScale() {
        GPConfig cfg = smallConfig();
        GPEloTable elo = new GPEloTable(cfg);
        List<GPMatch.MatchupResult> results = Arrays.asList(
                new GPMatch.MatchupResult("easy", 0, 0.0, 0, false),
                new GPMatch.MatchupResult("hard", 0, 1.0, 0, false));
        double baseline = GPPopulation.weightedHarmonicScore(results, cfg, elo);

        Map<String, Double> ratings = new HashMap<>();
        ratings.put("easy@map0", 500.0);
        ratings.put("hard@map0", 1500.0);
        elo.restore(ratings);
        double weighted = GPPopulation.weightedHarmonicScore(results, cfg, elo);

        assertTrue("Down-weighting a solved low-score case and up-weighting a hard win must help", weighted > baseline);
    }

    @Test
    public void archiveReplayDoesNotDoubleCountElo() {
        GPConfig cfg = smallConfig();
        GPPopulation population = new GPPopulation(cfg, new UnitTypeTable(), new Random(1));
        try {
            GPIndividual individual = new GPIndividual(new Idle());
            individual.matchupResults = Arrays.asList(
                    new GPMatch.MatchupResult("WorkerRush", 0, 0.5, 0, false),
                    new GPMatch.MatchupResult("WorkerRush", 0, 0.0, 0, true));
            population.restoreState(Collections.singletonList(individual), 0,
                    Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), false);
            population.updateEloRatings();
            assertEquals(1000.0, population.eloSnapshot().get("WorkerRush@map0"), 1e-9);
        } finally {
            population.close();
        }
    }

    @Test
    public void noveltyOnlySeparatesSameCombatBand() {
        GPConfig cfg = smallConfig();
        cfg.useNoveltyBonus = true;
        cfg.combatTieEpsilon = 0.01;
        GPPopulation population = new GPPopulation(cfg, new UnitTypeTable(), new Random(1));
        try {
            GPIndividual stronger = new GPIndividual(new Idle());
            stronger.combatScore = 0.52;
            GPIndividual novel = new GPIndividual(new Idle());
            novel.combatScore = 0.50;
            novel.noveltyScore = 100;
            assertTrue(population.compareIndividuals(stronger, novel) > 0);

            stronger.combatScore = 0.503;
            novel.combatScore = 0.501;
            assertTrue(population.compareIndividuals(novel, stronger) > 0);
        } finally {
            population.close();
        }
    }

    @Test
    public void checkpointRestoresPopulationAndRandomState() throws Exception {
        GPConfig cfg = smallConfig();
        cfg.populationSize = 8;
        cfg.useHardCaseArchive = true;
        UnitTypeTable utt = new UnitTypeTable();
        GPPopulation original = new GPPopulation(cfg, utt, new Random(123));
        original.initialize();
        original.setGeneralistChampion(new GPIndividual(new TrainWorker()));
        original.setCurriculumChampion(new GPIndividual(new Idle()));
        original.addSeededSpecialist(new GPIndividual(new HarvestResources()), 0);
        original.pinHardCases(Collections.singletonList(
                new GPMatch.EvaluationCase(0, "WorkerRush")));
        Path checkpoint = Files.createTempFile("gp-checkpoint", ".properties");
        GPPopulation restored = null;
        try {
            GPCheckpoint.save(checkpoint, original);
            restored = GPCheckpoint.load(checkpoint, cfg, utt);
            assertEquals("(TrainWorker)", restored.getGeneralistChampion().toSExpression());
            assertEquals("(Idle)", restored.getCurriculumChampion().toSExpression());
            assertEquals("(HarvestResources)",
                    restored.getSeededSpecialists().get(0).toSExpression());
            assertTrue(restored.hardCaseSnapshot().get(0).pinned);
            original.nextGeneration();
            restored.nextGeneration();
            Set<String> retained = new HashSet<>();
            for (GPIndividual individual : restored.getIndividuals()) {
                retained.add(individual.toSExpression());
            }
            assertTrue(retained.contains("(TrainWorker)"));
            assertTrue(retained.contains("(Idle)"));
            assertTrue(retained.contains("(HarvestResources)"));
            for (int i = 0; i < cfg.populationSize; i++) {
                assertEquals(original.getIndividuals().get(i).toSExpression(),
                        restored.getIndividuals().get(i).toSExpression());
            }
        } finally {
            original.close();
            if (restored != null) restored.close();
            Files.deleteIfExists(checkpoint);
        }
    }

    @Test
    public void matchupSamplingIsDeterministicAndRotates() {
        List<GPMatch.EvaluationCase> pool = new ArrayList<>();
        for (int i = 0; i < 10; i++) pool.add(new GPMatch.EvaluationCase(i, "bot" + i));
        List<GPMatch.EvaluationCase> first = GPMatchupSampler.sample(pool, 3, 0, 42);
        List<GPMatch.EvaluationCase> repeated = GPMatchupSampler.sample(pool, 3, 0, 42);
        List<GPMatch.EvaluationCase> next = GPMatchupSampler.sample(pool, 3, 1, 42);
        assertEquals(keys(first), keys(repeated));
        assertTrue(!keys(first).equals(keys(next)));
    }

    @Test
    public void matchupSamplingCoversOpponentAndMapArchetypes() {
        String[] maps = {
                "maps/4x4/basesWorkers4x4.xml",
                "maps/16x16/basesWorkers16x16.xml",
                "maps/BWDistantResources32x32.xml",
                "maps/8x8/basesWorkers8x8Obstacle.xml"
        };
        String[] opponents = {"WorkerRush", "EconomyMilitaryRush", "HeavyDefense"};
        List<GPMatch.EvaluationCase> pool = GPMatchupSampler.allCases(maps, maps, opponents);
        List<GPMatch.EvaluationCase> sample = GPMatchupSampler.sample(pool, 6, 7, 42, maps);
        Set<String> opponentGroups = new HashSet<>();
        Set<String> mapGroups = new HashSet<>();
        for (GPMatch.EvaluationCase c : sample) {
            opponentGroups.add(GPMatchupSampler.opponentGroup(c.opponentName));
            mapGroups.add(GPMatchupSampler.mapGroup(c.mapIndex, maps));
        }
        assertEquals(new HashSet<>(Arrays.asList("rush", "economy", "defense")), opponentGroups);
        assertTrue(mapGroups.size() >= 3);
    }

    @Test
    public void fullEvaluationWeaknessesEnterHardCaseArchive() {
        GPConfig cfg = smallConfig();
        cfg.useHardCaseArchive = true;
        GPPopulation population = new GPPopulation(cfg, new UnitTypeTable(), new Random(1));
        try {
            List<GPMatch.MatchupResult> results = Arrays.asList(
                    new GPMatch.MatchupResult("WorkerRush", 0, 0.0, 0, false),
                    new GPMatch.MatchupResult("EconomyMilitaryRush", 1, 0.25, 0, false),
                    new GPMatch.MatchupResult("HeavyDefense", 2, 0.5, 0, false));
            assertEquals(2, population.addFullEvaluationHardCases(results, 2));
            assertEquals(2, population.getHardCaseArchiveSize());
        } finally {
            population.close();
        }
    }

    @Test
    public void unreachableActionsDoNotCountTowardsCapability() {
        GPIndividual constantDead = new GPIndividual(GPSExpression.parseAction(
                "(If (Not (True)) (BuildBarracks) (HarvestResources))"));
        assertEquals(new HashSet<>(Arrays.asList("BuildBarracks", "HarvestResources")),
                constantDead.actionNames());
        assertEquals(Collections.singleton("HarvestResources"), constantDead.reachableActionNames());
        assertEquals(1, constantDead.distinctActionCount());
        assertEquals(1, new GPIndividual(new HarvestResources()).distinctActionCount());
    }

    @Test
    public void repeatedConditionOnAPathMakesTheInnerBranchUnreachable() {
        // The shape the gp-1786302912023 champion used to claim capability it could never execute:
        // CanHarvest is a unit-type predicate, so re-testing it inside its own else branch is dead.
        String expression = "(If (CanHarvest) (AttackNearestEnemy)"
                + " (If (CanHarvest) (BuildBarracks) (TrainWorker)))";
        GPIndividual gamed = new GPIndividual(GPSExpression.parseAction(expression));
        assertTrue(gamed.actionNames().contains("BuildBarracks"));
        assertEquals(new HashSet<>(Arrays.asList("AttackNearestEnemy", "TrainWorker")),
                gamed.reachableActionNames());
        assertEquals(2, gamed.distinctActionCount());

        assertEquals("(If (CanHarvest) (AttackNearestEnemy) (TrainWorker))",
                GPSExpression.write(GPTreeOps.reduce(GPSExpression.parseAction(expression))));
    }

    @Test
    public void pathConditionsDecomposeThroughAndOrAndNot() {
        // A true And pins both sides; a false Or pins both sides; Not flips.
        assertEquals(Collections.singleton("HarvestResources"),
                new GPIndividual(GPSExpression.parseAction(
                        "(If (And (CanHarvest) (IsMilitary)) (If (IsMilitary) (HarvestResources) (BuildBase))"
                                + " (HarvestResources))")).reachableActionNames());
        assertEquals(Collections.singleton("TrainWorker"),
                new GPIndividual(GPSExpression.parseAction(
                        "(If (Or (CanHarvest) (IsMilitary)) (TrainWorker)"
                                + " (If (CanHarvest) (BuildBase) (TrainWorker)))")).reachableActionNames());
        assertEquals(new HashSet<>(Arrays.asList("TrainWorker", "HarvestResources")),
                new GPIndividual(GPSExpression.parseAction(
                        "(If (Not (CanHarvest)) (TrainWorker)"
                                + " (If (CanHarvest) (HarvestResources) (BuildBase)))")).reachableActionNames());
    }

    @Test
    public void reduceKeepsBranchesThePathHasNotDecided() {
        // Distinct thresholds on the same predicate are not treated as implying each other, so
        // nothing here may be pruned.
        String expression = "(If (GameTimeAtLeast 1200) (If (GameTimeAtLeast 200) (BuildBase)"
                + " (HarvestResources)) (TrainWorker))";
        assertEquals(expression,
                GPSExpression.write(GPTreeOps.reduce(GPSExpression.parseAction(expression))));
    }

    @Test
    public void parsimonyPressureCannotStripActionsInsideACombatBand() throws Exception {
        GPConfig cfg = smallConfig();
        UnitTypeTable utt = new UnitTypeTable(cfg.unitTypeTableVersion, cfg.conflictPolicy);
        GPPopulation population = new GPPopulation(cfg, utt, new Random(1));
        try {
            // Same combat band, and the capable tree is the larger one — exactly the situation in
            // which unguarded parsimony pressure discards the barracks chain.
            GPIndividual capable = new GPIndividual(GPSExpression.parseAction(
                    "(If (OwnWorkersAtLeast 3) (BuildBarracks) (If (IsCarryingResources) "
                            + "(TrainMilitary) (HarvestResources)))"));
            GPIndividual small = new GPIndividual(new HarvestResources());
            capable.combatScore = 0.2001;
            small.combatScore = 0.2002;
            assertTrue(capable.size() > small.size());

            assertTrue("More action vocabulary must win the band before size is consulted",
                    population.compareIndividuals(capable, small) > 0);

            cfg.capabilityParsimonyGate = false;
            assertTrue("With the gate off, the smaller tree wins the band as before",
                    population.compareIndividuals(capable, small) < 0);

            // Above the ceiling the gate stops paying out, so size decides again and a bloated tree
            // cannot buy immunity with one extra action terminal.
            cfg.capabilityParsimonyGate = true;
            cfg.capabilityGateCeiling = 1;
            assertTrue("Both are at the ceiling, so the smaller tree wins",
                    population.compareIndividuals(capable, small) < 0);
        } finally {
            population.close();
        }
    }

    @Test
    public void selectionComparatorStaysTransitiveAcrossCapabilityAndSize() throws Exception {
        GPConfig cfg = smallConfig();
        UnitTypeTable utt = new UnitTypeTable(cfg.unitTypeTableVersion, cfg.conflictPolicy);
        GPPopulation population = new GPPopulation(cfg, utt, new Random(1));
        try {
            // Ranking capability by subset containment rather than by count would make this trio
            // cyclic (a beats b on containment, b beats c on size, c beats a on size), which is
            // enough for TimSort to reject the comparator.
            GPIndividual a = new GPIndividual(GPSExpression.parseAction(
                    "(If (OwnWorkersAtLeast 3) (BuildBarracks) (If (GameTimeAtLeast 50) "
                            + "(HarvestResources) (HarvestResources)))"));
            GPIndividual b = new GPIndividual(new HarvestResources());
            GPIndividual c = new GPIndividual(GPSExpression.parseAction(
                    "(If (GameTimeAtLeast 50) (TrainLight) (TrainLight))"));
            for (GPIndividual individual : Arrays.asList(a, b, c)) individual.combatScore = 0.2;

            List<GPIndividual> all = new ArrayList<>(Arrays.asList(a, b, c));
            for (GPIndividual x : all) {
                for (GPIndividual y : all) {
                    assertEquals("Comparator must be antisymmetric",
                            Integer.signum(population.compareIndividuals(x, y)),
                            -Integer.signum(population.compareIndividuals(y, x)));
                }
            }
            // No cycle: sorting the six permutations must agree on a single winner.
            for (List<GPIndividual> permutation : Arrays.asList(
                    Arrays.asList(a, b, c), Arrays.asList(a, c, b), Arrays.asList(b, a, c),
                    Arrays.asList(b, c, a), Arrays.asList(c, a, b), Arrays.asList(c, b, a))) {
                List<GPIndividual> sorted = new ArrayList<>(permutation);
                sorted.sort(population::compareIndividuals);
                assertEquals(a, sorted.get(sorted.size() - 1));
            }
        } finally {
            population.close();
        }
    }

    @Test
    public void pinnedCurriculumCaseSurvivesArchiveEviction() {
        GPHardCaseArchive archive = new GPHardCaseArchive(2);
        assertTrue(archive.addPinned(0, "WorkerRush"));
        assertTrue(archive.add(1, "LightRush"));
        assertTrue(archive.add(2, "HeavyRush"));
        assertEquals(2, archive.size());
        assertTrue(archive.cases().get(0).pinned);
        assertEquals("WorkerRush@map0", archive.cases().get(0).key());
    }

    @Test
    public void wilsonIntervalContainsObservedRate() {
        double[] interval = GPPlay.wilsonInterval(60, 100, 1.96);
        assertTrue(interval[0] < 0.6 && interval[1] > 0.6);
        assertArrayEquals(new double[]{0, 0}, GPPlay.wilsonInterval(0, 0, 1.96), 0);
    }

    @Test
    public void seededEconomyOpponentEvaluationRepeats() throws Exception {
        GPConfig cfg = smallConfig();
        UnitTypeTable utt = new UnitTypeTable(cfg.unitTypeTableVersion, cfg.conflictPolicy);
        List<PhysicalGameState> maps = Collections.singletonList(
                PhysicalGameState.load("maps/4x4/basesWorkers4x4.xml", utt));
        List<GPMatch.EvaluationCase> cases = Collections.singletonList(
                new GPMatch.EvaluationCase(0, "EconomyMilitaryRush"));
        GPIndividual individual = new GPIndividual(new Idle());
        GPMatch.EvalResult first = GPMatch.evaluateCases(individual, utt, maps, cases,
                Collections.emptyList(), 100, 30, false, 99);
        GPMatch.EvalResult second = GPMatch.evaluateCases(individual, utt, maps, cases,
                Collections.emptyList(), 100, 30, false, 99);
        assertEquals(first.matchups.get(0).score, second.matchups.get(0).score, 0);
        assertEquals(first.matchups.get(0).margin, second.matchups.get(0).margin, 0);
    }

    @Test
    public void fullMapDoesNotCreateNegativeBuildPosition() {
        UnitTypeTable utt = new UnitTypeTable();
        PhysicalGameState pgs = oneCellState(utt);
        Player player = pgs.getPlayer(0);
        Unit worker = pgs.getUnits().get(0);
        StructuredGPAI ai = new StructuredGPAI(utt, new Idle());
        List<Integer> reserved = new ArrayList<>();

        assertFalse(ai.buildIfNotAlreadyBuilding(worker, utt.getUnitType("Base"),
                0, 0, reserved, player, pgs));
        assertTrue(reserved.isEmpty());
        assertNull(ai.getAbstractAction(worker));
    }

    @Test
    public void adaptiveWorkerConditionsDifferentiateRolesAndStrategy() {
        UnitTypeTable utt = new UnitTypeTable();
        PhysicalGameState pgs = adaptiveWorkerState(utt);
        GameState gs = new GameState(pgs, utt);
        StructuredGPAI ai = new StructuredGPAI(utt, new Idle());
        GPTurnContext context = new GPTurnContext(ai, gs, 0, utt);
        Unit closestWorker = pgs.getUnitAt(2, 0);
        Unit fartherWorker = pgs.getUnitAt(0, 0);

        context.unit = closestWorker;
        assertTrue(new OwnWorkersAtLeast(3).eval(context));
        assertTrue(new OwnHasBarracks().eval(context));
        assertTrue(new EnemyHasBarracks().eval(context));
        assertTrue(new GameTimeAtLeast(0).eval(context));
        assertFalse(new GameTimeAtLeast(1).eval(context));
        assertTrue(new IsCarryingResources().eval(context));
        assertTrue(new WorkerAttackRankAtMost(1).eval(context));

        context.unit = fartherWorker;
        assertFalse(new IsCarryingResources().eval(context));
        assertFalse(new WorkerAttackRankAtMost(1).eval(context));
        assertTrue(new WorkerAttackRankAtMost(3).eval(context));
    }

    @Test
    public void adaptiveConditionsRoundTripThroughSExpressions() {
        String expression = "(If (And (OwnWorkersAtLeast 3) (WorkerAttackRankAtMost 1)) "
                + "(AttackNearestEnemy) (If (Or (OwnHasBarracks) (EnemyHasBarracks)) "
                + "(If (GameTimeAtLeast 200) (TrainMilitary) (HarvestResources)) "
                + "(If (IsCarryingResources) (HarvestResources) (TrainWorker))))";
        assertEquals(expression, GPSExpression.write(GPSExpression.parseAction(expression)));
    }

    private static GPConfig smallConfig() {
        GPConfig cfg = new GPConfig();
        cfg.threads = 1;
        cfg.populationSize = 2;
        cfg.useNoveltyBonus = false;
        cfg.useHardCaseArchive = false;
        return cfg;
    }

    private static PhysicalGameState oneCellState(UnitTypeTable utt) {
        PhysicalGameState pgs = new PhysicalGameState(1, 1);
        pgs.addPlayer(new Player(0, 100));
        pgs.addPlayer(new Player(1, 0));
        pgs.addUnit(new Unit(0, utt.getUnitType("Worker"), 0, 0));
        return pgs;
    }

    private static PhysicalGameState adaptiveWorkerState(UnitTypeTable utt) {
        PhysicalGameState pgs = new PhysicalGameState(4, 4);
        pgs.addPlayer(new Player(0, 100));
        pgs.addPlayer(new Player(1, 100));
        pgs.addUnit(new Unit(0, utt.getUnitType("Worker"), 0, 0, 0));
        pgs.addUnit(new Unit(0, utt.getUnitType("Worker"), 2, 0, 1));
        pgs.addUnit(new Unit(0, utt.getUnitType("Worker"), 0, 3, 0));
        pgs.addUnit(new Unit(0, utt.getUnitType("Barracks"), 0, 1));
        pgs.addUnit(new Unit(1, utt.getUnitType("Base"), 3, 0));
        pgs.addUnit(new Unit(1, utt.getUnitType("Barracks"), 3, 1));
        return pgs;
    }

    private static List<String> keys(List<GPMatch.EvaluationCase> cases) {
        List<String> keys = new ArrayList<>();
        for (GPMatch.EvaluationCase c : cases) keys.add(c.key());
        return keys;
    }
}
