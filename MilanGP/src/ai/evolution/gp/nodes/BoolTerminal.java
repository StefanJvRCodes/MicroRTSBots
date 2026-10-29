package ai.evolution.gp.nodes;

import java.util.Collections;
import java.util.List;

public abstract class BoolTerminal extends BoolNode {

    @Override
    public List<String> getParams() { return Collections.emptyList(); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(getName() + " has no children");
    }

    @Override
    public BoolNode copy() { return this; }
}
