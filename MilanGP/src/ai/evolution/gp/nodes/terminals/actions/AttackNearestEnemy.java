package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import rts.units.Unit;

public class AttackNearestEnemy extends ActionTerminal {
    public static final String NAME = "AttackNearestEnemy";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        Unit enemy = u.getType().canAttack ? ctx.nearestEnemy(u) : null;
        if (enemy != null) {
            Actions.attack(ctx, enemy);
        } else {
            ctx.ai.idle(u);
        }
    }
}
