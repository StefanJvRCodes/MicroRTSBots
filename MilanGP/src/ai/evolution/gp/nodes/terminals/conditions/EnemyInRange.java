package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;

import rts.units.Unit;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class EnemyInRange extends BoolNode implements PerturbableTerminal {
    public static final String NAME = "EnemyInRange";
    private static final double MIN_FRACTION = 0.02;
    private static final double MAX_FRACTION = 0.5;
    private static final double MAX_DELTA = 0.05;
    private final double rangeFraction;

    public EnemyInRange(double rangeFraction) {
        this.rangeFraction = rangeFraction;
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        Unit u = ctx.unit;
        Unit enemy = GPUtil.nearestEnemy(ctx.pgs, u, ctx.playerID);
        if (enemy == null) return false;
        int range = GPUtil.absoluteRange(ctx.pgs, rangeFraction);
        return GPUtil.manhattan(u.getX(), u.getY(), enemy.getX(), enemy.getY()) <= range;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(String.valueOf(rangeFraction)); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(NAME + " has no children");
    }

    @Override
    public BoolNode copy() { return new EnemyInRange(rangeFraction); }

    @Override
    public GPNode perturb(Random rnd) {
        double perturbed = rangeFraction + (rnd.nextDouble() * 2 - 1) * MAX_DELTA;
        return new EnemyInRange(Math.max(MIN_FRACTION, Math.min(MAX_FRACTION, perturbed)));
    }
}
