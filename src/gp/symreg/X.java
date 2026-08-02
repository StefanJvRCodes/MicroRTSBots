package gp.symreg;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;

/**
 * Terminal: the independent variable x, read from the problem's current test case.
 *
 * This is the shape every microRTS terminal will take: a zero-arity GPNode that
 * reads one fact out of the surrounding context and writes it into the GPData.
 * There, instead of problem.currentValue, it will call gp.Features.someMethod(...).
 */
public class X extends GPNode {
    public String toString() { return "x"; }

    public int expectedChildren() { return 0; }

    public void eval(final EvolutionState state, final int thread, final GPData input,
                     final ADFStack stack, final GPIndividual individual, final Problem problem) {
        ((SymRegData) input).x = ((SymRegProblem) problem).currentValue;
    }
}
