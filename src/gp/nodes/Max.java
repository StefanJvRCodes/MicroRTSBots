package gp.nodes;

import ec.EvolutionState;
import ec.Problem;
import ec.gp.ADFStack;
import ec.gp.GPData;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import gp.ScoreData;

/**
 * Two-argument max. Mirrors gp.nodes.Min exactly apart from the operation
 * and toString. Referenced by config/microrts.params as gp.fs.0.func.5 --
 * ECJ resolves it reflectively at setup, so a missing class here fails at
 * "Initializing Generation 0" rather than at compile time.
 *
 * Place at: src/gp/nodes/Max.java
 */
public class Max extends GPNode {

    public String toString() { return "max"; }

    public int expectedChildren() { return 2; }

    public void eval(EvolutionState state, int thread, GPData input,
                     ADFStack stack, GPIndividual individual, Problem problem) {
        ScoreData sd = (ScoreData) input;
        children[0].eval(state, thread, input, stack, individual, problem);
        double a = sd.score;
        children[1].eval(state, thread, input, stack, individual, problem);
        sd.score = Math.max(a, sd.score);
    }
}
