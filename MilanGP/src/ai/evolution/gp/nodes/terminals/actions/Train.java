package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.PerturbableTerminal;
import rts.units.Unit;
import rts.units.UnitType;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class Train extends ActionTerminal implements PerturbableTerminal {
    public static final String NAME = "Train";
    public static final String[] TYPES = {"Worker", "Light", "Heavy", "Ranged", "Cheapest"};

    private final String type;

    public Train(String type) {
        this.type = type;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<String> getParams() {
        return Collections.singletonList(type);
    }

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        UnitType wanted = type.equals("Cheapest") ? cheapestAffordableMilitary(ctx, u) : ctx.utt.getUnitType(type);
        if (wanted == null || !u.getType().produces.contains(wanted) || ctx.player.getResources() < wanted.cost) {
            ctx.ai.idle(u);
            return;
        }
        ctx.ai.train(u, wanted);
    }

    private UnitType cheapestAffordableMilitary(GPTurnContext ctx, Unit u) {
        UnitType best = null;
        for (UnitType t : u.getType().produces) {
            if (!t.canAttack || t.canHarvest || t.cost > ctx.player.getResources()) continue;
            if (best == null || t.cost < best.cost) best = t;
        }
        return best;
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new Train(TYPES[rnd.nextInt(TYPES.length)]);
    }
}
