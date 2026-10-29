package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class ResourcesAtLeast extends BoolTerminal implements PerturbableTerminal {
    public static final String NAME = "ResourcesAtLeast";
    private static final double MIN = 0.05, MAX = 1.0, STEP = 0.1;
    private static final double AREA_PER_UNIT = 256.0 / 20;

    private final double fraction;

    public ResourcesAtLeast(double fraction) {
        this.fraction = fraction;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<String> getParams() {
        return Collections.singletonList(String.valueOf(fraction));
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        int have = ctx.player.getResources();
        return have >= GPUtil.absoluteCount(ctx.pgs, fraction, AREA_PER_UNIT);
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new ResourcesAtLeast(GPUtil.perturb(fraction, STEP, MIN, MAX, rnd));
    }
}
