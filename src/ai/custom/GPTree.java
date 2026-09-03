package ai.custom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

final class GPTree {

    enum GenerationMode {
        GROW,
        FULL
    }

    static final Map<String, Integer> FUNCTION_ARITY = Map.of(
            "+", 2,
            "-", 2,
            "*", 2,
            "/", 2,
            "if>0", 3
    );

    private static final String[] DEFAULT_FUNCTIONS = {"if>0", "+", "-", "*", "/"};

    private final Random random;
    private final double modiRate;
    private final int numOutputCells;
    private final int functionProbability;
    private final int maxDepth;
    private final String[] functions;
    private final int[] featureIndices;
    private final double[] outputVector;

    private Node root;

    GPTree(Random random, int maxDepth, double modiRate, int numOutputCells, int functionProbability) {
        this(random, maxDepth, modiRate, numOutputCells, functionProbability, DEFAULT_FUNCTIONS,
                new int[] {
                        GPFeatureExtractor.FEATURE_ENEMY_FAR_AWAY,
                        GPFeatureExtractor.FEATURE_ENEMY_IN_RANGE,
                        GPFeatureExtractor.FEATURE_NUM_ENEMIES,
                        GPFeatureExtractor.FEATURE_ENEMY_TYPE,
                        GPFeatureExtractor.FEATURE_NUM_ALLIES_NEAR,
                        GPFeatureExtractor.FEATURE_MY_UNIT_TYPE,
                        GPFeatureExtractor.FEATURE_NUM_RESOURCES
                }, GenerationMode.GROW);
    }

    GPTree(Random random, int maxDepth, double modiRate, int numOutputCells, int functionProbability,
           String[] functions, int[] featureIndices, GenerationMode mode) {
        this.random = random;
        this.maxDepth = maxDepth;
        this.modiRate = modiRate;
        this.numOutputCells = numOutputCells;
        this.functionProbability = functionProbability;
        this.functions = (functions != null && functions.length > 0) ? functions : DEFAULT_FUNCTIONS;
        this.featureIndices = (featureIndices != null && featureIndices.length > 0)
                ? featureIndices
                : new int[] {
                    GPFeatureExtractor.FEATURE_ENEMY_FAR_AWAY,
                    GPFeatureExtractor.FEATURE_ENEMY_IN_RANGE,
                    GPFeatureExtractor.FEATURE_NUM_ENEMIES,
                    GPFeatureExtractor.FEATURE_ENEMY_TYPE,
                    GPFeatureExtractor.FEATURE_NUM_ALLIES_NEAR,
                    GPFeatureExtractor.FEATURE_MY_UNIT_TYPE,
                    GPFeatureExtractor.FEATURE_NUM_RESOURCES
                };
        this.outputVector = new double[numOutputCells];
        this.root = generate(mode, maxDepth);
        configureModiRecursive(root);
    }

    GPTree(Node root, Random random, double modiRate, int numOutputCells) {
        this.random = random;
        this.maxDepth = root == null ? 1 : root.getDepth();
        this.modiRate = modiRate;
        this.numOutputCells = numOutputCells;
        this.functionProbability = 100;
        this.functions = DEFAULT_FUNCTIONS;
        this.featureIndices = new int[] {
                GPFeatureExtractor.FEATURE_ENEMY_FAR_AWAY,
                GPFeatureExtractor.FEATURE_ENEMY_IN_RANGE,
                GPFeatureExtractor.FEATURE_NUM_ENEMIES,
                GPFeatureExtractor.FEATURE_ENEMY_TYPE,
                GPFeatureExtractor.FEATURE_NUM_ALLIES_NEAR,
                GPFeatureExtractor.FEATURE_MY_UNIT_TYPE,
                GPFeatureExtractor.FEATURE_NUM_RESOURCES
        };
        this.outputVector = new double[numOutputCells];
        this.root = root;
    }

    static GPTree randomTree(Random random, int maxDepth, double modiRate, int numOutputCells, int functionProbability) {
        GenerationMode mode = random.nextBoolean() ? GenerationMode.GROW : GenerationMode.FULL;
        return new GPTree(random, maxDepth, modiRate, numOutputCells, functionProbability, DEFAULT_FUNCTIONS,
                new int[] {
                        GPFeatureExtractor.FEATURE_ENEMY_FAR_AWAY,
                        GPFeatureExtractor.FEATURE_ENEMY_IN_RANGE,
                        GPFeatureExtractor.FEATURE_NUM_ENEMIES,
                        GPFeatureExtractor.FEATURE_ENEMY_TYPE,
                        GPFeatureExtractor.FEATURE_NUM_ALLIES_NEAR,
                        GPFeatureExtractor.FEATURE_MY_UNIT_TYPE,
                        GPFeatureExtractor.FEATURE_NUM_RESOURCES
                }, mode);
    }

    GPTree deepCopy() {
        Node rootCopy = root == null ? null : root.deepCopy();
        GPTree copy = new GPTree(rootCopy, random, modiRate, numOutputCells);
        copy.configureModiRecursive(copy.root);
        return copy;
    }

    Node getRoot() {
        return root;
    }

    double[] evaluate(double[] features) {
        Arrays.fill(outputVector, 0.0);
        if (root != null) {
            root.evaluate(features, outputVector);
        }
        return Arrays.copyOf(outputVector, outputVector.length);
    }

    void grow() {
        root = generate(GenerationMode.GROW, maxDepth);
        configureModiRecursive(root);
    }

    void full() {
        root = generate(GenerationMode.FULL, maxDepth);
        configureModiRecursive(root);
    }

    private Node generate(GenerationMode mode, int depth) {
        if (depth <= 0) {
            return createTerminalNode();
        }
        if (mode == GenerationMode.GROW && random.nextInt(100) >= functionProbability) {
            return createTerminalNode();
        }

        Node node = createFunctionNode();
        for (int i = 0; i < node.children.length; i++) {
            node.setChild(i, generate(mode, depth - 1));
        }
        return node;
    }

    private Node createTerminalNode() {
        return new Node(random, functions, featureIndices, Node.NodeType.TERMINAL, modiRate, numOutputCells);
    }

    private Node createFunctionNode() {
        return new Node(random, functions, featureIndices, Node.NodeType.FUNCTION, modiRate, numOutputCells);
    }

    private void configureModiRecursive(Node node) {
        if (node == null) {
            return;
        }
        if (node.type == Node.NodeType.TERMINAL) {
            node.isModi = false;
            node.outputCellIndex = -1;
            return;
        }

        node.isModi = random.nextDouble() < modiRate;
        node.outputCellIndex = node.isModi ? random.nextInt(numOutputCells) : -1;

        if (node.children != null) {
            for (Node child : node.children) {
                configureModiRecursive(child);
            }
        }
    }

    @Override
    public String toString() {
        return root == null ? "<empty tree>" : root.toString();
    }

    static final class Node {
        enum NodeType { FUNCTION, TERMINAL }

        final NodeType type;
        final String value;
        final Random random;
        final int[] featureIndices;
        final String[] functions;
        final double modiRate;
        final int numOutputCells;

        Node[] children;
        Node parent;
        boolean isFeatureTerminal;
        int featureIndex = -1;
        double numValue;
        boolean isModi;
        int outputCellIndex = -1;

        Node(Random random, String[] functions, int[] featureIndices, NodeType type, double modiRate, int numOutputCells) {
            this.random = random;
            this.functions = functions;
            this.featureIndices = featureIndices;
            this.type = type;
            this.modiRate = modiRate;
            this.numOutputCells = numOutputCells;
            this.parent = null;

            if (type == NodeType.TERMINAL) {
                boolean useFeature = featureIndices != null && featureIndices.length > 0 && random.nextBoolean();
                if (useFeature) {
                    isFeatureTerminal = true;
                    featureIndex = featureIndices[random.nextInt(featureIndices.length)];
                    value = featureName(featureIndex);
                } else {
                    isFeatureTerminal = false;
                    numValue = random.nextDouble() * 20.0 - 10.0;
                    value = Double.toString(numValue);
                }
                children = null;
            } else {
                isFeatureTerminal = false;
                value = functions[random.nextInt(functions.length)];
                children = new Node[FUNCTION_ARITY.getOrDefault(value, 2)];
            }
        }

        private Node(NodeType type, String value, Random random) {
            this.type = type;
            this.value = value;
            this.random = random;
            this.featureIndices = null;
            this.functions = null;
            this.modiRate = 0.0;
            this.numOutputCells = 0;
        }

        void setChild(int index, Node node) {
            if (type == NodeType.TERMINAL) {
                throw new IllegalStateException("Cannot set child on a terminal node");
            }
            children[index] = node;
            if (node != null) {
                node.parent = this;
            }
        }

        double evaluate(double[] features, double[] output) {
            if (type == NodeType.TERMINAL) {
                return isFeatureTerminal ? features[Math.max(0, Math.min(featureIndex, features.length - 1))] : numValue;
            }

            double[] childValues = new double[children.length];
            for (int i = 0; i < children.length; i++) {
                childValues[i] = children[i].evaluate(features, output);
            }

            double computed = applyFunction(childValues);
            if (isModi && output.length > 0) {
                output[outputCellIndex] += computed;
            }
            return computed;
        }

        private double applyFunction(double[] c) {
            switch (value) {
                case "+":
                    return c[0] + c[1];
                case "-":
                    return c[0] - c[1];
                case "*":
                    return c[0] * c[1];
                case "/":
                    return Math.abs(c[1]) < 1e-9 ? 1.0 : c[0] / c[1];
                case "if>0":
                    return c[0] > 0 ? c[1] : c[2];
                default:
                    throw new IllegalStateException("Unknown function symbol: " + value);
            }
        }

        Node deepCopy() {
            Node copy = new Node(this.type, this.value, this.random);
            copy.isFeatureTerminal = this.isFeatureTerminal;
            copy.featureIndex = this.featureIndex;
            copy.numValue = this.numValue;
            copy.isModi = this.isModi;
            copy.outputCellIndex = this.outputCellIndex;
            if (this.children != null) {
                copy.children = new Node[this.children.length];
                for (int i = 0; i < this.children.length; i++) {
                    if (this.children[i] != null) {
                        copy.children[i] = this.children[i].deepCopy();
                        copy.children[i].parent = copy;
                    }
                }
            }
            return copy;
        }

        int getDepth() {
            if (type == NodeType.TERMINAL || children == null || children.length == 0) {
                return 1;
            }
            int max = 0;
            for (Node child : children) {
                if (child != null) {
                    max = Math.max(max, child.getDepth());
                }
            }
            return max + 1;
        }

        void collectNodes(List<Node> out) {
            out.add(this);
            if (children != null) {
                for (Node child : children) {
                    if (child != null) {
                        child.collectNodes(out);
                    }
                }
            }
        }

        Node getRandomNode(Random rnd) {
            List<Node> nodes = new ArrayList<>();
            collectNodes(nodes);
            return nodes.get(rnd.nextInt(nodes.size()));
        }

        Node findParentOf(Node target) {
            if (children != null) {
                for (Node child : children) {
                    if (child == target) {
                        return this;
                    }
                    if (child != null) {
                        Node found = child.findParentOf(target);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
            return null;
        }

        boolean replaceChild(Node target, Node replacement) {
            if (children == null) {
                return false;
            }
            for (int i = 0; i < children.length; i++) {
                if (children[i] == target) {
                    setChild(i, replacement);
                    return true;
                }
            }
            return false;
        }

        Node getRoot() {
            Node current = this;
            while (current.parent != null) {
                current = current.parent;
            }
            return current;
        }

        private static String featureName(int featureIndex) {
            switch (featureIndex) {
                case GPFeatureExtractor.FEATURE_ENEMY_FAR_AWAY:
                    return "enemyFarAway";
                case GPFeatureExtractor.FEATURE_ENEMY_IN_RANGE:
                    return "enemyInRange";
                case GPFeatureExtractor.FEATURE_NUM_ENEMIES:
                    return "numEnemies";
                case GPFeatureExtractor.FEATURE_ENEMY_TYPE:
                    return "enemyType";
                case GPFeatureExtractor.FEATURE_NUM_ALLIES_NEAR:
                    return "numAlliesNear";
                case GPFeatureExtractor.FEATURE_MY_UNIT_TYPE:
                    return "myUnitType";
                case GPFeatureExtractor.FEATURE_NUM_RESOURCES:
                    return "numResources";
                default:
                    return "feature" + featureIndex;
            }
        }

        @Override
        public String toString() {
            if (type == NodeType.TERMINAL) {
                return isFeatureTerminal ? value + "[F" + featureIndex + "]" : value;
            }
            StringBuilder sb = new StringBuilder();
            sb.append('(').append(value);
            if (isModi) {
                sb.append("*cell").append(outputCellIndex);
            }
            if (children != null) {
                for (Node child : children) {
                    sb.append(' ').append(child == null ? "null" : child.toString());
                }
            }
            sb.append(')');
            return sb.toString();
        }
    }
}
