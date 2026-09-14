package ai.evolution.gp.nodes;

import java.util.Collections;
import java.util.List;

public abstract class ActionTerminal extends ActionNode {

    @Override
    public List<String> getParams() { return Collections.emptyList(); }

    @Override
    public List<GPNode> getChildren() { return Collections.emptyList(); }

    @Override
    public void setChild(int index, GPNode child) {
        throw new UnsupportedOperationException(getName() + " has no children");
    }

    @Override
    public ActionNode copy() { return this; }
}
