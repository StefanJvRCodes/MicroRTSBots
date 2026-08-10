package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;

import rts.units.Unit;

import java.util.Collections;
import java.util.List;

public class HarvestResources extends ActionNode {
    public static final String NAME = "HarvestResources";

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        if (!u.getType().canHarvest) { ctx.ai.idle(u); return; }
        Unit base = GPUtil.nearestOwnBase(ctx.pgs, u, ctx.playerID);
        if (u.getResources() > 0) {
            if (base != null) ctx.ai.harvest(u, null, base); else ctx.ai.idle(u);
            return;
        }
        Unit resource = GPUtil.nearestResource(ctx.pgs, u);
        if (resource != null && base != null) ctx.ai.harvest(u, resource, base);
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
    public ActionNode copy() { return new HarvestResources(); }
}
