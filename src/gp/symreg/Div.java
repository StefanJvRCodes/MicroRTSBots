package gp.symreg;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;

/**
 * Protected division: returns 1.0 when the denominator is (near) zero.
 *
 * This is the CLOSURE property in practice. Crossover splices arbitrary subtrees
 * together, so any child can end up under any parent; every function must therefore
 * accept every possible input without throwing. Raw '/' would divide by zero and
 * kill the run, so we define a total version instead.
 *
 * The same discipline applies to every microRTS function we add later: no exceptions,
 * no NaN, no infinities escaping upward.
 */
public class Div extends GPNode {
    public String toString() { return "/"; }

    public int expectedChildren() { return 2; }

    public void eval(final EvolutionState state, final int thread, final GPData input,
                     final ADFStack stack, final GPIndividual individual, final Problem problem) {
        SymRegData rd = (SymRegData) input;
        children[0].eval(state, thread, input, stack, individual, problem);
        double numerator = rd.x;
        children[1].eval(state, thread, input, stack, individual, problem);
        rd.x = (Math.abs(rd.x) < 1e-9) ? 1.0 : numerator / rd.x;
    }
}
