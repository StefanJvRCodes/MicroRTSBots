package ai.custom.GP_bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Node {

    public enum NodeType { FUNCTION, TERMINAL }

    public static final int FEATURE_ENEMY_FAR_AWAY = 0;
    public static final int FEATURE_ENEMY_IN_RANGE = 1;
    public static final int FEATURE_NUM_ENEMIES = 2;
    public static final int FEATURE_ENEMY_TYPE = 3;
    public static final int FEATURE_NUM_ALLIES_NEAR = 4;
    public static final int FEATURE_MY_UNIT_TYPE = 5;
    public static final int FEATURE_NUM_RESOURCES = 6;

    public static final String[] FEATURE_NAMES = {
        "enemyFarAway",
        "enemyInRange",
        "numEnemies",
        "enemyType",
        "numAlliesNear",
        "myUnitType",
        "numResources"
    };

    public static final int[] DEFAULT_FEATURE_INDICES = {
        FEATURE_ENEMY_FAR_AWAY,
        FEATURE_ENEMY_IN_RANGE,
        FEATURE_NUM_ENEMIES,
        FEATURE_ENEMY_TYPE,
        FEATURE_NUM_ALLIES_NEAR,
        FEATURE_MY_UNIT_TYPE,
        FEATURE_NUM_RESOURCES
    };

    /** Default function set shared by every subtree-generation call site. */
    public static final String[] DEFAULT_FUNCTIONS = {"if>0", "+", "-", "*", "/"};

    /** Arity of each supported function symbol. This is the single source of truth. */
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
    public double[] features = new double[FEATURE_NAMES.length];

    public Node(Random random, String[] functions, String[] terminals, int[] featureIndices, NodeType type, double modiRate, int numOutputCells) {
        this.random = random;
        this.parent = null;
        this.type = type;
        this.isModi = false;
        this.outputCellIndex = -1;
        this.featureIndex = -1;

        if (type == NodeType.TERMINAL) {
            this.children = null;
            int[] usableFeatureIndices = (featureIndices != null && featureIndices.length > 0)
                    ? featureIndices
                    : DEFAULT_FEATURE_INDICES;
            boolean useFeature = usableFeatureIndices.length > 0 && random.nextBoolean();
            if (useFeature) {
                this.isFeatureTerminal = true;
                this.featureIndex = usableFeatureIndices[random.nextInt(usableFeatureIndices.length)];
                this.value = FEATURE_NAMES[Math.max(0, Math.min(featureIndex, FEATURE_NAMES.length - 1))];
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
            // Modi-node assignment (isModi / outputCellIndex) is handled as a
            // separate pass by Tree.configureModiRecursive, not here, so that
            // it stays in exactly one place regardless of how a Node was built.
        }
    }

    /** Bare constructor used internally by deepCopy(). */
    public Node(NodeType type, String value, Random random) {
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
    // Evaluation
    // ---------------------------------------------------------------

    /**
     * Evaluates this subtree against a feature vector, writing into the
     * shared {@code output} vector along the way. Call this on the root
     * with a freshly-reset OutputVector to evaluate one input pattern.
     */
    public double evaluate(double[] features, double[] output) {
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
            output[outputCellIndex] = computed;
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

    public Node getRoot() {
        Node current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
    }

    @Override
    public String toString() {
        if (type == NodeType.TERMINAL) {
            return isFeatureTerminal ? value + "[F" + featureIndex + "]" : value;
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