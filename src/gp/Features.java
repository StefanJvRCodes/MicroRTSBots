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
     */
    public static double movesToward(Unit u, UnitAction a, int tx, int ty) {
        if (a.getType() != UnitAction.TYPE_MOVE) return 0;
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

    // ---- helpers ----
    private static int manhattan(Unit a, Unit b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }
    private static double count(GameState gs, int player, String kind) {
        int n = 0;
        for (Unit u : gs.getPhysicalGameState().getUnits()) {
            if (u.getPlayer() != player) continue;
            switch (kind) {
                case "worker":   if (u.getType().canHarvest) n++; break;
                case "barracks": if (u.getType().name.equals("Barracks")) n++; break;
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
