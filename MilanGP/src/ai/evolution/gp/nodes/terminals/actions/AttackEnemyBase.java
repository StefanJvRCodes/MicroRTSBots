package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import rts.units.Unit;

public class AttackEnemyBase extends ActionTerminal {
    public static final String NAME = "AttackEnemyBase";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        Unit base = u.getType().canAttack ? GPUtil.nearestEnemyBase(ctx.pgs, u, ctx.playerID) : null;
        if (base != null) {
            Actions.attack(ctx, base);
        } else {
            ctx.ai.idle(u);
        }
    }
}
