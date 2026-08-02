package eval;

import ai.core.AI;
import ai.evaluation.EvaluationFunction;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.UnitTypeTable;

import java.lang.reflect.Constructor;

/**
 * Opponent construction and headless game running, shared by the GP fitness evaluator
 * and the end-of-run benchmark so that both measure the same thing the same way.
 */
public final class Panel {

    /** Packages searched when a bot is named without one. */
    private static final String[] PACKAGES = {
        "ai.abstraction.", "ai.", "ai.mcts.naivemcts.", "ai.montecarlo.", "ai.abstraction.cRush.",
    };

    /** The scripted bots your teammate benchmarked against, in the same order. */
    public static final String[] FULL_PANEL = {
        "WorkerRush", "LightRush", "HeavyRush", "RangedRush", "WorkerRushPlusPlus",
        "EconomyRush", "EconomyRushBurster", "EconomyMilitaryRush", "EMRDeterministico",
        "SimpleEconomyRush", "LightDefense", "HeavyDefense", "RangedDefense", "WorkerDefense",
        "RandomAI", "RandomBiasedAI", "RandomBiasedSingleUnitAI", "PassiveAI",
    };

    private Panel() {}

    /**
     * Build a bot by (simple or fully-qualified) class name. Tries a UnitTypeTable
     * constructor first, then a no-arg one.
     */
    public static AI make(String name, UnitTypeTable utt) {
        Class<?> c = resolve(name);
        if (c == null) throw new IllegalArgumentException("Unknown bot: " + name);
        try {
            for (Constructor<?> ctor : c.getConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0] == UnitTypeTable.class) return (AI) ctor.newInstance(utt);
            }
            return (AI) c.getConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not construct " + name + ": " + e, e);
        }
    }

    private static Class<?> resolve(String name) {
        if (name.contains(".")) {
            try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; }
        }
        for (String pkg : PACKAGES) {
            try { return Class.forName(pkg + name); } catch (ClassNotFoundException ignored) { }
        }
        return null;
    }

    /** Map an evaluation function's output onto [0,1], 1 being dominant for {@code player}. */
    private static double normalise(EvaluationFunction ef, GameState gs, int player) {
        float raw = ef.evaluate(player, 1 - player, gs);
        float bound = ef.upperBound(gs);
        if (bound <= 0) return 0.5;
        double v = (raw / bound + 1.0) / 2.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Outcome of one game from the perspective of the bot passed as {@code ai}. */
    public static final class Result {
        public int winner;        // -1 draw, else player id
        public int cyclesTaken;
        public int myPlayer;      // which side our bot played
        public GameState finalState;

        /**
         * Mean state evaluation from our perspective, sampled DURING the game and
         * normalised to [0,1]. Sampled rather than taken at the end because the final
         * state of a loss is always "we have no units" -- identical for a bot that
         * fought well and one that never moved. This is the dense quality signal.
         */
        public double meanEval = 0.5;

        /** Best state evaluation reached at any point, same normalisation. */
        public double peakEval = 0.5;

        public boolean won()  { return winner == myPlayer; }
        public boolean lost() { return winner != -1 && winner != myPlayer; }
        public boolean drew() { return winner == -1; }
    }

    /**
     * Play one headless game. {@code ourSide} is 0 or 1; swap it across repeats to
     * cancel first-player advantage, which on 8x8 maps is substantial.
     */
    public static Result play(AI ours, AI theirs, String mapPath, UnitTypeTable utt,
                              int ourSide, int maxCycles) throws Exception {
        return play(ours, theirs, mapPath, utt, ourSide, maxCycles, null, 50);
    }

    /**
     * As above, but sampling {@code ef} every {@code sampleEvery} cycles to build the
     * dense quality signal the GP fitness needs.
     */
    public static Result play(AI ours, AI theirs, String mapPath, UnitTypeTable utt,
                              int ourSide, int maxCycles,
                              EvaluationFunction ef, int sampleEvery) throws Exception {
        PhysicalGameState pgs = PhysicalGameState.load(mapPath, utt);
        GameState gs = new GameState(pgs, utt);

        AI p0 = (ourSide == 0) ? ours : theirs;
        AI p1 = (ourSide == 0) ? theirs : ours;
        p0.reset();
        p1.reset();

        double evalSum = 0.0;
        double evalPeak = 0.0;
        int samples = 0;

        boolean over = false;
        while (!over && gs.getTime() < maxCycles) {
            PlayerAction a0 = p0.getAction(0, gs);
            PlayerAction a1 = p1.getAction(1, gs);
            gs.issueSafe(a0);
            gs.issueSafe(a1);
            over = gs.cycle();

            if (ef != null && gs.getTime() % sampleEvery == 0) {
                double v = normalise(ef, gs, ourSide);
                evalSum += v;
                if (v > evalPeak) evalPeak = v;
                samples++;
            }
        }

        Result r = new Result();
        r.winner = gs.winner();
        r.cyclesTaken = gs.getTime();
        r.myPlayer = ourSide;
        r.finalState = gs;
        if (samples > 0) {
            r.meanEval = evalSum / samples;
            r.peakEval = evalPeak;
        }
        return r;
    }
}
