package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.GPTurnContext;
import rts.units.Unit;
import rts.units.UnitType;

/** Shared bodies for the train/build terminals. Every helper idles the unit when the order is impossible. */
final class Actions {
    private Actions() {}

    static void train(GPTurnContext ctx, String typeName) {
        Unit u = ctx.unit;
        UnitType type = ctx.utt.getUnitType(typeName);
        if (type != null && u.getType().produces.contains(type) && ctx.player.getResources() >= type.cost) {
            ctx.ai.train(u, type);
        } else {
            ctx.ai.idle(u);
        }
    }

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
