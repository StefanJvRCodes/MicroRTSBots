package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPSExpression;

import java.util.List;

public class GPIndividual {
    public final ActionNode root;
    private int size = -1;
    private String canonical;

    public List<GPMatch.MatchupResult> matchups;
    public double combatScore;
    public double winRate;
    public double worstCase;
    public double margin;

    public GPIndividual(ActionNode root) {
        this.root = root;
    }

    public GPIndividual copy() {
        return new GPIndividual(root.copy());
    }

    public int size() {
        if (size < 0) size = GPTreeOps.size(root);
        return size;
    }

    public String toSExpression() {
        return GPSExpression.write(root);
    }

    public String canonical() {
        if (canonical == null) canonical = GPSExpression.write(GPTreeOps.reduce(root));
        return canonical;
    }
}
