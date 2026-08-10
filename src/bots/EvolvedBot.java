package bots;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import ec.EvolutionState;
import ec.gp.ADFStack;
import ec.gp.GPIndividual;
import ec.gp.GPProblem;
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
import java.util.List;

/**
 * Chimera's getAction loop with ONE thing changed: scoreAction is the evolved tree.
 *
 * Everything structural is identical to the hand-authored bot -- the durative-action
 * reservation pass, the per-unit enumeration of legal actions, the resource-consistency
 * check, the argmax, the NONE fallback. That is the whole point of the score-then-argmax
 * representation: the GP swaps out a function, not an architecture.
 *
 * The per-unit scores[] array is still built and still exposed, so the G2 adapter hook
 * survives the swap intact.
 *
 * THREADING: one EvolvedBot instance belongs to one evaluation thread. ECJ clones the
 * Problem per eval thread, so each thread must build its own bot with its own ScoreData.
 * Sharing one across threads will corrupt the evaluation context.
 */
public class EvolvedBot extends AIWithComputationBudget {

    private final UnitTypeTable utt;
    private final GPIndividual individual;
    private final EvolutionState state;
    private final int threadnum;
    private final GPProblem problem;
    private final ScoreData data;
    private final ADFStack stack;

    /** Scores for the most recently considered unit -- the adapter/G2 hook. */
    public double[] lastScores = new double[0];

    /** Worst per-cycle decision time seen, in ms. The G1 100 ms gate reads this. */
    public long worstCycleMillis = 0;

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
    }

    /** Evaluate the evolved tree for one candidate action. */
    private double scoreAction(Unit u, UnitAction a, GameState gs, int player) {
        data.set(u, a, gs, player);
        data.score = 0.0;
        individual.trees[0].child.eval(state, threadnum, data, stack, individual, problem);
        double s = data.score;
        return (Double.isNaN(s)) ? Double.NEGATIVE_INFINITY : s;
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) throws Exception {
        long start = System.currentTimeMillis();

        PhysicalGameState pgs = gs.getPhysicalGameState();
        PlayerAction pa = new PlayerAction();

        if (!gs.canExecuteAnyAction(player)) return pa;

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

            List<UnitAction> legal = u.getUnitActions(gs);
            if (legal.isEmpty()) continue;

            double[] scores = new double[legal.size()];
            for (int i = 0; i < legal.size(); i++) {
                scores[i] = scoreAction(u, legal.get(i), gs, player);
            }
            lastScores = scores;

            // Walk candidates best-first; the top choice may be unaffordable given
            // what other units already claimed this cycle.
            boolean assigned = false;
            boolean[] used = new boolean[legal.size()];
            for (int attempt = 0; attempt < legal.size() && !assigned; attempt++) {
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
                    assigned = true;
                }
            }
            if (!assigned) {
                pa.addUnitAction(u, new UnitAction(UnitAction.TYPE_NONE));
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > worstCycleMillis) worstCycleMillis = elapsed;

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
