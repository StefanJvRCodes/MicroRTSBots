
import java.util.Random;

public class Tree {
    //functions are add, subtract, multiply, divide
    //terminals are date, time, Residential_electricity_price, Residential_solar_generation, Residential_wind_generation, Temperature, Relative Humidity
    Node head;
    GenerationType type;
    int maxDepth;
    Random random;
    public enum GenerationType {
        GROW,
        FULL,
        HALF_AND_HALF
    }


    
    public double evaluate(TrainingCase trainingCase, String[][] terminals) {
        return Double.parseDouble(head.evaluate(trainingCase, terminals));
    }



    public double meanSquaredError(TrainingCase trainingCase, String[][] terminals) {
        return head.meanSquaredError(trainingCase, terminals);
    }




    public Tree(GenerationType type, int maxDepth, Random random, String[] functions, String[][] terminals, int functionProbability, int terminalProbability) {
        this.type = type;
        this.maxDepth = maxDepth;
        this.random = random;
        head = null;

        switch (this.type) {
            case GROW -> grow(functions, terminals, functionProbability, terminalProbability);
            case FULL -> full(functions, terminals);
            case HALF_AND_HALF -> {
                if (random.nextBoolean()) {
                    grow(functions, terminals, functionProbability, terminalProbability);
                } else {
                    full(functions, terminals);
                }
            }
        }
    }



    public void printTree() {
        //printNode(head, 0);
    }




    public String getRandomFunction() {
        String[] functions = {"ADD", "SUBTRACT", "MULTIPLY", "DIVIDE"};

        int index = random.nextInt(functions.length);
        return functions[index];
    }

    public int getDepth(){
        return head.getDepth() - 1;
    }




    public void grow(String[] functions, String[][] terminals, int functionProbability, int terminalProbability) {
        int min = 1;
        int max = 100;
        int randomNodeType = random.nextInt((max - min) + 1) + min;
        if (randomNodeType <= functionProbability) {
            head = new Node(2, Node.NodeType.FUNCTION, getRandomFunction());
            head.grow(maxDepth, random, functionProbability, terminalProbability, functions, terminals);
        } else {
            head = new Node(random, terminals);
        }
    }


    //functions: "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE"
    public void full(String[] functions, String[][] terminals) {
        head = new Node(2, Node.NodeType.FUNCTION, getRandomFunction());
        head.full(maxDepth, random, functions, terminals);
    }



    public void generateRandomTree(int maxDepth) {
        // Generate a random tree based on the specified generation type and maximum depth
    }



    @Override
    public String toString() {
        if (head == null) {
            return "<empty tree>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(getHeadLabel());
        if (head.children != null && head.children.length > 0) {
            for (int i = 0; i < head.children.length; i++) {
                boolean isLast = i == head.children.length - 1;
                appendNode(sb, head.children[i], "", isLast);
            }
        }
        return sb.toString();
    }



    private void appendNode(StringBuilder sb, Node node, String prefix, boolean isLast) {
        sb.append("\n").append(prefix).append(isLast ? "└── " : "├── ");

        if (node == null) {
            sb.append("null");
            return;
        }

        sb.append(getNodeLabel(node));

        if (node.children != null && node.children.length > 0) {
            String childPrefix = prefix + (isLast ? "    " : "│   ");
            for (int i = 0; i < node.children.length; i++) {
                boolean childIsLast = i == node.children.length - 1;
                appendNode(sb, node.children[i], childPrefix, childIsLast);
            }
        }
    }



    private String getNodeLabel(Node node) {
        if (node.type == Node.NodeType.FUNCTION) {
            return "(" + node.value + ")";
        }
        return node.value + " = " + node.numValue;
    }



    private String getHeadLabel() {
        if (head.type == Node.NodeType.TERMINAL) {
            return getNodeLabel(head);
        }
        try {
            return "(" + head.value + ") = " + head.evaluateDouble();
        } catch (Exception e) {
            return "(" + head.value + ") = <error>";
        }
    }
}
