package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;

public class OwnHasBarracks extends BoolTerminal {
    public static final String NAME = "OwnHasBarracks";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return GPUtil.any(ctx.pgs, u -> u.getPlayer() == ctx.playerID && GPUtil.isBarracks(u));
    }
}
