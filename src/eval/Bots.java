package eval;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.core.AI;
import rts.units.UnitTypeTable;

/**
 * Single place where a bot NAME becomes an ai.core.AI instance.
 *
 * Two tiers:
 *  - Bots we compile against directly (engine core + our own src/bots).
 *  - Bots loaded by REFLECTION from lib/bots/*.jar. These are not compile-time
 *    dependencies, so build.sh needs no change and a missing jar or a wrong
 *    constructor signature fails loudly at the moment you ask for that bot,
 *    not at build time.
 *
 * Add an evolved-tree bot here later, e.g. name "evolved:results/best.tree",
 * so Watch can replay a specific individual (see makeEvolved stub at bottom).
 */
public final class Bots {

    private Bots() {}

    /** Reflection tier: friendly name -> fully-qualified class name. */
    private static final Map<String, String> REFLECTED = new LinkedHashMap<>();
    static {
        // Competition panel (lib/bots/*.jar)
        REFLECTED.put("coac",     "ai.coac.CoacAI");
        REFLECTED.put("mayari",   "mayariBot.mayari");
        REFLECTED.put("izanagi",  "Izanagi.Izanagi");
        REFLECTED.put("tiamat",   "TiamatBot.TiamatBot");
        REFLECTED.put("droplet",  "Droplet.Droplet");
        REFLECTED.put("grojoa3n", "GRojoA3N.GRojoA3N");
        REFLECTED.put("mixedbot", "MixedBot.MixedBot");
        // Engine bots whose constructors I would rather not guess at compile time
        REFLECTED.put("naivemcts", "ai.mcts.naivemcts.NaiveMCTS");
        REFLECTED.put("puppetmcts", "ai.puppet.PuppetSearchMCTS");
    }

    public static AI make(String name, UnitTypeTable utt) {
        String key = name.toLowerCase();

        switch (key) {
            // --- our bots -------------------------------------------------
            case "chimera":
                return new bots.Chimera(utt);

            // --- engine trivial ------------------------------------------
            case "passive":
                return new ai.PassiveAI(utt);
            case "random":
                return new ai.RandomAI(utt);
            case "randombiased":
                return new ai.RandomBiasedAI(utt);

            // --- engine scripted (rush) ----------------------------------
            case "workerrush":
                return new ai.abstraction.WorkerRush(utt, new ai.abstraction.pathfinding.BFSPathFinding());
            case "lightrush":
                return new ai.abstraction.LightRush(utt, new ai.abstraction.pathfinding.BFSPathFinding());
            case "heavyrush":
                return new ai.abstraction.HeavyRush(utt, new ai.abstraction.pathfinding.BFSPathFinding());
            case "rangedrush":
                return new ai.abstraction.RangedRush(utt, new ai.abstraction.pathfinding.BFSPathFinding());

            // --- engine scripted (defense) -------------------------------
            case "workerdefense":
                return new ai.abstraction.WorkerDefense(utt, new ai.abstraction.pathfinding.BFSPathFinding());
            case "lightdefense":
                return new ai.abstraction.LightDefense(utt, new ai.abstraction.pathfinding.BFSPathFinding());
            case "heavydefense":
                return new ai.abstraction.HeavyDefense(utt, new ai.abstraction.pathfinding.BFSPathFinding());
            case "rangeddefense":
                return new ai.abstraction.RangedDefense(utt, new ai.abstraction.pathfinding.BFSPathFinding());

            default:
                String cls = REFLECTED.get(key);
                if (cls == null && name.contains(".")) cls = name;  // fully-qualified passthrough
                if (cls == null) {
                    throw new IllegalArgumentException(
                            "Unknown bot '" + name + "'.\nAvailable: " + available());
                }
                return byClassName(cls, utt);
        }
    }

    /**
     * Instantiate by class name. Tries (UnitTypeTable) first, then no-arg.
     * Anything else -- and the message tells you exactly which constructors
     * the class actually offers, so you can extend this method in one line.
     */
    public static AI byClassName(String className, UnitTypeTable utt) {
        Class<?> c;
        try {
            c = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Class not found: " + className
                  + "\nIs lib/bots/* on the classpath? (run-watch.sh adds it; run.sh may not.)", e);
        }
        try {
            try {
                Constructor<?> ctor = c.getConstructor(UnitTypeTable.class);
                return (AI) ctor.newInstance(utt);
            } catch (NoSuchMethodException ignored) {
                Constructor<?> ctor = c.getConstructor();
                return (AI) ctor.newInstance();
            }
        } catch (NoSuchMethodException e) {
            StringBuilder sb = new StringBuilder();
            for (Constructor<?> ctor : c.getConstructors()) sb.append("\n  ").append(ctor);
            throw new IllegalArgumentException(
                    className + " has no (UnitTypeTable) or no-arg constructor. It offers:" + sb, e);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not construct " + className, e);
        }
    }

    public static String available() {
        return "chimera, passive, random, randombiased, "
             + "workerrush, lightrush, heavyrush, rangedrush, "
             + "workerdefense, lightdefense, heavydefense, rangeddefense, "
             + String.join(", ", REFLECTED.keySet())
             + "\n  (or any fully-qualified class name, e.g. ai.coac.CoacAI)";
    }

    // TODO Phase 3b+: once trees are persisted, accept "evolved:<path>" here and
    // return a bots.EvolvedBot wrapping the deserialized individual. Watch already
    // passes the raw name through, so no change is needed on the Watch side.
}
