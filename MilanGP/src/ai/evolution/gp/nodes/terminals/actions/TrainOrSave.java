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

public class TrainOrSave extends ActionTerminal implements PerturbableTerminal {
    public static final String NAME = "TrainOrSave";
    public static final String[] TYPES = {"Light", "Heavy", "Ranged", "Cheapest"};

    private final String militaryType;

    public TrainOrSave(String militaryType) {
        this.militaryType = militaryType;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(militaryType); }

    @Override
    public void exec(GPTurnContext ctx) {
        Unit u = ctx.unit;
        UnitType wanted = producibleMilitaryType(ctx, u);
        if (wanted != null && ctx.player.getResources() >= wanted.cost) ctx.ai.train(u, wanted);
        else ctx.ai.idle(u);
    }

    private UnitType producibleMilitaryType(GPTurnContext ctx, Unit u) {
        if (militaryType.equals("Cheapest")) {
            UnitType best = null;
            for (UnitType t : u.getType().produces) {
                if (!t.canAttack || t.canHarvest) continue;
                if (best == null || t.cost < best.cost) best = t;
            }
            return best;
        }
        UnitType t = ctx.utt.getUnitType(militaryType);
        return t != null && u.getType().produces.contains(t) ? t : null;
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new TrainOrSave(TYPES[rnd.nextInt(TYPES.length)]);
    }
}
