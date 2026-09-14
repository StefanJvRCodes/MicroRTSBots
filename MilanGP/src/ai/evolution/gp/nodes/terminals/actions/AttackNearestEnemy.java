package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import rts.units.Unit;

/** Attacks the nearest enemy unit. Idles if this unit cannot attack or no enemy exists. */
public class AttackNearestEnemy extends ActionTerminal {
    public static final String NAME = "AttackNearestEnemy";

    @Override
    public String getName() { return NAME; }

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        Unit enemy = u.getType().canAttack ? ctx.nearestEnemy(u) : null;
        if (enemy != null) ctx.ai.attack(u, enemy); else ctx.ai.idle(u);
    }
}
