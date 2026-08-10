package gp.nodes;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import gp.ScoreData;

/**
 * Protected division: returns 1.0 when the denominator is (near) zero.
 *
 * Worth knowing before you read evolved trees: this makes (/ x x) a way to manufacture
 * the constant 1 out of any terminal. The symbolic-regression smoke test did exactly
 * that at generation 2. Expect the same trick here with boolean-valued features.
 */
public class Div extends GPNode {
    public String toString() { return "/"; }

    public int expectedChildren() { return 2; }

    public void eval(final EvolutionState state, final int thread, final GPData input,
                     final ADFStack stack, final GPIndividual individual, final Problem problem) {
        ScoreData rd = (ScoreData) input;
        children[0].eval(state, thread, input, stack, individual, problem);
        double numerator = rd.score;
        children[1].eval(state, thread, input, stack, individual, problem);
        rd.score = (Math.abs(rd.score) < 1e-9) ? 1.0 : Guard.finite(numerator / rd.score);
    }
}
