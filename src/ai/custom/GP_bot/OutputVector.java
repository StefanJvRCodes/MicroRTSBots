import java.util.Arrays;
import java.util.Random;

/**
 * The "virtual" output vector from the Modi paper: it exists only for the
 * duration of one evaluation, gets updated additively by whichever Modi
 * nodes fire during that evaluation, and is otherwise not part of the
 * persistent program representation (only the tree is).
 */
public class OutputVector {

    public final double[] cells;

    public OutputVector(int size) {
        this.cells = new double[size];
    }

    public void reset() {
        Arrays.fill(cells, 0.0);
    }

    public void add(int index, double value) {
        cells[index] += value;
    }

    /** Original paper behavior: winner-takes-all classification. */
    public int argmax() {
        int best = 0;
        for (int i = 1; i < cells.length; i++) {
            if (cells[i] > cells[best]) best = i;
        }
        return best;
    }

    /**
     * Converts the raw output-vector magnitudes into an action probability
     * distribution via temperature-scaled softmax:
     *
     *     P(a) = exp(cells[a] / T) / sum_b exp(cells[b] / T)
     *
     * MAGNITUDE DRIFT — read before using this for a policy:
     * Modi cells are built by *addition* across however many Modi nodes
     * happen to route to them. There's no learned/calibrated output scale
     * the way a neural net's final layer would have, so raw magnitudes
     * aren't comparable across programs, across generations, or even across
     * cells of the same program. Feed that straight into softmax with T=1
     * and you'll typically get near one-hot distributions regardless of how
     * close the underlying values actually are (killing exploration), or
     * occasionally the opposite (a program whose cells are all tiny nets a
     * nearly-uniform distribution even when it clearly "prefers" one
     * action). Always tune {@code temperature} per-problem — don't assume
     * T=1 is meaningful here the way it might be after a calibrated network
     * layer. A reasonable starting point is to set T proportional to the
     * typical spread (e.g. std-dev) of cell values you observe across a
     * batch of evaluations, and treat it as its own evolvable/tunable
     * parameter rather than a fixed constant.
     */
    public double[] softmax(double temperature) {
        if (temperature <= 0) {
            throw new IllegalArgumentException("temperature must be > 0");
        }
        double max = Double.NEGATIVE_INFINITY;
        for (double v : cells) {
            max = Math.max(max, v / temperature);
        }

        double[] exp = new double[cells.length];
        double sum = 0.0;
        for (int i = 0; i < cells.length; i++) {
            exp[i] = Math.exp(cells[i] / temperature - max);
            sum += exp[i];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }

    /** Samples an action index from the temperature-scaled softmax distribution. */
    public int sampleAction(double temperature, Random random) {
        double[] probs = softmax(temperature);
        double r = random.nextDouble();
        double cumulative = 0.0;
        for (int i = 0; i < probs.length; i++) {
            cumulative += probs[i];
            if (r <= cumulative) return i;
        }
        return probs.length - 1; // floating-point safety net
    }

    @Override
    public String toString() {
        return Arrays.toString(cells);
    }
}
