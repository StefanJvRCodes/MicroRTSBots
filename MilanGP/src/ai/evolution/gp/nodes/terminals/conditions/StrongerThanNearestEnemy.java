package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import rts.units.Unit;

public class StrongerThanNearestEnemy extends BoolTerminal {
    public static final String NAME = "StrongerThanNearestEnemy";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        Unit enemy = ctx.nearestEnemy(ctx.unit);
        if (enemy == null) return false;
        int mine = attacksToKill(ctx.unit, enemy);
        return mine != Integer.MAX_VALUE && mine <= attacksToKill(enemy, ctx.unit);
    }

    private static int attacksToKill(Unit attacker, Unit target) {
        int damage = attacker.getType().maxDamage;
        if (!attacker.getType().canAttack || damage <= 0) return Integer.MAX_VALUE;
        return (target.getHitPoints() + damage - 1) / damage;
    }
}
