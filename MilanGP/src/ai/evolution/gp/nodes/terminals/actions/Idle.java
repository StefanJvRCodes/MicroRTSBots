package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;

public class Idle extends ActionTerminal {
    public static final String NAME = "Idle";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void exec(GPTurnContext ctx) {
        ctx.ai.idle(ctx.unit);
    }
}
