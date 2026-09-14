package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.GPTurnContext;
import rts.units.Unit;
import rts.units.UnitType;

final class Actions {
    private Actions() {}

    static void build(GPTurnContext ctx, String typeName) {
        Unit u = ctx.unit;
        UnitType type = ctx.utt.getUnitType(typeName);
        if (type != null && u.getType().canHarvest && ctx.player.getResources() >= type.cost) {
            ctx.ai.buildIfNotAlreadyBuilding(u, type, u.getX(), u.getY(),
                    ctx.reservedBuildPositions, ctx.player, ctx.pgs);
        } else {
            ctx.ai.idle(u);
        }
    }
}
