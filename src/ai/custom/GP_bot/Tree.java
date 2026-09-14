package ai.custom.GP_bot;
import java.util.Random;

// feature vector is input to tree

public class Tree {
    public Node root;

    Random random;
    double[] features;
    double[] outputVector;
    int growProbability;
    int fullProbability;
    int functionProbability;
    int terminalProbability;
    int maxDepth;
    int numOutputCells;
    double modiRate;
    String[] functions;
    String[] terminals;
    int[] featureIndices;

    double winRate = 0.0;

    /**
     * Fully-configured tree. Every parameter Tree needs to build and
     * evaluate itself is passed in explicitly (no hidden defaults leaking
     * in through uninitialized fields).
     */
    public Tree(Random random, double modiRate, int functionProbability, int terminalProbability,
                int maxDepth, int fullProbability, int growProbability,
                String[] functions, String[] terminals, int[] featureIndices, int numOutputCells) {

        this.random = random;
        this.functionProbability = functionProbability;
        this.terminalProbability = terminalProbability;
        this.maxDepth = maxDepth;
        this.fullProbability = fullProbability;
        this.growProbability = growProbability;
        this.modiRate = modiRate;
        this.numOutputCells = numOutputCells;

        this.outputVector = new double[numOutputCells];

        this.functions = (functions != null && functions.length > 0) ? functions : Node.DEFAULT_FUNCTIONS;
        this.terminals = terminals;
        this.featureIndices = (featureIndices != null && featureIndices.length > 0) ? featureIndices : Node.DEFAULT_FEATURE_INDICES;
    }

    /**
     * Wraps an already-built root node (used for crossover/mutation/reproduction
     * results). Still needs numOutputCells so the tree can allocate its own
     * outputVector — without it, evaluate() would hand Node a null output
     * array and NPE the moment a Modi node tries to write into it.
     */
    public Tree(Node node, int numOutputCells) {
        this.root = node;
        this.numOutputCells = numOutputCells;
        this.outputVector = new double[numOutputCells];
    }

    public void full() {
        root = recursiveFull(maxDepth);
    }

    public void subtreeFull(Node node, int depth) {
        if (depth <= 0) {
            return;
        }
        Node newSubtree = recursiveFull(depth);
        node.parent.replaceChild(node, newSubtree);
    }

    public Node recursiveFull(int depth) {
        if (depth <= 0) {
            return createNode(Node.NodeType.TERMINAL);
        }

        Node newNode = createNode(Node.NodeType.FUNCTION);
        for (int i = 0; i < newNode.children.length; i++) {
            newNode.setChild(i, recursiveFull(depth - 1));
        }
        return newNode;
    }

    public void grow() {
        root = recursiveGrow(maxDepth);
        configureModiRecursive(root);
    }

    public void grow(int currDepth) {
        root = recursiveGrow(currDepth);
        configureModiRecursive(root);
    }

    public void subtreeGrow(Node node, int depth) {
        if (depth <= 0) {
            return;
        }
        Node newSubtree = recursiveGrow(depth);
        node.parent.replaceChild(node, newSubtree);
        configureModiRecursive(newSubtree);
    }

    public Node recursiveGrow(int depth) {
        if (depth <= 0) {
            return createNode(Node.NodeType.TERMINAL);
        }

        boolean chooseFunction = random.nextInt(100) < functionProbability;

        if (!chooseFunction) {
            return createNode(Node.NodeType.TERMINAL);
        }

        Node newNode = createNode(Node.NodeType.FUNCTION);
        for (int i = 0; i < newNode.children.length; i++) {
            newNode.setChild(i, recursiveGrow(depth - 1));
        }
        return newNode;
    }

    public void evaluate(double[] features) {
        this.features = features;
        if (outputVector != null) {
            for (int i = 0; i < outputVector.length; i++) {
                outputVector[i] = 0.0;
            }
        }
        root.evaluate(features, outputVector);
    }

    public Node createNode(Node.NodeType type) {
        return new Node(random, functions, terminals, featureIndices, type, modiRate, numOutputCells);
    }

    /**
     * Assigns Modi nodes across the subtree rooted at {@code node}, following
     * the paper's rules: leaves are never Modi, and every other node becomes
     * Modi with probability {@code modiRate}, uniformly picking an
     * output-vector cell when it does. This is the single place tree
     * construction and mutation/crossover funnel through to (re)configure
     * Modi nodes, using this tree's own modiRate/numOutputCells/random.
     */
    public void configureModiRecursive(Node node) {
        if (node.type == Node.NodeType.TERMINAL) {
            node.isModi = false;
            return;
        }
        node.isModi = random.nextDouble() < modiRate;
        node.outputCellIndex = node.isModi ? random.nextInt(numOutputCells) : -1;

        if (node.children != null) {
            for (Node c : node.children) {
                if (c != null) configureModiRecursive(c);
            }
        }
    }

    public void enforceRootModi() {
        if (root != null) {
            root.isModi = true;
            root.outputCellIndex = random.nextInt(numOutputCells);
        }
    }
}