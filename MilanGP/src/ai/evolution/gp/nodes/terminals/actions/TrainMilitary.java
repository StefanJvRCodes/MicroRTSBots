package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import rts.units.Unit;
import rts.units.UnitType;

public class TrainMilitary extends ActionTerminal {
    public static final String NAME = "TrainMilitary";

    @Override
    public String getName() { return NAME; }

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        UnitType best = null;
        for (UnitType t : u.getType().produces) {
            if (!t.canAttack || t.canHarvest || t.cost > ctx.player.getResources()) continue;
            if (best == null || t.cost < best.cost) best = t;
        }
        if (best != null) ctx.ai.train(u, best); else ctx.ai.idle(u);
    }
}
