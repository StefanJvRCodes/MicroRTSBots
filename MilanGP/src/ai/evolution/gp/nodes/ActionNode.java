package ai.evolution.gp.nodes;

public abstract class ActionNode implements GPNode {
    public abstract void exec(GPTurnContext ctx);

    @Override
    public abstract ActionNode copy();
}
