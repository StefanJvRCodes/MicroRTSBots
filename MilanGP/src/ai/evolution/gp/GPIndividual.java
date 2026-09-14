package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPSExpression;

import java.util.List;

/** One program plus the scores the last evaluation attached to it. */
public class GPIndividual {
    public final ActionNode root;
    private int size = -1;

    /** Per-matchup results from the last evaluation; null until evaluated. */
    public List<GPMatch.MatchupResult> matchups;
    /** Harmonic mean of shaped matchup scores. The selection fitness. */
    public double combatScore;
    /** Plain mean of raw win/draw/loss scores. What we report. */
    public double winRate;
    /** Lowest raw matchup score. */
    public double worstCase;
    /** Mean material margin over all games. */
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
}
