package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class OwnWorkersAtLeast extends BoolTerminal implements PerturbableTerminal {
    public static final String NAME = "OwnWorkersAtLeast";
    private static final int MIN = 1, MAX = 12;

    private final int count;

    public OwnWorkersAtLeast(int count) {
        this.count = Math.max(MIN, count);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<String> getParams() {
        return Collections.singletonList(String.valueOf(count));
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return GPUtil.count(ctx.pgs, u -> u.getPlayer() == ctx.playerID && u.getType().canHarvest) >= count;
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new OwnWorkersAtLeast(GPUtil.perturb(count, 1, MIN, MAX, rnd));
    }
}
