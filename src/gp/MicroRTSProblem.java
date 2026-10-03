package gp;

import ai.core.AI;
import ai.evaluation.EvaluationFunction;
import ai.evaluation.SimpleSqrtEvaluationFunction3;
import bots.EvolvedBot;
import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPIndividual;
import ec.gp.GPProblem;
import ec.gp.koza.KozaFitness;
import ec.simple.SimpleProblemForm;
import ec.util.Parameter;
import eval.Panel;
import rts.units.UnitTypeTable;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fitness = play real microRTS games and score the outcome.
 *
 * THE SIGNAL (standardized: LOWER IS BETTER -- ECJ convention, and the easiest thing in
 * this whole file to get backwards). Each outcome owns a full unit-wide BAND, and the
 * shaping terms move an individual only WITHIN its band:
 *
 *     win   0.0 - 1.0     faster is better
 *     draw  1.0 - 2.0     better play during the game is better
 *     loss  2.0 - 3.0     survived longer and played better is better
 *
 * ...then the mean across games is SCALED DOWN in proportion to the fraction of games
 * won, because bands fix ONE game and fitness averages MANY (see below).
 *
 * WHY BANDS. An earlier form was outcome + speed + marginWeight*margin, which ordered
 * win < draw < loss correctly but left only 0.25 of shaping room inside a band against a
 * gap of 1.0 between bands. Two draws that played very differently scored within a
 * quarter-point of each other, selection could barely tell them apart, and the population
 * sat in the draw band for eleven generations. Bands give each outcome the full unit
 * interval while keeping the ordering un-invertible.
 *
 * WHY A WIN REWARD ON TOP. An individual beating PassiveAI on both sides and losing to
 * RandomBiasedAI on both sides averages about (0.3+0.3+2.5+2.5)/4 = 1.40; one that draws
 * everything averages 1.50. The winner led by 0.1 inside a band spanning 1.0, so a
 * marginally better draw outranked a real win and evolution learned the safe uniform
 * strategy. The reward makes winning worth more than any amount of drawing well.
 *
 * WHY IT IS MULTIPLICATIVE, NOT SUBTRACTED. THIS PROJECT HAS NOW FLATTENED ITS OWN
 * LANDSCAPE THREE TIMES AND THE PATTERN IS WORTH READING.
 *
 * The reward was originally `Math.max(0.0, mean - winBonus * winFrac)`. A win scores near
 * ZERO by construction -- the win band runs 0.0 to 1.0 and a fast win sits around 0.1 --
 * so subtracting 0.5 from an individual that wins every game gives a negative number,
 * which the max() clamps to 0.0. EVERY all-winning individual therefore scored EXACTLY
 * 0.0: a bot winning in 200 cycles and one winning in 2900 were indistinguishable, hits
 * saturated at the games-per-individual count, and betterThan() never fired, so
 * best_of_run froze on whichever winner appeared first.
 *
 * The run of 2026-09-12 is the evidence: 100 generations, 50,000 evaluations, every
 * single generation reporting Standardized=0.0 Hits=8, and a best-of-run tree of 9 nodes
 * at depth 3 -- no bloat, because there had been no selection pressure of any kind since
 * generation 0. The benchmark from that run (13 of 18 panel bots beaten) was found by
 * RANDOM SEARCH in the initial population, not by evolution, and must be reported so.
 *
 * The same failure three times, in three places:
 *   - at the BOTTOM: every losing final state is identical, so the margin term was
 *     constant (fixed by sampling quality during play);
 *   - in the MIDDLE: 0.25 of shaping room against a 1.0 gap between bands, so draws were
 *     unrankable (fixed by bands);
 *   - at the TOP: a subtracted reward larger than the entire win band, so wins were
 *     unrankable (fixed here).
 * The lesson generalises: any term that can saturate will saturate exactly where the
 * population ends up, which is precisely where the gradient is needed.
 *
 * Scaling by (1 - winBonus * winFrac) cannot saturate, because it is proportional rather
 * than absolute. With winBonus = 0.5: all wins fast -> 0.1 * 0.5 = 0.05; all wins slow ->
 * 0.5 * 0.5 = 0.25; half wins half losses -> about 1.01; all draws -> 1.5. Wins still
 * dominate draws by a wide margin, the win band keeps its full internal gradient, and
 * nothing ever reaches the floor.
 *
 * WHY REPEATS. RandomBiasedAI is stochastic. With one game per side, an individual can
 * luck into a win, get selected on that, and fail to reproduce it -- selection then ranks
 * noise as much as skill, which is what stalls a run. The symptom was a training Hits=2
 * that the benchmark contradicted: 0W against RandomBiasedAI over 10 games, alongside
 * 5W against deterministic PassiveAI. Those only reconcile if some training wins were
 * luck. Repeating stochastic matchups and averaging shrinks that variance.
 *
 * REPEATS ARE PER-OPPONENT, DELIBERATELY. This bot is deterministic and so is PassiveAI,
 * so replaying that pair returns a byte-identical result -- pure wasted compute. Only
 * opponents named in 'stochastic-opponents' are repeated; everything else plays once.
 *
 * NOTE ON REPRODUCIBILITY: RandomBiasedAI seeds its own RNG independently of ECJ's seed.
 * Two runs with identical seed.0 and evalthreads=1 produced different populations
 * (verified 2026-09-12). Runs whose opponent list is entirely deterministic are
 * bit-for-bit reproducible; runs including RandomBiasedAI or any other stochastic
 * opponent are reproducible only in distribution, and must be reported across seeds.
 *
 * The two earlier findings are preserved and still shape this file:
 *
 *   - Speed must not be penalised on a LOSS. A cycles-used penalty on losses pays
 *     evolution to lose faster, and a bot suiciding at cycle 100 outranks one surviving
 *     to 1400. Among losses, surviving longer is REWARDED here.
 *
 *   - Quality must be sampled DURING play, not read off the final state. You lose in
 *     microRTS by having no units, so every losing final state is identical, the term
 *     goes constant, and the landscape is exactly flat.
 *
 * Sides are always swapped: first-player advantage on 8x8 is large enough to swamp the
 * quality term.
 *
 * ------------------------------------------------------------------------------------
 * THREADING. ECJ's SimpleEvaluator clones this Problem once per eval thread per
 * generation and each clone evaluates its own chunk of the population. GPProblem.clone()
 * deep-copies the GPData and the ADFStack, so per-thread scoring context is already
 * handled -- but everything else goes through Object.clone(), which is SHALLOW. The
 * UnitTypeTable and the EvaluationFunction would therefore be one shared instance across
 * every thread. clone() below hands each thread its own. gp.Features is already safe:
 * it holds no static mutable state.
 * ------------------------------------------------------------------------------------
 *
 * ------------------------------------------------------------------------------------
 * 2026-10: FAIL LOUDLY ON A BAD MAP OR OPPONENT NAME -- A FOURTH WAY TO FLATTEN THE
 * LANDSCAPE. evaluate() catches any exception from a game and scores it 3.0, which is
 * right for a crashing INDIVIDUAL and catastrophic for a typo in the params: a missing
 * map file or an unknown opponent makes every game throw, every individual scores 3.0,
 * and the run looks like a hopeless fitness landscape while printing one warnOnce line.
 * That risk grew the moment the map list went from one entry to several, so setup()
 * now checks every map exists and every opponent constructs, and fatals otherwise.
 *
 * 2026-10: TIE-RATE REPORT. EvolvedBot counts decisions decided by getUnitActions()
 * list order rather than by the tree; this file aggregates them per generation the same
 * way it aggregates the calibration range, and prints them per role, split into ALL
 * ties and CROSS-TYPE ties (tied actions of different types). Only the second means the
 * engine chose what the unit does; ties among equivalent placements are mostly benign.
 *
 * 2026-10: NONE DURATION -- A HANDICAP EVERY EVOLVED BOT HAD UNTIL STAGE 6.
 * Unit.getUnitActions(gs) offers NONE with a duration of TEN cycles (the engine default).
 * A worker's attack takes five. So whenever an evolved bot chose to wait, that unit was
 * frozen for ten cycles and could not take the first strike when an enemy stepped
 * adjacent -- and workers have 1 HP, so first strike decides every worker fight.
 * Scripted bots (AbstractionLayerAI) never lock units like that.
 * Found 2026-10-03 while diagnosing the 16x16 WorkerRush wall: no scripted bot in the
 * engine beats WorkerRush on the basesWorkers16x16 maps (every army strategy 0/12; the
 * WorkerRush mirror 6/12, side 0 only), and hand-written scorers over gp.Features won
 * 0/12 with the default NONE, 3-4/12 with a 1-cycle NONE -- including the first win from
 * both sides. 'none-duration' makes this a parameter; default 10 keeps every archived
 * individual replaying exactly as it was evolved.
 * ------------------------------------------------------------------------------------
 */
public class MicroRTSProblem extends GPProblem implements SimpleProblemForm {

    private static final long serialVersionUID = 1L;

    public static final String P_MAPS = "maps";
    public static final String P_OPPONENTS = "opponents";
    public static final String P_MAX_CYCLES = "max-cycles";
    public static final String P_SPEED_WEIGHT = "speed-weight";
    public static final String P_MARGIN_WEIGHT = "margin-weight";
    public static final String P_SWAP_SIDES = "swap-sides";
    public static final String P_EVAL_LO = "eval-lo";
    public static final String P_EVAL_HI = "eval-hi";
    public static final String P_CALIBRATE = "calibrate-eval";
    public static final String P_WIN_BONUS = "win-bonus";
    public static final String P_REPEATS = "repeats";
    public static final String P_STOCHASTIC = "stochastic-opponents";
    public static final String P_NONE_DURATION = "none-duration";

    /**
     * Opponents assumed stochastic when 'stochastic-opponents' is not set. Anything with
     * randomness in its action selection belongs here; a name absent from this set is
     * played once per side because replaying it could not produce a different result.
     */
    private static final String[] DEFAULT_STOCHASTIC = {
        "RandomAI", "RandomBiasedAI", "RandomBiasedSingleUnitAI", "NaiveMCTS", "MonteCarlo"
    };

    public String[] maps;
    public String[] opponents;
    public int maxCycles;
    public boolean swapSides;

    public double survivalShare;
    public double qualityShare;

    /**
     * Strength of the win reward, as a MULTIPLIER not a subtraction. Fitness is scaled by
     * (1 - winBonus * winFraction), so 0.0 disables the reward entirely and values
     * approaching 1.0 drive an all-winning individual's fitness toward zero -- which is
     * the saturation this form replaced, so the parameter is clamped below 1.0.
     */
    public double winBonus;

    public int repeats;
    public Set<String> stochastic;

    /**
     * Duration, in cycles, of the NONE (wait) action offered to an evolved bot's units.
     * See the 2026-10 block in the class comment. Default 10 = the engine's own default,
     * which is what every individual archived before stage 6 was evolved and benchmarked
     * with, so their params snapshots (which lack this key) replay identically.
     */
    public int noneDuration;

    /**
     * Range of Panel.Result.meanEval, used to map raw evaluation values onto 0..1.
     * Panel already normalises and clamps meanEval to [0,1] with 0.5 meaning an even
     * game, so these bounds are not converting an unbounded score onto a scale -- they
     * stretch the narrow band that real games occupy across the full range, which is
     * contrast enhancement. The band is OPPONENT-DEPENDENT: bounds calibrated on
     * PassiveAI were badly wrong once RandomBiasedAI joined and most of the distribution
     * clamped, silently flattening the quality term. Recalibrate whenever opponents
     * change.
     */
    public double evalLo;
    public double evalHi;
    public boolean calibrate;

    public transient UnitTypeTable utt;
    public transient EvaluationFunction stateEval;

    // ---------------------------------------------------------------------------------
    // Calibration accumulator.
    //
    // STATIC AND SYNCHRONIZED, ON PURPOSE. These were instance fields, which was correct
    // at evalthreads = 1 and produces N partial reports per generation above that -- each
    // thread seeing only its own slice of the population and printing its own range. The
    // accumulator is shared so the reported range covers the whole generation, and it
    // resets itself when the generation number changes.
    //
    // This is REPORTING ONLY. Nothing here feeds back into a fitness value, so the lock
    // cannot make evaluation order-dependent, and contention is one short critical
    // section per game.
    // ---------------------------------------------------------------------------------
    private static final Object CAL_LOCK = new Object();
    private static int calGeneration = -1;
    private static double calLo = Double.POSITIVE_INFINITY;
    private static double calHi = Double.NEGATIVE_INFINITY;
    private static int calCount = 0;
    private static boolean calReported = false;

    // ---------------------------------------------------------------------------------
    // Tie-rate accumulator (2026-10). Same pattern and same caveat as calibration:
    // reporting only, never feeds fitness. Reported once per generation after
    // TIE_SAMPLE_GAMES games, so it is a SAMPLE of the generation, not all of it.
    // ---------------------------------------------------------------------------------
    private static final int TIE_SAMPLE_GAMES = 2000;
    private static final Object TIE_LOCK = new Object();
    private static int tieGeneration = -1;
    private static final long[] tieDecisions = new long[4];
    private static final long[] tieTied = new long[4];
    private static final long[] tieCross = new long[4];
    private static int tieGames = 0;
    private static boolean tieReported = false;

    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);

        if (!(input instanceof ScoreData)) {
            state.output.fatal("GPData must be gp.ScoreData", base.push(P_DATA), null);
        }

        maps = splitList(state.parameters.getString(base.push(P_MAPS), null));
        opponents = splitList(state.parameters.getString(base.push(P_OPPONENTS), null));
        if (maps == null || maps.length == 0) state.output.fatal("Need " + P_MAPS, base.push(P_MAPS));
        if (opponents == null || opponents.length == 0) state.output.fatal("Need " + P_OPPONENTS, base.push(P_OPPONENTS));

        maxCycles = state.parameters.getIntWithDefault(base.push(P_MAX_CYCLES), null, 3000);
        swapSides = state.parameters.getBoolean(base.push(P_SWAP_SIDES), null, true);

        double sw = state.parameters.getDoubleWithDefault(base.push(P_SPEED_WEIGHT), null, 0.5);
        double mw = state.parameters.getDoubleWithDefault(base.push(P_MARGIN_WEIGHT), null, 0.25);
        double sum = sw + mw;
        if (sum <= 0.0) { sw = 0.6; mw = 0.4; sum = 1.0; }
        survivalShare = sw / sum;
        qualityShare = mw / sum;

        winBonus = state.parameters.getDoubleWithDefault(base.push(P_WIN_BONUS), null, 0.5);
        if (winBonus < 0.0 || winBonus >= 1.0) {
            // At 1.0 an all-winning individual scores exactly 0.0 and the win band
            // collapses to a point -- the precise failure this form was written to avoid.
            state.output.warning("win-bonus must be in [0.0, 1.0); using 0.5.");
            winBonus = 0.5;
        }

        repeats = state.parameters.getIntWithDefault(base.push(P_REPEATS), null, 1);
        if (repeats < 1) {
            state.output.warning("repeats must be at least 1; using 1.");
            repeats = 1;
        }

        noneDuration = state.parameters.getIntWithDefault(base.push(P_NONE_DURATION), null, 10);
        if (noneDuration < 1) {
            state.output.warning("none-duration must be at least 1; using 1.");
            noneDuration = 1;
        }

        stochastic = new HashSet<>();
        String[] declared = splitList(state.parameters.getString(base.push(P_STOCHASTIC), null));
        for (String s : (declared == null || declared.length == 0) ? DEFAULT_STOCHASTIC : declared) {
            stochastic.add(s.toLowerCase(Locale.ROOT));
        }

        evalLo = state.parameters.getDoubleWithDefault(base.push(P_EVAL_LO), null, 0.0);
        evalHi = state.parameters.getDoubleWithDefault(base.push(P_EVAL_HI), null, 1.0);
        calibrate = state.parameters.getBoolean(base.push(P_CALIBRATE), null, true);
        if (evalHi <= evalLo) {
            state.output.warning("eval-hi must exceed eval-lo; falling back to 0..1.");
            evalLo = 0.0;
            evalHi = 1.0;
        }

        utt = new UnitTypeTable();
        stateEval = new SimpleSqrtEvaluationFunction3();

        validateMapsAndOpponents(state, base);

        state.output.message("microRTS fitness: " + gamesPerIndividual() + " games per individual"
                + " (" + maps.length + " maps x " + opponents.length + " opponents"
                + (swapSides ? " x 2 sides" : "") + ", " + repeats + " repeats on stochastic"
                + " opponents), cap " + maxCycles + " cycles.");
        state.output.message("Stochastic opponents this run: " + stochasticInUse());
        state.output.message("Unit NONE (wait) action lasts " + noneDuration + " cycle(s)"
                + (noneDuration == 10 ? " -- engine default, as in stages 1-5." : "."));
        state.output.message("Banded fitness: win 0-1, draw 1-2, loss 2-3."
                + " Loss band split " + round2(survivalShare) + " survival / "
                + round2(qualityShare) + " quality."
                + " Win reward: fitness x (1 - " + round2(winBonus) + " x win fraction)."
                + " meanEval mapped from [" + round2(evalLo) + ", " + round2(evalHi) + "].");
    }

    /**
     * See the 2026-10 block in the class comment: a bad name here would otherwise turn
     * into a silently flat landscape rather than an error.
     */
    private void validateMapsAndOpponents(final EvolutionState state, final Parameter base) {
        for (String m : maps) {
            if (!new File(m).isFile()) {
                state.output.fatal("Map not found: '" + m + "' (relative to working directory "
                        + System.getProperty("user.dir") + "). A missing map makes every game"
                        + " throw and every individual score 3.0.", base.push(P_MAPS));
            }
        }
        for (String o : opponents) {
            AI probe = null;
            String why = "returned null";
            try {
                probe = Panel.make(o, utt);
            } catch (Exception e) {
                why = e.toString();
            }
            if (probe == null) {
                state.output.fatal("Opponent '" + o + "' cannot be constructed (" + why + ")."
                        + " An unknown opponent makes every game against it score 3.0.",
                        base.push(P_OPPONENTS));
            }
        }
    }

    /**
     * Per-thread evaluation context.
     *
     * GPProblem.clone() already deep-copies 'input' (the ScoreData a tree scores into) and
     * 'stack'. Everything else is a shallow field copy, so without this override all eval
     * threads would share one UnitTypeTable and one EvaluationFunction. Neither is
     * documented as thread-safe, and a shared UnitTypeTable is handed to both bots and to
     * every PhysicalGameState.load() call in Panel.
     *
     * Per-thread tables stay self-consistent because playOne() threads a single utt through
     * Panel.make() and Panel.play() for the whole game, and Features compares unit types by
     * NAME rather than by identity, so nothing ever compares a type across two tables.
     */
    @Override
    public Object clone() {
        MicroRTSProblem p = (MicroRTSProblem) super.clone();
        p.utt = new UnitTypeTable();
        p.stateEval = new SimpleSqrtEvaluationFunction3();
        return p;
    }

    /** How many games one evaluation actually costs, given per-opponent repeats. */
    private int gamesPerIndividual() {
        int sides = swapSides ? 2 : 1;
        int perMap = 0;
        for (String o : opponents) perMap += sides * repeatsFor(o);
        return maps.length * perMap;
    }

    private String stochasticInUse() {
        StringBuilder sb = new StringBuilder();
        for (String o : opponents) {
            if (isStochastic(o)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(o);
            }
        }
        return sb.length() == 0 ? "(none -- every matchup is deterministic, repeats unused)"
                                : sb.toString();
    }

    private boolean isStochastic(String opponent) {
        return stochastic.contains(opponent.trim().toLowerCase(Locale.ROOT));
    }

    /** Deterministic matchups replay identically, so repeating them buys nothing. */
    private int repeatsFor(String opponent) {
        return isStochastic(opponent) ? repeats : 1;
    }

    private static String[] splitList(String s) {
        if (s == null) return null;
        String[] parts = s.trim().split("\\s*,\\s*");
        return (parts.length == 1 && parts[0].isEmpty()) ? new String[0] : parts;
    }

    @Override
    public void evaluate(final EvolutionState state, final Individual ind,
                         final int subpopulation, final int threadnum) {
        if (ind.evaluated) return;

        double total = 0.0;
        int games = 0;
        int wins = 0;

        for (String map : maps) {
            for (String opponent : opponents) {
                int sides = swapSides ? 2 : 1;
                int reps = repeatsFor(opponent);
                for (int side = 0; side < sides; side++) {
                    for (int rep = 0; rep < reps; rep++) {
                        double f;
                        try {
                            // Fresh bot AND fresh opponent per game. This matters more now
                            // that games repeat: a reused opponent would carry internal state
                            // across repeats and the second game would not be an independent
                            // sample.
                            EvolvedBot ours = new EvolvedBot(utt, (GPIndividual) ind, state,
                                                             threadnum, this, (ScoreData) input, stack);
                            Panel.Result r = playOne(ours, map, opponent, side);
                            note(state, r.meanEval);
                            noteTies(state, ours);
                            f = score(r);
                            if (r.won()) wins++;
                        } catch (Exception e) {
                            // A crashing individual is a bad individual, not a crashed
                            // run. 3.0 is the bottom of the loss band, so it ranks below
                            // every individual that merely played badly.
                            state.output.warnOnce("Individual threw during evaluation: " + e);
                            f = 3.0;
                        }
                        total += f;
                        games++;
                    }
                }
            }
        }

        reportCalibration(state);
        reportTies(state);

        // Averaging over repeats is what shrinks the variance; the win reward then works
        // off a win FRACTION that reflects reliability rather than a single lucky game.
        //
        // MULTIPLICATIVE, NOT SUBTRACTED. A subtracted reward larger than the win band
        // (which it must be, to outrank draws) drives every all-winning individual to the
        // same clamped 0.0 and destroys the gradient exactly where the population lands.
        // Scaling is proportional, so it can never saturate: see the class comment.
        double mean = total / games;
        double winFrac = (double) wins / games;

        KozaFitness fit = (KozaFitness) ind.fitness;
        fit.setStandardizedFitness(state, mean * (1.0 - winBonus * winFrac));
        fit.hits = wins;                     // 'hits' reads as games won, in the .stat file
        ind.evaluated = true;
    }

    private Panel.Result playOne(EvolvedBot ours, String map, String opponent, int side)
            throws Exception {
        AI theirs = Panel.make(opponent, utt);
        return Panel.play(ours, theirs, map, utt, side, maxCycles, stateEval, 50);
    }

    /** Turn one finished game into a standardized fitness contribution. Lower is better. */
    private double score(Panel.Result r) {
        double timeFrac = clamp01((double) r.cyclesTaken / maxCycles);
        double quality = normaliseEval(r.meanEval);

        if (r.won()) {
            // 0.0 - 1.0. Win fast.
            return timeFrac;
        }

        if (r.drew()) {
            // 1.0 - 2.0. A draw always means the cycle cap was hit, so game length
            // carries no information -- quality of play is the whole gradient, and it is
            // what separates "razed everything but the last base" from "turtled".
            return 1.0 + (1.0 - quality);
        }

        // 2.0 - 3.0. Surviving longer is rewarded, NOT penalised.
        double survival = timeFrac;
        return 2.0 + (1.0 - (survivalShare * survival + qualityShare * quality));
    }

    private double normaliseEval(double raw) {
        return clamp01((raw - evalLo) / (evalHi - evalLo));
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static String pct(long num, long den) {
        return (Math.round(1000.0 * num / den) / 10.0) + "%";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Accumulate one observed meanEval into the shared per-generation range. */
    private void note(final EvolutionState state, final double raw) {
        if (!calibrate) return;
        synchronized (CAL_LOCK) {
            if (state.generation != calGeneration) {
                calGeneration = state.generation;
                calLo = Double.POSITIVE_INFINITY;
                calHi = Double.NEGATIVE_INFINITY;
                calCount = 0;
                calReported = false;
            }
            if (raw < calLo) calLo = raw;
            if (raw > calHi) calHi = raw;
            calCount++;
        }
    }

    /**
     * Reports the observed meanEval range once per generation. If it falls outside
     * [eval-lo, eval-hi] the quality term is clamping and is silently constant for those
     * games -- the flat landscape failure again, wearing a different hat.
     */
    private void reportCalibration(final EvolutionState state) {
        if (!calibrate) return;

        double lo, hi;
        synchronized (CAL_LOCK) {
            if (calReported || calCount < 200 || state.generation != calGeneration) return;
            calReported = true;
            lo = calLo;
            hi = calHi;
        }

        boolean bad = lo < evalLo - 0.02 || hi > evalHi + 0.02;
        state.output.message("meanEval generation " + state.generation + ": ["
                + round2(lo) + ", " + round2(hi) + "]  mapped from ["
                + round2(evalLo) + ", " + round2(evalHi) + "]"
                + (bad ? "   <-- CLAMPING, recalibrate" : "   ok"));
    }

    /** Accumulate one game's tie counts into the shared per-generation totals. */
    private void noteTies(final EvolutionState state, final EvolvedBot bot) {
        synchronized (TIE_LOCK) {
            if (state.generation != tieGeneration) {
                tieGeneration = state.generation;
                java.util.Arrays.fill(tieDecisions, 0);
                java.util.Arrays.fill(tieTied, 0);
                java.util.Arrays.fill(tieCross, 0);
                tieGames = 0;
                tieReported = false;
            }
            for (int r = 0; r < 4; r++) {
                tieDecisions[r] += bot.decisionsByRole[r];
                tieTied[r] += bot.tiesByRole[r];
                tieCross[r] += bot.crossTypeTiesByRole[r];
            }
            tieGames++;
        }
    }

    /**
     * Prints, per role, the share of multi-choice decisions that were decided by
     * getUnitActions() list order rather than by the tree. High numbers mean the engine's
     * fixed priority (attack > harvest > return > produce > move > none) is doing the
     * playing for that role.
     */
    private void reportTies(final EvolutionState state) {
        long[] d = new long[4], t = new long[4], c = new long[4];
        int g;
        synchronized (TIE_LOCK) {
            if (tieReported || tieGames < TIE_SAMPLE_GAMES || state.generation != tieGeneration) return;
            tieReported = true;
            System.arraycopy(tieDecisions, 0, d, 0, 4);
            System.arraycopy(tieTied, 0, t, 0, 4);
            System.arraycopy(tieCross, 0, c, 0, 4);
            g = tieGames;
        }
        // Format: role all-ties% / cross-type% . The second number is the one that
        // matters: it is the share of decisions where list order chose WHAT to do, not
        // merely which of several equivalent placements or steps.
        StringBuilder sb = new StringBuilder("tie rate generation " + state.generation
                + " (first " + g + " games, all / cross-type):");
        for (int r = 0; r < 4; r++) {
            sb.append("  ").append(EvolvedBot.ROLE_NAMES[r]).append(' ');
            sb.append(d[r] == 0 ? "n/a" : pct(t[r], d[r]) + " / " + pct(c[r], d[r]));
        }
        state.output.message(sb.toString());
    }
}
