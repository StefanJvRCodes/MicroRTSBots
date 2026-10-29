package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class GameTimeAtLeast extends BoolTerminal implements PerturbableTerminal {
    public static final String NAME = "GameTimeAtLeast";
    private static final int MIN = 0, MAX = 10000;

    private final int cycle;

    public GameTimeAtLeast(int cycle) {
        this.cycle = Math.max(MIN, cycle);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<String> getParams() {
        return Collections.singletonList(String.valueOf(cycle));
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return ctx.gs.getTime() >= cycle;
    }

    @Override
    public GPNode perturb(Random rnd) {
        int step = 25 + rnd.nextInt(176);
        return new GameTimeAtLeast(GPUtil.perturb(cycle, step, MIN, MAX, rnd));
    }
}
