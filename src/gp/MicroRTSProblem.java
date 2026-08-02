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

/**
 * Fitness = play real microRTS games and score the outcome.
 *
 * THE SIGNAL (standardized: LOWER IS BETTER -- ECJ convention, and the easiest thing in
 * this whole file to get backwards):
 *
 *     fitness = outcome + speedPenalty + marginWeight * margin
 *
 *   outcome  0.0 win / 1.0 draw / 2.0 loss. The term that actually matters.
 *
 *   speed    up to speedWeight, proportional to cycles used. This is the fix for the
 *            turtle-draw failure seen in the M0 session: without it, "survive to the
 *            cycle cap" is a local optimum that evolution finds long before it finds
 *            aggression, because stalling is a far simpler program than winning.
 *            Kept small enough that a slow win always beats a fast draw.
 *
 *   margin   1 - normalised SimpleSqrtEvaluationFunction3 on the final state, so a bot
 *            that lost with a barracks and three workers standing outranks one that lost
 *            having built nothing. THIS TERM IS WHY GENERATION 0 IS NOT FLAT. With
 *            win/loss alone, every random tree loses to WorkerRush, all 500 individuals
 *            tie, selection has nothing to rank, and the run degenerates into random
 *            search until something wins a whole game by luck. The margin is the entire
 *            selection pressure for the first several generations -- it is what lets
 *            evolution climb from "does nothing" to "gathers" to "produces" to "wins".
 *
 * Averaged over every (opponent, map, side) combination. Sides are swapped because
 * first-player advantage on 8x8 is large enough to swamp the margin term.
 */
public class MicroRTSProblem extends GPProblem implements SimpleProblemForm {

    private static final long serialVersionUID = 1L;

    public static final String P_MAPS = "maps";
    public static final String P_OPPONENTS = "opponents";
    public static final String P_MAX_CYCLES = "max-cycles";
    public static final String P_SPEED_WEIGHT = "speed-weight";
    public static final String P_MARGIN_WEIGHT = "margin-weight";
    public static final String P_SWAP_SIDES = "swap-sides";

    public String[] maps;
    public String[] opponents;
    public int maxCycles;
    public double speedWeight;
    public double marginWeight;
    public boolean swapSides;

    public transient UnitTypeTable utt;
    public transient EvaluationFunction stateEval;

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
        speedWeight = state.parameters.getDoubleWithDefault(base.push(P_SPEED_WEIGHT), null, 0.5);
        marginWeight = state.parameters.getDoubleWithDefault(base.push(P_MARGIN_WEIGHT), null, 0.25);
        swapSides = state.parameters.getBoolean(base.push(P_SWAP_SIDES), null, true);

        utt = new UnitTypeTable();
        stateEval = new SimpleSqrtEvaluationFunction3();

        int games = maps.length * opponents.length * (swapSides ? 2 : 1);
        state.output.message("microRTS fitness: " + games + " games per individual"
                + " (" + maps.length + " maps x " + opponents.length + " opponents"
                + (swapSides ? " x 2 sides" : "") + "), cap " + maxCycles + " cycles.");
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
                for (int side = 0; side < sides; side++) {
                    double f;
                    try {
                        Panel.Result r = playOne((GPIndividual) ind, state, threadnum,
                                                 map, opponent, side);
                        f = score(r);
                        if (r.won()) wins++;
                    } catch (Exception e) {
                        // A crashing individual is a bad individual, not a crashed run.
                        state.output.warnOnce("Individual threw during evaluation: " + e);
                        f = 3.0;
                    }
                    total += f;
                    games++;
                }
            }
        }

        KozaFitness fit = (KozaFitness) ind.fitness;
        fit.setStandardizedFitness(state, total / games);
        fit.hits = wins;                     // 'hits' reads as games won, in the .stat file
        ind.evaluated = true;
    }

    private Panel.Result playOne(GPIndividual ind, EvolutionState state, int threadnum,
                                 String map, String opponent, int side) throws Exception {
        // Fresh bot per game: the tree is stateless, but lastScores and the timing
        // counters are not, and the opponent scripts hold internal state too.
        EvolvedBot ours = new EvolvedBot(utt, ind, state, threadnum, this,
                                         (ScoreData) input, stack);
        AI theirs = Panel.make(opponent, utt);
        // Sample the state evaluation during play -- see Panel.Result.meanEval for why
        // the final state alone cannot carry this signal.
        return Panel.play(ours, theirs, map, utt, side, maxCycles, stateEval, 50);
    }

    /** Turn one finished game into a standardized fitness contribution. */
    private double score(Panel.Result r) {
        double outcome = r.won() ? 0.0 : (r.drew() ? 1.0 : 2.0);

        // Speed is only meaningful on a WIN. Applying a cycles-used penalty to losses
        // pays evolution to lose FASTER -- a suiciding bot then outscores one that
        // survives, which is the exact opposite of the pressure we want. Draws take the
        // full penalty (a draw means it hit the cap; that is the turtle case), and
        // losses take a constant, so game length creates no gradient among losses.
        double fraction = Math.min(1.0, (double) r.cyclesTaken / maxCycles);
        double speed;
        if (r.won())       speed = speedWeight * fraction;          // win fast
        else if (r.drew()) speed = speedWeight;                     // hit the cap: full penalty
        else               speed = speedWeight * (1.0 - fraction);  // among losses, survive longer

        // Quality of play, averaged over the whole game rather than read off the final
        // state. A loss always ends with zero units, so the final state is identical for
        // every losing individual -- measuring it there makes this term constant and the
        // landscape flat, which is exactly what happened on the first run.
        double margin = 1.0 - r.meanEval;

        return outcome + speed + marginWeight * margin;
    }
}
