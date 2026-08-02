package gp.symreg;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.ERC;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.util.Code;
import ec.util.DecodeReturn;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Ephemeral Random Constant, drawn once from [-1, 1) when the node is created and
 * then frozen for that node's lifetime (it is inherited unchanged through crossover).
 *
 * ERCs are how GP invents numeric coefficients it was never given. For microRTS this
 * is exactly the mechanism that will replace Chimera's hand-picked scoring weights:
 * we never specify "harvesting is worth 8.0", evolution finds the number.
 */
public class SymRegERC extends ERC {
    public double value;

    @Override
    public void resetNode(final EvolutionState state, final int thread) {
        value = state.random[thread].nextDouble() * 2.0 - 1.0;
    }

    @Override
    public int nodeHashCode() {
        long l = Double.doubleToLongBits(value);
        return this.getClass().hashCode() + (int) (l & 0xFFFFFFFFL) + (int) (l >>> 32);
    }

    @Override
    public boolean nodeEquals(final GPNode node) {
        if (this.getClass() != node.getClass()) return false;
        return ((SymRegERC) node).value == value;
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
        ((SymRegData) input).x = value;
    }
}
