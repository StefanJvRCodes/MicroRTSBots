package ai.custom.adapter;

import ai.custom.GP_bot.Tree;
import ai.custom.GP_bot.TreePolicy;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Random;

/**
 * A trained adapter together with everything needed to play it: the fixed
 * base tree, how the base output vector becomes a distribution (mode and
 * temperature), the policy network that adjusts that distribution, and the
 * value network PPO trains alongside it. Saved as one file, so a model is
 * always loaded with the base it was trained on.
 */
public final class AdapterModel implements Serializable {
    private static final long serialVersionUID = 1L;

    public final Tree baseTree;
    public final TreePolicy.Mode baseMode;
    public final double temperature;
    public final int patchRadius;
    /** Game length the time feature is normalized by. */
    public final int maxCycles;
    /** Unit observation -> one logit adjustment per output cell. */
    public final Mlp policy;
    /** Value observation -> state value. */
    public final Mlp value;

    public AdapterModel(Tree baseTree, TreePolicy.Mode baseMode, double temperature, int patchRadius, int maxCycles,
                        int hiddenSize, Random random) {
        if (baseMode == TreePolicy.Mode.ARGMAX) {
            throw new IllegalArgumentException("The adapter needs a base distribution - use onehot or softmax, not argmax.");
        }
        this.baseTree = baseTree;
        this.baseMode = baseMode;
        this.temperature = temperature;
        this.patchRadius = patchRadius;
        this.maxCycles = maxCycles;
        this.policy = new Mlp(new int[] {AdapterObservation.unitSize(patchRadius), hiddenSize, hiddenSize,
                AdapterObservation.CELLS}, 0.01, random);
        this.value = new Mlp(new int[] {AdapterObservation.valueSize(), hiddenSize, hiddenSize, 1}, 1.0, random);
    }

    /** A base policy instance for one game (the tree's output vector is scratch space, so games can't share one). */
    public TreePolicy newBasePolicy(long seed) {
        return new TreePolicy(baseTree, baseMode, temperature, seed).copy();
    }

    public static AdapterModel load(File file) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (AdapterModel) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to load adapter model from " + file.getAbsolutePath(), e);
        }
    }
}
