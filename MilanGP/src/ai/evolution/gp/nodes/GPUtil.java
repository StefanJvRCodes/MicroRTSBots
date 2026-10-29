package ai.evolution.gp.nodes;

import rts.PhysicalGameState;
import rts.units.Unit;

import java.util.Random;
import java.util.function.Predicate;

public final class GPUtil {
    private GPUtil() {
    }

    public static boolean isEnemyOf(Unit u, int player) {
        return u.getPlayer() >= 0 && u.getPlayer() != player;
    }

    public static boolean isMilitary(Unit u) {
        return u.getType().canAttack && !u.getType().canHarvest;
    }

    public static boolean isBarracks(Unit u) {
        return "Barracks".equals(u.getType().name);
    }

    public static int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    public static int manhattan(Unit a, Unit b) {
        return manhattan(a.getX(), a.getY(), b.getX(), b.getY());
    }

    public static int absoluteRange(PhysicalGameState pgs, double fraction) {
        return (int) Math.round(fraction * (pgs.getWidth() + pgs.getHeight()));
    }

    public static int absoluteCount(PhysicalGameState pgs, double fraction, double areaPerUnit) {
        double area = pgs.getWidth() * pgs.getHeight();
        return Math.max(1, (int) Math.round(fraction * area / areaPerUnit));
    }

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

    public static double perturb(double value, double maxDelta, double min, double max, Random rnd) {
        double moved = value + (rnd.nextDouble() * 2 - 1) * maxDelta;
        double rounded = Math.round(moved * 100) / 100.0;
        return Math.clamp(rounded, min, max);
    }

    public static int perturb(int value, int step, int min, int max, Random rnd) {
        int moved = value + (rnd.nextBoolean() ? step : -step);
        return Math.clamp(moved, min, max);
    }
}
