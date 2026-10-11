package gp.sbgp;

import ec.gp.ERC;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import gp.nodes.FeatureNode;

import java.io.Serializable;

/**
 * A GLOBAL AREA in structure-based GP (Scheepers & Pillay, GPEM 27:2, 2026, section 4):
 * the top Dg levels of a tree, used to steer the search -- frozen during local search,
 * avoided during later global searches. One root per tree, so role-tree individuals get
 * one area per role; how per-tree similarities combine is decided by the filter pipeline.
 *
 * SIMILARITY IS MATCHED TOP-DOWN FROM THE ROOT (paper, Figure 3): a node counts only if it
 * AND EVERY ANCESTOR match. Figure 3's answer is 3; a naive positional count gives 4.
 * gp.sbgp.SimilarityTest checks this.
 *
 * NODE IDENTITY: every terminal is gp.nodes.FeatureNode, so terminals are keyed by feature
 * name, not class. Switches: countTerminals (paper text: all nodes; Pillay 2004: function
 * nodes only) and ercByValue (constants as one symbol, or compared by value).
 *
 * Depth: root is level 0; Dg levels = levels 0 .. Dg-1.
 */
public final class GlobalArea implements Serializable {

    private static final long serialVersionUID = 1L;

    public final GPNode[] roots;
    public final int depth;
    public final int generation;
    public final double fitness;

    public GlobalArea(GPIndividual source, int depth, int generation, double fitness) {
        if (depth < 1) throw new IllegalArgumentException("Global area depth must be >= 1, got " + depth);
        this.depth = depth;
        this.generation = generation;
        this.fitness = fitness;
        this.roots = new GPNode[source.trees.length];
        for (int t = 0; t < roots.length; t++) {
            // Deep copy: the archive must not alias a live individual.
            roots[t] = (GPNode) source.trees[t].child.clone();
        }
    }

    public int[] similarityPerTree(GPIndividual ind, boolean countTerminals, boolean ercByValue) {
        int n = Math.min(roots.length, ind.trees.length);
        int[] out = new int[n];
        for (int t = 0; t < n; t++) {
            out[t] = similarity(roots[t], ind.trees[t].child, depth, countTerminals, ercByValue);
        }
        return out;
    }

    public static int similarity(GPNode a, GPNode b, int dg, boolean countTerminals, boolean ercByValue) {
        return match(a, b, 0, dg, countTerminals, ercByValue);
    }

    private static int match(GPNode a, GPNode b, int level, int dg,
                             boolean countTerminals, boolean ercByValue) {
        if (a == null || b == null || level >= dg) return 0;
        if (!symbol(a, ercByValue).equals(symbol(b, ercByValue))) return 0;
        int count = (countTerminals || !isTerminal(a)) ? 1 : 0;
        int kids = Math.min(childCount(a), childCount(b));
        for (int i = 0; i < kids; i++) {
            count += match(a.children[i], b.children[i], level + 1, dg, countTerminals, ercByValue);
        }
        return count;
    }

    public static String symbol(GPNode n, boolean ercByValue) {
        if (n instanceof FeatureNode) return "T:" + n.toString();
        if (n instanceof ERC) return ercByValue ? "C:" + ((ERC) n).encode() : "C";
        return "F:" + n.getClass().getName();
    }

    public static boolean isTerminal(GPNode n) { return childCount(n) == 0; }

    private static int childCount(GPNode n) { return n.children == null ? 0 : n.children.length; }

    public String describe(int tree) {
        StringBuilder sb = new StringBuilder();
        print(roots[tree], 0, sb);
        return sb.toString();
    }

    private void print(GPNode n, int level, StringBuilder sb) {
        if (n == null) { sb.append("?"); return; }
        if (level >= depth) { sb.append("_"); return; }
        String label = (n instanceof ERC) ? ((ERC) n).toStringForHumans() : n.toString();
        if (isTerminal(n)) { sb.append(label); return; }
        sb.append('(').append(label);
        for (GPNode c : n.children) { sb.append(' '); print(c, level + 1, sb); }
        sb.append(')');
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GlobalArea[Dg=" + depth + ", gen " + generation
                + ", fitness " + fitness + "]");
        for (int t = 0; t < roots.length; t++) sb.append("\n  tree ").append(t).append(": ").append(describe(t));
        return sb.toString();
    }
}