package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolTerminal;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;
import ai.evolution.gp.nodes.GPUtil;
import ai.evolution.gp.nodes.PerturbableTerminal;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class WorkerAttackRankAtMost extends BoolTerminal implements PerturbableTerminal {
    public static final String NAME = "WorkerAttackRankAtMost";
    private static final int MIN = 1, MAX = 6;

    private final int maxRank;

    public WorkerAttackRankAtMost(int maxRank) {
        this.maxRank = Math.max(MIN, maxRank);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<String> getParams() {
        return Collections.singletonList(String.valueOf(maxRank));
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return ctx.workerAttackRank(ctx.unit) <= maxRank;
    }

    @Override
    public GPNode perturb(Random rnd) {
        return new WorkerAttackRankAtMost(GPUtil.perturb(maxRank, 1, MIN, MAX, rnd));
    }
}
