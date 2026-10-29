package ai.custom.GP_bot;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import rts.GameState;
import rts.PlayerAction;
import rts.units.UnitTypeTable;

/**
 * The GP-evolved bot, usable both during evolution and as an exported
 * competition entry.
 *
 * <p>During evolution, GP constructs it directly with the individual's
 * {@link Tree}. For competition/tournament use it is instantiated with just
 * a {@link UnitTypeTable} and loads the evolved tree from the
 * {@value #TREE_RESOURCE_NAME} resource bundled inside the exported jar.
 * Either way every decision goes through {@link TreePolicy}, so the exported
 * bot behaves exactly like the individual did during fitness evaluation
 * (given the same action selection mode).
 */
public class GPEvolvedBot extends AIWithComputationBudget {
    private static final long serialVersionUID = 1L;

    /** Name of the serialized-tree resource bundled in the exported jar. */
    public static final String TREE_RESOURCE_NAME = "bestTree.ser";

    private final TreePolicy policy;
    private final UnitTypeTable utt;

    /** Competition/tournament constructor: the tree is loaded from the jar and played deterministically. */
    public GPEvolvedBot(UnitTypeTable utt) {
        super(-1, -1);
        this.utt = utt;
        Tree tree = loadTreeFromClasspath();
        if (tree == null || tree.root == null) {
            throw new IllegalStateException(TREE_RESOURCE_NAME
                    + " was not found on the classpath or failed to deserialize - "
                    + "the exported jar must bundle it at the jar root.");
        }
        this.policy = new TreePolicy(tree, TreePolicy.Mode.ARGMAX, 1.0, System.nanoTime());
    }

    /** Evolution constructor: deterministic (argmax) play of a tree from the population. */
    public GPEvolvedBot(Tree tree, UnitTypeTable utt) {
        this(tree, utt, TreePolicy.Mode.ARGMAX, 1.0, System.nanoTime());
    }

    public GPEvolvedBot(Tree tree, UnitTypeTable utt, TreePolicy.Mode mode, double temperature, long seed) {
        this(new TreePolicy(tree, mode, temperature, seed), utt);
    }

    private GPEvolvedBot(TreePolicy policy, UnitTypeTable utt) {
        super(-1, -1);
        this.policy = policy;
        this.utt = utt;
    }

    private static Tree loadTreeFromClasspath() {
        try (InputStream in = GPEvolvedBot.class.getResourceAsStream("/" + TREE_RESOURCE_NAME)) {
            if (in == null) {
                return null;
            }
            try (ObjectInputStream ois = new ObjectInputStream(in)) {
                return (Tree) ois.readObject();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public TreePolicy policy() {
        return policy;
    }

    @Override
    public void reset() {
        // Playing games does not mutate the tree; nothing to reset.
    }

    @Override
    public AI clone() {
        return new GPEvolvedBot(policy.copy(), utt);
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        return policy.getAction(player, gs);
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return new ArrayList<>();
    }

    @Override
    public String toString() {
        return "GPEvolvedBot{" + policy.mode() + (policy.mode() == TreePolicy.Mode.ARGMAX ? "" : ", T=" + policy.temperature())
                + ", " + policy.tree() + "}";
    }
}
