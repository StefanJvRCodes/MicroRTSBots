package ai.evolution.gp.nodes;

public abstract class BoolNode implements GPNode {
    public abstract boolean eval(GPTurnContext ctx);

    @Override
    public abstract BoolNode copy();
}
