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
    public static double distNearestEnemy(GameState gs, Unit u, int player) {
        return distNearest(gs, u, 1 - player, "any");
    }
    public static double distNearestResource(GameState gs, Unit u) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        int best = pgs.getWidth() + pgs.getHeight();
        for (Unit o : pgs.getUnits())
            if (o.getType().isResource) best = Math.min(best, manhattan(u, o));
        return best;
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
     * terminal that reads the action's DIRECTION to look one step ahead.
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
        int best = pgs.getWidth() + pgs.getHeight();
        for (Unit o : pgs.getUnits())
            if (o.getPlayer() == player && o.getType().name.equals(name)) best = Math.min(best, manhattan(u, o));
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
