package ai.custom;

import java.util.List;
import rts.GameState;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;

final class GPFeatureExtractor {
    static final int FEATURE_ENEMY_FAR_AWAY = 0;
    static final int FEATURE_ENEMY_IN_RANGE = 1;
    static final int FEATURE_NUM_ENEMIES = 2;
    static final int FEATURE_ENEMY_TYPE = 3;
    static final int FEATURE_NUM_ALLIES_NEAR = 4;
    static final int FEATURE_MY_UNIT_TYPE = 5;
    static final int FEATURE_NUM_RESOURCES = 6;
    static final int FEATURE_COUNT = 7;

    private static final int ENEMY_COUNT_CAP = 10;
    private static final int ALLY_COUNT_CAP = 5;
    private static final int RESOURCE_CAP = 200;

    private GPFeatureExtractor() {
    }

    static double[] extract(Unit unit, GameState gs, int player, UnitTypeTable utt) {
        Unit closestEnemy = findClosestEnemy(unit, gs);
        Unit closestResource = findClosestResource(unit, gs);

        int nearestEnemyDistance = closestEnemy == null ? Integer.MAX_VALUE : distance(unit, closestEnemy);
        int enemySightRadius = Math.max(1, unit.getType().sightRadius);
        int enemyRange = Math.max(1, unit.getType().attackRange);

        int enemyCount = countUnitsWithinRadius(unit, gs, player, true, enemySightRadius);
        int allyCount = countUnitsWithinRadius(unit, gs, player, false, enemySightRadius);
        int resources = gs.getPlayer(player).getResources();

        double enemyFarAway = (closestEnemy == null || nearestEnemyDistance > enemyRange) ? 1.0 : 0.0;
        double enemyInRange = (closestEnemy != null && nearestEnemyDistance <= enemyRange) ? 1.0 : 0.0;
        double numEnemies = clamp(enemyCount, 0, ENEMY_COUNT_CAP) / (double) ENEMY_COUNT_CAP;
        double enemyType = encodeUnitType(closestEnemy == null ? null : closestEnemy.getType());
        double numAlliesNear = clamp(allyCount, 0, ALLY_COUNT_CAP) / (double) ALLY_COUNT_CAP;
        double myUnitType = encodeUnitType(unit.getType());
        double numResources = clamp(resources, 0, RESOURCE_CAP) / (double) RESOURCE_CAP;

        // The closest resource is intentionally touched here so the extractor stays
        // close to the requested in-game context and can be extended with a
        // dedicated resource-distance feature later without changing callers.
        if (closestResource != null && closestEnemy == null) {
            numEnemies = 0.0;
        }

        return new double[] {
                enemyFarAway,
                enemyInRange,
                numEnemies,
                enemyType,
                numAlliesNear,
                myUnitType,
                numResources
        };
    }

    static Unit findClosestEnemy(Unit unit, GameState gs) {
        Unit closest = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Unit other : gs.getUnits()) {
            if (other.getPlayer() < 0 || other.getPlayer() == unit.getPlayer()) {
                continue;
            }
            int distance = distance(unit, other);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = other;
            }
        }

        return closest;
    }

    static Unit findClosestResource(Unit unit, GameState gs) {
        Unit closest = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Unit other : gs.getUnits()) {
            if (!other.getType().isResource) {
                continue;
            }
            int distance = distance(unit, other);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = other;
            }
        }

        return closest;
    }

    static int countUnitsWithinRadius(Unit unit, GameState gs, int player, boolean enemies, int radius) {
        int count = 0;
        for (Unit other : gs.getUnits()) {
            if (other == unit || other.getPlayer() < 0) {
                continue;
            }
            boolean isEnemy = other.getPlayer() != player;
            if (enemies != isEnemy) {
                continue;
            }
            if (distance(unit, other) <= radius) {
                count++;
            }
        }
        return count;
    }

    static int distance(Unit a, Unit b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static double encodeUnitType(UnitType type) {
        if (type == null) {
            return 0.0;
        }
        String name = type.name;
        if ("Worker".equals(name)) return 0.0;
        if ("Light".equals(name)) return 0.25;
        if ("Heavy".equals(name)) return 0.50;
        if ("Ranged".equals(name)) return 0.75;
        if ("Base".equals(name)) return 0.85;
        if ("Barracks".equals(name)) return 0.95;
        if ("Resource".equals(name)) return 1.0;
        return 0.5;
    }
}
