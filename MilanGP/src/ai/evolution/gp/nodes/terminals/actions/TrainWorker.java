package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;

/** Trains a Worker if this unit can produce one and the player can afford it. */
public class TrainWorker extends ActionTerminal {
    public static final String NAME = "TrainWorker";

    @Override
    public String getName() { return NAME; }

    @Override
    public void exec(GPTurnContext ctx) {
        Actions.train(ctx, "Worker");
    }
}
