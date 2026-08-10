package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import rts.units.Unit;

import java.util.Collections;
import java.util.List;

public class EnemyHasBarracks extends BoolNode {
    public static final String NAME = "EnemyHasBarracks";

    @Override
    public boolean eval(GPTurnContext ctx) {
        for (Unit unit : ctx.pgs.getUnits()) {
            if (unit.getPlayer() >= 0 && unit.getPlayer() != ctx.playerID
                    && "Barracks".equals(unit.getType().name)) return true;
        }
        return false;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.emptyList(); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(NAME + " has no children");
    }

    @Override
    public BoolNode copy() { return new EnemyHasBarracks(); }
}
