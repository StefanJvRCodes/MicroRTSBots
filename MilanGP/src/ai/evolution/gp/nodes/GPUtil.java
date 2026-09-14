package ai.evolution.gp.nodes;

import rts.PhysicalGameState;
import rts.units.Unit;

import java.util.Random;
import java.util.function.Predicate;

/** Shared game-state queries and small numeric helpers used by the terminal nodes. */
public final class GPUtil {
    private GPUtil() {}

    // ---- unit predicates

    public static boolean isEnemyOf(Unit u, int player) {
        return u.getPlayer() >= 0 && u.getPlayer() != player;
    }

    /** Can attack but cannot harvest: a dedicated combat unit, so Workers do not count. */
    public static boolean isMilitary(Unit u) {
        return u.getType().canAttack && !u.getType().canHarvest;
    }

    public static boolean isBarracks(Unit u) {
        return "Barracks".equals(u.getType().name);
    }

    // ---- distances

    public static int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    public static int manhattan(Unit a, Unit b) {
        return manhattan(a.getX(), a.getY(), b.getX(), b.getY());
    }

    /** Converts a map-relative fraction into tiles: {@code fraction * (width + height)}. */
    public static int absoluteRange(PhysicalGameState pgs, double fraction) {
        return (int) Math.round(fraction * (pgs.getWidth() + pgs.getHeight()));
    }

    /**
     * Converts a map-relative fraction into a unit count. {@code areaPerUnit} is calibrated so that
     * {@code fraction = 1.0} on the 16x16 reference map (area 256) gives the intended ceiling.
     */
    public static int absoluteCount(PhysicalGameState pgs, double fraction, double areaPerUnit) {
        double area = pgs.getWidth() * pgs.getHeight();
        return Math.max(1, (int) Math.round(fraction * area / areaPerUnit));
    }

    // ---- searches

    public static Unit nearest(PhysicalGameState pgs, Unit from, Predicate<Unit> predicate) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit u : pgs.getUnits()) {
            if (!predicate.test(u)) continue;
            int d = manhattan(from, u);
            if (d < bestDist) {
                best = u;
                bestDist = d;
            }
        }
        return best;
    }

    public static Unit nearestEnemy(PhysicalGameState pgs, Unit from, int player) {
        return nearest(pgs, from, u -> isEnemyOf(u, player));
    }

    public static Unit nearestResource(PhysicalGameState pgs, Unit from) {
        return nearest(pgs, from, u -> u.getType().isResource);
    }

    public static Unit nearestOwnBase(PhysicalGameState pgs, Unit from, int player) {
        return nearest(pgs, from, u -> u.getType().isStockpile && u.getPlayer() == player);
    }

    public static Unit nearestEnemyBase(PhysicalGameState pgs, Unit from, int player) {
        return nearest(pgs, from, u -> u.getType().isStockpile && isEnemyOf(u, player));
    }

    /** The enemy unit with the fewest hit points anywhere on the map. */
    public static Unit weakestEnemy(PhysicalGameState pgs, int player) {
        Unit best = null;
        for (Unit u : pgs.getUnits()) {
            if (!isEnemyOf(u, player)) continue;
            if (best == null || u.getHitPoints() < best.getHitPoints()) best = u;
        }
        return best;
    }

    public static int count(PhysicalGameState pgs, Predicate<Unit> predicate) {
        int n = 0;
        for (Unit u : pgs.getUnits()) if (predicate.test(u)) n++;
        return n;
    }

    public static boolean any(PhysicalGameState pgs, Predicate<Unit> predicate) {
        for (Unit u : pgs.getUnits()) if (predicate.test(u)) return true;
        return false;
    }

    // ---- constant perturbation (used by PerturbableTerminal implementations)

    /**
     * {@code value} nudged by up to {@code maxDelta} either way, rounded to two decimals and clamped
     * to {@code [min, max]}. Rounding keeps printed trees readable and lets the duplicate check in
     * GPPopulation recognise trees that differ only in noise on a constant.
     */
    public static double perturb(double value, double maxDelta, double min, double max, Random rnd) {
        double moved = value + (rnd.nextDouble() * 2 - 1) * maxDelta;
        double rounded = Math.round(moved * 100) / 100.0;
        return Math.max(min, Math.min(max, rounded));
    }

    /** {@code value} moved one {@code step} up or down, clamped to {@code [min, max]}. */
    public static int perturb(int value, int step, int min, int max, Random rnd) {
        int moved = value + (rnd.nextBoolean() ? step : -step);
        return Math.max(min, Math.min(max, moved));
    }
}
