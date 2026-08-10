package gp.nodes;

/**
 * Closure guard, applied at the output of every function node.
 *
 * Arithmetic on unbounded feature values overflows: a couple of nested multiplications
 * on a large distance value reaches Infinity, and Infinity - Infinity is NaN. A NaN
 * score silently loses every argmax comparison, so a tree that produces one behaves
 * like a passive bot and evolution cannot tell why. Clamping keeps the search space
 * total and the failure visible.
 */
final class Guard {
    /** Widest value a score may take. Well inside double range, so squares stay finite. */
    static final double LIMIT = 1.0e9;

    private Guard() {}

    static double finite(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v > LIMIT) return LIMIT;
        if (v < -LIMIT) return -LIMIT;
        return v;
    }
}
