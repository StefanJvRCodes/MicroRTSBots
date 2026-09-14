package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import rts.units.Unit;

public class EnemyInSightRange extends BoolTerminal {
    public static final String NAME = "EnemyInSightRange";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        Unit enemy = ctx.nearestEnemy(ctx.unit);
        return enemy != null && GPUtil.manhattan(ctx.unit, enemy) <= ctx.unit.getType().sightRadius;
    }
}
