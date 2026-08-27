import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class NodeOld {
    public enum NodeType {
        HEAD,
        ACTION_HEAD,
        FUNCTION,
        TERMINAL
    }

    //only applies to action heads, which are the roots of action subtrees
    public enum ACTION {
        MOVE_UP, MOVE_RIGHT, MOVE_LEFT, MOVE_DOWN,
        ATTACK_UP, ATTACK_RIGHT, ATTACK_LEFT, ATTACK_DOWN,
        PRODUCE_BASE_UP, PRODUCE_BASE_RIGHT, PRODUCE_BASE_LEFT, PRODUCE_BASE_DOWN,
        PRODUCE_BARRACKS_UP, PRODUCE_BARRACKS_RIGHT, PRODUCE_BARRACKS_LEFT, PRODUCE_BARRACKS_DOWN,
        PRODUCE_WORKER_UP, PRODUCE_WORKER_RIGHT, PRODUCE_WORKER_LEFT, PRODUCE_WORKER_DOWN,
        PRODUCE_LIGHT_UP, PRODUCE_LIGHT_RIGHT, PRODUCE_LIGHT_LEFT, PRODUCE_LIGHT_DOWN,
        PRODUCE_HEAVY_UP, PRODUCE_HEAVY_RIGHT, PRODUCE_HEAVY_LEFT, PRODUCE_HEAVY_DOWN,
        PRODUCE_RANGED_UP, PRODUCE_RANGED_RIGHT, PRODUCE_RANGED_LEFT, PRODUCE_RANGED_DOWN,
        HARVEST_UP, HARVEST_RIGHT, HARVEST_LEFT, HARVEST_DOWN,
        RETURN_UP, RETURN_RIGHT, RETURN_LEFT, RETURN_DOWN
    }
    //actions: attack(up, right, left, down), move(up, right, left, down)
    //actions: produce(unitType)(up, right, left, down), build(buildingType)(up, right, left, down)
    //actions: harvest(up, right, left, down), return(up, right, left, down)
    //"wait", "move", "harvest", "return", "produce", "attack_location"


    public int numChildren;
    public Node[] children;
    public Node parent;
    public String value;
    public double numValue;
    public Random random;
    public NodeType type;
    public boolean head;
    public int actionIndex;
    public ACTION actionType;

    public Node(int numChildren, String value, Random random, NodeType type) {
        this.numChildren = Math.max(0, numChildren);
        this.value = value;
        this.random = random;
        this.type = type;
        this.parent = null;
        this.head = (type == NodeType.HEAD);
        this.actionIndex = -1;

        if (this.type == NodeType.TERMINAL) {
            this.numChildren = 0;
        }

        if (this.type == NodeType.HEAD){
            this.numChildren = 40;
        }

        this.children = this.numChildren > 0 ? new Node[this.numChildren] : null;
    }

    //create random function node (may differentiate between unit and bot functions)
    //create random function node (may differentiate between unit and bot terminals)
    public Node(Random random, String[] functions, String[] terminals, NodeType type, int numChildren) {
        this.random = random;
        this.parent = null;

        this.type = type;
        this.head = (type == NodeType.HEAD);
        this.actionIndex = -1;

        if (type == NodeType.TERMINAL) {
            this.numChildren = 0;
            this.children = null;
        } else if (type == NodeType.FUNCTION) {
            this.numChildren = numChildren;
            this.children = new Node[this.numChildren];
        } else {
            this.numChildren = Math.max(0, numChildren);
            this.children = this.numChildren > 0 ? new Node[this.numChildren] : null;
        }

        if (this.type == NodeType.FUNCTION) {
            if (functions == null || functions.length == 0) {
                throw new IllegalArgumentException("Function set cannot be empty");
            }
            int funcIndex = random.nextInt(functions.length);
            this.value = functions[funcIndex];
        } else if (this.type == NodeType.TERMINAL) {
            if (terminals == null || terminals.length == 0) {
                throw new IllegalArgumentException("Terminal set cannot be empty");
            }
            int termIndex = random.nextInt(terminals.length);
            this.value = terminals[termIndex];
        } else {
            this.value = "HEAD";
        }
    }

    public boolean isStructuralHead() {
        return type == NodeType.HEAD || type == NodeType.ACTION_HEAD;
    }

    public Node getPrimaryChild() {
        if (children == null || children.length == 0) {
            return null;
        }
        return children[0];
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
            if (this.type == NodeType.ACTION_HEAD && node.actionIndex < 0) {
                node.actionIndex = this.actionIndex;
            }
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
        copy.numValue = this.numValue;
        copy.parent = null;
        copy.head = this.head;
        copy.actionIndex = this.actionIndex;
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
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).type == NodeType.HEAD) {
                list.remove(i);
                i--;
            }
        }

        return list.get(rnd.nextInt(list.size()));
    }


    //make sure to check root/head replacements when using this function
    public Node replaceSubtree(Node target, Node replacement) {
        if (target == this) {
            if (replacement != null) {
                replacement.parent = this.parent;
                replacement.head = this.head;
                replacement.actionIndex = this.actionIndex;
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
                        replacement.actionIndex = target.actionIndex;
                    }
                    return this;
                } else if (children[i] != null) {
                    Node res = children[i].replaceSubtree(target, replacement);
                    if (res != children[i]) {
                        children[i] = res;
                        if (children[i] != null) {
                            children[i].parent = this;
                            children[i].head = false;
                            children[i].actionIndex = target.actionIndex;
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
        sb.append("(").append(getLabel());
        if (children != null) {
            for (Node c : children) {
                sb.append(" ");
                sb.append(c == null ? "null" : c.toString());
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private String getLabel() {
        switch (type) {
            case HEAD:
                return value == null ? "HEAD" : value;
            case ACTION_HEAD:
                if (value == null) {
                    return "ACTION_HEAD";
                }
                if (actionIndex >= 0) {
                    return value + "#" + actionIndex;
                }
                return value;
            case FUNCTION:
            case TERMINAL:
            default:
                return value;
        }
    }


    public Node getActionHead() {
        Node cur = this;
        while (cur.type != NodeType.ACTION_HEAD && cur.parent != null) {
            cur = cur.parent;
        }
        return cur.type == NodeType.ACTION_HEAD ? cur : null;
    }

    private void collectNodesFromActionSubtree(Node node, List<Node> out) {
        if (node == null) return;
        out.add(node);
        if (node.children != null) {
            for (Node c : node.children) {
                collectNodesFromActionSubtree(c, out);
            }
        }
    }


    public Node getRandomNodeFromActionSubtree(Node actionHead) {
        if (actionHead == null) {
            return null;
        }
        List<Node> list = new ArrayList<>();
        collectNodesFromActionSubtree(actionHead, list);
        if (list.isEmpty()) return null;

        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).type == NodeType.HEAD) {
                list.remove(i);
                i--;
            }
        }

        return list.get(random.nextInt(list.size()));
    }


    public Node generateRandomActionSubtree(Random random, int maxDepth, ACTION_TYPE actionType) {
        //TODO: Implement random action subtree generation
        return null;
    }








    //TODO: Full and Grow

    //TODO: Evaluate
    
}

