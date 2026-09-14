package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPTurnContext;

public class CanHarvest extends BoolTerminal {
    public static final String NAME = "CanHarvest";

    @Override
    public String getName() { return NAME; }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return ctx.unit.getType().canHarvest;
    }
}
