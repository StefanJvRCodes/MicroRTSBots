package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;

import rts.units.Unit;
import rts.units.UnitType;

import java.util.Collections;
import java.util.List;

public class TrainRanged extends ActionNode {
    public static final String NAME = "TrainRanged";

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        UnitType rangedType = ctx.utt.getUnitType("Ranged");
        if (rangedType != null && u.getType().produces.contains(rangedType) && ctx.player.getResources() >= rangedType.cost) {
            ctx.ai.train(u, rangedType);
        } else {
            ctx.ai.idle(u);
        }
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
    public ActionNode copy() { return new TrainRanged(); }
}
