package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;

import rts.units.Unit;
import rts.units.UnitType;

import java.util.Collections;
import java.util.List;

public class TrainMilitary extends ActionNode {
    public static final String NAME = "TrainMilitary";

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        UnitType best = null;
        for (UnitType t : u.getType().produces) {
            if (!t.canAttack || t.canHarvest) continue;
            if (t.cost > ctx.player.getResources()) continue;
            if (best == null || t.cost < best.cost) best = t;
        }
        if (best != null) ctx.ai.train(u, best); else ctx.ai.idle(u);
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
    public ActionNode copy() { return new TrainMilitary(); }
}
