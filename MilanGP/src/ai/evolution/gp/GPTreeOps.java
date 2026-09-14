package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPNodes;
import ai.evolution.gp.nodes.GPSExpression;
import ai.evolution.gp.nodes.PerturbableTerminal;
import ai.evolution.gp.nodes.functions.And;
import ai.evolution.gp.nodes.functions.IfThenElse;
import ai.evolution.gp.nodes.functions.Not;
import ai.evolution.gp.nodes.functions.Or;
import ai.evolution.gp.nodes.terminals.conditions.True;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class GPTreeOps {
    private GPTreeOps() {}

    public record NodeRef(GPNode parent, int index, GPNode node, int depth) {}

    public static List<NodeRef> collect(GPNode root) {
        List<NodeRef> out = new ArrayList<>();
        collect(null, -1, root, 0, out);
        return out;
    }

    private static void collect(GPNode parent, int index, GPNode node, int depth, List<NodeRef> out) {
        out.add(new NodeRef(parent, index, node, depth));
        List<GPNode> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) collect(node, i, children.get(i), depth + 1, out);
    }

    public static int size(GPNode root) {
        return collect(root).size();
    }

    public static int depth(GPNode root) {
        int max = 0;
        for (NodeRef ref : collect(root)) max = Math.max(max, ref.depth());
        return max;
    }

    public static ActionNode grow(int maxDepth, boolean full, double terminalProbability, Random rnd) {
        if (maxDepth <= 0 || (!full && rnd.nextDouble() < terminalProbability)) {
            return GPNodes.randomAction(rnd);
        }
        int conditionDepth = Math.min(2, maxDepth - 1);
        return new IfThenElse(
                growCondition(conditionDepth, full, terminalProbability, rnd),
                grow(maxDepth - 1, full, terminalProbability, rnd),
                grow(maxDepth - 1, full, terminalProbability, rnd));
    }

    public static BoolNode growCondition(int maxDepth, boolean full, double terminalProbability, Random rnd) {
        if (maxDepth <= 0 || (!full && rnd.nextDouble() < terminalProbability)) {
            return GPNodes.randomCondition(rnd);
        }
        return switch (rnd.nextInt(3)) {
            case 0 -> new And(growCondition(maxDepth - 1, full, terminalProbability, rnd),
                    growCondition(maxDepth - 1, full, terminalProbability, rnd));
            case 1 -> new Or(growCondition(maxDepth - 1, full, terminalProbability, rnd),
                    growCondition(maxDepth - 1, full, terminalProbability, rnd));
            default -> new Not(growCondition(maxDepth - 1, full, terminalProbability, rnd));
        };
    }

    public static ActionNode crossover(ActionNode a, ActionNode b, Random rnd, int maxDepth) {
        return crossover(a, b, rnd, maxDepth, 0, Integer.MAX_VALUE);
    }

    public static ActionNode crossover(ActionNode a, ActionNode b, Random rnd, int maxDepth,
                                       int minLevel, int maxLevel) {
        ActionNode child = a.copy();
        List<NodeRef> targets = inBand(collect(child), minLevel, maxLevel);
        if (targets.isEmpty()) return child;
        Collections.shuffle(targets, rnd);
        List<NodeRef> donorNodes = collect(b);

        for (NodeRef target : targets) {
            boolean wantBool = target.node() instanceof BoolNode;
            List<NodeRef> donors = new ArrayList<>();
            for (NodeRef ref : donorNodes) if (wantBool == (ref.node() instanceof BoolNode)) donors.add(ref);
            if (donors.isEmpty()) continue;

            GPNode replacement = donors.get(rnd.nextInt(donors.size())).node().copy();
            if (target.parent() == null) {
                if (depth(replacement) <= maxDepth) return (ActionNode) replacement;
                continue;
            }
            target.parent().setChild(target.index(), replacement);
            if (depth(child) <= maxDepth) return child;
            target.parent().setChild(target.index(), target.node());
        }
        return child;
    }

    public static ActionNode mutate(ActionNode root, Random rnd, int maxDepth,
                                    double terminalProbability, double ercPerturbRate) {
        return mutate(root, rnd, maxDepth, terminalProbability, ercPerturbRate, 0, Integer.MAX_VALUE);
    }

    public static ActionNode mutate(ActionNode root, Random rnd, int maxDepth,
                                    double terminalProbability, double ercPerturbRate,
                                    int minLevel, int maxLevel) {
        ActionNode copy = root.copy();
        List<NodeRef> nodes = inBand(collect(copy), minLevel, maxLevel);
        if (nodes.isEmpty()) return copy;
        NodeRef pick = nodes.get(rnd.nextInt(nodes.size()));
        int remainingDepth = Math.max(0, maxDepth - pick.depth());

        GPNode fresh;
        if (pick.node() instanceof PerturbableTerminal terminal && rnd.nextDouble() < ercPerturbRate) {
            fresh = terminal.perturb(rnd);
        } else if (pick.node() instanceof BoolNode) {
            fresh = growCondition(remainingDepth, false, terminalProbability, rnd);
        } else {
            fresh = grow(remainingDepth, false, terminalProbability, rnd);
        }
        if (pick.parent() == null) return (ActionNode) fresh;
        pick.parent().setChild(pick.index(), fresh);
        return copy;
    }

    private static List<NodeRef> inBand(List<NodeRef> nodes, int minLevel, int maxLevel) {
        if (minLevel <= 0 && maxLevel == Integer.MAX_VALUE) return nodes;
        List<NodeRef> band = new ArrayList<>();
        for (NodeRef ref : nodes) {
            if (ref.depth() >= minLevel && ref.depth() <= maxLevel) band.add(ref);
        }
        return band;
    }

    public static ActionNode reduce(ActionNode root) {
        return (ActionNode) reduce(root.copy(), new HashMap<>());
    }

    private enum Truth { TRUE, FALSE, UNKNOWN }

    private static GPNode reduce(GPNode node, Map<String, Boolean> known) {
        if (node instanceof IfThenElse) {
            List<GPNode> children = new ArrayList<>(node.getChildren());
            GPNode condition = children.get(0);
            Truth resolved = resolve(condition, known);
            if (resolved == Truth.TRUE) return reduce(children.get(1), known);
            if (resolved == Truth.FALSE) return reduce(children.get(2), known);
            node.setChild(0, reduce(condition, known));
            node.setChild(1, reduce(children.get(1), assuming(condition, true, known)));
            node.setChild(2, reduce(children.get(2), assuming(condition, false, known)));
            return rewrite(node);
        }
        List<GPNode> children = new ArrayList<>(node.getChildren());
        for (int i = 0; i < children.size(); i++) node.setChild(i, reduce(children.get(i), known));
        return rewrite(node);
    }

    private static Map<String, Boolean> assuming(GPNode condition, boolean value, Map<String, Boolean> known) {
        Map<String, Boolean> extended = new HashMap<>(known);
        assume(condition, value, extended);
        return extended;
    }

    private static Truth resolve(GPNode condition, Map<String, Boolean> known) {
        if (condition instanceof True) return Truth.TRUE;
        if (condition instanceof Not) {
            return switch (resolve(condition.getChildren().getFirst(), known)) {
                case TRUE -> Truth.FALSE;
                case FALSE -> Truth.TRUE;
                case UNKNOWN -> Truth.UNKNOWN;
            };
        }
        if (condition instanceof And || condition instanceof Or) {
            Truth left = resolve(condition.getChildren().get(0), known);
            Truth right = resolve(condition.getChildren().get(1), known);
            Truth shortCircuit = condition instanceof And ? Truth.FALSE : Truth.TRUE;
            Truth both = condition instanceof And ? Truth.TRUE : Truth.FALSE;
            if (left == shortCircuit || right == shortCircuit) return shortCircuit;
            if (left == both && right == both) return both;
            return Truth.UNKNOWN;
        }
        Boolean value = known.get(GPSExpression.write(condition));
        return value == null ? Truth.UNKNOWN : value ? Truth.TRUE : Truth.FALSE;
    }

    private static void assume(GPNode condition, boolean value, Map<String, Boolean> known) {
        if (condition instanceof True) return;
        if (condition instanceof Not) {
            assume(condition.getChildren().getFirst(), !value, known);
        } else if (condition instanceof And || condition instanceof Or) {
            boolean forcesBothSides = value == (condition instanceof And);
            if (forcesBothSides) {
                assume(condition.getChildren().get(0), value, known);
                assume(condition.getChildren().get(1), value, known);
            }
        } else {
            known.put(GPSExpression.write(condition), value);
        }
    }

    private static GPNode rewrite(GPNode node) {
        if (node instanceof And) {
            GPNode l = node.getChildren().get(0), r = node.getChildren().get(1);
            if (isTrue(l)) return r;
            if (isTrue(r)) return l;
            if (isFalse(l) || isFalse(r)) return isFalse(l) ? l : r;
            if (same(l, r)) return l;
        } else if (node instanceof Or) {
            GPNode l = node.getChildren().get(0), r = node.getChildren().get(1);
            if (isTrue(l) || isTrue(r)) return isTrue(l) ? l : r;
            if (isFalse(l)) return r;
            if (isFalse(r)) return l;
            if (same(l, r)) return l;
        } else if (node instanceof Not) {
            GPNode child = node.getChildren().getFirst();
            if (child instanceof Not) return child.getChildren().getFirst();
        } else if (node instanceof IfThenElse) {
            GPNode cond = node.getChildren().get(0), then = node.getChildren().get(1), other = node.getChildren().get(2);
            if (isTrue(cond)) return then;
            if (isFalse(cond)) return other;
            if (same(then, other)) return then;
        }
        return node;
    }

    private static boolean isTrue(GPNode n) { return n instanceof True; }

    private static boolean isFalse(GPNode n) { return n instanceof Not && isTrue(n.getChildren().getFirst()); }

    private static boolean same(GPNode a, GPNode b) { return GPSExpression.write(a).equals(GPSExpression.write(b)); }
}
