import java.util.ArrayList;
import java.util.List;
import java.util.Random;

//unit terminals:
//bot terminals:

//unit functions:
//bot functions:


public class Node {
    public enum NodeType {
        FUNCTION,
        TERMINAL
    }



    public int numChildren;
    public Node[] children;
    public Node parent;
    public boolean head;
    public String value;
    public Random random;
    public NodeType type;

    public Node(int numChildren, String value, Random random, NodeType type) {
        this.numChildren = numChildren;
        this.value = value;
        this.random = random;
        this.type = type;
        this.parent = null;
        this.head = true;

        if (this.type == NodeType.TERMINAL) {
            this.numChildren = 0;
        }

        this.children = new Node[numChildren];

    }

    //create random function node (may differentiate between unit and bot functions)
    //create random function node (may differentiate between unit and bot terminals)
    public Node(Random random, String[] functions, String[] terminals, NodeType type, int numChildren) {
        this.random = random;
        this.parent = null;
        this.head = true;

        this.type = type;

        if (type == NodeType.TERMINAL) {
            this.numChildren = 0;
        } else {
            this.numChildren = numChildren;
        }

        children = new Node[this.numChildren];



        if (this.type == NodeType.FUNCTION) {
            int funcIndex = random.nextInt(functions.length);
            this.value = functions[funcIndex];
        } else if (this.type == NodeType.TERMINAL) {
            int termIndex = random.nextInt(terminals.length);
            this.value = terminals[termIndex];
        }
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
        if (node != null) {
            node.parent = this;
            node.head = false;
        }
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


    public Node deepCopy() {
        Node copy = new Node(this.numChildren, this.value, this.random, this.type);
        copy.parent = null;
        copy.head = this.head;
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


    //make sure to check root/head replacements when using this function
    public Node replaceSubtree(Node target, Node replacement) {
        if (target == this) {
            if (replacement != null) {
                replacement.parent = this.parent;
                replacement.head = this.head;
            }
            this.head = false;
            return replacement;
        }
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                if (children[i] == target) {
                    children[i] = replacement;
                    if (replacement != null) {
                        replacement.parent = this;
                        replacement.head = false;
                    }
                    return this;
                } else if (children[i] != null) {
                    Node res = children[i].replaceSubtree(target, replacement);
                    if (res != children[i]) {
                        children[i] = res;
                        if (children[i] != null) {
                            children[i].parent = this;
                            children[i].head = false;
                        }
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
        while (!cur.head && cur.parent != null) {
            cur = cur.parent;
        }
        cur.head = true;
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








    //TODO: Full and Grow

    //TODO: Evaluate
    
}

