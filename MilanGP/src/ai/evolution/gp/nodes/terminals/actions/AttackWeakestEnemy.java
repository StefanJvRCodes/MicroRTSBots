package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import rts.units.Unit;

/** Attacks the enemy unit with the fewest hit points anywhere on the map. */
public class AttackWeakestEnemy extends ActionTerminal {
    public static final String NAME = "AttackWeakestEnemy";

    @Override
    public String getName() { return NAME; }

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        Unit enemy = u.getType().canAttack ? GPUtil.weakestEnemy(ctx.pgs, ctx.playerID) : null;
        if (enemy != null) ctx.ai.attack(u, enemy); else ctx.ai.idle(u);
    }
}
