package bots;

import gp.Features;
import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import rts.*;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Chimera — M0 reference bot for the structure-based-GP microRTS project.
 *
 * Structure (shared by every later configuration):
 *   for each of my idle units:
 *       enumerate its LEGAL UnitActions        (engine guarantees legality/affordability)
 *       score every candidate action           <-- the ONLY part that changes across configs
 *       pick the highest-scoring action that is resource-consistent with actions already chosen
 *   bundle into one PlayerAction, return it this cycle.
 *
 * How the four configurations reuse this class:
 *   - plain GP  : replace {@link #scoreAction} with an evolved tree eval over {@link Features}.
 *   - SBGP      : same, but the tree's structure is evolved separately from its contents.
 *   - + adapter : take the per-unit score vector (see {@code scores[]} in getAction), softmax it
 *                 with temperature, let the trained adapter add its adjustment, then argmax.
 *                 That score vector is exactly the G2 hook — it exists here already, at M0.
 *
 * At M0 the scorer is a hand-authored economy+rush heuristic. Its job is not to be strong; it is
 * to prove the {@link Features} terminal set can express a bot that plays a complete game.
 */
public class Chimera extends AIWithComputationBudget {

    private final UnitTypeTable utt;

    public Chimera(UnitTypeTable utt) {
        super(100, -1);          // 100 ms/cycle (classic track), iteration budget unused
        this.utt = utt;
    }

    @Override public void reset() {}

    @Override public AI clone() { return new Chimera(utt); }

    @Override public List<ParameterSpecification> getParameters() { return new ArrayList<>(); }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        PlayerAction pa = new PlayerAction();
        if (!gs.canExecuteAnyAction(player)) return pa;

        // Reserve resources already committed by units mid-action (durative actions in flight).
        for (Unit u : pgs.getUnits()) {
            UnitActionAssignment uaa = gs.getActionAssignment(u);
            if (uaa != null) pa.getResourceUsage().merge(uaa.action.resourceUsage(u, pgs));
        }

        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() != player || gs.getActionAssignment(u) != null) continue;

            List<UnitAction> legal = u.getUnitActions(gs);
            if (legal.isEmpty()) continue;

            // ---- score every legal action (this vector is the adapter/G2 hook) ----
            double[] scores = new double[legal.size()];
            for (int i = 0; i < legal.size(); i++)
                scores[i] = scoreAction(u, legal.get(i), gs, player);

            // ---- pick the best action that stays resource-consistent; fall back to NONE ----
            UnitAction none = null;
            for (UnitAction a : legal) if (a.getType() == UnitAction.TYPE_NONE) none = a;

            Integer[] order = argsortDesc(scores);
            boolean placed = false;
            for (int idx : order) {
                UnitAction a = legal.get(idx);
                ResourceUsage ru = a.resourceUsage(u, pgs);
                if (ru.consistentWith(pa.getResourceUsage(), gs)) {
                    pa.getResourceUsage().merge(ru);
                    pa.addUnitAction(u, a);
                    placed = true;
                    break;
                }
            }
            if (!placed && none != null) pa.addUnitAction(u, none);
        }
        return pa;
    }

    /**
     * scoreAction — hand-authored heuristic standing in for the evolved tree.
     * Reads ONLY terminals in {@link Features}, so the GP inherits the same information.
     * Higher = more desirable. Tune freely; this is the piece we tear apart in Phase 3a.
     */
    public double scoreAction(Unit u, UnitAction a, GameState gs, int player) {
        double s = 0;

        // Economy: workers gather and return.
        if (Features.uIsWorker(u) == 1) {
            if (Features.aIsReturn(a) == 1)  s += 8;                       // carrying -> deposit
            if (Features.aIsHarvest(a) == 1) s += 6;                       // free -> mine
        }

        // Attacking is almost always worth it.
        if (Features.aIsAttack(a) == 1) s += 10;

        // Production policy.
        if (Features.aIsProduce(a) == 1) {
            if (Features.aProducesWorker(a) == 1) {
                // keep a small economy, stop flooding workers past a few
                double workers = Features.myWorkerCount(gs, player);
                s += (workers < 3) ? 5 : -2;
            }
            if (Features.aProducesBuilding(a) == 1) {
                // build a barracks once, if we can afford to and have none yet
                s += (Features.myBarracksCount(gs, player) < 1) ? 7 : -3;
            }
            if (Features.aProducesCombat(a) == 1) s += 9;                  // barracks -> army
        }

        // Movement: aim combat units at the nearest enemy; nudge spare workers toward enemy base.
        if (Features.aIsMove(a) == 1) {
            Unit target = Features.nearestEnemy(gs, u, player);
            if (target != null && Features.uIsCombat(u) == 1)
                s += 3 * Features.movesToward(u, a, target.getX(), target.getY());
            else if (Features.uIsWorker(u) == 1 && Features.uCarrying(u) == 0)
                s += 0.5 * Features.movesToward(u, a,
                        enemyBaseX(gs, player, u), enemyBaseY(gs, player, u));
        }

        // NONE is the zero baseline; leave it at 0 so any purposeful action beats idling.
        return s;
    }

    // crude enemy-base coordinates (falls back to unit's own position -> movesToward yields 0)
    private int enemyBaseX(GameState gs, int player, Unit u) {
        for (Unit o : gs.getPhysicalGameState().getUnits())
            if (o.getPlayer() == 1 - player && o.getType().name.equals("Base")) return o.getX();
        return u.getX();
    }
    private int enemyBaseY(GameState gs, int player, Unit u) {
        for (Unit o : gs.getPhysicalGameState().getUnits())
            if (o.getPlayer() == 1 - player && o.getType().name.equals("Base")) return o.getY();
        return u.getY();
    }

    private static Integer[] argsortDesc(double[] v) {
        Integer[] idx = new Integer[v.length];
        for (int i = 0; i < v.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(v[y], v[x]));
        return idx;
    }
}
