package eval;

import ai.core.AI;
import ai.evaluation.EvaluationFunction;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.UnitTypeTable;

/**
 * Headless game running, shared by the GP fitness evaluator and the end-of-run benchmark
 * so that both measure the same thing the same way.
 *
 * BOT CONSTRUCTION MOVED OUT. make() used to resolve and construct bots itself, in
 * parallel with eval.Bots doing the same job differently -- Bots gave WorkerRush an
 * explicit BFSPathFinding while this class took the (UnitTypeTable) constructor and the
 * engine's default pathfinder. Two factories meant "WorkerRush" could mean two different
 * bots depending on which entry point you came in through. There is now one factory,
 * eval.Bots, and make() below is a thin delegate kept so existing call sites compile
 * unchanged.
 *
 * THREADING: nothing in this class is static and mutable. PACKAGES and the old resolve()
 * are gone to Bots; FULL_PANEL is an immutable name list; play() loads a fresh
 * PhysicalGameState per game rather than caching a parsed map. Safe to call concurrently
 * from every ECJ eval thread.
 */
public final class Panel {

    /** The scripted bots the benchmark runs, in a fixed order. */
    public static final String[] FULL_PANEL = {
        "WorkerRush", "LightRush", "HeavyRush", "RangedRush", "WorkerRushPlusPlus",
        "EconomyRush", "EconomyRushBurster", "EconomyMilitaryRush", "EMRDeterministico",
        "SimpleEconomyRush", "LightDefense", "HeavyDefense", "RangedDefense", "WorkerDefense",
        "RandomAI", "RandomBiasedAI", "RandomBiasedSingleUnitAI", "PassiveAI",
    };

    private Panel() {}

    /**
     * Build a bot by name. Delegates to the single factory; see eval.Bots for resolution
     * order and for why the (UnitTypeTable) constructor is canonical.
     */
    public static AI make(String name, UnitTypeTable utt) {
        return Bots.make(name, utt);
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
         *
         * NOTE FOR THE FITNESS CODE: this value is ALREADY normalised and clamped to
         * [0,1] here, with 0.5 meaning an even game. MicroRTSProblem's eval-lo/eval-hi
         * are therefore not converting an unbounded score onto a scale -- they are
         * stretching the narrow band that real games occupy across the full range, which
         * is contrast enhancement. The observed [0.27, 0.46] means our bots were behind
         * for most of most games, not that the scale was arbitrary.
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
