package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPTurnContext;

public class IsCarryingResources extends BoolTerminal {
    public static final String NAME = "IsCarryingResources";

    @Override
    public String getName() { return NAME; }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return ctx.unit.getResources() > 0;
    }
}
