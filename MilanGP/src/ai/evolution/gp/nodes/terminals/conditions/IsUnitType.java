package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class IsUnitType extends BoolTerminal implements PerturbableTerminal {
    public static final String NAME = "IsUnitType";
    public static final String[] TYPES = {"Worker", "Light", "Heavy", "Ranged", "Base", "Barracks"};

    private final String unitType;

    public IsUnitType(String unitType) {
        this.unitType = unitType;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(unitType); }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return unitType.equals(ctx.unit.getType().name);
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new IsUnitType(TYPES[rnd.nextInt(TYPES.length)]);
    }
}
