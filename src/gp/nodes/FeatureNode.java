package gp.nodes;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.util.Parameter;
import gp.ScoreData;
import rts.GameState;
import rts.UnitAction;
import rts.units.Unit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * ONE terminal class that serves EVERY entry in the terminal set.
 *
 * Instead of writing 25 near-identical GPNode subclasses, each function-set entry names
 * the gp.Features method it wraps:
 *
 *     gp.fs.0.func.7 = gp.nodes.FeatureNode
 *     gp.fs.0.func.7.nc = nc0
 *     gp.fs.0.func.7.feature = distNearestEnemy
 *
 * Adding a terminal is then three lines of params, not a new source file. The binding is
 * resolved ONCE at setup() by reflection over gp.Features and cached; per-evaluation cost
 * is a cached Method.invoke, not a lookup.
 *
 * BINDING BY TYPE, NOT BY ORDER. An earlier version of this class held a table of
 * candidate parameter tuples and bound the first that matched exactly. That made argument
 * ORDER load-bearing: Features.distNearestEnemy(GameState, Unit, int) failed to bind
 * because only (Unit, GameState, int) was in the table, and the fix would have been
 * needed again for every future terminal written in a new order.
 *
 * This version instead inspects the method's parameter types and works out, per position,
 * which of the four available values that parameter wants. Order is irrelevant, arity is
 * irrelevant, and only genuinely unbindable methods fail.
 *
 * The four bindable values, from ScoreData: the acting Unit, the candidate UnitAction,
 * the GameState, and the player id (int).
 *
 * WHAT STILL FAILS, DELIBERATELY:
 *   - a parameter of a type not in that list
 *   - two or more int parameters, which would be ambiguous (see movesToward below)
 * Both fail at STARTUP with the list of available methods, never silently at generation 40.
 *
 * KNOWN NON-TERMINAL: Features.movesToward(Unit, UnitAction, int, int) takes target
 * coordinates, not a player id. Two ints cannot be resolved from ScoreData, so it is
 * correctly rejected as a terminal and remains available to hand-written scorers. To
 * expose it to the GP it would need to become an arity-2 FUNCTION whose children supply
 * tx and ty, which is a different class.
 *
 * Accepted return types: double, float, int, long, boolean (true -> 1.0, false -> 0.0).
 *
 * Place at: src/gp/nodes/FeatureNode.java
 */
public class FeatureNode extends GPNode {

    public static final String P_FEATURE = "feature";
    public static final String FEATURES_CLASS = "gp.Features";

    /** Argument sources, one per parameter position of the bound method. */
    private static final int ARG_UNIT = 0;
    private static final int ARG_ACTION = 1;
    private static final int ARG_STATE = 2;
    private static final int ARG_PLAYER = 3;

    private String featureName;
    private transient Method method;
    private transient int[] plan;

    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);

        featureName = state.parameters.getString(base.push(P_FEATURE), null);
        if (featureName == null) {
            state.output.fatal("FeatureNode needs a '" + P_FEATURE + "' parameter naming a "
                    + FEATURES_CLASS + " method.", base.push(P_FEATURE));
            return;
        }

        Class<?> features;
        try {
            features = Class.forName(FEATURES_CLASS);
        } catch (ClassNotFoundException e) {
            state.output.fatal("Could not find " + FEATURES_CLASS + " on the classpath.");
            return;
        }

        List<String> rejected = new ArrayList<>();

        for (Method m : features.getMethods()) {
            if (!m.getName().equals(featureName)) continue;
            if (!Modifier.isStatic(m.getModifiers())) {
                rejected.add(describe(m) + " -- not static");
                continue;
            }
            if (!isSupportedReturn(m.getReturnType())) {
                rejected.add(describe(m) + " -- returns " + m.getReturnType().getSimpleName());
                continue;
            }
            int[] candidate = planFor(m.getParameterTypes());
            if (candidate == null) {
                rejected.add(describe(m) + " -- parameters not resolvable from ScoreData");
                continue;
            }
            if (method != null) {
                state.output.fatal("Ambiguous feature '" + featureName + "' in " + FEATURES_CLASS
                        + ": more than one static overload is bindable. Rename one of them.",
                        base.push(P_FEATURE));
                return;
            }
            method = m;
            plan = candidate;
        }

        if (method == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("No usable static method '").append(featureName).append("' in ")
              .append(FEATURES_CLASS).append(".");
            sb.append("\nParameters are bound BY TYPE, in any order. Bindable types:");
            sb.append("\n    Unit, UnitAction, GameState, int (player id)");
            sb.append("\nAt most one int parameter is allowed -- two would be ambiguous.");
            if (!rejected.isEmpty()) {
                sb.append("\nMethods with this name that could not be bound:");
                for (String r : rejected) sb.append("\n    ").append(r);
            }
            sb.append("\nMethods actually available:");
            for (Method m : features.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && isSupportedReturn(m.getReturnType())) {
                    sb.append("\n    ").append(describe(m));
                    if (planFor(m.getParameterTypes()) == null) sb.append("   [not bindable]");
                }
            }
            state.output.fatal(sb.toString(), base.push(P_FEATURE));
        }
    }

    /**
     * Works out which ScoreData value fills each parameter position.
     * Returns null if any parameter is unbindable or if two ints are requested.
     */
    private static int[] planFor(Class<?>[] types) {
        int[] out = new int[types.length];
        boolean intSeen = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == Unit.class) {
                out[i] = ARG_UNIT;
            } else if (t == UnitAction.class) {
                out[i] = ARG_ACTION;
            } else if (t == GameState.class) {
                out[i] = ARG_STATE;
            } else if (t == int.class || t == Integer.class) {
                if (intSeen) return null;
                intSeen = true;
                out[i] = ARG_PLAYER;
            } else {
                return null;
            }
        }
        return out;
    }

    private static boolean isSupportedReturn(Class<?> t) {
        return t == double.class || t == float.class || t == int.class
            || t == long.class || t == boolean.class;
    }

    private static String describe(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append("(");
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    @Override
    public String toString() { return featureName == null ? "feature?" : featureName; }

    @Override
    public int expectedChildren() { return 0; }

    @Override
    public void eval(final EvolutionState state, final int thread, final GPData input,
                     final ADFStack stack, final GPIndividual individual, final Problem problem) {
        ScoreData d = (ScoreData) input;

        Object[] args = new Object[plan.length];
        for (int i = 0; i < plan.length; i++) {
            switch (plan[i]) {
                case ARG_UNIT:   args[i] = d.unit; break;
                case ARG_ACTION: args[i] = d.action; break;
                case ARG_STATE:  args[i] = d.gs; break;
                default:         args[i] = d.player; break;
            }
        }

        Object result;
        try {
            result = method.invoke(null, args);
        } catch (Exception e) {
            // A terminal must never take down a run. Treat a thrown feature as neutral,
            // but make it visible rather than silent.
            state.output.warnOnce("Feature '" + featureName + "' threw " + e.getCause()
                    + " -- returning 0.0 for this evaluation.");
            d.score = 0.0;
            return;
        }

        double v;
        if (result instanceof Boolean) v = ((Boolean) result) ? 1.0 : 0.0;
        else v = ((Number) result).doubleValue();

        // CLOSURE: never let NaN or infinity escape into the tree.
        d.score = (Double.isNaN(v) || Double.isInfinite(v)) ? 0.0 : v;
    }
}
