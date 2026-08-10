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

public class EnemyBaseInRange extends BoolNode implements PerturbableTerminal {
    public static final String NAME = "EnemyBaseInRange";
    private static final double MIN_FRACTION = 0.05;
    private static final double MAX_FRACTION = 1.0;
    private static final double MAX_DELTA = 0.08;
    private final double rangeFraction;

    public EnemyBaseInRange(double rangeFraction) {
        this.rangeFraction = rangeFraction;
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        Unit u = ctx.unit;
        Unit enemyBase = GPUtil.nearestEnemyBase(ctx.pgs, u, ctx.playerID);
        if (enemyBase == null) return false;
        int range = GPUtil.absoluteRange(ctx.pgs, rangeFraction);
        return GPUtil.manhattan(u.getX(), u.getY(), enemyBase.getX(), enemyBase.getY()) <= range;
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
    public BoolNode copy() { return new EnemyBaseInRange(rangeFraction); }

    @Override
    public GPNode perturb(Random rnd) {
        double perturbed = rangeFraction + (rnd.nextDouble() * 2 - 1) * MAX_DELTA;
        return new EnemyBaseInRange(Math.max(MIN_FRACTION, Math.min(MAX_FRACTION, perturbed)));
    }
}
