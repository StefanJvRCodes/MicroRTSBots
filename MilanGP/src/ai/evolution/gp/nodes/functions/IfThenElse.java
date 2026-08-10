package ai.evolution.gp.nodes.functions;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class IfThenElse extends ActionNode {
    public static final String NAME = "If";
    private BoolNode condition;
    private ActionNode thenBranch, elseBranch;

    public IfThenElse(BoolNode condition, ActionNode thenBranch, ActionNode elseBranch) {
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
    }

    @Override
    public void exec(GPTurnContext ctx) {
        if (condition.eval(ctx)) thenBranch.exec(ctx); else elseBranch.exec(ctx);
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.emptyList(); }

    @Override
    public List<GPNode> getChildren() { return Arrays.asList(condition, thenBranch, elseBranch); }

    @Override
    public void setChild(int index, GPNode child) {
        switch (index) {
            case 0: condition = (BoolNode) child; break;
            case 1: thenBranch = (ActionNode) child; break;
            case 2: elseBranch = (ActionNode) child; break;
            default: throw new IndexOutOfBoundsException(String.valueOf(index));
        }
    }

    @Override
    public ActionNode copy() { return new IfThenElse(condition.copy(), thenBranch.copy(), elseBranch.copy()); }
}
