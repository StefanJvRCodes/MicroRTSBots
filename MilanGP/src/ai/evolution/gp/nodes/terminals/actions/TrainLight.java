package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;

public class TrainLight extends ActionTerminal {
    public static final String NAME = "TrainLight";

    @Override
    public String getName() { return NAME; }

    @Override
    public void exec(GPTurnContext ctx) {
        Actions.train(ctx, "Light");
    }
}
