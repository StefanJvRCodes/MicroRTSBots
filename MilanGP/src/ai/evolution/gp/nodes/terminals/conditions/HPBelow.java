package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/** True when the unit's hit points are below a fraction of its maximum. */
public class HPBelow extends BoolTerminal implements PerturbableTerminal {
    public static final String NAME = "HPBelow";
    private static final double MIN = 0.05, MAX = 0.95, STEP = 0.1;

    private final double fraction;

    public HPBelow(double fraction) {
        this.fraction = fraction;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(String.valueOf(fraction)); }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return ctx.unit.getHitPoints() < fraction * ctx.unit.getMaxHitPoints();
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new HPBelow(GPUtil.perturb(fraction, STEP, MIN, MAX, rnd));
    }
}
