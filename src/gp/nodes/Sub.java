package gp.nodes;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import gp.ScoreData;

/** Arity-2 function: (-). */
public class Sub extends GPNode {
    public String toString() { return "-"; }

    public int expectedChildren() { return 2; }

    public void eval(final EvolutionState state, final int thread, final GPData input,
                     final ADFStack stack, final GPIndividual individual, final Problem problem) {
        ScoreData rd = (ScoreData) input;
        children[0].eval(state, thread, input, stack, individual, problem);
        double left = rd.score;
        children[1].eval(state, thread, input, stack, individual, problem);
        rd.score = Guard.finite(left - rd.score);
    }
}
