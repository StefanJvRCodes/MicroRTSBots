package ai.evolution.gp.nodes;

import java.util.List;

public interface GPNode {
    String getName();

    List<String> getParams();

    List<GPNode> getChildren();

    void setChild(int index, GPNode child);

    GPNode copy();
}
