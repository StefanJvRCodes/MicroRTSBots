package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import rts.units.Unit;

public class MoveToNearestResource extends ActionTerminal {
    public static final String NAME = "MoveToNearestResource";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        Unit target = u.getType().canMove ? GPUtil.nearestResource(ctx.pgs, u) : null;
        if (target != null) {
            ctx.ai.move(u, target.getX(), target.getY());
        } else {
            ctx.ai.idle(u);
        }
    }
}
