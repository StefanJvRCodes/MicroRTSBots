package ai.custom.GP_bot;

/**
 * Hand-built trees used to seed the initial population, so evolution starts
 * from a bot that can actually fight instead of from purely random trees.
 *
 * <p>TreePolicy (argmax mode) tries the output cells a Modi node wrote, highest value
 * first, skipping any whose action isn't legal for the unit right now. So a
 * tree that writes fixed constants in a chosen order acts as a fixed
 * priority list. Output cells (see TreePolicy):
 * 0 move toward enemy, 1 attack, 2 move toward resource, 3 harvest,
 * 4 return, 5 produce worker, 6 produce combat unit / building.
 */
public final class HandcraftedTrees {

    private HandcraftedTrees() {
    }

    /**
     * Approximates {@code ai.custom.StefanFirstBot}: attack, else return,
     * else harvest, else produce, else move toward the closest enemy.
     * Cell 2 (move toward resource) is left unwritten, since StefanFirstBot
     * only harvests when it is already next to a resource.
     *
     * <p>Shape (depth 6, within the default max depth):
     * <pre>
     * (* *cell1 7.0
     *    (* (* m(4, 6.0) m(3, 5.0))
     *       (* m(5, 4.0) (* m(6, 3.0) m(0, 1.0)))))
     * </pre>
     * where {@code m(cell, v)} is a Modi node {@code (* v 1.0)} that writes v
     * into the cell and returns 1.0. Every non-Modi {@code *} therefore
     * evaluates to 1.0, so the root writes exactly 7.0 into cell 1.
     */
    public static Tree stefanFirstBot(int numOutputCells) {
        Node chain = function("*",
                function("*", modi(4, 6.0), modi(3, 5.0)),
                function("*", modi(5, 4.0), function("*", modi(6, 3.0), modi(0, 1.0))));
        Node root = function("*", constant(7.0), chain);
        root.isModi = true;
        root.outputCellIndex = 1;
        return new Tree(root, numOutputCells);
    }

    /** Modi node that writes {@code value} into {@code cell} and returns 1.0 to its parent. */
    private static Node modi(int cell, double value) {
        Node node = function("*", constant(value), constant(1.0));
        node.isModi = true;
        node.outputCellIndex = cell;
        return node;
    }

    private static Node function(String symbol, Node... children) {
        Node node = new Node(Node.NodeType.FUNCTION, symbol, null);
        node.children = new Node[children.length];
        node.outputCellIndex = -1;
        node.featureIndex = -1;
        for (int i = 0; i < children.length; i++) {
            node.setChild(i, children[i]);
        }
        return node;
    }

    private static Node constant(double value) {
        Node node = new Node(Node.NodeType.TERMINAL, String.valueOf(value), null);
        node.numValue = value;
        node.isFeatureTerminal = false;
        node.featureIndex = -1;
        node.outputCellIndex = -1;
        return node;
    }
}
