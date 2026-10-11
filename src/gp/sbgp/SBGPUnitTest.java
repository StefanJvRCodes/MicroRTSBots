package gp.sbgp;

import ec.gp.GPNode;
import gp.nodes.Add;
import gp.nodes.FeatureNode;
import gp.nodes.Min;
import gp.nodes.Mul;
import gp.nodes.Sub;

import java.lang.reflect.Field;

/** Graft and top-match checks for SBGPBreeder.  java -cp "out;lib/*" gp.sbgp.SBGPUnitTest */
public class SBGPUnitTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // area source A = (Add (Sub d c) (Mul a a));  individual R = (Min (Add x y) e)
        GPNode a = fn(new Add(), fn(new Sub(), leaf("d"), leaf("c")), fn(new Mul(), leaf("a"), leaf("a")));
        GPNode r = fn(new Min(), fn(new Add(), leaf("x"), leaf("y")), leaf("e"));

        // Dg = 2: top two levels from A; level 2 from R where the path exists.
        // R has [0,0]=x and [0,1]=y; R[1] is a terminal, so [1,*] keep A's material.
        GPNode g2 = SBGPBreeder.graft(a, r, 2);
        GPNode e2 = fn(new Add(), fn(new Sub(), leaf("x"), leaf("y")), fn(new Mul(), leaf("a"), leaf("a")));
        same("graft Dg=2 -> (Add (Sub x y) (Mul a a))", e2, g2);

        // Dg = 1: only the root from A; both children from R.
        GPNode g1 = SBGPBreeder.graft(a, r, 1);
        GPNode e1 = fn(new Add(), fn(new Add(), leaf("x"), leaf("y")), leaf("e"));
        same("graft Dg=1 -> (Add (Add x y) e)", e1, g1);

        // The area must not be modified by grafting (deep copy).
        GPNode aCopy = fn(new Add(), fn(new Sub(), leaf("d"), leaf("c")), fn(new Mul(), leaf("a"), leaf("a")));
        same("area untouched after grafting", aCopy, a);

        // topMatches
        check("graft result carries the area top (Dg=2)", true, SBGPBreeder.topMatches(a, g2, 2));
        check("R does not carry the area top (Dg=2)", false, SBGPBreeder.topMatches(a, r, 2));
        check("shallow match: Dg=1 only needs the root symbol", true,
              SBGPBreeder.topMatches(a, fn(new Add(), leaf("q"), leaf("q")), 1));

        System.out.println();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void same(String name, GPNode expected, GPNode actual) {
        int n = count(expected);
        boolean ok = n == count(actual) && GlobalArea.similarity(expected, actual, 99, true, true) == n;
        if (!ok) failures++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
    }

    private static void check(String name, boolean expected, boolean actual) {
        boolean ok = expected == actual;
        if (!ok) failures++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + ": expected " + expected + ", got " + actual);
    }

    private static int count(GPNode n) {
        int c = 1;
        if (n.children != null) for (GPNode k : n.children) c += count(k);
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
}