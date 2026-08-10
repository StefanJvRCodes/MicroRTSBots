package gp.nodes;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.ERC;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.util.Code;
import ec.util.Parameter;
import ec.util.DecodeReturn;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Ephemeral Random Constant for the microRTS scorer.
 *
 * Range matters here in a way it did not for symbolic regression: these become the
 * scoring weights that decide between "harvest" and "attack", so drawing from [-1, 1)
 * would force evolution to build large weights out of repeated addition. Default is
 * [-10, 10), overridable per function-set entry.
 * then frozen for that node's lifetime (it is inherited unchanged through crossover).
 *
 * ERCs are how GP invents numeric coefficients it was never given. For microRTS this
 * is exactly the mechanism that will replace Chimera's hand-picked scoring weights:
 * we never specify "harvesting is worth 8.0", evolution finds the number.
 */
public class ScoreERC extends ERC {

    public static final String P_RANGE = "range";

    /** ERCs are drawn from [-range, range). Set gp.fs.0.func.N.range in the params. */
    public double range = 10.0;

    public double value;

    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);
        range = state.parameters.getDoubleWithDefault(base.push(P_RANGE), null, 10.0);
    }

    @Override
    public void resetNode(final EvolutionState state, final int thread) {
        value = (state.random[thread].nextDouble() * 2.0 - 1.0) * range;
    }

    @Override
    public int nodeHashCode() {
        long l = Double.doubleToLongBits(value);
        return this.getClass().hashCode() + (int) (l & 0xFFFFFFFFL) + (int) (l >>> 32);
    }

    @Override
    public boolean nodeEquals(final GPNode node) {
        if (this.getClass() != node.getClass()) return false;
        return ((ScoreERC) node).value == value;
    }

    @Override
    public void readNode(final EvolutionState state, final DataInput in) throws IOException {
        value = in.readDouble();
    }

    @Override
    public void writeNode(final EvolutionState state, final DataOutput out) throws IOException {
        out.writeDouble(value);
    }

    @Override
    public String encode() { return Code.encode(value); }

    @Override
    public boolean decode(final DecodeReturn dret) {
        int pos = dret.pos;
        String data = dret.data;
        Code.decode(dret);
        if (dret.type != DecodeReturn.T_DOUBLE) {
            dret.data = data;
            dret.pos = pos;
            return false;
        }
        value = dret.d;
        return true;
    }

    @Override
    public String toStringForHumans() { return String.format("%.4f", value); }

    @Override
    public void eval(final EvolutionState state, final int thread, final GPData input,
                     final ADFStack stack, final GPIndividual individual, final Problem problem) {
        ((gp.ScoreData) input).score = value;
    }
}
