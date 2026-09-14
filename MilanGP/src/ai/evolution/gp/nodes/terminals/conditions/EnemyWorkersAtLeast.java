package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/** True when the opponent has at least a map-relative number of workers. */
public class EnemyWorkersAtLeast extends BoolTerminal implements PerturbableTerminal {
    public static final String NAME = "EnemyWorkersAtLeast";
    private static final double MIN = 1.0 / 12, MAX = 1.0, STEP = 0.1;
    /** Calibration: fraction 1.0 on the 16x16 reference map means 12 workers. */
    private static final double AREA_PER_UNIT = 256.0 / 12;

    private final double fraction;

    public EnemyWorkersAtLeast(double fraction) {
        this.fraction = fraction;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(String.valueOf(fraction)); }

    @Override
    public boolean eval(GPTurnContext ctx) {
        int have = GPUtil.count(ctx.pgs, u -> GPUtil.isEnemyOf(u, ctx.playerID) && u.getType().canHarvest);
        return have >= GPUtil.absoluteCount(ctx.pgs, fraction, AREA_PER_UNIT);
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new EnemyWorkersAtLeast(GPUtil.perturb(fraction, STEP, MIN, MAX, rnd));
    }
}
