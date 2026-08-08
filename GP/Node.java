
import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class Node {
    public enum NodeType {
        FUNCTION,
        TERMINAL
    }


    public int numChildren;
    public Node[] children;
    public Node parent;


    public NodeType type;
    public String value;
    public double numValue;
    

    public Random random;


    public Node(int numChildren, NodeType type, String value) {
        this.numChildren = numChildren;
        this.type = type;
        this.value = value;
        this.parent = null;
        if (this.type == NodeType.TERMINAL) {
            this.numChildren = 0;
        }
        children = new Node[this.numChildren];
    }



    public Node(Random random, String[] functions, String[][] terminals) {
        this.random = random;
        this.parent = null;
        if (random.nextInt(2) == 0) {
            this.type = NodeType.FUNCTION;
            this.numChildren = 2;
            children = new Node[this.numChildren];
            int funcIndex = random.nextInt(functions.length);
            this.value = functions[funcIndex];
        } else {
            this.type = NodeType.TERMINAL;
            this.numChildren = 0;
            children = null;
            int termIndex = random.nextInt(terminals.length);
            int varIndex = random.nextInt(terminals[termIndex].length);
            this.value = terminals[termIndex][varIndex];
        }
    }



    public Node(Random random, String[][] terminals){
        this.random = random;
        this.parent = null;

        this.type = NodeType.TERMINAL;
        this.numChildren = 0;
        children = null;

        int termIndex = random.nextInt(terminals.length);
        int varIndex = random.nextInt(terminals[termIndex].length);
        this.value = terminals[termIndex][varIndex];
    }



    public Node(Random random, String[] functions) {
        this.random = random;
        this.parent = null;

        this.type = NodeType.FUNCTION;
        this.numChildren = 2;
        children = new Node[this.numChildren];

        int funcIndex = random.nextInt(functions.length);
        this.value = functions[funcIndex];
    }

    

    public void setChild(int index, Node node) {
        if (type == NodeType.TERMINAL) {
            throw new IllegalStateException("Cannot set child on terminal node");
        }
        if (children == null) {
            children = new Node[numChildren];
        }
        if (index < 0 || index >= numChildren) {
            throw new IndexOutOfBoundsException("Child index out of range: " + index);
        }
        children[index] = node;
        if (node != null) node.parent = this;
    }



    public void setFunctionValue(String function) {
        if (type == NodeType.FUNCTION) {
            this.value = function;
        }
    }



    public void setTerminalValue(String terminal) {
        if (type == NodeType.TERMINAL) {
            this.value = terminal;
        }
    }



    public String evaluate(TrainingCase trainingCase, String[][] terminals) {
        return String.valueOf(evaluateForTrainingCase(trainingCase, terminals));
    }



    public double meanSquaredError(TrainingCase trainingCase, String[][] terminals) {
        if (trainingCase == null || trainingCase.xBlock == null || trainingCase.xBlock.length == 0) {
            return 0.0;
        }

        double predicted = evaluateForTrainingCase(trainingCase, terminals);
        return Math.pow(predicted - trainingCase.targetY, 2);
    }



    public double evaluateForTrainingCase(TrainingCase trainingCase, String[][] terminals) {
        setValues(terminals, trainingCase.getTrainingArray());
        return evaluateDouble();
    }

    

    public double evaluateDouble() {
        if (type == NodeType.TERMINAL) {
            return numValue;
        }
        if (children == null || children.length < 1) {
            throw new IllegalStateException("Function node has no children: " + value);
        }
        switch (value) {
            case "ADD": {
                double result = 0.0;
                for (int i = 0; i < numChildren; i++) {
                    if (children[i] == null) continue;
                    result += children[i].evaluateDouble();
                }
                return result;
            }
            case "SUBTRACT": {
                double result = children[0] == null ? 0.0 : children[0].evaluateDouble();
                for (int i = 1; i < numChildren; i++) {
                    if (children[i] == null) continue;
                    result -= children[i].evaluateDouble();
                }
                return result;
            }
            case "MULTIPLY": {
                double result = 1.0;
                for (int i = 0; i < numChildren; i++) {
                    if (children[i] == null) continue;
                    result *= children[i].evaluateDouble();
                }
                return result;
            }
            case "DIVIDE": {
                double result = children[0] == null ? 0.0 : children[0].evaluateDouble();
                for (int i = 1; i < numChildren; i++) {
                    if (children[i] == null) continue;
                    double denominator = children[i].evaluateDouble();
                    if (Math.abs(denominator) < 1e-12) {
                        continue;
                    } else {
                        result /= denominator;
                    }
                }
                return result;
            }
            default:
                throw new IllegalArgumentException("Unknown function: " + value);
        }
    }



    public void setValues(String[][] terminals, double[][] terminalValues) {
        if (type == NodeType.TERMINAL) {
            for (int i = 0; i < terminals.length; i++) {
                for (int j = 0; j < terminals[i].length; j++) {
                    if (terminals[i][j].equals(value)) {
                        numValue = terminalValues[i][j];
                        return;
                    }
                }
            }
            return;
        }
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                if (children[i] != null) {
                    children[i].setValues(terminals, terminalValues);
                }
            }
        }
    }



    public Node deepCopy() {
        Node copy = new Node(this.numChildren, this.type, this.value);
        copy.numValue = this.numValue;
        copy.random = this.random;
        copy.parent = null;
        if (this.children != null) {
            copy.children = new Node[this.children.length];
            for (int i = 0; i < this.children.length; i++) {
                if (this.children[i] != null) {
                    Node childCopy = this.children[i].deepCopy();
                    copy.children[i] = childCopy;
                    childCopy.parent = copy;
                } else {
                    copy.children[i] = null;
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
            if (c != null) {
                int d = c.getDepth();
                if (d > max) max = d;
            }
        }
        return max + 1;
    }



    public int getDepthInTree() {
        int depth = 1;
        Node cur = this;
        while (cur.parent != null) {
            depth++;
            cur = cur.parent;
        }
        return depth;
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
        if (list.isEmpty()) return null;
        return list.get(rnd.nextInt(list.size()));
    }


    
    public Node replaceSubtree(Node target, Node replacement) {
        if (target == this) {
            if (replacement != null) replacement.parent = this.parent;
            return replacement;
        }
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                if (children[i] == target) {
                    children[i] = replacement;
                    if (replacement != null) replacement.parent = this;
                    return this;
                } else if (children[i] != null) {
                    Node res = children[i].replaceSubtree(target, replacement);
                    if (res != children[i]) {
                        children[i] = res;
                        if (children[i] != null) children[i].parent = this;
                        return this;
                    }
                }
            }
        }
        return this;
    }



    public Node[] getChildren() {
        return children;
    }


    
    public Node getHead(){
        Node cur = this;
        while (cur.parent != null) {
            cur = cur.parent;
        }
        return cur;
    }


    @Override
    public String toString() {
        if (type == NodeType.TERMINAL) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("(").append(value);
        if (children != null) {
            for (Node c : children) {
                sb.append(" ");
                sb.append(c == null ? "null" : c.toString());
            }
        }
        sb.append(")");
        return sb.toString();
    }



    public void grow(int maxDepth, Random random, int functionProbability, int terminalProbability, String[] functions, String[][] terminals) {
        if (this.type != NodeType.FUNCTION || this.children == null) {
            return;
        }

        for (int i = 0; i < this.numChildren; i++) {
            if (maxDepth <= 1) {
                this.setChild(i, new Node(random, terminals));
            } else {
                int randomNodeType = random.nextInt((100 - 1) + 1) + 1;
                if (randomNodeType < functionProbability) {
                    Node functionNode = new Node(random, functions);
                    this.setChild(i, functionNode);
                    functionNode.grow(maxDepth - 1, random, functionProbability, terminalProbability, functions, terminals);
                } else {
                    this.setChild(i, new Node(random, terminals));
                }
            }
        }
    }



    public void full(int maxDepth, Random random, String[] functions, String[][] terminals) {
        if (this.type != NodeType.FUNCTION || this.children == null) {
            return;
        }

        for (int i = 0; i < this.numChildren; i++) {
            if (maxDepth <= 1) {
                this.setChild(i, new Node(random, terminals));
            } else {
                Node functionNode = new Node(random, functions);
                this.setChild(i, functionNode);
                functionNode.full(maxDepth - 1, random, functions, terminals);
            }
        }
    }   
}
