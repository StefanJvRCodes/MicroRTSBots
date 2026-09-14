package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;

public class BuildBase extends ActionTerminal {
    public static final String NAME = "BuildBase";

    @Override
    public String getName() { return NAME; }

    @Override
    public void exec(GPTurnContext ctx) {
        Actions.build(ctx, "Base");
    }
}
