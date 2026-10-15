package rl;

import rts.UnitAction;
import rts.units.Unit;

/**
 * Fixed per-unit action vocabulary for the adapter's policy head: 86 codes.
 *
 *   0          NONE
 *   1..4       MOVE      up, right, down, left
 *   5..8       HARVEST   up, right, down, left
 *   9..12      RETURN    up, right, down, left
 *   13..36     PRODUCE   13 + type*4 + dir, type in PRODUCE_TYPES order
 *   37..85     ATTACK    37 + (dy+3)*7 + (dx+3), target offset within +-3 (max range is 3)
 *
 * Decoding never constructs a UnitAction: AdapterEnv maps a code back to an index in the
 * unit's own legal list, so a decoded action is always one the engine generated.
 */
public final class ActionCodec {

    public static final int N = 86;
    public static final int NONE = 0, MOVE0 = 1, HARVEST0 = 5, RETURN0 = 9, PRODUCE0 = 13, ATTACK0 = 37;
    public static final int ATTACK_RADIUS = 3, ATTACK_SIDE = 7;
    public static final String[] PRODUCE_TYPES = {"Worker", "Light", "Heavy", "Ranged", "Base", "Barracks"};
    private static final String[] DIRS = {"up", "right", "down", "left"};

    private ActionCodec() {}

    /** Code of a legal action of unit u, or -1 if it falls outside the vocabulary. */
    public static int encode(Unit u, UnitAction a) {
        int dir = a.getDirection();
        switch (a.getType()) {
            case UnitAction.TYPE_NONE:
                return NONE;
            case UnitAction.TYPE_MOVE:
                return dirOk(dir) ? MOVE0 + dir : -1;
            case UnitAction.TYPE_HARVEST:
                return dirOk(dir) ? HARVEST0 + dir : -1;
            case UnitAction.TYPE_RETURN:
                return dirOk(dir) ? RETURN0 + dir : -1;
            case UnitAction.TYPE_PRODUCE: {
                if (!dirOk(dir) || a.getUnitType() == null) return -1;
                int t = produceIndex(a.getUnitType().name);
                return t < 0 ? -1 : PRODUCE0 + t * 4 + dir;
            }
            case UnitAction.TYPE_ATTACK_LOCATION: {
                int dx = a.getLocationX() - u.getX();
                int dy = a.getLocationY() - u.getY();
                if (Math.abs(dx) > ATTACK_RADIUS || Math.abs(dy) > ATTACK_RADIUS) return -1;
                return ATTACK0 + (dy + ATTACK_RADIUS) * ATTACK_SIDE + (dx + ATTACK_RADIUS);
            }
            default:
                return -1;
        }
    }

    /** Action type (UnitAction.TYPE_*) of a code. */
    public static int typeOf(int code) {
        if (code == NONE) return UnitAction.TYPE_NONE;
        if (code < HARVEST0) return UnitAction.TYPE_MOVE;
        if (code < RETURN0) return UnitAction.TYPE_HARVEST;
        if (code < PRODUCE0) return UnitAction.TYPE_RETURN;
        if (code < ATTACK0) return UnitAction.TYPE_PRODUCE;
        return UnitAction.TYPE_ATTACK_LOCATION;
    }

    /** Human-readable name of a code, for logs. */
    public static String name(int code) {
        if (code < 0 || code >= N) return "invalid(" + code + ")";
        if (code == NONE) return "none";
        if (code < HARVEST0) return "move-" + DIRS[code - MOVE0];
        if (code < RETURN0) return "harvest-" + DIRS[code - HARVEST0];
        if (code < PRODUCE0) return "return-" + DIRS[code - RETURN0];
        if (code < ATTACK0) {
            int r = code - PRODUCE0;
            return "produce-" + PRODUCE_TYPES[r / 4] + "-" + DIRS[r % 4];
        }
        int r = code - ATTACK0;
        return "attack(" + (r % ATTACK_SIDE - ATTACK_RADIUS) + "," + (r / ATTACK_SIDE - ATTACK_RADIUS) + ")";
    }

    private static boolean dirOk(int d) {
        return d >= 0 && d < 4;
    }

    private static int produceIndex(String name) {
        for (int i = 0; i < PRODUCE_TYPES.length; i++) if (PRODUCE_TYPES[i].equals(name)) return i;
        return -1;
    }
}