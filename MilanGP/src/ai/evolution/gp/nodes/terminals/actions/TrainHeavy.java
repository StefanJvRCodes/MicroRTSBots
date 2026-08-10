package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;

import rts.units.Unit;
import rts.units.UnitType;

import java.util.Collections;
import java.util.List;

public class TrainHeavy extends ActionNode {
    public static final String NAME = "TrainHeavy";

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        UnitType heavyType = ctx.utt.getUnitType("Heavy");
        if (heavyType != null && u.getType().produces.contains(heavyType) && ctx.player.getResources() >= heavyType.cost) {
            ctx.ai.train(u, heavyType);
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
    public ActionNode copy() { return new TrainHeavy(); }
}
