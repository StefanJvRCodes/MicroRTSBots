package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;

public class IsMilitary extends BoolTerminal {
    public static final String NAME = "IsMilitary";

    @Override
    public String getName() { return NAME; }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return GPUtil.isMilitary(ctx.unit);
    }
}
