package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class EnemyWorkersAtLeast extends BoolNode implements PerturbableTerminal {
    public static final String NAME = "EnemyWorkersAtLeast";
    private static final double MIN_FRACTION = 1.0 / 12.0;
    private static final double MAX_FRACTION = 1.0;
    private static final double MAX_DELTA = 0.1;
    private static final double AREA_PER_UNIT = 256.0 / 12.0;
    private final double fraction;

    public EnemyWorkersAtLeast(double fraction) {
        this.fraction = fraction;
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return GPUtil.countEnemyWorkers(ctx.pgs, ctx.playerID) >= GPUtil.absoluteCount(ctx.pgs, fraction, AREA_PER_UNIT);
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(String.valueOf(fraction)); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(NAME + " has no children");
    }

    @Override
    public BoolNode copy() { return new EnemyWorkersAtLeast(fraction); }

    @Override
    public GPNode perturb(Random rnd) {
        double perturbed = fraction + (rnd.nextDouble() * 2 - 1) * MAX_DELTA;
        return new EnemyWorkersAtLeast(Math.max(MIN_FRACTION, Math.min(MAX_FRACTION, perturbed)));
    }
}
