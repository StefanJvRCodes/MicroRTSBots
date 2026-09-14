package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;

public class BuildBarracks extends ActionTerminal {
    public static final String NAME = "BuildBarracks";

    @Override
    public String getName() { return NAME; }

    @Override
    public void exec(GPTurnContext ctx) {
        Actions.build(ctx, "Barracks");
    }
}
