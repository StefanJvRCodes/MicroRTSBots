package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import rts.units.Unit;

/** Returns carried resources to the nearest base, otherwise harvests the nearest patch. */
public class HarvestResources extends ActionTerminal {
    public static final String NAME = "HarvestResources";

    @Override
    public String getName() { return NAME; }

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        if (!u.getType().canHarvest) { ctx.ai.idle(u); return; }
        Unit base = GPUtil.nearestOwnBase(ctx.pgs, u, ctx.playerID);
        Unit resource = u.getResources() > 0 ? null : GPUtil.nearestResource(ctx.pgs, u);
        boolean canReturn = u.getResources() > 0 && base != null;
        boolean canGather = resource != null && base != null;
        if (canReturn || canGather) ctx.ai.harvest(u, resource, base); else ctx.ai.idle(u);
    }
}
