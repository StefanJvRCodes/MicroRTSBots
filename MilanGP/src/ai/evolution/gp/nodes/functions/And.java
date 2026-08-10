package ai.evolution.gp.nodes.functions;

import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class And extends BoolNode {
    public static final String NAME = "And";
    private BoolNode left, right;

    public And(BoolNode left, BoolNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return left.eval(ctx) && right.eval(ctx);
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.emptyList(); }

    @Override
    public List<GPNode> getChildren() { return Arrays.asList(left, right); }

    @Override
    public void setChild(int index, GPNode child) {
        if (index == 0) left = (BoolNode) child; else right = (BoolNode) child;
    }

    @Override
    public BoolNode copy() { return new And(left.copy(), right.copy()); }
}
