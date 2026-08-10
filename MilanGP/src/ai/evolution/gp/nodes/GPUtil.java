package ai.evolution.gp.nodes;

import rts.PhysicalGameState;
import rts.units.Unit;

import java.util.function.Predicate;

public class GPUtil {
    private GPUtil() {}

    public static int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    public static int absoluteRange(PhysicalGameState pgs, double fraction) {
        return (int) Math.round(fraction * (pgs.getWidth() + pgs.getHeight()));
    }

    public static int absoluteCount(PhysicalGameState pgs, double fraction, double areaPerUnit) {
        double area = pgs.getWidth() * pgs.getHeight();
        return Math.max(1, (int) Math.round(fraction * area / areaPerUnit));
    }

    public static Unit nearest(PhysicalGameState pgs, int x, int y, Predicate<Unit> predicate) {
        Unit best = null;
        int bestDist = 0;
        for (Unit u : pgs.getUnits()) {
            if (!predicate.test(u)) continue;
            int d = manhattan(x, y, u.getX(), u.getY());
            if (best == null || d < bestDist) {
                best = u;
                bestDist = d;
            }
        }
        return best;
    }

    public static Unit nearestEnemy(PhysicalGameState pgs, Unit from, int player) {
        return nearest(pgs, from.getX(), from.getY(), u -> u.getPlayer() >= 0 && u.getPlayer() != player);
    }

    public static Unit nearestResource(PhysicalGameState pgs, Unit from) {
        return nearest(pgs, from.getX(), from.getY(), u -> u.getType().isResource);
    }

    public static Unit nearestOwnBase(PhysicalGameState pgs, Unit from, int player) {
        return nearest(pgs, from.getX(), from.getY(), u -> u.getType().isStockpile && u.getPlayer() == player);
    }

    public static Unit nearestEnemyBase(PhysicalGameState pgs, Unit from, int player) {
        return nearest(pgs, from.getX(), from.getY(), u -> u.getType().isStockpile && u.getPlayer() >= 0 && u.getPlayer() != player);
    }

    public static Unit weakestEnemy(PhysicalGameState pgs, int player) {
        Unit best = null;
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() < 0 || u.getPlayer() == player) continue;
            if (best == null || u.getHitPoints() < best.getHitPoints()) best = u;
        }
        return best;
    }

    public static int countMilitary(PhysicalGameState pgs, int player) {
        int count = 0;
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() == player && u.getType().canAttack && !u.getType().canHarvest) count++;
        }
        return count;
    }

    public static int countEnemyMilitary(PhysicalGameState pgs, int player) {
        int count = 0;
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() >= 0 && u.getPlayer() != player && u.getType().canAttack && !u.getType().canHarvest) count++;
        }
        return count;
    }

    public static int countEnemyWorkers(PhysicalGameState pgs, int player) {
        int count = 0;
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() >= 0 && u.getPlayer() != player && u.getType().canHarvest) count++;
        }
        return count;
    }

    public static int countWorkers(PhysicalGameState pgs, int player) {
        int count = 0;
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() == player && u.getType().canHarvest) count++;
        }
        return count;
    }

    public static boolean hasBarracks(PhysicalGameState pgs, int player) {
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() == player && "Barracks".equals(u.getType().name)) return true;
        }
        return false;
    }
}
