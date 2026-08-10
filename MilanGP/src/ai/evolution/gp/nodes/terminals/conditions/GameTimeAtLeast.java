package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class GameTimeAtLeast extends BoolNode implements PerturbableTerminal {
    public static final String NAME = "GameTimeAtLeast";
    private final int cycle;

    public GameTimeAtLeast(int cycle) {
        this.cycle = Math.max(0, cycle);
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return ctx.gs.getTime() >= cycle;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.singletonList(String.valueOf(cycle)); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(NAME + " has no children");
    }

    @Override
    public BoolNode copy() { return new GameTimeAtLeast(cycle); }

    @Override
    public GPNode perturb(Random rnd) {
        int delta = 25 + rnd.nextInt(176);
        return new GameTimeAtLeast(Math.min(10000, Math.max(0,
                cycle + (rnd.nextBoolean() ? delta : -delta))));
    }
}
