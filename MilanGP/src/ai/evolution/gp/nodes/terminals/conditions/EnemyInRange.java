package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;
import rts.units.Unit;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class EnemyInRange extends BoolTerminal implements PerturbableTerminal {
    public static final String NAME = "EnemyInRange";
    private static final double MIN = 0.02, MAX = 0.5, STEP = 0.05;

    private final double rangeFraction;

    public EnemyInRange(double rangeFraction) {
        this.rangeFraction = rangeFraction;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(String.valueOf(rangeFraction)); }

    @Override
    public boolean eval(GPTurnContext ctx) {
        Unit target = ctx.nearestEnemy(ctx.unit);
        return target != null
                && GPUtil.manhattan(ctx.unit, target) <= GPUtil.absoluteRange(ctx.pgs, rangeFraction);
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new EnemyInRange(GPUtil.perturb(rangeFraction, STEP, MIN, MAX, rnd));
    }
}
