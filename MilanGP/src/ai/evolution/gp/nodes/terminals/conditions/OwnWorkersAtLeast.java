package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class OwnWorkersAtLeast extends BoolNode implements PerturbableTerminal {
    public static final String NAME = "OwnWorkersAtLeast";
    private final int count;

    public OwnWorkersAtLeast(int count) {
        this.count = Math.max(1, count);
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return GPUtil.countWorkers(ctx.pgs, ctx.playerID) >= count;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(String.valueOf(count)); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(NAME + " has no children");
    }

    @Override
    public BoolNode copy() { return new OwnWorkersAtLeast(count); }

    @Override
    public GPNode perturb(Random rnd) {
        return new OwnWorkersAtLeast(Math.min(12, Math.max(1, count + (rnd.nextBoolean() ? 1 : -1))));
    }
}
