package gp;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import ec.EvolutionState;
import ec.gp.ADFStack;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.gp.GPNodeParent;
import ec.gp.GPProblem;
import ec.gp.GPTree;
import eval.Panel;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.ResourceUsage;
import rts.UnitAction;
import rts.UnitActionAssignment;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shrinks a finished evolved tree WITHOUT changing how it plays.
 *
 * WHAT THIS IS NOT. This is not parsimony pressure. Parsimony pressure (ec.parsimony.*)
 * changes the SEARCH: it biases selection toward smaller trees and therefore produces
 * DIFFERENT bots, not smaller versions of the same bot. That is a legitimate thing to
 * turn on, but it is a confound for this project's comparisons -- if ordinary GP runs
 * with parsimony pressure and SBGP does not, any difference in program size between them
 * is an artefact of the configuration rather than of the method. Decide it once, apply it
 * uniformly, and do it when setting up the Phase 4 comparison.
 *
 * This is post-hoc pruning: a MEASUREMENT applied to an already-finished individual. The
 * bot is unchanged; what you learn is what it actually was. The first run of this tool
 * took the 2026-09-12 best-of-run from 105 nodes to 17 -- 84% of that tree was doing
 * nothing at all, and the remaining 17 nodes are readable in one line.
 *
 * ---------------------------------------------------------------------------------
 * WHY NAIVE SIMPLIFICATION IS WRONG HERE, WITH THE CONCRETE EXAMPLE.
 *
 * That tree subtracts max(movesTowardResource, myResources) twice. A simplifier reasoning
 * "myResources is the same for every candidate action of a unit, so it cancels under
 * argmax, so delete it" would be WRONG. When the stockpile is zero that term stops
 * dominating, the max falls through to movesTowardResource, and the bot starts actively
 * penalising moves toward resources. Deleting it changes behaviour in exactly the
 * situation that matters most -- and pruning against real games KEPT it, confirming the
 * term is live.
 *
 * The lesson generalises: under argmax a term is irrelevant only if it is constant across
 * a unit's candidate actions IN EVERY STATE REACHED, which is not something you can read
 * off the expression. So the rewrite rules below are limited to identities that hold for
 * all inputs, and everything else is decided by PLAYING, not by reasoning.
 * ---------------------------------------------------------------------------------
 *
 * HOW IT WORKS
 *
 * Phase 1, algebraic. Only rules true for every input:
 *   max(x, x) -&gt; x        min(x, x) -&gt; x
 *   if&gt;(a, a, then, else) -&gt; else      (nothing is strictly greater than itself)
 * Structural equality is compared on makeLispTree() form. No games needed, no risk.
 *
 * Phase 2, behavioural. Greedily try replacing each node with one of its children,
 * largest subtrees first. A candidate is accepted only if it makes the SAME CHOICE as the
 * original at EVERY decision point of a set of played games -- not a similar score, the
 * same selected UnitAction, including the resource-consistency fallback, since that is
 * what actually determines play. Nothing is stored: the reference tree drives the game
 * while the candidate is evaluated alongside it, and the first divergence rejects the
 * candidate and stops that game immediately.
 *
 * ---------------------------------------------------------------------------------
 * RESTORING A REJECTED CANDIDATE MUST PUT BACK THE ORIGINAL OBJECT, NOT A COPY.
 *
 * The first version restored by splicing in a CLONE of the rejected node. That looks
 * equivalent and is not. The scan iterates a node list built before any edit; restoring a
 * clone leaves the list holding the ORIGINAL object, now detached from the tree, along
 * with everything beneath it. The scan then went on "editing" orphaned subtrees: the live
 * tree never changed, so every candidate played identically, so every attempt was
 * accepted, so the loop never terminated. The symptom was a round counter in the thousands
 * with the node count barely moving -- rounds can never legitimately exceed the node
 * count, since every genuine acceptance removes at least one node.
 *
 * Restoring the original object keeps object identity intact and the node list valid.
 * assertProgress() is the belt-and-braces check: any "acceptance" that fails to shrink the
 * tree stops the run loudly rather than spinning.
 * ---------------------------------------------------------------------------------
 * VERIFYING AGAINST A STOCHASTIC OPPONENT NEEDS A DIFFERENT TEST.
 *
 * -verify originally played the original and the pruned tree against each panel bot and
 * compared the W/T/L tables. That is exact for a DETERMINISTIC opponent and meaningless
 * for a stochastic one: ai.RandomBiasedAI seeds its RNG from the clock, so the two runs
 * are different games and the tables can differ for reasons that have nothing to do with
 * pruning. The first run of this tool reported exactly that -- 17 rows identical and
 * RandomBiasedAI flagged as DIFFERS, which was the verifier's bug rather than a real
 * divergence.
 *
 * Stochastic opponents are therefore checked by DECISION EQUIVALENCE over sampled games:
 * the same test phase 2 uses, repeated a few times so different random draws are seen.
 * That is strictly stronger evidence than table equality anyway -- matching outcomes can
 * hide divergent play, matching decisions cannot.
 * ---------------------------------------------------------------------------------
 *
 * THE LIMIT OF THE GUARANTEE, WHICH BELONGS IN THE WRITE-UP. Equivalence is established
 * only over states actually visited. A pruned tree could in principle diverge on a map or
 * opponent never traced. Trace widely, verify afterwards, and if a row diverges treat it
 * as a finding rather than a broken tool.
 *
 * WHICH OPPONENTS TO TRACE AGAINST -- A METHODOLOGY DECISION, NOT A DEFAULT. Pruning is
 * guided by the games it traces, so tracing against the held-out panel would let held-out
 * opponents shape the artefact that is later evaluated on them. That is leakage, the same
 * kind the train/eval split exists to prevent. The default here is the TRAINING opponents
 * from the params file. Overriding with -opponents is supported because it is sometimes
 * the right call, but if the overridden set includes held-out bots, say so wherever the
 * pruned tree is reported.
 *
 * USAGE
 *   java -cp "out;lib/*;lib/bots/*" gp.Pruner results/best-&lt;stamp&gt;.ind -verify
 *   java -cp "out;lib/*;lib/bots/*" gp.Pruner results/best-&lt;stamp&gt;.ind \
 *        -params results/best-&lt;stamp&gt;.params -opponents PassiveAI,WorkerRush \
 *        -verify -verify-samples 10
 *
 * Writes &lt;input&gt;-pruned.ind, which every existing runner accepts as
 * "evolved:results/best-&lt;stamp&gt;-pruned.ind".
 */
public final class Pruner {

    private Pruner() {}

    /** Raised the moment a candidate tree picks a different action from the reference. */
    private static final class Divergence extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Divergence() { super(null, null, false, false); }   // no stack trace: it is control flow
    }

    // =================================================================================
    // Entry point
    // =================================================================================

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: gp.Pruner <path.ind> [-params <path.params>]");
            System.out.println("                  [-opponents A,B,C] [-maxcycles 3000]");
            System.out.println("                  [-out <path.ind>] [-verify] [-verify-samples N]");
            System.out.println();
            System.out.println("Default opponents are the TRAINING opponents from the params");
            System.out.println("file. Tracing against held-out bots would leak them.");
            return;
        }

        String indPath = args[0];
        String paramsPath = null;
        String outPath = null;
        String[] opponents = null;
        int maxCycles = -1;
        boolean verify = false;
        int verifySamples = 5;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-params":         paramsPath = args[++i]; break;
                case "-out":            outPath = args[++i]; break;
                case "-opponents":      opponents = args[++i].trim().split("\\s*,\\s*"); break;
                case "-maxcycles":      maxCycles = Integer.parseInt(args[++i]); break;
                case "-verify":         verify = true; break;
                case "-verify-samples": verifySamples = Integer.parseInt(args[++i]); break;
                default:
                    System.out.println("Unknown option: " + args[i]);
                    return;
            }
        }

        String spec = (paramsPath == null) ? indPath : indPath + "@" + paramsPath;
        IndividualLoader.Loaded loaded = IndividualLoader.load(spec);
        MicroRTSProblem problem = (MicroRTSProblem) loaded.problem;

        if (opponents == null) opponents = problem.opponents;
        if (maxCycles <= 0) maxCycles = problem.maxCycles;
        if (outPath == null) {
            outPath = indPath.endsWith(".ind")
                    ? indPath.substring(0, indPath.length() - 4) + "-pruned.ind"
                    : indPath + "-pruned";
        }

        System.out.println();
        System.out.println("=== Pruning " + loaded.indPath + " ===");
        System.out.println("trace opponents : " + String.join(", ", opponents)
                + "   maps: " + String.join(", ", problem.maps)
                + "   cycle cap: " + maxCycles);
        System.out.println("original        : " + describe(loaded.individual));
        System.out.println();

        GPIndividual working = (GPIndividual) loaded.individual.clone();

        int before = countNodes(working);
        int algebraic = simplifyAlgebraically(working);
        System.out.println("phase 1 (algebraic): removed " + algebraic + " nodes -> "
                + countNodes(working) + " nodes");

        int behavioural = pruneBehaviourally(loaded, working, opponents,
                                             problem.maps, maxCycles);
        int after = countNodes(working);
        System.out.println("phase 2 (behavioural): removed " + behavioural + " nodes -> "
                + after + " nodes");
        System.out.println();
        System.out.println("TOTAL: " + before + " -> " + after + " nodes ("
                + String.format("%.0f%%", 100.0 * (before - after) / Math.max(1, before))
                + " smaller), depth " + depth(working));
        System.out.println();
        System.out.println("Pruned scoring function");
        System.out.println("-----------------------");
        System.out.println(lisp(working));

        writeIndividual(loaded.state, working, outPath);
        System.out.println("Wrote " + Paths.get(outPath).toAbsolutePath());

        if (verify) {
            System.out.println();
            System.out.println("=== Verification against the full panel ===");
            verifyPanel(loaded, working, problem, maxCycles, verifySamples);
        }
    }

    // =================================================================================
    // Phase 1 -- algebraic
    // =================================================================================

    /**
     * Apply only identities that hold for every input. Returns the number of nodes removed.
     *
     * Class names rather than toString() are used to identify operators: toString() is a
     * display concern that could be changed without anyone thinking about this file,
     * whereas the class names are what the params file binds.
     */
    private static int simplifyAlgebraically(GPIndividual ind) {
        int removed = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (GPTree tree : ind.trees) {
                if (tree == null || tree.child == null) continue;
                for (GPNode node : preorder(tree.child)) {
                    GPNode replacement = algebraicRewrite(node);
                    if (replacement == null) continue;
                    int sizeBefore = node.numNodes(GPNode.NODESEARCH_ALL);
                    int sizeAfter = replacement.numNodes(GPNode.NODESEARCH_ALL);
                    splice(tree, node, (GPNode) replacement.clone());
                    removed += sizeBefore - sizeAfter;
                    changed = true;
                    break;
                }
                if (changed) break;
            }
        }
        return removed;
    }

    /** The rewritten subtree, or null if no rule applies. */
    private static GPNode algebraicRewrite(GPNode node) {
        String kind = node.getClass().getSimpleName();
        GPNode[] c = node.children;
        if (c == null) return null;

        if ((kind.equals("Max") || kind.equals("Min")) && c.length == 2
                && sameShape(c[0], c[1])) {
            return c[0];                       // max(x,x) = min(x,x) = x
        }
        if (kind.equals("IfGreater") && c.length == 4 && sameShape(c[0], c[1])) {
            return c[3];                       // x > x is never true
        }
        return null;
    }

    private static boolean sameShape(GPNode a, GPNode b) {
        return a.makeLispTree().equals(b.makeLispTree());
    }

    // =================================================================================
    // Phase 2 -- behavioural
    // =================================================================================

    /**
     * Repeatedly try to replace a node with one of its children, keeping any replacement
     * that plays identically. Largest subtrees are tried first, since accepting one of
     * those removes the most nodes and makes every later test cheaper.
     *
     * After any ACCEPTED change the scan restarts from a freshly built node list: the tree
     * has changed shape and the old list is stale. After a REJECTED change the original
     * node object is spliced back, so the list remains valid and the scan continues.
     */
    private static int pruneBehaviourally(IndividualLoader.Loaded loaded, GPIndividual working,
                                          String[] opponents, String[] maps, int maxCycles) {
        int removed = 0;
        boolean changed = true;
        int round = 0;
        int guard = countNodes(working) + 8;   // acceptances cannot exceed node count

        while (changed) {
            changed = false;
            round++;
            if (round > guard) {
                throw new IllegalStateException(
                        "Pruner exceeded " + guard + " rounds. Every accepted replacement must"
                      + " remove at least one node, so this is a bug in the tree surgery, not a"
                      + " hard problem. Check that rejected candidates restore the ORIGINAL node"
                      + " object rather than a clone.");
            }

            for (GPTree tree : working.trees) {
                if (tree == null || tree.child == null) continue;

                List<GPNode> nodes = preorder(tree.child);
                // Biggest first: the largest accepted replacement is the biggest win.
                nodes.sort((x, y) -> Integer.compare(
                        y.numNodes(GPNode.NODESEARCH_ALL), x.numNodes(GPNode.NODESEARCH_ALL)));

                for (GPNode node : nodes) {
                    if (node.children == null || node.children.length == 0) continue;

                    int sizeBefore = countNodes(working);

                    for (GPNode child : node.children) {
                        int delta = node.numNodes(GPNode.NODESEARCH_ALL)
                                  - child.numNodes(GPNode.NODESEARCH_ALL);
                        if (delta <= 0) continue;

                        // Remember exactly where this node hangs, so a rejection can put
                        // THIS OBJECT back rather than a copy of it.
                        GPNodeParent savedParent = node.parent;
                        byte savedPosition = node.argposition;

                        GPNode candidate = (GPNode) child.clone();
                        splice(tree, node, candidate);

                        if (playsIdentically(loaded, working, opponents, maps, maxCycles)) {
                            assertProgress(working, sizeBefore, delta);
                            removed += delta;
                            changed = true;
                            System.out.println("  round " + round + ": -" + delta
                                    + " nodes (" + countNodes(working) + " left)");
                            break;
                        }

                        restore(tree, node, savedParent, savedPosition);
                    }
                    if (changed) break;
                }
                if (changed) break;
            }
        }
        return removed;
    }

    /**
     * An acceptance that did not shrink the tree means the edit landed somewhere that is
     * not in the tree -- the detached-subtree bug. Fail loudly instead of looping.
     */
    private static void assertProgress(GPIndividual working, int sizeBefore, int delta) {
        int now = countNodes(working);
        if (now != sizeBefore - delta) {
            throw new IllegalStateException(
                    "Tree surgery did not take effect: expected " + (sizeBefore - delta)
                  + " nodes after removing " + delta + ", found " + now
                  + ". The edited node was probably not part of the live tree.");
        }
    }

    /** Put the original node object back where it came from. */
    private static void restore(GPTree tree, GPNode original,
                                GPNodeParent parent, byte position) {
        original.parent = parent;
        original.argposition = position;
        if (parent instanceof GPNode) {
            ((GPNode) parent).children[position] = original;
        } else {
            tree.child = original;
            original.parent = tree;
            original.argposition = 0;
        }
    }

    /** Decision equivalence across every (map x opponent x side) combination. */
    private static boolean playsIdentically(IndividualLoader.Loaded loaded, GPIndividual candidate,
                                            String[] opponents, String[] maps, int maxCycles) {
        for (String map : maps) {
            for (String opponent : opponents) {
                for (int side = 0; side < 2; side++) {
                    if (!traceOne(loaded, candidate, map, opponent, side, maxCycles)) return false;
                }
            }
        }
        return true;
    }

    /**
     * One trace game. The reference tree drives, so the trajectory is always the one the
     * ORIGINAL bot produces -- comparing on a trajectory the candidate steered would be
     * comparing two different games. Returns false on the first divergence.
     */
    private static boolean traceOne(IndividualLoader.Loaded loaded, GPIndividual candidate,
                                    String map, String opponent, int side, int maxCycles) {
        UnitTypeTable utt = new UnitTypeTable();
        TraceBot bot = new TraceBot(loaded, candidate);
        try {
            AI theirs = Panel.make(opponent, utt);
            Panel.play(bot, theirs, map, utt, side, maxCycles);
            return true;
        } catch (Divergence d) {
            return false;
        } catch (Exception e) {
            // A trace game that cannot be played proves nothing, so refuse the candidate
            // rather than accept it on absent evidence.
            System.out.println("  (trace game failed against " + opponent
                    + ": " + e + " -- candidate rejected)");
            return false;
        }
    }

    /**
     * Plays using the REFERENCE tree while scoring every decision with the CANDIDATE too,
     * and raises Divergence the moment the two would pick different actions.
     *
     * NOTE: the selection logic below is deliberately identical to bots.EvolvedBot --
     * best-first walk, resource-consistency check, NONE fallback -- because comparing raw
     * argmax would miss divergences that only appear once an unaffordable top choice is
     * skipped. If EvolvedBot's selection ever changes, this must change with it.
     */
    private static final class TraceBot extends AIWithComputationBudget {

        private final EvolutionState state;
        private final GPProblem problem;
        private final GPIndividual refInd;
        private final GPIndividual candInd;
        private final GPNode refTree;
        private final GPNode candTree;
        private final ScoreData refData;
        private final ScoreData candData;
        private final ADFStack refStack;
        private final ADFStack candStack;

        TraceBot(IndividualLoader.Loaded loaded, GPIndividual candidate) {
            super(100, -1);
            this.state = loaded.state;
            this.problem = loaded.problem;
            this.refInd = loaded.individual;
            this.candInd = candidate;
            this.refTree = loaded.individual.trees[0].child;
            this.candTree = candidate.trees[0].child;
            this.refData = (ScoreData) loaded.problem.input.clone();
            this.candData = (ScoreData) loaded.problem.input.clone();
            this.refStack = (ADFStack) loaded.problem.stack.clone();
            this.candStack = (ADFStack) loaded.problem.stack.clone();
        }

        private double score(GPNode tree, GPIndividual ind, ScoreData data, ADFStack stack,
                             Unit u, UnitAction a, GameState gs, int player) {
            data.set(u, a, gs, player);
            data.score = 0.0;
            tree.eval(state, 0, data, stack, ind, problem);
            double s = data.score;
            return Double.isNaN(s) ? Double.NEGATIVE_INFINITY : s;
        }

        @Override
        public PlayerAction getAction(int player, GameState gs) throws Exception {
            PhysicalGameState pgs = gs.getPhysicalGameState();
            PlayerAction pa = new PlayerAction();
            if (!gs.canExecuteAnyAction(player)) return pa;

            for (Unit u : pgs.getUnits()) {
                UnitActionAssignment uaa = gs.getActionAssignment(u);
                if (uaa != null) pa.getResourceUsage().merge(uaa.action.resourceUsage(u, pgs));
            }

            for (Unit u : pgs.getUnits()) {
                if (u.getPlayer() != player || gs.getActionAssignment(u) != null) continue;

                List<UnitAction> legal = u.getUnitActions(gs);
                if (legal.isEmpty()) continue;

                double[] refScores = new double[legal.size()];
                double[] candScores = new double[legal.size()];
                for (int i = 0; i < legal.size(); i++) {
                    UnitAction a = legal.get(i);
                    refScores[i] = score(refTree, refInd, refData, refStack, u, a, gs, player);
                    candScores[i] = score(candTree, candInd, candData, candStack, u, a, gs, player);
                }

                int refPick = select(legal, refScores, u, pgs, gs, pa);
                int candPick = select(legal, candScores, u, pgs, gs, pa);
                if (refPick != candPick) throw new Divergence();

                if (refPick >= 0) {
                    UnitAction ua = legal.get(refPick);
                    pa.getResourceUsage().merge(ua.resourceUsage(u, pgs));
                    pa.addUnitAction(u, ua);
                } else {
                    pa.addUnitAction(u, new UnitAction(UnitAction.TYPE_NONE));
                }
            }
            return pa;
        }

        /** EvolvedBot's chooser: best-first, first resource-consistent wins. -1 = NONE. */
        private static int select(List<UnitAction> legal, double[] scores, Unit u,
                                  PhysicalGameState pgs, GameState gs, PlayerAction pa) {
            boolean[] used = new boolean[legal.size()];
            for (int attempt = 0; attempt < legal.size(); attempt++) {
                int best = -1;
                for (int i = 0; i < legal.size(); i++) {
                    if (!used[i] && (best == -1 || scores[i] > scores[best])) best = i;
                }
                if (best == -1) break;
                used[best] = true;
                ResourceUsage ru = legal.get(best).resourceUsage(u, pgs);
                if (ru.consistentWith(pa.getResourceUsage(), gs)) return best;
            }
            return -1;
        }

        @Override public void reset() { }
        @Override public AI clone() { return this; }
        @Override public List<ParameterSpecification> getParameters() { return new ArrayList<>(); }
    }

    // =================================================================================
    // Verification
    // =================================================================================

    /**
     * Confirm the pruned tree is equivalent across the whole panel, choosing the right
     * test per opponent.
     *
     * DETERMINISTIC opponents: play both trees and compare W/T/L. Exact, and only two
     * distinct games exist per map, so there is nothing to gain by playing more.
     *
     * STOCHASTIC opponents: table comparison is INVALID -- ai.RandomBiasedAI seeds from
     * the clock, so the two runs are different games and the tables can differ for reasons
     * unrelated to pruning. Use decision equivalence over sampled games instead, which is
     * the stronger property anyway: matching outcomes can hide divergent play, matching
     * decisions cannot.
     */
    private static void verifyPanel(IndividualLoader.Loaded loaded, GPIndividual pruned,
                                    MicroRTSProblem problem, int maxCycles, int samples) {
        GPIndividual original = loaded.individual;
        String[] maps = problem.maps;
        boolean allMatch = true;

        System.out.println("Deterministic opponents: outcome tables must match exactly.");
        System.out.println("Stochastic opponents: decisions compared over " + samples
                + " sampled games per side (tables would be meaningless).");
        System.out.println();

        for (String opponent : Panel.FULL_PANEL) {
            if (isStochastic(problem, opponent)) {
                boolean equivalent = true;
                int played = 0;
                for (String map : maps) {
                    for (int side = 0; side < 2 && equivalent; side++) {
                        for (int s = 0; s < samples && equivalent; s++) {
                            equivalent = traceOne(loaded, pruned, map, opponent, side, maxCycles);
                            played++;
                        }
                    }
                }
                allMatch &= equivalent;
                System.out.printf("vs %-24s %s over %d sampled games%s%n", opponent,
                        equivalent ? "decisions identical" : "DECISIONS DIVERGE", played,
                        equivalent ? "" : "   <-- REAL DIVERGENCE");
            } else {
                String a = record(loaded, original, opponent, maps, maxCycles);
                String b = record(loaded, pruned, opponent, maps, maxCycles);
                boolean same = a.equals(b);
                allMatch &= same;
                System.out.printf("vs %-24s original %-12s pruned %-12s%s%n",
                        opponent, a, b, same ? "" : "   <-- DIFFERS");
            }
        }

        System.out.println();
        System.out.println(allMatch
                ? "Equivalent across the whole panel. The pruned tree is the same bot."
                : "DIVERGENCE FOUND. The pruned tree differs on states the trace games never "
                + "visited. Re-run pruning with a wider -opponents set, and until then report "
                + "the original tree.");
    }

    private static boolean isStochastic(MicroRTSProblem problem, String opponent) {
        return problem.stochastic != null
            && problem.stochastic.contains(opponent.trim().toLowerCase(Locale.ROOT));
    }

    private static String record(IndividualLoader.Loaded loaded, GPIndividual ind,
                                 String opponent, String[] maps, int maxCycles) {
        int w = 0, t = 0, l = 0;
        for (String map : maps) {
            for (int side = 0; side < 2; side++) {
                UnitTypeTable utt = new UnitTypeTable();
                try {
                    bots.EvolvedBot ours = new bots.EvolvedBot(
                            utt, ind, loaded.state, 0, loaded.problem,
                            (ScoreData) loaded.problem.input.clone(),
                            (ADFStack) loaded.problem.stack.clone());
                    AI theirs = Panel.make(opponent, utt);
                    Panel.Result r = Panel.play(ours, theirs, map, utt, side, maxCycles);
                    if (r.won()) w++; else if (r.drew()) t++; else l++;
                } catch (Exception e) {
                    return "ERROR";
                }
            }
        }
        return w + "W-" + t + "T-" + l + "L";
    }

    // =================================================================================
    // Tree plumbing
    // =================================================================================

    /** Every node of the subtree, parents before children. */
    private static List<GPNode> preorder(GPNode root) {
        List<GPNode> out = new ArrayList<>();
        gather(root, out);
        return out;
    }

    private static void gather(GPNode n, List<GPNode> out) {
        out.add(n);
        if (n.children != null) for (GPNode c : n.children) gather(c, out);
    }

    /**
     * Swap `target` for `replacement` wherever it hangs, fixing up the parent link and
     * argument position. ECJ nodes know their parent, which is either another GPNode or
     * the GPTree itself when the node is the root.
     */
    private static void splice(GPTree tree, GPNode target, GPNode replacement) {
        GPNodeParent parent = target.parent;
        replacement.parent = parent;
        replacement.argposition = target.argposition;

        if (parent instanceof GPNode) {
            ((GPNode) parent).children[target.argposition] = replacement;
        } else {
            tree.child = replacement;
            replacement.parent = tree;
            replacement.argposition = 0;
        }
    }

    private static int countNodes(GPIndividual ind) {
        int n = 0;
        for (GPTree t : ind.trees) {
            if (t != null && t.child != null) n += t.child.numNodes(GPNode.NODESEARCH_ALL);
        }
        return n;
    }

    private static int depth(GPIndividual ind) {
        int d = 0;
        for (GPTree t : ind.trees) {
            if (t != null && t.child != null) d = Math.max(d, t.child.depth());
        }
        return d;
    }

    private static String lisp(GPIndividual ind) {
        StringBuilder sb = new StringBuilder();
        for (GPTree t : ind.trees) {
            sb.append(t == null || t.child == null ? "(empty)" : t.child.makeLispTree());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String describe(GPIndividual ind) {
        return countNodes(ind) + " nodes, depth " + depth(ind)
             + ", fitness " + ind.fitness.fitnessToStringForHumans();
    }

    /**
     * Write in ECJ's reloadable format so the pruned tree is a first-class individual --
     * loadable by gp.IndividualLoader, playable as "evolved:&lt;path&gt;" anywhere.
     *
     * The fitness line is carried over from the original and is now STALE: it was measured
     * for the unpruned tree. That is harmless for play (nothing reads it) but it must not
     * be quoted as the pruned tree's fitness. Re-evaluate if you need a number.
     */
    private static void writeIndividual(EvolutionState state, GPIndividual ind, String path)
            throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ind.printIndividual(state, pw);
        pw.flush();

        Path p = Paths.get(path);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.write(p, sw.toString().getBytes(StandardCharsets.UTF_8));
    }
}
