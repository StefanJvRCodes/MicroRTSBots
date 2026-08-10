package ai.evolution.gp.nodes.terminals.conditions;

import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPTurnContext;

import java.util.Collections;
import java.util.List;

public class True extends BoolNode {
    public static final String NAME = "True";

    @Override
    public boolean eval(GPTurnContext ctx) { return true; }

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> getParams() { return Collections.emptyList(); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(NAME + " has no children");
    }

    @Override
    public BoolNode copy() { return new True(); }
}
