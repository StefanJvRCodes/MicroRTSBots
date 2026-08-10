package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;

import rts.units.Unit;

import java.util.Collections;
import java.util.List;

public class MoveToOwnBase extends ActionNode {
    public static final String NAME = "MoveToOwnBase";

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        if (!u.getType().canMove) { ctx.ai.idle(u); return; }
        Unit ownBase = GPUtil.nearestOwnBase(ctx.pgs, u, ctx.playerID);
        if (ownBase != null) ctx.ai.move(u, ownBase.getX(), ownBase.getY());
        else ctx.ai.idle(u);
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
    public ActionNode copy() { return new MoveToOwnBase(); }
}
