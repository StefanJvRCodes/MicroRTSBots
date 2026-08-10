package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPSExpression;

import java.util.List;
import java.util.Set;

public class GPIndividual {
    public ActionNode root;
    public double fitness = 0;
    public double margin = 0;

    public double combatScore = 0;
    public double winRate = 0;
    public List<GPMatch.MatchupResult> matchupResults;
    public double[] behaviorVector;
    public double noveltyScore = 0;
    private Set<String> cachedActionNames;

    public GPIndividual(ActionNode root) {
        this.root = root;
    }

    public GPIndividual copy() {
        return new GPIndividual(root.copy());
    }

    public int size() { return GPTreeOps.size(root); }

    public Set<String> actionNames() {
        return GPTreeOps.actionNames(root);
    }

    public Set<String> reachableActionNames() {
        if (cachedActionNames == null) cachedActionNames = GPTreeOps.reachableActionNames(root);
        return cachedActionNames;
    }

    public int distinctActionCount() { return reachableActionNames().size(); }

    public int depth() { return GPTreeOps.depth(root); }

    public String toSExpression() { return GPSExpression.write(root); }
}
