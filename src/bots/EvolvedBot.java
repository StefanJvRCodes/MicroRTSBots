package bots;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import ec.EvolutionState;
import ec.gp.ADFStack;
import ec.gp.GPIndividual;
import ec.gp.GPProblem;
import gp.MicroRTSProblem;
import gp.ScoreData;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.ResourceUsage;
import rts.UnitAction;
import rts.UnitActionAssignment;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chimera's getAction loop with ONE thing changed: scoreAction is the evolved tree.
 *
 * Everything structural is identical to the hand-authored bot -- the durative-action
 * reservation pass, the per-unit enumeration of legal actions, the resource-consistency
 * check, the argmax, the NONE fallback. That is the whole point of the score-then-argmax
 * representation: the GP swaps out a function, not an architecture.
 *
 * THREADING: one EvolvedBot instance belongs to one evaluation thread. ECJ clones the
 * Problem per eval thread, so each thread must build its own bot with its own ScoreData.
 * Sharing one across threads will corrupt the evaluation context.
 *
 * ------------------------------------------------------------------------------------
 * 2026-10: ROLE TREES. If the individual has 4 or more trees, each unit is scored by the
 * tree for its role: 0 worker, 1 base, 2 barracks, 3 combat. With 1 tree, every unit uses
 * trees[0], exactly as before -- so every archived single-tree .ind still plays
 * identically. The tree count comes from the params file
 * (pop.subpop.0.species.ind.numtrees), never from this class. Apply the same choice to
 * BOTH ordinary GP and SBGP, or the comparison is confounded.
 *
 * 2026-10: TIE COUNTERS. For every decision with more than one legal action, records
 * whether the top score was shared. A tied decision is decided by getUnitActions() list
 * order, not by the tree, so the tie rate is the fraction of the bot's behaviour that is
 * the ENGINE'S policy rather than an evolved one.
 *
 * 2026-10: NONE DURATION. Legal actions come from getUnitActions(gs, noneDuration), not
 * getUnitActions(gs), whose NONE lasts 10 cycles. See MicroRTSProblem for why this matters.
 *
 * 2026-10-14: ADAPTER HOOK (Phase 5). getAction is split in two:
 *   propose(player, gs) -- exactly the old getAction body, additionally returning one
 *       UnitDecision per idle unit: the unit, its legal list, its scores, and `pick`, the
 *       index of the action the base policy took (-1 = the NONE fallback). `pick` is the
 *       one-hot base action of Adapter-RL (Jin, Slabaugh & Lucas).
 *   commit(gs, player, decisions, chosen, executed) -- builds a PlayerAction from chosen
 *       indices under the same resource rules: per unit, in the same order, the chosen
 *       action if affordable, else the base pick if affordable, else NONE.
 * getAction() is now `return propose(...).action`, so play is unchanged. commit() with
 * chosen == null reproduces propose()'s action exactly (rl.HookTest checks this every
 * cycle). The RL environment and the Java-side adapted bot both go through commit(), so
 * training and evaluation apply actions identically.
 * KNOWN APPROXIMATION: base picks are computed with resources reserved for the BASE picks
 * of earlier units. If the adapter changes an earlier unit's action, a later unit's base
 * pick is not recomputed; commit() only re-checks affordability.
 * ------------------------------------------------------------------------------------
 */
public class EvolvedBot extends AIWithComputationBudget {

    public static final int ROLE_WORKER = 0, ROLE_BASE = 1, ROLE_BARRACKS = 2, ROLE_COMBAT = 3;
    public static final String[] ROLE_NAMES = {"worker", "base", "barracks", "combat"};

    private final UnitTypeTable utt;
    private final GPIndividual individual;
    private final EvolutionState state;
    private final int threadnum;
    private final GPProblem problem;
    private final ScoreData data;
    private final ADFStack stack;

    /** Duration of the NONE action in each unit's legal list (problem's none-duration). */
    private final int noneDuration;

    /** Scores for the most recently considered unit (kept for compatibility). */
    public double[] lastScores = new double[0];

    /** Worst per-cycle decision time seen, in ms. The G1 100 ms gate reads this. */
    public long worstCycleMillis = 0;

    /** Decisions with more than one legal action, per role. */
    public final long[] decisionsByRole = new long[4];
    /** Of those, decisions where the top score was shared (list order decided). */
    public final long[] tiesByRole = new long[4];
    /** Of the ties, those where the tied actions are of DIFFERENT TYPES. */
    public final long[] crossTypeTiesByRole = new long[4];

    /** One idle unit's decision as the base policy sees it. */
    public static final class UnitDecision {
        public final Unit unit;
        public final int role;
        public final List<UnitAction> legal;
        public final double[] scores;
        /** Index into legal of the action the base policy took; -1 = NONE fallback. */
        public final int pick;

        UnitDecision(Unit unit, int role, List<UnitAction> legal, double[] scores, int pick) {
            this.unit = unit;
            this.role = role;
            this.legal = legal;
            this.scores = scores;
            this.pick = pick;
        }
    }

    /** The base policy's PlayerAction plus the per-unit decisions behind it. */
    public static final class Proposal {
        public final PlayerAction action;
        public final List<UnitDecision> decisions;

        Proposal(PlayerAction action, List<UnitDecision> decisions) {
            this.action = action;
            this.decisions = decisions;
        }
    }

    public EvolvedBot(UnitTypeTable utt, GPIndividual individual, EvolutionState state,
                      int threadnum, GPProblem problem, ScoreData data, ADFStack stack) {
        super(100, -1);
        this.utt = utt;
        this.individual = individual;
        this.state = state;
        this.threadnum = threadnum;
        this.problem = problem;
        this.data = data;
        this.stack = stack;
        this.noneDuration = noneDurationOf(problem);
    }

    /** The NONE duration a given problem specifies; 10 (engine default) if unspecified. */
    public static int noneDurationOf(GPProblem problem) {
        return (problem instanceof MicroRTSProblem) ? ((MicroRTSProblem) problem).noneDuration : 10;
    }

    /** NONE duration this bot uses; the RL environment needs the same legal lists. */
    public int noneDuration() {
        return noneDuration;
    }

    /** Role of a unit for tree dispatch and tie statistics. */
    public static int roleOf(Unit u) {
        if (u.getType().canHarvest) return ROLE_WORKER;
        String n = u.getType().name;
        if (n.equals("Base")) return ROLE_BASE;
        if (n.equals("Barracks")) return ROLE_BARRACKS;
        return ROLE_COMBAT;
    }

    /** Evaluate the evolved tree for one candidate action. */
    private double scoreAction(Unit u, UnitAction a, GameState gs, int player, int tree) {
        data.set(u, a, gs, player);
        data.score = 0.0;
        individual.trees[tree].child.eval(state, threadnum, data, stack, individual, problem);
        double s = data.score;
        return (Double.isNaN(s)) ? Double.NEGATIVE_INFINITY : s;
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) throws Exception {
        return propose(player, gs).action;
    }

    /** The old getAction body, unchanged, plus a record of every per-unit decision. */
    public Proposal propose(int player, GameState gs) {
        long start = System.currentTimeMillis();

        PhysicalGameState pgs = gs.getPhysicalGameState();
        PlayerAction pa = new PlayerAction();
        List<UnitDecision> decisions = new ArrayList<>();

        if (!gs.canExecuteAnyAction(player)) return new Proposal(pa, Collections.emptyList());

        boolean roleTrees = individual.trees.length >= 4;

        // 1. Reserve resources already committed to in-flight durative actions.
        for (Unit u : pgs.getUnits()) {
            UnitActionAssignment uaa = gs.getActionAssignment(u);
            if (uaa != null) {
                pa.getResourceUsage().merge(uaa.action.resourceUsage(u, pgs));
            }
        }

        // 2. For each idle unit of ours, score every legal action and take the best
        //    one that is resource-consistent with what we have already committed.
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() != player || gs.getActionAssignment(u) != null) continue;

            List<UnitAction> legal = u.getUnitActions(gs, noneDuration);
            if (legal.isEmpty()) continue;

            int role = roleOf(u);
            int tree = roleTrees ? role : 0;

            double[] scores = new double[legal.size()];
            for (int i = 0; i < legal.size(); i++) {
                scores[i] = scoreAction(u, legal.get(i), gs, player, tree);
            }
            lastScores = scores;

            if (legal.size() > 1) {
                double top = Double.NEGATIVE_INFINITY;
                for (double s : scores) if (s > top) top = s;
                int atTop = 0, firstType = -1;
                boolean crossType = false;
                for (int i = 0; i < scores.length; i++) {
                    if (scores[i] != top) continue;
                    atTop++;
                    int ty = legal.get(i).getType();
                    if (firstType == -1) firstType = ty;
                    else if (ty != firstType) crossType = true;
                }
                decisionsByRole[role]++;
                if (atTop > 1) tiesByRole[role]++;
                if (crossType) crossTypeTiesByRole[role]++;
            }

            // Walk candidates best-first; the top choice may be unaffordable given
            // what other units already claimed this cycle.
            int pick = -1;
            boolean[] used = new boolean[legal.size()];
            for (int attempt = 0; attempt < legal.size() && pick < 0; attempt++) {
                int best = -1;
                for (int i = 0; i < legal.size(); i++) {
                    if (!used[i] && (best == -1 || scores[i] > scores[best])) best = i;
                }
                if (best == -1) break;
                used[best] = true;

                UnitAction ua = legal.get(best);
                ResourceUsage ru = ua.resourceUsage(u, pgs);
                if (ru.consistentWith(pa.getResourceUsage(), gs)) {
                    pa.getResourceUsage().merge(ru);
                    pa.addUnitAction(u, ua);
                    pick = best;
                }
            }
            if (pick < 0) {
                pa.addUnitAction(u, new UnitAction(UnitAction.TYPE_NONE));
            }
            decisions.add(new UnitDecision(u, role, legal, scores, pick));
        }

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > worstCycleMillis) worstCycleMillis = elapsed;

        return new Proposal(pa, decisions);
    }

    /**
     * Build a PlayerAction from chosen indices (one per decision, into that unit's legal
     * list; null = the base picks). Per unit, in order: chosen if affordable, else the
     * base pick if affordable, else NONE. If `executed` is non-null, executed[k] receives
     * the index actually applied for decision k (-1 = NONE fallback).
     */
    public static PlayerAction commit(GameState gs, int player, List<UnitDecision> decisions,
                                      int[] chosen, int[] executed) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        PlayerAction pa = new PlayerAction();
        if (decisions.isEmpty()) return pa;

        for (Unit u : pgs.getUnits()) {
            UnitActionAssignment uaa = gs.getActionAssignment(u);
            if (uaa != null) pa.getResourceUsage().merge(uaa.action.resourceUsage(u, pgs));
        }

        for (int k = 0; k < decisions.size(); k++) {
            UnitDecision d = decisions.get(k);
            int want = (chosen == null) ? d.pick : chosen[k];
            int done = -1;
            int[] order = (want == d.pick) ? new int[] {want} : new int[] {want, d.pick};
            for (int idx : order) {
                if (idx < 0 || idx >= d.legal.size()) continue;
                UnitAction ua = d.legal.get(idx);
                ResourceUsage ru = ua.resourceUsage(d.unit, pgs);
                if (ru.consistentWith(pa.getResourceUsage(), gs)) {
                    pa.getResourceUsage().merge(ru);
                    pa.addUnitAction(d.unit, ua);
                    done = idx;
                    break;
                }
            }
            if (done < 0) pa.addUnitAction(d.unit, new UnitAction(UnitAction.TYPE_NONE));
            if (executed != null) executed[k] = done;
        }
        return pa;
    }

    @Override
    public void reset() {
        lastScores = new double[0];
    }

    @Override
    public AI clone() {
        return new EvolvedBot(utt, individual, state, threadnum, problem, data, stack);
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return new ArrayList<>();
    }
}