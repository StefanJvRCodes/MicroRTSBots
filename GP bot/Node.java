import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import rts.units.UnitType;

public class Node {

    public enum NodeType { FUNCTION, TERMINAL }

    /** Arity of each supported function symbol. */
    public static final Map<String, Integer> FUNCTION_ARITY = Map.of(
            "+", 2,
            "-", 2,
            "*", 2,
            "/", 2,      // protected division
            "if>0", 3    // condition, then-branch, else-branch
    );

    public NodeType type;
    public String value;
    public double numValue;
    public boolean isFeatureTerminal;
    public int featureIndex;

    public Node[] children;
    public Node parent;


    public boolean isModi;
    public int outputCellIndex;
    
    public Random random;
    public double[] features= new double[9];

    

    

    public double encodeUnitType(UnitType type) {
        switch(type.name) {
            case "Worker": return 0.0;
            case "Light": return 0.33;
            case "Heavy": return 0.66;
            case "Ranged": return 0.5;
            case "Base": return 1.0;
            case "Barracks": return 0.9;
            case "Resource": return 0.5;
            default: return 0.5;
        }
    }



    public Node(Random random, String[] functions, String[] terminals, int[] featureIndices, NodeType type, double modiRate, int numOutputCells) {
        this.random = random;
        this.parent = null;
        this.type = type;
        this.isModi = false;
        this.outputCellIndex = -1;

        if (type == NodeType.TERMINAL) {
            this.children = null;
            boolean useFeature = featureIndices != null && featureIndices.length > 0
                    && (terminals == null || terminals.length == 0 || random.nextBoolean());
            if (useFeature) {
                this.isFeatureTerminal = true;
                this.featureIndex = featureIndices[random.nextInt(featureIndices.length)];
                this.value = "F" + featureIndex;
            } else {
                this.isFeatureTerminal = false;
                this.numValue = random.nextDouble() * 20.0 - 10.0; // random constant in [-10, 10]
                this.value = String.valueOf(numValue);
            }
        } else {
            if (functions == null || functions.length == 0) {
                throw new IllegalArgumentException("Function set cannot be empty");
            }
            this.value = functions[random.nextInt(functions.length)];
            int arity = FUNCTION_ARITY.getOrDefault(this.value, 2);
            this.children = new Node[arity];
            this.isModi = random.nextDouble() < modiRate;
            if (this.isModi) {
                this.outputCellIndex = random.nextInt(numOutputCells);
            }
        }
    }

    /** Bare constructor used internally by deepCopy(). */
    private Node(NodeType type, String value, Random random) {
        this.type = type;
        this.value = value;
        this.random = random;
        this.parent = null;
    }

    public void setChild(int index, Node node) {
        if (type == NodeType.TERMINAL) {
            throw new IllegalStateException("Cannot set child on a terminal node");
        }
        if (index < 0 || index >= children.length) {
            throw new IndexOutOfBoundsException("Child index out of range: " + index);
        }
        children[index] = node;
        if (node != null) {
            node.parent = this;
        }
    }

    // ---------------------------------------------------------------
    // Modi node assignment (paper section 2.4)
    // ---------------------------------------------------------------

    /**
     * Assigns Modi nodes across this tree, rooted at {@code root}, following
     * the paper's three rules, and uniformly assigns each Modi node an
     * output-vector cell index in [0, numOutputCells).
     */
    public static void configureModiNodes(Node root, double modiRate, int numOutputCells, Random random) {
        if (root.type == NodeType.TERMINAL) {
            throw new IllegalArgumentException("Root cannot be a terminal — a Modi tree needs at least one function node");
        }
        // Rule 2: root is always Modi.
        root.isModi = true;
        root.outputCellIndex = random.nextInt(numOutputCells);

        if (root.children != null) {
            for (Node c : root.children) {
                if (c != null) configureModiRecursive(c, modiRate, numOutputCells, random);
            }
        }
    }

    private static void configureModiRecursive(Node node, double modiRate, int numOutputCells, Random random) {
        // Rule 1: leaves are never Modi.
        if (node.type == NodeType.TERMINAL) {
            node.isModi = false;
            return;
        }
        // Rule 3: intermediate nodes are Modi with probability modiRate.
        node.isModi = random.nextDouble() < modiRate;
        node.outputCellIndex = node.isModi ? random.nextInt(numOutputCells) : -1;

        if (node.children != null) {
            for (Node c : node.children) {
                if (c != null) configureModiRecursive(c, modiRate, numOutputCells, random);
            }
        }
    }

    // ---------------------------------------------------------------
    // Random tree generation (grow / full)
    // ---------------------------------------------------------------

    public static Node generateGrow(Random random, String[] functions, String[] terminals,
                                     int[] featureIndices, int maxDepth) {
        boolean makeTerminal = maxDepth <= 1 || random.nextDouble() < 0.3;
        if (makeTerminal) {
            return new Node(random, functions, terminals, featureIndices, NodeType.TERMINAL);
        }
        Node node = new Node(random, functions, terminals, featureIndices, NodeType.FUNCTION);
        for (int i = 0; i < node.children.length; i++) {
            node.setChild(i, generateGrow(random, functions, terminals, featureIndices, maxDepth - 1));
        }
        return node;
    }

    public static Node generateFull(Random random, String[] functions, String[] terminals,
                                     int[] featureIndices, int maxDepth) {
        if (maxDepth <= 1) {
            return new Node(random, functions, terminals, featureIndices, NodeType.TERMINAL);
        }
        Node node = new Node(random, functions, terminals, featureIndices, NodeType.FUNCTION);
        for (int i = 0; i < node.children.length; i++) {
            node.setChild(i, generateFull(random, functions, terminals, featureIndices, maxDepth - 1));
        }
        return node;
    }

    /** Convenience: generate a tree, then assign its Modi nodes in one call. */
    public static Node generateModiProgram(Random random, String[] functions, String[] terminals,
                                            int[] featureIndices, int maxDepth, double modiRate,
                                            int numOutputCells, boolean full) {
        Node root = full
                ? generateFull(random, functions, terminals, featureIndices, maxDepth)
                : generateGrow(random, functions, terminals, featureIndices, maxDepth);
        configureModiNodes(root, modiRate, numOutputCells, random);
        return root;
    }

    // ---------------------------------------------------------------
    // Evaluation
    // ---------------------------------------------------------------

    /**
     * Evaluates this subtree against a feature vector, writing into the
     * shared {@code output} vector along the way. Call this on the root
     * with a freshly-reset OutputVector to evaluate one input pattern.
     */
    public double evaluate(double[] features, OutputVector output) {
        if (type == NodeType.TERMINAL) {
            if (isFeatureTerminal) {
                return features[featureIndex];
            } else {
                return numValue;
            }
        }

        double[] childValues = new double[children.length];
        for (int i = 0; i < children.length; i++) {
            childValues[i] = children[i].evaluate(features, output);
        }

        double computed = applyFunction(childValues);

        if (isModi) {
            output.add(outputCellIndex, computed);
            return childValues[childValues.length - 1];
        }
        return computed;
    }


    private double applyFunction(double[] c) {
        switch (value) {
            case "+": return c[0] + c[1];
            case "-": return c[0] - c[1];
            case "*": return c[0] * c[1];
            case "/": return Math.abs(c[1]) < 1e-9 ? 1.0 : c[0] / c[1];

            case "if>0": return c[0] > 0 ? c[1] : c[2];
            default: throw new IllegalStateException("Unknown function symbol: " + value);
        }
    }

    // ---------------------------------------------------------------
    // Generic tree utilities
    // ---------------------------------------------------------------

    public Node deepCopy() {
        Node copy = new Node(this.type, this.value, this.random);
        copy.numValue = this.numValue;
        copy.isFeatureTerminal = this.isFeatureTerminal;
        copy.featureIndex = this.featureIndex;
        copy.isModi = this.isModi;
        copy.outputCellIndex = this.outputCellIndex;

        if (this.children != null) {
            copy.children = new Node[this.children.length];
            for (int i = 0; i < this.children.length; i++) {
                if (this.children[i] != null) {
                    Node childCopy = this.children[i].deepCopy();
                    copy.children[i] = childCopy;
                    childCopy.parent = copy;
                }
            }
        }
        return copy;
    }

    public int getNodeCount() {
        int count = 1;
        if (children != null) {
            for (Node c : children) {
                if (c != null) count += c.getNodeCount();
            }
        }
        return count;
    }

    public int getDepth() {
        if (type == NodeType.TERMINAL || children == null) return 1;
        int max = 0;
        for (Node c : children) {
            if (c != null) max = Math.max(max, c.getDepth());
        }
        return max + 1;
    }

    public void collectNodes(List<Node> out) {
        out.add(this);
        if (children != null) {
            for (Node c : children) {
                if (c != null) c.collectNodes(out);
            }
        }
    }


    public Node getRandomNode(Random rnd) {
        List<Node> list = new ArrayList<>();
        collectNodes(list);
        return list.get(rnd.nextInt(list.size()));
    }


    public Node findParentOf(Node target) {
        if (children != null) {
            for (Node c : children) {
                if (c == target) return this;
                if (c != null) {
                    Node found = c.findParentOf(target);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }


    public boolean replaceChild(Node target, Node replacement) {
        if (children == null) return false;
        for (int i = 0; i < children.length; i++) {
            if (children[i] == target) {
                setChild(i, replacement);
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        if (type == NodeType.TERMINAL) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("(").append(value);
        if (isModi) {
            sb.append("*cell").append(outputCellIndex);
        }
        if (children != null) {
            for (Node c : children) {
                sb.append(" ").append(c == null ? "null" : c.toString());
            }
        }
        sb.append(")");
        return sb.toString();
    }

}
