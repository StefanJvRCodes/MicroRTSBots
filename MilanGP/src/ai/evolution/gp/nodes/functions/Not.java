package ai.evolution.gp.nodes.functions;

import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;

import java.util.Collections;
import java.util.List;

public class Not extends BoolNode {
    public static final String NAME = "Not";
    private BoolNode child;

    public Not(BoolNode child) {
        this.child = child;
    }

    @Override
    public boolean eval(GPTurnContext ctx) {
        return !child.eval(ctx);
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.emptyList(); }

    @Override
    public List<GPNode> getChildren() { return Collections.singletonList(child); }

    @Override
    public void setChild(int index, GPNode newChild) { this.child = (BoolNode) newChild; }

    @Override
    public BoolNode copy() { return new Not(child.copy()); }
}
