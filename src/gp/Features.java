package gp;

import rts.*;
import rts.units.Unit;
import rts.units.UnitType;
import java.util.List;

/**
 * Features — the TERMINAL SET for Chimera.
 *
 * Every value the decision logic is allowed to read about a (unit, candidate action, state)
 * triple lives here as one static method. The hand-authored Chimera scorer combines these by
 * hand; the evolved GP bot will combine these exact same terminals with a function set
 * (+, -, *, protected /, IF-greater, min, max, ...). Keeping the vocabulary in one place is
 * what lets the M0 milestone actually prove the primitive set is expressive enough BEFORE we
 * trust evolution — and lets the plain-GP bot be "Chimera with score() swapped for a tree".
 *
 * Design rule: a terminal returns a double and NEVER mutates state. Predicates return 0.0/1.0.
 * Distances are Manhattan (cheap; no pathfinding at M0 — pathfinding is a later primitive).
 *
 * NO STATIC MUTABLE STATE, DELIBERATELY. Every method here reads only its arguments, which is
 * what makes raising ECJ's evalthreads above 1 safe: N evaluation threads can call into this
 * class concurrently with no interference. Do not add a cache or a memo field here without
 * making it thread-local.
 *
 * ---------------------------------------------------------------------------------------
 * 2026-09 ADDITION: the movesToward* family. READ THIS BEFORE EDITING THE TERMINAL SET.
 *
 * Until now, every terminal exposed to the GP returned the SAME value for MOVE-up,
 * MOVE-right, MOVE-down and MOVE-left. The action-type predicates cannot separate two moves
 * from each other, and the spatial terminals below (distNearestEnemy, distNearestResource,
 * distEnemyBase) take no UnitAction at all — they describe the unit's current position, not
 * where an action would take it. The consequence is that an evolved tree scored all four
 * moves identically and EvolvedBot's argmax fell through to getUnitActions() list order:
 * movement direction was never under evolutionary control, and the bot could only win
 * reactively, by attacking whatever walked into range.
 *
 * movesToward() has read the action's direction since M0, but takes target COORDINATES, so
 * its two int parameters cannot be resolved from ScoreData and gp.nodes.FeatureNode
 * correctly rejects it as a terminal. The movesToward* wrappers below pick the target from
 * the game state, leaving a single int (the player id), which FeatureNode binds by type with
 * no change to that class.
 *
 * ---------------------------------------------------------------------------------------
 * 2026-10 ADDITION: NON-MOVE DISCRIMINATION. READ THIS TOO.
 *
 * The pruned 17-node best-of-run showed every non-MOVE action scoring identically, so the
 * choice among them fell to getUnitActions() list order. That order is fixed by the engine
 * (rts.units.Unit.getUnitActions, checked against the source):
 *
 *     ATTACK (up, right, down, left, then any in range)  >  HARVEST  >  RETURN
 *       >  PRODUCE (in UnitTypeTable order, each UP/RIGHT/DOWN/LEFT)  >  MOVE  >  NONE
 *
 * and argmax keeps the FIRST maximum. So the evolved bot's "always attack when possible"
 * and "base always trains a worker, placed UP if free" were the ENGINE'S priority list, not
 * evolved behaviour. Likewise a barracks with all-tied scores always trains Light (Light is
 * produced first in UnitTypeTable), and a worker with all-tied produce options always prefers
 * a Base over Barracks. Any write-up claim about what the bot "learned" must account for this.
 *
 * The new terminals each vary across the candidate actions they describe:
 *   - produce TARGET split by type (Light / Heavy / Ranged / Barracks / Base);
 *   - produce PLACEMENT (which side of the producer the new unit appears on);
 *   - attack TARGET properties (wounded, killable this hit, worker / combat / building);
 *   - threat at the cell the unit will occupy AFTER the action (moves vs staying put);
 *   - major-axis variants of the direction terminals, which break the diagonal tie
 *     symmetrically (see majorAxisToward).
 * plus a few CONTEXT terminals (army counts, enemies near my base, game time). Context
 * terminals are constant across a unit's actions, so on their own argmax eats them — they
 * matter only as gates inside if> or as multipliers of an action-discriminating term.
 *
 * EVERY EXISTING METHOD IS UNCHANGED IN BEHAVIOUR. movesToward was refactored to share
 * stepToward with the placement terminals, with identical results, so archived .ind files
 * and their best-*.params snapshots still load and play exactly as they did.
 * ---------------------------------------------------------------------------------------
 */
public final class Features {

    private Features() {}

    // ---- action-type predicates (one-hot over UnitAction.TYPE_*) ----
    public static double aIsNone(UnitAction a)    { return a.getType() == UnitAction.TYPE_NONE            ? 1 : 0; }
    public static double aIsMove(UnitAction a)    { return a.getType() == UnitAction.TYPE_MOVE            ? 1 : 0; }
    public static double aIsHarvest(UnitAction a) { return a.getType() == UnitAction.TYPE_HARVEST         ? 1 : 0; }
    public static double aIsReturn(UnitAction a)  { return a.getType() == UnitAction.TYPE_RETURN          ? 1 : 0; }
    public static double aIsProduce(UnitAction a) { return a.getType() == UnitAction.TYPE_PRODUCE         ? 1 : 0; }
    public static double aIsAttack(UnitAction a)  { return a.getType() == UnitAction.TYPE_ATTACK_LOCATION ? 1 : 0; }

    // ---- what a PRODUCE action would build (0 for non-produce actions) ----
    public static double aProducesWorker(UnitAction a) {
        UnitType t = a.getUnitType();
        return t != null && t.name.equals("Worker") ? 1 : 0;
    }
    public static double aProducesCombat(UnitAction a) {
        UnitType t = a.getUnitType();
        return t != null && (t.name.equals("Light") || t.name.equals("Heavy") || t.name.equals("Ranged")) ? 1 : 0;
    }
    public static double aProducesBuilding(UnitAction a) {
        UnitType t = a.getUnitType();
        return t != null && (t.name.equals("Base") || t.name.equals("Barracks")) ? 1 : 0;
    }

    // ---- 2026-10: produce target split by type ----
    //
    // aProducesCombat cannot tell Light from Ranged, so with all-tied scores a barracks
    // always trains Light (first in UnitTypeTable). These let army COMPOSITION evolve.
    public static double aProducesLight(UnitAction a)    { return producesNamed(a, "Light"); }
    public static double aProducesHeavy(UnitAction a)    { return producesNamed(a, "Heavy"); }
    public static double aProducesRanged(UnitAction a)   { return producesNamed(a, "Ranged"); }
    public static double aProducesBarracks(UnitAction a) { return producesNamed(a, "Barracks"); }
    public static double aProducesBase(UnitAction a)     { return producesNamed(a, "Base"); }

    // ---- acting unit's identity / condition ----
    public static double uIsWorker(Unit u)   { return u.getType().canHarvest ? 1 : 0; }
    public static double uIsBase(Unit u)     { return u.getType().name.equals("Base") ? 1 : 0; }
    public static double uIsBarracks(Unit u) { return u.getType().name.equals("Barracks") ? 1 : 0; }
    public static double uIsCombat(Unit u) {
        UnitType t = u.getType();
        return (t.canAttack && !t.canHarvest && t.canMove) ? 1 : 0;   // Light/Heavy/Ranged
    }
    public static double uCarrying(Unit u)   { return u.getResources() > 0 ? 1 : 0; }
    public static double uHpFrac(Unit u)     { return u.getType().hp == 0 ? 0 : (double) u.getHitPoints() / u.getType().hp; }

    // ---- player economy / army-size context ----
    public static double myResources(GameState gs, int player) {
        return gs.getPhysicalGameState().getPlayer(player).getResources();
    }
    public static double myWorkerCount(GameState gs, int player) { return count(gs, player, "worker"); }
    public static double myUnitCount(GameState gs, int player)   { return count(gs, player, "any"); }
    public static double enemyUnitCount(GameState gs, int player){ return count(gs, 1 - player, "any"); }
    public static double myBarracksCount(GameState gs, int player){ return count(gs, player, "barracks"); }

    // ---- 2026-10: context (constant per unit — GATES ONLY, see class comment) ----
    public static double myCombatCount(GameState gs, int player)    { return count(gs, player, "combat"); }
    public static double enemyCombatCount(GameState gs, int player) { return count(gs, 1 - player, "combat"); }
    public static double enemyWorkerCount(GameState gs, int player) { return count(gs, 1 - player, "worker"); }

    /**
     * Number of enemy units within Manhattan distance 4 of any of my bases. The WorkerRush
     * signal: a rush shows up here many cycles before it shows up as an attack option.
     */
    public static double enemiesNearMyBase(GameState gs, int player) {
        List<Unit> units = gs.getPhysicalGameState().getUnits();
        int n = 0;
        for (Unit e : units) {
            if (e.getPlayer() != 1 - player) continue;
            for (Unit b : units) {
                if (b.getPlayer() == player && b.getType().name.equals("Base") && manhattan(e, b) <= 4) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** Game time in thousands of cycles, so early- and late-game behaviour can differ. */
    public static double gameTimeK(GameState gs) { return gs.getTime() / 1000.0; }

    // ---- spatial terminals (Manhattan; a "far" default when no target exists) ----
    //
    // NOTE: none of these four takes a UnitAction, so each returns one value per UNIT, not
    // per candidate action. They let a tree GATE on position ("am I near the enemy base?")
    // but they cannot STEER — under argmax, a term identical across all of a unit's actions
    // is invisible. Steering is the movesToward* family below.

    public static double distNearestEnemy(GameState gs, Unit u, int player) {
        return distNearest(gs, u, 1 - player, "any");
    }
    public static double distNearestResource(GameState gs, Unit u) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        int far = pgs.getWidth() + pgs.getHeight();
        Unit r = nearestResourceUnit(gs, u);
        return r == null ? far : Math.min(manhattan(u, r), far);
    }
    public static double distEnemyBase(GameState gs, Unit u, int player) {
        return distNearestNamed(gs, u, 1 - player, "Base");
    }
    public static double mapArea(GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        return pgs.getWidth() * pgs.getHeight();
    }

    /**
     * movesToward: for a MOVE action, +1 if the step reduces Manhattan distance to (tx,ty),
     * -1 if it increases it, 0 otherwise or if the action is not a MOVE. This is the only
     * primitive that reads the action's DIRECTION to look one step ahead.
     *
     * NOT A TERMINAL: two int parameters cannot be resolved from ScoreData. Reachable by
     * the GP only through the movesToward* wrappers below, and directly by hand-written
     * scorers such as Chimera.
     *
     * 2026-10: body moved into stepToward, unchanged in behaviour.
     */
    public static double movesToward(Unit u, UnitAction a, int tx, int ty) {
        if (a.getType() != UnitAction.TYPE_MOVE) return 0;
        return stepToward(u, a, tx, ty);
    }

    // ---- direction-aware terminals: the ONLY primitives that separate one MOVE from another ----
    //
    // Each returns +1 / 0 / -1 and is 0 for every non-MOVE action, so a tree can weight them
    // freely without disturbing the scoring of harvest, return, produce or attack.
    //
    // All four bind cleanly in gp.nodes.FeatureNode: distinct types plus at most one int.

    /** +1 if this MOVE steps closer to the nearest enemy unit, -1 further, 0 otherwise. */
    public static double movesTowardEnemy(GameState gs, Unit u, UnitAction a, int player) {
        Unit e = nearestEnemy(gs, u, player);
        return e == null ? 0 : movesToward(u, a, e.getX(), e.getY());
    }

    /**
     * +1 if this MOVE steps closer to the nearest enemy Base.
     * This is the capability needed to defeat a passive opponent: a stationary target that
     * distNearestEnemy can measure but cannot navigate to.
     */
    public static double movesTowardEnemyBase(GameState gs, Unit u, UnitAction a, int player) {
        Unit b = nearestNamed(gs, u, 1 - player, "Base");
        return b == null ? 0 : movesToward(u, a, b.getX(), b.getY());
    }

    /** +1 if this MOVE steps closer to our own Base — the return-with-resources leg. */
    public static double movesTowardMyBase(GameState gs, Unit u, UnitAction a, int player) {
        Unit b = nearestNamed(gs, u, player, "Base");
        return b == null ? 0 : movesToward(u, a, b.getX(), b.getY());
    }

    /** +1 if this MOVE steps closer to the nearest resource patch — the harvest leg. */
    public static double movesTowardResource(GameState gs, Unit u, UnitAction a) {
        Unit r = nearestResourceUnit(gs, u);
        return r == null ? 0 : movesToward(u, a, r.getX(), r.getY());
    }

    // ---- 2026-10: major-axis direction terminals (symmetric diagonal tie-break) ----
    //
    // When the target is diagonal, TWO moves reduce Manhattan distance and movesToward*
    // scores both +1. The tie then falls to list order, UP before RIGHT before DOWN before
    // LEFT. On basesWorkers8x8 — which is 180-degree rotationally symmetric, checked —
    // player 0 heads down-right and resolves the tie as RIGHT (horizontal), while player 1
    // heads up-left and resolves it as UP (vertical). The two sides walk non-mirrored paths
    // from a mirrored start. That is a candidate explanation for the WorkerRush side split
    // (wins as one player, loses as the other); watch both sides to confirm or kill it.
    //
    // These return +1 only for the move along the axis with the LARGER remaining gap, and
    // on an exact diagonal prefer HORIZONTAL. Both rules commute with a 180-degree rotation,
    // so mirrored starts produce mirrored paths. (They do NOT commute with a diagonal
    // reflection; a map symmetric only that way would need the opposite convention.)

    /** +1 if this MOVE closes the larger axis gap to the nearest enemy unit. */
    public static double movesTowardEnemyMajor(GameState gs, Unit u, UnitAction a, int player) {
        Unit e = nearestEnemy(gs, u, player);
        return e == null ? 0 : majorAxisToward(u, a, e.getX(), e.getY());
    }

    /** +1 if this MOVE closes the larger axis gap to the nearest enemy Base. */
    public static double movesTowardEnemyBaseMajor(GameState gs, Unit u, UnitAction a, int player) {
        Unit b = nearestNamed(gs, u, 1 - player, "Base");
        return b == null ? 0 : majorAxisToward(u, a, b.getX(), b.getY());
    }

    // ---- 2026-10: produce PLACEMENT ----
    //
    // A PRODUCE action has a direction too, and all four placements of the same unit type
    // used to tie — the new unit always appeared UP if that cell was free. These are +1 if
    // the new unit appears on the side of the producer nearer the target, -1 if farther,
    // 0 for non-produce actions. Barracks placement and "spawn workers on the resource side"
    // both live here.

    public static double placesTowardEnemy(GameState gs, Unit u, UnitAction a, int player) {
        if (a.getType() != UnitAction.TYPE_PRODUCE) return 0;
        Unit e = nearestEnemy(gs, u, player);
        return e == null ? 0 : stepToward(u, a, e.getX(), e.getY());
    }

    public static double placesTowardResource(GameState gs, Unit u, UnitAction a) {
        if (a.getType() != UnitAction.TYPE_PRODUCE) return 0;
        Unit r = nearestResourceUnit(gs, u);
        return r == null ? 0 : stepToward(u, a, r.getX(), r.getY());
    }

    // ---- 2026-10: attack TARGET ----
    //
    // Several ATTACK actions can be legal at once (one per adjacent or in-range enemy), and
    // until now they all tied and the engine's up/right/down/left order picked the target.
    // Each returns 0 for non-attack actions.

    /** 1 - target HP fraction: higher for more damaged targets. Focus fire. */
    public static double tgtWounded(GameState gs, UnitAction a) {
        Unit t = attackTarget(gs, a);
        if (t == null || t.getType().hp == 0) return 0;
        return 1.0 - (double) t.getHitPoints() / t.getType().hp;
    }

    /** 1 if this attack is guaranteed to kill its target (target HP <= my minimum damage). */
    public static double tgtKillable(GameState gs, Unit u, UnitAction a) {
        Unit t = attackTarget(gs, a);
        return t != null && t.getHitPoints() <= u.getType().minDamage ? 1 : 0;
    }

    public static double tgtIsWorker(GameState gs, UnitAction a) {
        Unit t = attackTarget(gs, a);
        return t != null && t.getType().canHarvest ? 1 : 0;
    }

    public static double tgtIsCombat(GameState gs, UnitAction a) {
        Unit t = attackTarget(gs, a);
        if (t == null) return 0;
        UnitType ty = t.getType();
        return ty.canAttack && !ty.canHarvest && ty.canMove ? 1 : 0;
    }

    public static double tgtIsBuilding(GameState gs, UnitAction a) {
        Unit t = attackTarget(gs, a);
        if (t == null) return 0;
        String n = t.getType().name;
        return n.equals("Base") || n.equals("Barracks") ? 1 : 0;
    }

    // ---- 2026-10: threat at the destination ----

    /**
     * Number of enemy units that could hit the cell this unit will occupy AFTER the action:
     * the destination for a MOVE, the current cell for anything else. An enemy counts if the
     * cell is within its attack range plus one step (if it can move). Squared Euclidean, to
     * match how the engine checks attack range.
     *
     * Unlike most new terminals this is NOT zero for non-moves — it compares "step there"
     * against "stay here", which is exactly the choice a unit under a WorkerRush faces.
     * Enemy workers can attack, so they count.
     */
    public static double threatAtDest(GameState gs, Unit u, UnitAction a, int player) {
        int x = u.getX(), y = u.getY();
        if (a.getType() == UnitAction.TYPE_MOVE && a.getDirection() != UnitAction.DIRECTION_NONE) {
            x += UnitAction.DIRECTION_OFFSET_X[a.getDirection()];
            y += UnitAction.DIRECTION_OFFSET_Y[a.getDirection()];
        }
        int n = 0;
        for (Unit e : gs.getPhysicalGameState().getUnits()) {
            if (e.getPlayer() != 1 - player || !e.getType().canAttack) continue;
            int reach = e.getType().attackRange + (e.getType().canMove ? 1 : 0);
            int dx = e.getX() - x, dy = e.getY() - y;
            if (dx * dx + dy * dy <= reach * reach) n++;
        }
        return n;
    }

    // ---- helpers ----
    private static int manhattan(Unit a, Unit b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }

    private static double producesNamed(UnitAction a, String name) {
        UnitType t = a.getUnitType();
        return t != null && t.name.equals(name) ? 1 : 0;
    }

    /** The unit at an ATTACK_LOCATION action's target cell, or null for any other action. */
    private static Unit attackTarget(GameState gs, UnitAction a) {
        if (a.getType() != UnitAction.TYPE_ATTACK_LOCATION) return null;
        return gs.getPhysicalGameState().getUnitAt(a.getLocationX(), a.getLocationY());
    }

    /**
     * +1 if the cell in the action's direction is closer to (tx,ty) than the unit's own cell,
     * -1 if farther, 0 if equal or the action has no direction. Does NOT check action type:
     * callers decide which action types it applies to (MOVE for movesToward, PRODUCE for the
     * placement terminals).
     */
    private static double stepToward(Unit u, UnitAction a, int tx, int ty) {
        int nx = u.getX(), ny = u.getY();
        switch (a.getDirection()) {
            case UnitAction.DIRECTION_UP:    ny--; break;
            case UnitAction.DIRECTION_RIGHT: nx++; break;
            case UnitAction.DIRECTION_DOWN:  ny++; break;
            case UnitAction.DIRECTION_LEFT:  nx--; break;
            default: return 0;
        }
        int before = Math.abs(u.getX() - tx) + Math.abs(u.getY() - ty);
        int after  = Math.abs(nx - tx) + Math.abs(ny - ty);
        return Integer.compare(before, after); // 1 if closer, -1 if farther, 0 if equal
    }

    /** See the major-axis block above. MOVE only; +1 or 0. */
    private static double majorAxisToward(Unit u, UnitAction a, int tx, int ty) {
        if (a.getType() != UnitAction.TYPE_MOVE) return 0;
        int dir = a.getDirection();
        if (dir == UnitAction.DIRECTION_NONE) return 0;
        boolean horizontal = dir == UnitAction.DIRECTION_RIGHT || dir == UnitAction.DIRECTION_LEFT;
        int step  = horizontal ? UnitAction.DIRECTION_OFFSET_X[dir] : UnitAction.DIRECTION_OFFSET_Y[dir];
        int gap   = horizontal ? tx - u.getX() : ty - u.getY();
        int other = horizontal ? ty - u.getY() : tx - u.getX();
        if (Integer.signum(gap) != step) return 0;          // not closing this axis
        int ag = Math.abs(gap), ao = Math.abs(other);
        if (ag > ao) return 1;
        return (ag == ao && horizontal) ? 1 : 0;            // exact diagonal: horizontal wins
    }

    private static double count(GameState gs, int player, String kind) {
        int n = 0;
        for (Unit u : gs.getPhysicalGameState().getUnits()) {
            if (u.getPlayer() != player) continue;
            UnitType t = u.getType();
            switch (kind) {
                case "worker":   if (t.canHarvest) n++; break;
                case "barracks": if (t.name.equals("Barracks")) n++; break;
                case "combat":   if (t.canAttack && !t.canHarvest && t.canMove) n++; break;
                default:         n++;
            }
        }
        return n;
    }
    private static double distNearest(GameState gs, Unit u, int player, String kind) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        int best = pgs.getWidth() + pgs.getHeight();
        boolean found = false;
        for (Unit o : pgs.getUnits()) {
            if (o.getPlayer() != player) continue;
            best = Math.min(best, manhattan(u, o));
            found = true;
        }
        return found ? best : pgs.getWidth() + pgs.getHeight();
    }
    private static double distNearestNamed(GameState gs, Unit u, int player, String name) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        int far = pgs.getWidth() + pgs.getHeight();
        Unit o = nearestNamed(gs, u, player, name);
        return o == null ? far : Math.min(manhattan(u, o), far);
    }

    /** Nearest unit belonging to `player` with the given type name, or null. */
    private static Unit nearestNamed(GameState gs, Unit u, int player, String name) {
        Unit best = null;
        int bd = Integer.MAX_VALUE;
        for (Unit o : gs.getPhysicalGameState().getUnits()) {
            if (o.getPlayer() != player || !o.getType().name.equals(name)) continue;
            int d = manhattan(u, o);
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    /** Nearest resource patch to u, or null. Resource units belong to no player. */
    private static Unit nearestResourceUnit(GameState gs, Unit u) {
        Unit best = null;
        int bd = Integer.MAX_VALUE;
        for (Unit o : gs.getPhysicalGameState().getUnits()) {
            if (!o.getType().isResource) continue;
            int d = manhattan(u, o);
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    /** Nearest enemy unit to u (or null). Used by the scorer to aim MOVE steps. */
    public static Unit nearestEnemy(GameState gs, Unit u, int player) {
        Unit best = null; int bd = Integer.MAX_VALUE;
        for (Unit o : gs.getPhysicalGameState().getUnits()) {
            if (o.getPlayer() != 1 - player) continue;
            int d = manhattan(u, o);
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }
}
