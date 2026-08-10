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
 * resolved ONCE at setup() by reflection over gp.Features, and the resulting Method is
 * cached; per-evaluation cost is a cached Method.invoke, not a lookup.
 *
 * SIGNATURE PROBING: gp.Features methods take different arguments (some need the unit,
 * some the action, some only the game state). Rather than force one signature, setup()
 * probes a list of plausible ones in order and binds the first that matches. If nothing
 * matches, it fails at STARTUP with the list of what it tried -- never silently at
 * generation 40.
 *
 * Accepted return types: double, float, int, long, boolean (true -> 1.0, false -> 0.0).
 *
 * NOTE: reflection here is a deliberate trade. It makes this class independent of the
 * exact shape of your Features.java, which is still under review with the supervisors.
 * If profiling later shows Method.invoke on the hot path, replace the invoke with a
 * switch over feature names calling the methods directly -- the params files and every
 * evolved tree stay valid, because only the dispatch changes.
 */
public class FeatureNode extends GPNode {

    public static final String P_FEATURE = "feature";
    public static final String FEATURES_CLASS = "gp.Features";

    /** Candidate parameter lists, probed in this order. */
    private static final Class<?>[][] SIGNATURES = {
        { Unit.class, UnitAction.class, GameState.class, int.class },
        { Unit.class, UnitAction.class, GameState.class },
        { Unit.class, GameState.class, int.class },
        { Unit.class, UnitAction.class },
        { GameState.class, int.class },
        { Unit.class, GameState.class },
        { UnitAction.class },
        { Unit.class },
        { GameState.class },
        {},
    };

    private String featureName;
    private transient Method method;
    private transient int signatureIndex;

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

        for (int i = 0; i < SIGNATURES.length; i++) {
            try {
                Method m = features.getMethod(featureName, SIGNATURES[i]);
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (!isSupportedReturn(m.getReturnType())) continue;
                method = m;
                signatureIndex = i;
                break;
            } catch (NoSuchMethodException ignored) {
                // try the next signature
            }
        }

        if (method == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("No usable static method '").append(featureName).append("' in ")
              .append(FEATURES_CLASS).append(".\nSignatures tried:");
            for (Class<?>[] sig : SIGNATURES) {
                sb.append("\n    ").append(featureName).append("(");
                for (int j = 0; j < sig.length; j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(sig[j].getSimpleName());
                }
                sb.append(")");
            }
            sb.append("\nMethods actually available:");
            for (Method m : features.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && isSupportedReturn(m.getReturnType())) {
                    sb.append("\n    ").append(m.getName()).append(describe(m.getParameterTypes()));
                }
            }
            state.output.fatal(sb.toString(), base.push(P_FEATURE));
        }
    }

    private static boolean isSupportedReturn(Class<?> t) {
        return t == double.class || t == float.class || t == int.class
            || t == long.class || t == boolean.class;
    }

    private static String describe(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
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
        Object result;
        try {
            switch (signatureIndex) {
                case 0:  result = method.invoke(null, d.unit, d.action, d.gs, d.player); break;
                case 1:  result = method.invoke(null, d.unit, d.action, d.gs); break;
                case 2:  result = method.invoke(null, d.unit, d.gs, d.player); break;
                case 3:  result = method.invoke(null, d.unit, d.action); break;
                case 4:  result = method.invoke(null, d.gs, d.player); break;
                case 5:  result = method.invoke(null, d.unit, d.gs); break;
                case 6:  result = method.invoke(null, d.action); break;
                case 7:  result = method.invoke(null, d.unit); break;
                case 8:  result = method.invoke(null, d.gs); break;
                default: result = method.invoke(null); break;
            }
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
