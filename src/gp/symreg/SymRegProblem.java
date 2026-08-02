package gp.symreg;

import ec.EvolutionState;
import ec.Individual;
import ec.gp.GPIndividual;
import ec.gp.GPProblem;
import ec.gp.koza.KozaFitness;
import ec.simple.SimpleProblemForm;
import ec.util.Parameter;

/**
 * SMOKE TEST ONLY (Phase 3b, step 1).
 *
 * Koza's quartic symbolic regression: recover y = x^4 + x^3 + x^2 + x on [-1, 1]
 * from 20 sampled points, using only { +, -, *, protected /, x, ERC }.
 *
 * The point is NOT the maths. The point is to prove that ECJ initialises a population,
 * evaluates it, selects, crosses over, mutates, and drives fitness down in THIS build
 * setup (this JDK, this classpath, this params file) before we spend a single game of
 * microRTS on it. If this run does not converge, the bug is in the wiring, not in the
 * game. Delete this package once the microRTS problem replaces it.
 *
 * WHERE THE MICRORTS VERSION DIFFERS
 * ----------------------------------
 * Only evaluate() changes shape. Instead of looping over 20 (x, y) pairs and summing
 * absolute error, it will:
 *   1. wrap the individual's tree in a scorer,
 *   2. hand that scorer to a bot that plays a full microRTS game against a training
 *      script (WorkerRush / LightRush / NaiveMCTS),
 *   3. turn the final GameState into a standardized fitness (lower is better), with a
 *      time term so that winning FAST beats surviving to the cycle cap.
 * Everything else here -- setup(), the KozaFitness contract, the `evaluated` guard --
 * carries over unchanged.
 */
public class SymRegProblem extends GPProblem implements SimpleProblemForm {

    private static final long serialVersionUID = 1L;

    public static final String P_SIZE = "size";

    /** The x of the test case currently being evaluated; the X terminal reads this. */
    public double currentValue;

    public int trainingSetSize;
    public double[] inputs;
    public double[] outputs;

    /** The target function we are asking evolution to rediscover. */
    public double func(double x) {
        return x * x * x * x + x * x * x + x * x + x;
    }

    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);

        if (!(input instanceof SymRegData)) {
            state.output.fatal("GPData class must subclass from " + SymRegData.class,
                    base.push(P_DATA), null);
        }

        trainingSetSize = state.parameters.getInt(base.push(P_SIZE), null, 1);
        if (trainingSetSize < 1) {
            state.output.fatal("Training set size must be >= 1", base.push(P_SIZE));
        }

        inputs = new double[trainingSetSize];
        outputs = new double[trainingSetSize];
        for (int i = 0; i < trainingSetSize; i++) {
            inputs[i] = state.random[0].nextDouble(true, true) * 2.0 - 1.0;  // [-1, 1]
            outputs[i] = func(inputs[i]);
        }
    }

    @Override
    public void evaluate(final EvolutionState state, final Individual ind,
                         final int subpopulation, final int threadnum) {
        if (ind.evaluated) return;   // fitness is deterministic here, so never redo it

        SymRegData data = (SymRegData) this.input;

        final double HIT_LEVEL = 0.01;
        final double PROBABLY_ZERO = 1.11E-15;
        final double BIG_NUMBER = 1.0e15;

        int hits = 0;
        double sum = 0.0;

        for (int i = 0; i < trainingSetSize; i++) {
            currentValue = inputs[i];

            // Evaluate the whole tree once per test case. The result lands in data.x.
            ((GPIndividual) ind).trees[0].child.eval(
                    state, threadnum, data, stack, (GPIndividual) ind, this);

            double error = Math.abs(outputs[i] - data.x);

            // Guard against NaN/Infinity: note the deliberate !(error < BIG) phrasing,
            // because NaN compares false against everything.
            if (!(error < BIG_NUMBER)) error = BIG_NUMBER;
            else if (error < PROBABLY_ZERO) error = 0.0;

            if (error <= HIT_LEVEL) hits++;
            sum += error;
        }

        // KozaFitness is STANDARDIZED: 0 is perfect, larger is worse. When we move to
        // microRTS, remember to invert win rate accordingly (or use setStandardizedFitness
        // with a loss-shaped quantity), otherwise evolution will optimise for losing.
        KozaFitness f = (KozaFitness) ind.fitness;
        f.setStandardizedFitness(state, sum);
        f.hits = hits;
        ind.evaluated = true;
    }
}
