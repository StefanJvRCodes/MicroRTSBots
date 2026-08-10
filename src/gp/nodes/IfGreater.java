package gp.nodes;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import gp.ScoreData;

/**
 * Arity-4 conditional, Koza style: (if-greater a b then else)
 * evaluates to 'then' when a > b, otherwise 'else'.
 *
 * This is the ONLY genuinely non-arithmetic function in the set, and the only way the
 * tree can express "behave differently depending on the situation" -- a worker near a
 * resource doing something other than a worker near an enemy. Arithmetic can approximate
 * conditionals by multiplying through boolean features, but it cannot switch cleanly.
 *
 * Note that only two of the four children are evaluated per call, so the untaken branch
 * costs nothing at runtime but still carries its subtree through crossover. That is
 * where much of GP's "junk DNA" accumulates.
 */
public class IfGreater extends GPNode {
    public String toString() { return "if>"; }

    public int expectedChildren() { return 4; }

    public void eval(final EvolutionState state, final int thread, final GPData input,
                     final ADFStack stack, final GPIndividual individual, final Problem problem) {
        ScoreData rd = (ScoreData) input;
        children[0].eval(state, thread, input, stack, individual, problem);
        double a = rd.score;
        children[1].eval(state, thread, input, stack, individual, problem);
        double b = rd.score;
        children[(a > b) ? 2 : 3].eval(state, thread, input, stack, individual, problem);
    }
}
