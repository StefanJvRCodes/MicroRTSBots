package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;

import rts.units.Unit;

import java.util.Collections;
import java.util.List;

public class EnemyInSightRange extends BoolNode {
    public static final String NAME = "EnemyInSightRange";

    @Override
    public boolean eval(GPTurnContext ctx) {
        Unit u = ctx.unit;
        Unit enemy = GPUtil.nearestEnemy(ctx.pgs, u, ctx.playerID);
        if (enemy == null) return false;
        return GPUtil.manhattan(u.getX(), u.getY(), enemy.getX(), enemy.getY()) <= u.getType().sightRadius;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.emptyList(); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(NAME + " has no children");
    }

    @Override
    public BoolNode copy() { return new EnemyInSightRange(); }
}
