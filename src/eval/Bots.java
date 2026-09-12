package eval;

import ai.core.AI;
import rts.units.UnitTypeTable;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The SINGLE place where a bot NAME becomes an ai.core.AI instance.
 *
 * WHY THIS IS THE ONLY FACTORY. There used to be two: this class, used by Watch, and
 * eval.Panel.make, used by the GP fitness evaluator and the benchmark. They did not agree.
 * This class handed WorkerRush an explicit BFSPathFinding; Panel reflected over the
 * constructors and took the (UnitTypeTable) one, which the engine fills with its own
 * default pathfinder. So "WorkerRush" meant one bot when you watched a game and a
 * different bot when you scored one, and nothing would ever have reported the difference.
 * Panel.make now delegates here.
 *
 * WHICH ONE WON, AND WHY. The (UnitTypeTable) constructor is canonical. That is what
 * Panel has always used, so every benchmark number already in results/ was produced
 * against it. Standardising on the BFS variant instead would have been just as defensible
 * on the merits and would have silently invalidated the whole archive. Comparability beat
 * the pathfinder preference. If you do want BFS, add it as a SEPARATE alias name rather
 * than redefining an existing one.
 *
 * RESOLUTION ORDER for a name:
 *   0. "evolved:<path.ind>" -- a saved individual, loaded via gp.IndividualLoader
 *   1. alias table below (case-insensitive)
 *   2. fully-qualified class name, if it contains a dot
 *   3. simple name searched across the engine's bot packages
 *
 * CONSTRUCTION: (UnitTypeTable) first, then no-arg. Anything else fails loudly, listing
 * the constructors the class actually offers.
 */
public final class Bots {

    private Bots() {}

    /**
     * Prefix for a bot loaded from a saved ECJ individual.
     *
     *   evolved:results/best-seed4242-20260912-135703.ind
     *   evolved:results/best-....ind@config/microrts.params
     *
     * The optional @params names the function set to read the tree against; without it
     * gp.IndividualLoader.DEFAULT_PARAMS is used. A .ind is NOT self-describing -- its
     * node names only mean something relative to a function set -- so an individual
     * archived under one terminal set cannot be read by a params file with a different
     * one. Keep the params file alongside the .ind in results/.
     */
    public static final String PREFIX_EVOLVED = "evolved:";

    /** Packages searched when a bot is named without one. Order matters. */
    private static final String[] PACKAGES = {
        "ai.abstraction.", "ai.", "ai.mcts.naivemcts.", "ai.montecarlo.", "ai.abstraction.cRush.",
    };

    /**
     * Short names, and anything whose class name you would rather not have to remember.
     * Competition bots (coac, mayari, ...) live in lib/bots/*.jar and are NOT compile-time
     * dependencies, so they resolve here or not at all -- and if the jar is missing from
     * the classpath the failure happens the moment you ask for that bot.
     */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    static {
        // ours
        ALIASES.put("chimera", "bots.Chimera");

        // engine trivial -- both spellings, since the params files say PassiveAI and the
        // command line tends to say passive
        ALIASES.put("passive", "ai.PassiveAI");
        ALIASES.put("random", "ai.RandomAI");
        ALIASES.put("randombiased", "ai.RandomBiasedAI");
        ALIASES.put("randombiasedsingleunit", "ai.RandomBiasedSingleUnitAI");

        // competition panel (lib/bots/*.jar)
        ALIASES.put("coac", "ai.coac.CoacAI");
        ALIASES.put("mayari", "mayariBot.mayari");
        ALIASES.put("izanagi", "Izanagi.Izanagi");
        ALIASES.put("tiamat", "TiamatBot.TiamatBot");
        ALIASES.put("droplet", "Droplet.Droplet");
        ALIASES.put("grojoa3n", "GRojoA3N.GRojoA3N");
        ALIASES.put("mixedbot", "MixedBot.MixedBot");

        // search bots, whose packages are awkward to type
        ALIASES.put("naivemcts", "ai.mcts.naivemcts.NaiveMCTS");
        ALIASES.put("puppetmcts", "ai.puppet.PuppetSearchMCTS");
    }

    /**
     * Build a bot by name. Throws IllegalArgumentException with a diagnosis if the name
     * cannot be resolved or the class cannot be constructed -- never returns null, and
     * never returns a silently different bot than the one asked for.
     */
    public static AI make(String name, UnitTypeTable utt) {
        String trimmed = name.trim();

        // Checked BEFORE any lowercasing: the rest of the spec is a FILE PATH, and paths
        // are case-sensitive on every platform this project has to work on.
        if (trimmed.regionMatches(true, 0, PREFIX_EVOLVED, 0, PREFIX_EVOLVED.length())) {
            String spec = trimmed.substring(PREFIX_EVOLVED.length()).trim();
            if (spec.isEmpty()) {
                throw new IllegalArgumentException(
                        "'" + PREFIX_EVOLVED + "' needs a path, e.g. "
                      + PREFIX_EVOLVED + "results/best-seed4242-....ind");
            }
            return gp.IndividualLoader.makeBot(spec, utt);
        }

        Class<?> c = resolve(trimmed);
        if (c == null) {
            throw new IllegalArgumentException(
                    "Unknown bot '" + name + "'."
                  + "\nIf it is a competition bot, is lib/bots/* on the classpath?"
                  + "\nKnown names: " + available());
        }
        return construct(c, utt);
    }

    /** Resolve a name to a class, or null. Not used for "evolved:" specs. */
    public static Class<?> resolve(String name) {
        String key = name.trim().toLowerCase();

        String alias = ALIASES.get(key);
        if (alias != null) {
            try {
                return Class.forName(alias);
            } catch (ClassNotFoundException e) {
                return null;   // aliased but absent -- almost always a classpath gap
            }
        }

        if (name.contains(".")) {
            try {
                return Class.forName(name.trim());
            } catch (ClassNotFoundException e) {
                return null;
            }
        }

        for (String pkg : PACKAGES) {
            try {
                return Class.forName(pkg + name.trim());
            } catch (ClassNotFoundException ignored) { }
        }
        return null;
    }

    /**
     * (UnitTypeTable) constructor first, then no-arg.
     *
     * Do not "improve" this by preferring a richer constructor when one exists. The
     * one-arg form is what defines what a bot NAME means across this project, and every
     * archived benchmark number depends on it staying that way.
     */
    public static AI construct(Class<?> c, UnitTypeTable utt) {
        try {
            for (Constructor<?> ctor : c.getConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0] == UnitTypeTable.class) {
                    return (AI) ctor.newInstance(utt);
                }
            }
            return (AI) c.getConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            StringBuilder sb = new StringBuilder();
            for (Constructor<?> ctor : c.getConstructors()) sb.append("\n  ").append(ctor);
            throw new IllegalArgumentException(
                    c.getName() + " has no (UnitTypeTable) or no-arg constructor. It offers:"
                  + sb, e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Could not construct " + c.getName() + ": " + e, e);
        }
    }

    public static String available() {
        return String.join(", ", ALIASES.keySet())
             + "\n  (or any engine bot by simple name, e.g. WorkerRush, LightDefense,"
             + " or any fully-qualified class name, e.g. ai.coac.CoacAI,"
             + " or " + PREFIX_EVOLVED + "<path.ind> for a saved evolved individual)";
    }
}
