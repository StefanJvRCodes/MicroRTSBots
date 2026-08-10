package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.BoolNode;
import ai.evolution.gp.nodes.GPNode;
import ai.evolution.gp.nodes.GPNodeFactory;
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
import java.util.Set;
import java.util.TreeSet;

public class GPTreeOps {
    private GPTreeOps() {}

    public static class NodeRef {
        public final GPNode parent;
        public final int index;
        public final GPNode node;
        public final int depth;

        NodeRef(GPNode parent, int index, GPNode node, int depth) {
            this.parent = parent;
            this.index = index;
            this.node = node;
            this.depth = depth;
        }
    }

    public static List<NodeRef> collect(GPNode root) {
        List<NodeRef> result = new ArrayList<>();
        collect(null, -1, root, 0, result);
        return result;
    }

    private static void collect(GPNode parent, int index, GPNode node, int depth, List<NodeRef> out) {
        out.add(new NodeRef(parent, index, node, depth));
        List<GPNode> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) collect(node, i, children.get(i), depth + 1, out);
    }

    public static int size(GPNode root) { return collect(root).size(); }

    public static Set<String> actionNames(GPNode root) {
        Set<String> names = new TreeSet<>();
        for (NodeRef ref : collect(root)) {
            if (ref.node instanceof ActionNode && ref.node.getChildren().isEmpty()) {
                names.add(ref.node.getName());
            }
        }
        return names;
    }

    public static Set<String> reachableActionNames(GPNode root) {
        Set<String> names = new TreeSet<>();
        collectReachable(root, new HashMap<>(), names);
        return names;
    }

    private static void collectReachable(GPNode node, Map<String, Boolean> known, Set<String> out) {
        if (node instanceof IfThenElse) {
            List<GPNode> children = node.getChildren();
            GPNode condition = children.get(0);
            Truth known0 = resolve(condition, known);
            if (known0 != Truth.FALSE) collectReachable(children.get(1), branchKnowledge(condition, true, known0, known), out);
            if (known0 != Truth.TRUE) collectReachable(children.get(2), branchKnowledge(condition, false, known0, known), out);
            return;
        }
        if (node instanceof ActionNode && node.getChildren().isEmpty()) {
            out.add(node.getName());
            return;
        }
        for (GPNode child : node.getChildren()) collectReachable(child, known, out);
    }

    private static Map<String, Boolean> branchKnowledge(GPNode condition, boolean taken, Truth resolved,
                                                        Map<String, Boolean> known) {
        if (resolved != Truth.UNKNOWN) return known;
        Map<String, Boolean> extended = new HashMap<>(known);
        assume(condition, taken, extended);
        return extended;
    }

    private enum Truth { TRUE, FALSE, UNKNOWN }

    private static Truth resolve(GPNode condition, Map<String, Boolean> known) {
        if (condition instanceof True) return Truth.TRUE;
        if (condition instanceof Not) {
            Truth inner = resolve(condition.getChildren().get(0), known);
            if (inner == Truth.TRUE) return Truth.FALSE;
            if (inner == Truth.FALSE) return Truth.TRUE;
            return Truth.UNKNOWN;
        }
        if (condition instanceof And || condition instanceof Or) {
            Truth left = resolve(condition.getChildren().get(0), known);
            Truth right = resolve(condition.getChildren().get(1), known);
            boolean conjunction = condition instanceof And;
            Truth shortCircuit = conjunction ? Truth.FALSE : Truth.TRUE;
            if (left == shortCircuit || right == shortCircuit) return shortCircuit;
            Truth other = conjunction ? Truth.TRUE : Truth.FALSE;
            if (left == other && right == other) return other;
            return Truth.UNKNOWN;
        }
        Boolean value = known.get(GPSExpression.write(condition));
        return value == null ? Truth.UNKNOWN : value ? Truth.TRUE : Truth.FALSE;
    }

    private static void assume(GPNode condition, boolean value, Map<String, Boolean> known) {
        if (condition instanceof True) return;
        if (condition instanceof Not) {
            assume(condition.getChildren().get(0), !value, known);
            return;
        }
        if (condition instanceof And || condition instanceof Or) {
            boolean decomposes = value == condition instanceof And;
            if (decomposes) {
                assume(condition.getChildren().get(0), value, known);
                assume(condition.getChildren().get(1), value, known);
            }
            return;
        }
        known.put(GPSExpression.write(condition), value);
    }

    public static int depth(GPNode root) {
        int max = 0;
        for (NodeRef ref : collect(root)) max = Math.max(max, ref.depth);
        return max;
    }

    public static ActionNode crossover(ActionNode a, ActionNode b, Random rnd, int maxDepth) {
        ActionNode childRoot = a.copy();
        ActionNode donorRoot = b.copy();
        List<NodeRef> childNodes = collect(childRoot);
        Collections.shuffle(childNodes, rnd);

        for (NodeRef candidate : childNodes) {
            boolean wantBool = candidate.node instanceof BoolNode;
            List<NodeRef> donorCandidates = new ArrayList<>();
            for (NodeRef ref : collect(donorRoot)) {
                if (wantBool == (ref.node instanceof BoolNode)) donorCandidates.add(ref);
            }
            if (donorCandidates.isEmpty()) continue;

            NodeRef donorPick = donorCandidates.get(rnd.nextInt(donorCandidates.size()));
            GPNode replacement = donorPick.node.copy();

            if (candidate.parent == null) {
                if (depth(replacement) <= maxDepth) return (ActionNode) replacement;
            } else {
                candidate.parent.setChild(candidate.index, replacement);
                if (depth(childRoot) <= maxDepth) return childRoot;
                candidate.parent.setChild(candidate.index, candidate.node);
            }
        }
        return a.copy();
    }

    public static ActionNode mutate(ActionNode root, Random rnd, GPNodeFactory factory, int maxDepth) {
        return mutate(root, rnd, factory, maxDepth, 0.0);
    }

    public static ActionNode mutate(ActionNode root, Random rnd, GPNodeFactory factory, int maxDepth, double ercPerturbRate) {
        ActionNode copy = root.copy();
        List<NodeRef> nodes = collect(copy);
        NodeRef pick = nodes.get(rnd.nextInt(nodes.size()));
        int remainingDepth = Math.max(0, maxDepth - pick.depth);

        if (pick.node instanceof PerturbableTerminal && rnd.nextDouble() < ercPerturbRate) {
            BoolNode perturbed = (BoolNode) ((PerturbableTerminal) pick.node).perturb(rnd);
            pick.parent.setChild(pick.index, perturbed);
        } else if (pick.node instanceof BoolNode) {
            BoolNode fresh = factory.randomBool(remainingDepth, rnd, false);
            pick.parent.setChild(pick.index, fresh);
        } else {
            ActionNode fresh = factory.randomAction(remainingDepth, rnd, false);
            if (pick.parent == null) return fresh;
            pick.parent.setChild(pick.index, fresh);
        }
        return copy;
    }

    public static ActionNode reduce(ActionNode root) {
        return (ActionNode) reduceNode(root.copy(), new HashMap<>());
    }

    private static GPNode reduceNode(GPNode node, Map<String, Boolean> known) {
        if (node instanceof IfThenElse) {
            List<GPNode> children = new ArrayList<>(node.getChildren());
            GPNode condition = children.get(0);
            Truth resolved = resolve(condition, known);
            if (resolved == Truth.TRUE) return reduceNode(children.get(1), known);
            if (resolved == Truth.FALSE) return reduceNode(children.get(2), known);
            node.setChild(0, reduceNode(condition, known));
            node.setChild(1, reduceNode(children.get(1), branchKnowledge(condition, true, resolved, known)));
            node.setChild(2, reduceNode(children.get(2), branchKnowledge(condition, false, resolved, known)));
            return rewrite(node);
        }
        List<GPNode> children = new ArrayList<>(node.getChildren());
        for (int i = 0; i < children.size(); i++) {
            node.setChild(i, reduceNode(children.get(i), known));
        }
        return rewrite(node);
    }

    private static GPNode rewrite(GPNode node) {
        if (node instanceof And) {
            GPNode l = node.getChildren().get(0), r = node.getChildren().get(1);
            if (isConstTrue(l)) return r;
            if (isConstTrue(r)) return l;
            if (isConstFalse(l)) return l;
            if (isConstFalse(r)) return r;
            if (sameExpression(l, r)) return l;
            return node;
        }
        if (node instanceof Or) {
            GPNode l = node.getChildren().get(0), r = node.getChildren().get(1);
            if (isConstTrue(l)) return l;
            if (isConstTrue(r)) return r;
            if (isConstFalse(l)) return r;
            if (isConstFalse(r)) return l;
            if (sameExpression(l, r)) return l;
            return node;
        }
        if (node instanceof Not) {
            GPNode child = node.getChildren().get(0);
            if (child instanceof Not) return child.getChildren().get(0);
            return node;
        }
        if (node instanceof IfThenElse) {
            GPNode cond = node.getChildren().get(0);
            GPNode then = node.getChildren().get(1);
            GPNode elseBranch = node.getChildren().get(2);
            if (isConstTrue(cond)) return then;
            if (isConstFalse(cond)) return elseBranch;
            if (sameExpression(then, elseBranch)) return then;
            return node;
        }
        return node;
    }

    private static boolean isConstTrue(GPNode n) { return n instanceof True; }

    private static boolean isConstFalse(GPNode n) {
        return n instanceof Not && isConstTrue(n.getChildren().get(0));
    }

    private static boolean sameExpression(GPNode a, GPNode b) {
        return GPSExpression.write(a).equals(GPSExpression.write(b));
    }
}
