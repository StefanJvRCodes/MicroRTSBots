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

public class NearOwnBase extends BoolTerminal implements PerturbableTerminal {
    public static final String NAME = "NearOwnBase";
    private static final double MIN = 0.03, MAX = 0.75, STEP = 0.05;

    private final double rangeFraction;

    public NearOwnBase(double rangeFraction) {
        this.rangeFraction = rangeFraction;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(String.valueOf(rangeFraction)); }

    @Override
    public boolean eval(GPTurnContext ctx) {
        Unit target = GPUtil.nearestOwnBase(ctx.pgs, ctx.unit, ctx.playerID);
        return target != null
                && GPUtil.manhattan(ctx.unit, target) <= GPUtil.absoluteRange(ctx.pgs, rangeFraction);
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new NearOwnBase(GPUtil.perturb(rangeFraction, STEP, MIN, MAX, rnd));
    }
}
