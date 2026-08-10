package ai.evolution.gp;

import ai.core.AI;
import gui.PhysicalGameStateJFrame;
import gui.PhysicalGameStatePanel;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.UnitAction;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;
import util.Pair;

import java.util.ArrayList;
import java.util.List;

public class GPMatch {
    private GPMatch() {}

    public static class Result {
        public final double score;
        public final double margin;
        public final GPBehaviorTrace trace;
        public final long cycles;
        public final boolean endedByLimit;
        public final boolean crashed;

        public Result(double score, double margin) {
            this(score, margin, null, 0, false);
        }

        public Result(double score, double margin, GPBehaviorTrace trace) {
            this(score, margin, trace, 0, false);
        }

        public Result(double score, double margin, GPBehaviorTrace trace, long cycles) {
            this(score, margin, trace, cycles, false);
        }

        public Result(double score, double margin, GPBehaviorTrace trace, long cycles,
                      boolean endedByLimit) {
            this(score, margin, trace, cycles, endedByLimit, false);
        }

        public Result(double score, double margin, GPBehaviorTrace trace, long cycles,
                      boolean endedByLimit, boolean crashed) {
            this.score = score;
            this.margin = margin;
            this.trace = trace;
            this.cycles = cycles;
            this.endedByLimit = endedByLimit;
            this.crashed = crashed;
        }
    }

    public static class EvaluationCase {
        public final int mapIndex;
        public final String opponentName;

        public EvaluationCase(int mapIndex, String opponentName) {
            this.mapIndex = mapIndex;
            this.opponentName = opponentName;
        }

        public String key() { return opponentName + "@map" + mapIndex; }
    }

    public static class MatchupResult {
        public final String opponentName;
        public final int mapIndex;
        public final double score;
        public final double rawScore;
        public final double margin;
        public final double candidateAsPlayer0Score;
        public final double candidateAsPlayer1Score;
        public final int limitedGames;
        public final boolean archiveCase;

        public MatchupResult(String opponentName, int mapIndex, double score, double margin, boolean archiveCase) {
            this(opponentName, mapIndex, score, score, margin, archiveCase,
                    score, score, 0);
        }

        public MatchupResult(String opponentName, int mapIndex, double score, double rawScore,
                             double margin, boolean archiveCase,
                             double candidateAsPlayer0Score, double candidateAsPlayer1Score,
                             int limitedGames) {
            this.opponentName = opponentName;
            this.mapIndex = mapIndex;
            this.score = score;
            this.rawScore = rawScore;
            this.margin = margin;
            this.archiveCase = archiveCase;
            this.candidateAsPlayer0Score = candidateAsPlayer0Score;
            this.candidateAsPlayer1Score = candidateAsPlayer1Score;
            this.limitedGames = limitedGames;
        }

        public String opponentKey() { return opponentName + "@map" + mapIndex; }
    }

    public static class EvalResult {
        public final List<MatchupResult> matchups;
        public final double meanMargin;
        public final double[] behaviorVector;

        public EvalResult(List<MatchupResult> matchups, double meanMargin, double[] behaviorVector) {
            this.matchups = matchups;
            this.meanMargin = meanMargin;
            this.behaviorVector = behaviorVector;
        }
    }

    public static EvalResult evaluate(GPIndividual individual, UnitTypeTable utt, List<PhysicalGameState> maps,
                                       List<String> opponentNames, List<GPHardCaseArchive.Case> archiveCases,
                                       int maxCycles, int maxInactiveCycles, boolean collectBehavior) throws Exception {
        List<EvaluationCase> cases = new ArrayList<>();
        for (int mapIndex = 0; mapIndex < maps.size(); mapIndex++) {
            for (String opponentName : opponentNames) cases.add(new EvaluationCase(mapIndex, opponentName));
        }
        return evaluateCases(individual, utt, maps, cases, archiveCases, maxCycles, maxInactiveCycles,
                collectBehavior, 0L);
    }

    public static EvalResult evaluateCases(GPIndividual individual, UnitTypeTable utt, List<PhysicalGameState> maps,
                                           List<EvaluationCase> cases, List<GPHardCaseArchive.Case> archiveCases,
                                           int maxCycles, int maxInactiveCycles, boolean collectBehavior,
                                           long evaluationSeed) throws Exception {
        return evaluateCases(individual, utt, maps, cases, archiveCases, maxCycles,
                maxInactiveCycles, collectBehavior, evaluationSeed, 0);
    }

    public static EvalResult evaluateCases(GPIndividual individual, UnitTypeTable utt, List<PhysicalGameState> maps,
                                           List<EvaluationCase> cases, List<GPHardCaseArchive.Case> archiveCases,
                                           int maxCycles, int maxInactiveCycles, boolean collectBehavior,
                                           long evaluationSeed, double drawMarginWeight) throws Exception {
        return evaluateCases(individual, utt, maps, cases, archiveCases, maxCycles, maxInactiveCycles,
                collectBehavior, evaluationSeed, drawMarginWeight, 0);
    }

    public static EvalResult evaluateCases(GPIndividual individual, UnitTypeTable utt, List<PhysicalGameState> maps,
                                           List<EvaluationCase> cases, List<GPHardCaseArchive.Case> archiveCases,
                                           int maxCycles, int maxInactiveCycles, boolean collectBehavior,
                                           long evaluationSeed, double drawMarginWeight,
                                           double lossMarginWeight) throws Exception {
        List<MatchupResult> matchups = new ArrayList<>();
        List<GPBehaviorTrace> traces = collectBehavior ? new ArrayList<>() : null;

        for (EvaluationCase c : cases) {
            matchups.add(playMatchup(individual, utt, maps.get(c.mapIndex), c.mapIndex, c.opponentName,
                    maxCycles, maxInactiveCycles, false, traces, evaluationSeed, drawMarginWeight,
                    lossMarginWeight));
        }
        for (GPHardCaseArchive.Case c : archiveCases) {
            matchups.add(playMatchup(individual, utt, maps.get(c.mapIndex), c.mapIndex, c.opponentName,
                    maxCycles, maxInactiveCycles, true, traces, evaluationSeed, drawMarginWeight,
                    lossMarginWeight));
        }

        double marginSum = 0;
        for (MatchupResult m : matchups) marginSum += m.margin;
        double meanMargin = matchups.isEmpty() ? 0 : marginSum / matchups.size();

        double[] behaviorVector = collectBehavior ? GPBehaviorVector.build(traces, maxCycles, unitTypeNames(utt)) : null;
        return new EvalResult(matchups, meanMargin, behaviorVector);
    }

    private static MatchupResult playMatchup(GPIndividual individual, UnitTypeTable utt, PhysicalGameState map, int mapIndex,
                                              String opponentName, int maxCycles, int maxInactiveCycles, boolean archiveCase,
                                              List<GPBehaviorTrace> traceSink, long evaluationSeed,
                                              double drawMarginWeight, double lossMarginWeight) throws Exception {
        boolean collectTrace = traceSink != null;
        long matchupSeed = mixSeed(evaluationSeed, opponentName, mapIndex);
        Result r1 = playOneGame(new StructuredGPAI(utt, individual.root), GPOpponents.build(opponentName, utt, matchupSeed),
                map, utt, maxCycles, maxInactiveCycles, 0, false, collectTrace);
        Result r2 = playOneGame(GPOpponents.build(opponentName, utt, matchupSeed ^ 0x9E3779B97F4A7C15L), new StructuredGPAI(utt, individual.root),
                map, utt, maxCycles, maxInactiveCycles, 1, false, collectTrace);
        if (collectTrace) {
            traceSink.add(r1.trace);
            traceSink.add(r2.trace);
        }
        double rawScore = (r1.score + r2.score) / 2.0;
        double shapedScore = (shapeOutcome(r1, drawMarginWeight, lossMarginWeight)
                + shapeOutcome(r2, drawMarginWeight, lossMarginWeight)) / 2.0;
        return new MatchupResult(opponentName, mapIndex, shapedScore, rawScore,
                (r1.margin + r2.margin) / 2.0, archiveCase, r1.score, r2.score,
                (r1.endedByLimit ? 1 : 0) + (r2.endedByLimit ? 1 : 0));
    }

    static double shapeOutcome(Result result, double drawMarginWeight, double lossMarginWeight) {
        if (result.score == 0.0) return shapeLoss(result, lossMarginWeight, drawMarginWeight);
        return shapeTimeoutDraw(result, drawMarginWeight);
    }

    static double shapeTimeoutDraw(Result result, double drawMarginWeight) {
        if (!result.endedByLimit || result.score != 0.5 || drawMarginWeight == 0) {
            return result.score;
        }
        return Math.max(0, Math.min(1, 0.5 + drawMarginWeight * result.margin));
    }

    static double shapeLoss(Result result, double lossMarginWeight, double drawMarginWeight) {
        if (result.crashed || lossMarginWeight <= 0) return result.score;
        double cap = Math.min(lossMarginWeight, Math.max(0, 0.5 - drawMarginWeight));
        if (cap <= 0) return result.score;
        double retained = Math.max(0, Math.min(1, (1 + result.margin) / 2));
        return cap * retained;
    }

    private static long mixSeed(long seed, String opponentName, int mapIndex) {
        long x = seed ^ ((long) opponentName.hashCode() << 32) ^ mapIndex;
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        return x;
    }

    private static List<String> unitTypeNames(UnitTypeTable utt) {
        List<String> names = new ArrayList<>();
        for (UnitType ut : utt.getUnitTypes()) names.add(ut.name);
        return names;
    }

    public static Result playOneGame(AI ai1, AI ai2, PhysicalGameState map, UnitTypeTable utt,
                                      int maxCycles, int maxInactiveCycles, int candidatePlayer) throws Exception {
        return playOneGame(ai1, ai2, map, utt, maxCycles, maxInactiveCycles, candidatePlayer, false, false);
    }

    public static Result playOneGame(AI ai1, AI ai2, PhysicalGameState map, UnitTypeTable utt,
                                      int maxCycles, int maxInactiveCycles, int candidatePlayer, boolean visualize) throws Exception {
        return playOneGame(ai1, ai2, map, utt, maxCycles, maxInactiveCycles, candidatePlayer, visualize, false);
    }

    public static Result playOneGame(AI ai1, AI ai2, PhysicalGameState map, UnitTypeTable utt,
                                     int maxCycles, int maxInactiveCycles, int candidatePlayer,
                                     boolean visualize, int visualDelayMillis) throws Exception {
        return playOneGame(ai1, ai2, map, utt, maxCycles, maxInactiveCycles,
                candidatePlayer, visualize, false, visualDelayMillis);
    }

    public static Result playOneGame(AI ai1, AI ai2, PhysicalGameState map, UnitTypeTable utt,
                                      int maxCycles, int maxInactiveCycles, int candidatePlayer, boolean visualize,
                                      boolean collectTrace) throws Exception {
        return playOneGame(ai1, ai2, map, utt, maxCycles, maxInactiveCycles,
                candidatePlayer, visualize, collectTrace, 1);
    }

    public static Result playOneGame(AI ai1, AI ai2, PhysicalGameState map, UnitTypeTable utt,
                                     int maxCycles, int maxInactiveCycles, int candidatePlayer,
                                     boolean visualize, boolean collectTrace,
                                     int visualDelayMillis) throws Exception {
        ai1.reset(utt);
        ai2.reset(utt);
        GameState gs = new GameState(map.clone(), utt);
        PhysicalGameStateJFrame w = visualize ? PhysicalGameStatePanel.newVisualizer(gs, 600, 600, false) : null;
        GPBehaviorTrace trace = collectTrace ? new GPBehaviorTrace() : null;
        long lastActionTime = 0;
        boolean gameover;
        do {
            PlayerAction pa1, pa2;
            try {
                pa1 = ai1.getAction(0, gs);
                pa2 = ai2.getAction(1, gs);
            } catch (RuntimeException e) {
                reportGameFailure(ai1, ai2, gs, e);
                if (w != null) w.dispose();
                return new Result(0.0, 0.0, trace, gs.getTime(), false, true);
            }
            if (gs.issueSafe(pa1)) lastActionTime = gs.getTime();
            if (gs.issueSafe(pa2)) lastActionTime = gs.getTime();
            if (trace != null) sampleTrace(trace, candidatePlayer == 0 ? pa1 : pa2, gs, candidatePlayer);
            gameover = gs.cycle();
            if (w != null) {
                w.setStateCloning(gs);
                w.repaint();
                Thread.sleep(Math.max(0, visualDelayMillis));
            }
        } while (!gameover && gs.getTime() < maxCycles && gs.getTime() - lastActionTime < maxInactiveCycles);
        if (w != null) w.dispose();
        int winner = gs.winner();
        ai1.gameOver(winner);
        ai2.gameOver(winner);
        int opponentPlayer = 1 - candidatePlayer;
        double margin = materialMargin(gs, candidatePlayer, opponentPlayer);
        if (trace != null) finalizeTrace(trace, gs, candidatePlayer);
        boolean endedByLimit = !gameover;
        if (winner == -1) return new Result(0.5, margin, trace, gs.getTime(), endedByLimit);
        return new Result(winner == candidatePlayer ? 1.0 : 0.0, margin, trace,
                gs.getTime(), endedByLimit);
    }

    private static final java.util.Set<String> reportedFailures =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void reportGameFailure(AI ai1, AI ai2, GameState gs, RuntimeException e) {
        StackTraceElement origin = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
        String signature = ai1.getClass().getSimpleName() + " vs " + ai2.getClass().getSimpleName()
                + " | " + e.getClass().getName() + " @ " + origin;
        if (!reportedFailures.add(signature)) return;
        System.out.println("WARNING: bot threw at cycle " + gs.getTime() + " (" + signature
                + "); scored as a loss for the candidate. Further identical failures are silent.");
        e.printStackTrace(System.out);
    }

    private static void sampleTrace(GPBehaviorTrace trace, PlayerAction candidateAction, GameState gs, int candidatePlayer) {
        int time = (int) gs.getTime();
        if (trace.firstAttackCycle < 0) {
            for (Pair<Unit, UnitAction> p : candidateAction.getActions()) {
                if (p.m_b.getType() == UnitAction.TYPE_ATTACK_LOCATION) {
                    trace.firstAttackCycle = time;
                    break;
                }
            }
        }
        int army = 0, workers = 0, bases = 0;
        for (Unit u : gs.getPhysicalGameState().getUnits()) {
            if (u.getPlayer() != candidatePlayer) continue;
            if (u.getType().canHarvest) workers++;
            else if (u.getType().canAttack) army++;
            if (u.getType().isStockpile) bases++;
        }
        trace.peakArmySize = Math.max(trace.peakArmySize, army);
        trace.peakWorkerCount = Math.max(trace.peakWorkerCount, workers);
        if (trace.firstExpansionCycle < 0 && bases >= 2) trace.firstExpansionCycle = time;
    }

    private static void finalizeTrace(GPBehaviorTrace trace, GameState gs, int candidatePlayer) {
        for (Unit u : gs.getPhysicalGameState().getUnits()) {
            if (u.getPlayer() != candidatePlayer) continue;
            trace.finalComposition.merge(u.getType().name, 1, Integer::sum);
        }
    }

    private static double materialMargin(GameState gs, int candidatePlayer, int opponentPlayer) {
        double mine = materialValue(gs, candidatePlayer);
        double theirs = materialValue(gs, opponentPlayer);
        return (mine - theirs) / (mine + theirs + 1e-6);
    }

    private static double materialValue(GameState gs, int player) {
        double value = gs.getPlayer(player).getResources();
        for (Unit u : gs.getPhysicalGameState().getUnits()) {
            if (u.getPlayer() == player) value += u.getType().cost;
        }
        return value;
    }
}
