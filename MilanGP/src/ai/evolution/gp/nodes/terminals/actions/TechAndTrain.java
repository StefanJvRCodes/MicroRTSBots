package ai.evolution.gp.nodes.terminals.actions;

import ai.evolution.gp.nodes.ActionTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;
import rts.units.Unit;
import rts.units.UnitType;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class TechAndTrain extends ActionTerminal implements PerturbableTerminal {
    public static final String NAME = "TechAndTrain";
    public static final String[] TYPES = {"Light", "Heavy", "Ranged", "Cheapest"};

    private static final HarvestResources HARVEST = new HarvestResources();
    private static final AttackNearestEnemy ATTACK = new AttackNearestEnemy();

    private final String militaryType;

    public TechAndTrain(String militaryType) {
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
        if (wanted != null) {
            if (ctx.player.getResources() >= wanted.cost) ctx.ai.train(u, wanted); else ctx.ai.idle(u);
            return;
        }
        if (u.getType().canHarvest) {
            UnitType barracks = ctx.utt.getUnitType("Barracks");
            boolean haveBarracks = GPUtil.any(ctx.pgs, x -> x.getPlayer() == ctx.playerID && GPUtil.isBarracks(x));
            if (barracks != null && !haveBarracks && ctx.player.getResources() >= barracks.cost) {
                Actions.build(ctx, "Barracks");
            } else {
                HARVEST.exec(ctx);
            }
            return;
        }
        UnitType worker = ctx.utt.getUnitType("Worker");
        if (worker != null && u.getType().produces.contains(worker)) {
            Actions.train(ctx, "Worker");
            return;
        }
        ATTACK.exec(ctx);
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
        return new TechAndTrain(TYPES[rnd.nextInt(TYPES.length)]);
    }
}
