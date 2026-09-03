import java.util.Map;
import java.util.Random;


//feature vector is input to tree


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

        private static final String[] DEFAULT_FUNCTIONS = {"if>0", "+", "-", "*", "/"};
        private static final int[] DEFAULT_FEATURE_INDICES = {
            Node.FEATURE_ENEMY_FAR_AWAY,
            Node.FEATURE_ENEMY_IN_RANGE,
            Node.FEATURE_NUM_ENEMIES,
            Node.FEATURE_ENEMY_TYPE,
            Node.FEATURE_NUM_ALLIES_NEAR,
            Node.FEATURE_MY_UNIT_TYPE,
            Node.FEATURE_NUM_RESOURCES
        };

    double winRate = 0.0;



    public static final Map<String, Integer> FUNCTION_ARITY = Map.of(
            "+", 2,
            "-", 2,
            "*", 2,
            "/", 2,      // protected division
            "if>0", 3    // condition, then-branch, else-branch
    );


    



    public Tree(){

    }


    public Tree(Node node){
        this.root = node;
    }


    public Tree(Random random, String[] functions, String[] terminals, int[] featureIndices, double modiRate, int numOutputCells, int functionProbability, int terminalProbability, int maxDepth, int fullProbability, int growProbability) {
        
        this.random = random;
        this.functionProbability = functionProbability;
        this.terminalProbability = terminalProbability;
        this.maxDepth = maxDepth;
        this.fullProbability = fullProbability;
        this.growProbability = growProbability;
        this.numOutputCells = numOutputCells;
        this.modiRate = modiRate;
        this.outputVector = new double[numOutputCells];
        for (int K = 0; K < numOutputCells; K++) {
            outputVector[K] = 0.0;
        }


        this.functions = (functions != null && functions.length > 0) ? functions : DEFAULT_FUNCTIONS;
        this.terminals = terminals;
        this.featureIndices = (featureIndices != null && featureIndices.length > 0) ? featureIndices : DEFAULT_FEATURE_INDICES;
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
        configureModiRecursive(root, modiRate, numOutputCells);
    }

    public void grow(int currDepth) {
        root = recursiveGrow(currDepth);
        configureModiRecursive(root, modiRate, numOutputCells);
    }

    public void subtreeGrow(Node node, int depth) {
        if (depth <= 0) {
            return;
        }
        Node newSubtree = recursiveGrow(depth);
        node.parent.replaceChild(node, newSubtree);
        configureModiRecursive(newSubtree, modiRate, numOutputCells);
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


    public Node createNode(Node.NodeType type){
        if (type == Node.NodeType.TERMINAL) {
            return new Node(random, functions, terminals, featureIndices, Node.NodeType.TERMINAL, modiRate, numOutputCells);
        } else {
            return new Node(random, functions, terminals, featureIndices, Node.NodeType.FUNCTION, modiRate, numOutputCells);
        }
    }



    public void configureModiRecursive(Node node, double modiRate, int numOutputCells) {
        if (node.type == Node.NodeType.TERMINAL) {
            node.isModi = false;
            return;
        }
        node.isModi = random.nextDouble() < modiRate;
        if (node.isModi) {
            node.outputCellIndex = random.nextInt(numOutputCells);
        } else {
            node.outputCellIndex = -1;
        }

        if (node.children != null) {
            for (Node c : node.children) {
                if (c != null) configureModiRecursive(c, modiRate, numOutputCells);
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