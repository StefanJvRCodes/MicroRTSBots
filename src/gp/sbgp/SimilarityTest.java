package gp.sbgp;

import ec.gp.GPNode;
import gp.nodes.Add;
import gp.nodes.FeatureNode;
import gp.nodes.Min;
import gp.nodes.Mul;
import gp.nodes.ScoreERC;
import gp.nodes.Sub;

import java.lang.reflect.Field;

/** Checks GlobalArea's similarity index against the paper.  java -cp "out;lib/*" gp.sbgp.SimilarityTest */
public class SimilarityTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // 1. FIGURE 3, Dg = 3 -- paper's answer is 3
        GPNode tree = fn(new Add(),
                fn(new Add(), fn(new Add(), leaf("d"), leaf("c")), fn(new Sub(), leaf("d"), leaf("a"))),
                fn(new Mul(), fn(new Add(), leaf("a"), leaf("a")), fn(new Add(), leaf("c"), leaf("d"))));
        GPNode area = fn(new Add(),
                fn(new Sub(), fn(new Add(), leaf("d"), leaf("c")), fn(new Mul(), leaf("d"), leaf("a"))),
                fn(new Mul(), fn(new Add(), leaf("a"), leaf("a")), fn(new Sub(), leaf("c"), leaf("d"))));
        check("Figure 3 (paper's answer)", 3, GlobalArea.similarity(tree, area, 3, true, false));
        check("Figure 3, naive positional count would give", 4, naive(tree, area, 0, 3));

        // 2. identical tree: Dg counts levels
        check("identical, Dg=3 -> 1+2+4", 7, GlobalArea.similarity(tree, tree, 3, true, false));
        check("identical, Dg=2 -> 1+2",   3, GlobalArea.similarity(tree, tree, 2, true, false));
        check("identical, Dg=1 -> root",  1, GlobalArea.similarity(tree, tree, 1, true, false));

        // 3. countTerminals switch on a real stage-7 shape
        GPNode w = fn(new Min(), leaf("movesTowardEnemy"), leaf("movesTowardEnemyMajor"));
        check("(min mTE mTEM) self, terminals counted", 3, GlobalArea.similarity(w, w, 4, true, false));
        check("(min mTE mTEM) self, function nodes only", 1, GlobalArea.similarity(w, w, 4, false, false));
        GPNode w2 = fn(new Min(), leaf("movesTowardEnemy"), leaf("movesTowardEnemyBase"));
        check("one terminal differs", 2, GlobalArea.similarity(w, w2, 4, true, false));

        // 4. terminal identity: all terminals share the FeatureNode class
        GPNode x1 = fn(new Min(), leaf("aIsMove"), leaf("gameTimeK"));
        GPNode x2 = fn(new Min(), leaf("uIsWorker"), leaf("gameTimeK"));
        check("aIsMove vs uIsWorker must NOT match", 2, GlobalArea.similarity(x1, x2, 3, true, false));

        // 5. ERC switch
        GPNode c1 = fn(new Min(), erc(1.0), leaf("gameTimeK"));
        GPNode c2 = fn(new Min(), erc(2.0), leaf("gameTimeK"));
        check("ERCs compared by structure", 3, GlobalArea.similarity(c1, c2, 3, true, false));
        check("ERCs compared by value",     2, GlobalArea.similarity(c1, c2, 3, true, true));

        // 6. different roots share nothing
        check("different roots -> 0", 0, GlobalArea.similarity(w, tree, 4, true, false));

        System.out.println();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, int expected, int actual) {
        boolean ok = expected == actual;
        if (!ok) failures++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + ": expected " + expected + ", got " + actual);
    }

    private static int naive(GPNode a, GPNode b, int level, int dg) {
        if (a == null || b == null || level >= dg) return 0;
        int c = GlobalArea.symbol(a, false).equals(GlobalArea.symbol(b, false)) ? 1 : 0;
        int kids = Math.min(a.children == null ? 0 : a.children.length,
                            b.children == null ? 0 : b.children.length);
        for (int i = 0; i < kids; i++) c += naive(a.children[i], b.children[i], level + 1, dg);
        return c;
    }

    private static GPNode fn(GPNode n, GPNode... kids) {
        n.children = kids;
        for (int i = 0; i < kids.length; i++) { kids[i].parent = n; kids[i].argposition = (byte) i; }
        return n;
    }

    private static GPNode leaf(String feature) throws Exception {
        FeatureNode f = new FeatureNode();
        Field name = FeatureNode.class.getDeclaredField("featureName");
        name.setAccessible(true);
        name.set(f, feature);
        f.children = new GPNode[0];
        return f;
    }

    private static GPNode erc(double v) {
        ScoreERC e = new ScoreERC();
        e.value = v;
        e.children = new GPNode[0];
        return e;
    }
}