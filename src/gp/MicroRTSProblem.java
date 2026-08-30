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
 * ...then a WIN BONUS is subtracted from the mean, proportional to the fraction of games
 * won, because bands fix ONE game and fitness averages MANY (see below).
 *
 * WHY BANDS. An earlier form was outcome + speed + marginWeight*margin, which ordered
 * win < draw < loss correctly but left only 0.25 of shaping room inside a band against a
 * gap of 1.0 between bands. Two draws that played very differently scored within a
 * quarter-point of each other, selection could barely tell them apart, and the population
 * sat in the draw band for eleven generations. Bands give each outcome the full unit
 * interval while keeping the ordering un-invertible.
 *
 * WHY A WIN BONUS ON TOP. An individual beating PassiveAI on both sides and losing to
 * RandomBiasedAI on both sides averages about (0.3+0.3+2.5+2.5)/4 = 1.40; one that draws
 * everything averages 1.50. The winner led by 0.1 inside a band spanning 1.0, so a
 * marginally better draw outranked a real win and evolution learned the safe uniform
 * strategy. The bonus makes winning worth more than any amount of drawing well.
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
 * With PassiveAI + RandomBiasedAI and repeats=3, that is 2 + 6 = 8 games per individual
 * rather than the 12 a blanket repeat would cost.
 *
 * The two findings from earlier sessions are preserved and still shape this file:
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
    public double winBonus;

    public int repeats;
    public Set<String> stochastic;

    /**
     * Range of Panel.Result.meanEval, used to map raw evaluation values onto 0..1.
     * SimpleSqrtEvaluationFunction3 is unbounded, so hardcoding a scale would be a guess.
     * IMPORTANT: the range is opponent-dependent -- bounds calibrated on PassiveAI were
     * badly wrong once RandomBiasedAI joined, and most of the distribution clamped to
     * zero, silently flattening the quality term. Recalibrate whenever opponents change.
     */
    public double evalLo;
    public double evalHi;
    public boolean calibrate;

    public transient UnitTypeTable utt;
    public transient EvaluationFunction stateEval;

    private transient double seenLo = Double.POSITIVE_INFINITY;
    private transient double seenHi = Double.NEGATIVE_INFINITY;
    private transient int seenCount = 0;
    private transient boolean calibrationReported = false;

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

        repeats = state.parameters.getIntWithDefault(base.push(P_REPEATS), null, 1);
        if (repeats < 1) {
            state.output.warning("repeats must be at least 1; using 1.");
            repeats = 1;
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

        state.output.message("microRTS fitness: " + gamesPerIndividual() + " games per individual"
                + " (" + maps.length + " maps x " + opponents.length + " opponents"
                + (swapSides ? " x 2 sides" : "") + ", " + repeats + " repeats on stochastic"
                + " opponents), cap " + maxCycles + " cycles.");
        state.output.message("Stochastic opponents this run: " + stochasticInUse());
        state.output.message("Banded fitness: win 0-1, draw 1-2, loss 2-3."
                + " Loss band split " + round2(survivalShare) + " survival / "
                + round2(qualityShare) + " quality."
                + " Win bonus " + round2(winBonus) + " x win fraction."
                + " meanEval mapped from [" + round2(evalLo) + ", " + round2(evalHi) + "].");
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
                            Panel.Result r = playOne((GPIndividual) ind, state, threadnum,
                                                     map, opponent, side);
                            note(r.meanEval);
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

        // Averaging over repeats is what shrinks the variance; the win bonus then works
        // off a win FRACTION that reflects reliability rather than a single lucky game.
        double mean = total / games;
        double bonus = winBonus * ((double) wins / games);

        KozaFitness fit = (KozaFitness) ind.fitness;
        fit.setStandardizedFitness(state, Math.max(0.0, mean - bonus));
        fit.hits = wins;                     // 'hits' reads as games won, in the .stat file
        ind.evaluated = true;
    }

    private Panel.Result playOne(GPIndividual ind, EvolutionState state, int threadnum,
                                 String map, String opponent, int side) throws Exception {
        // Fresh bot AND fresh opponent per game. This matters more now that games repeat:
        // a reused opponent would carry internal state across repeats and the second game
        // would not be an independent sample.
        EvolvedBot ours = new EvolvedBot(utt, ind, state, threadnum, this,
                                         (ScoreData) input, stack);
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

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void note(double raw) {
        if (!calibrate) return;
        if (raw < seenLo) seenLo = raw;
        if (raw > seenHi) seenHi = raw;
        seenCount++;
    }

    /**
     * Reports the observed meanEval range. If it falls outside [eval-lo, eval-hi] the
     * quality term is clamping and is silently constant for those games -- the flat
     * landscape failure again, wearing a different hat. ECJ reconstructs the Problem
     * between generations, so this is a per-generation range, which is more useful than
     * a single reading since the range drifts as the population changes.
     */
    private void reportCalibration(final EvolutionState state) {
        if (!calibrate || calibrationReported || seenCount < 200) return;
        calibrationReported = true;
        boolean bad = seenLo < evalLo - 0.02 || seenHi > evalHi + 0.02;
        state.output.message("meanEval this generation: [" + round2(seenLo) + ", "
                + round2(seenHi) + "]  mapped from [" + round2(evalLo) + ", "
                + round2(evalHi) + "]" + (bad ? "   <-- CLAMPING, recalibrate" : "   ok"));
    }
}