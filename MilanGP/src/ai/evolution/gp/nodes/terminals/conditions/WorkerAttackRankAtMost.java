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

public class WorkerAttackRankAtMost extends BoolNode implements PerturbableTerminal {
    public static final String NAME = "WorkerAttackRankAtMost";
    private final int maxRank;

    public WorkerAttackRankAtMost(int maxRank) {
        this.maxRank = Math.max(1, maxRank);
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        Unit current = ctx.unit;
        if (!current.getType().canHarvest) return false;
        Unit currentTarget = GPUtil.nearestEnemy(ctx.pgs, current, ctx.playerID);
        if (currentTarget == null) return false;
        int currentDistance = GPUtil.manhattan(current.getX(), current.getY(),
                currentTarget.getX(), currentTarget.getY());
        int rank = 1;
        for (Unit other : ctx.pgs.getUnits()) {
            if (other == current || other.getPlayer() != ctx.playerID || !other.getType().canHarvest) continue;
            Unit otherTarget = GPUtil.nearestEnemy(ctx.pgs, other, ctx.playerID);
            if (otherTarget == null) continue;
            int otherDistance = GPUtil.manhattan(other.getX(), other.getY(),
                    otherTarget.getX(), otherTarget.getY());
            if (otherDistance < currentDistance
                    || otherDistance == currentDistance && other.getID() < current.getID()) rank++;
        }
        return rank <= maxRank;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(String.valueOf(maxRank)); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(NAME + " has no children");
    }

    @Override
    public BoolNode copy() { return new WorkerAttackRankAtMost(maxRank); }

    @Override
    public GPNode perturb(Random rnd) {
        return new WorkerAttackRankAtMost(Math.min(6, Math.max(1,
                maxRank + (rnd.nextBoolean() ? 1 : -1))));
    }
}
