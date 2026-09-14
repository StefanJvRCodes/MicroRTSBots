package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPNode;

import java.util.ArrayList;
import java.util.List;

public class GPStructure {

    public enum Phase {EXPLORE, EXPLOIT}

    private static final double NO_CHANGE = 1e-9;

    private final GPConfig cfg;
    private final List<ActionNode> memory = new ArrayList<>();
    private Phase phase = Phase.EXPLORE;
    private int generationsInPhase;
    private ActionNode globalArea;
    private double bestInArea = Double.NEGATIVE_INFINITY;
    private int generationsWithoutChange;
    private boolean seeding;

    public GPStructure(GPConfig cfg) {
        this.cfg = cfg;
    }

    public int minLevel() {
        return phase == Phase.EXPLORE ? 0 : cfg.globalAreaDepth + 1;
    }

    public int maxLevel() {
        return phase == Phase.EXPLORE ? cfg.globalAreaDepth : Integer.MAX_VALUE;
    }

    public boolean seeding() {
        return seeding;
    }

    public boolean accepts(ActionNode tree) {
        if (phase == Phase.EXPLOIT) {
            return globalArea == null || containsArea(tree, globalArea, cfg.globalAreaDepth);
        }
        for (ActionNode spent : memory) {
            if (similarity(tree, spent, cfg.globalAreaDepth) >= cfg.globalSimilarityThreshold) return false;
        }
        return true;
    }

    public void afterGeneration(GPIndividual best) {
        generationsInPhase++;
        if (phase == Phase.EXPLORE) {
            if (generationsInPhase >= cfg.globalAreaGenerations) enterExploit(best);
            return;
        }
        seeding = false;
        if (best.combatScore > bestInArea + NO_CHANGE) {
            bestInArea = best.combatScore;
            generationsWithoutChange = 0;
        } else if (++generationsWithoutChange >= cfg.globalAreaWindow) {
            enterExplore();
        }
    }

    private void enterExploit(GPIndividual best) {
        globalArea = best.root.copy();
        phase = Phase.EXPLOIT;
        generationsInPhase = 0;
        generationsWithoutChange = 0;
        bestInArea = best.combatScore;
        seeding = true;
    }

    private void enterExplore() {
        memory.add(globalArea);
        globalArea = null;
        phase = Phase.EXPLORE;
        generationsInPhase = 0;
        seeding = false;
    }

    public Phase phase() {
        return phase;
    }

    public int areasExplored() {
        return memory.size();
    }

    public String describe() {
        return (phase == Phase.EXPLORE ? "explore" : "exploit") + " area " + memory.size();
    }

    public static int similarity(GPNode tree, GPNode area, int maxDepth) {
        if (tree == null || area == null || !tree.getName().equals(area.getName())) return 0;
        int matches = 1;
        List<GPNode> treeChildren = tree.getChildren();
        List<GPNode> areaChildren = area.getChildren();
        if (maxDepth > 0 && !treeChildren.isEmpty() && !areaChildren.isEmpty()) {
            int shared = Math.min(treeChildren.size(), areaChildren.size());
            for (int i = 0; i < shared; i++) {
                matches += similarity(treeChildren.get(i), areaChildren.get(i), maxDepth - 1);
            }
        }
        return matches;
    }

    public static int countToDepth(GPNode node, int maxDepth) {
        int count = 1;
        if (maxDepth > 0) {
            for (GPNode child : node.getChildren()) count += countToDepth(child, maxDepth - 1);
        }
        return count;
    }

    public static boolean containsArea(GPNode tree, GPNode area, int maxDepth) {
        return similarity(tree, area, maxDepth) == countToDepth(area, maxDepth);
    }
}
