package ai.custom.adapter;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import ai.custom.GP_bot.TreePolicy;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import rts.GameState;
import rts.PlayerAction;
import rts.UnitAction;
import rts.units.Unit;
import rts.units.UnitTypeTable;

/**
 * Adapter-RL bot: the frozen GP tree is the base agent and the adapter only
 * adjusts its output vector. Per idle unit, using the additive policy
 * adjuster (paper eq. 12):
 *
 * <pre>
 *   base logits   = TreePolicy's distribution over the 7 output cells (onehot: 1/T for the
 *                   tree's choice, 0 for other applicable cells; -inf for cells that don't apply)
 *   final logits  = base logits + adapter(unit observation)
 *   cell          ~ softmax(final logits)
 * </pre>
 *
 * and the chosen cell is turned into a concrete action exactly as the base
 * bot would (move toward enemy, harvest, produce worker, ...). The tree and
 * that mapping never change; only the adapter network learns.
 */
public class AdapterBot extends AIWithComputationBudget {
    /** Default model file for the tournament constructor; override with -Dadapter.model=path. */
    public static final String DEFAULT_MODEL_FILE = "adapter_runs/default/adapter_final.ser";

    /** One unit's choice, as PPO needs it. */
    public static final class UnitSample {
        public final double[] observation;
        public final double[] baseLogits;
        public final int action;
        public final double logProb;

        UnitSample(double[] observation, double[] baseLogits, int action, double logProb) {
            this.observation = observation;
            this.baseLogits = baseLogits;
            this.action = action;
            this.logProb = logProb;
        }
    }

    private final AdapterModel model;
    private final UnitTypeTable utt;
    private final TreePolicy base;
    private final Random random;
    /** False plays the base distribution alone (adapter logits treated as 0) - for baselines. */
    private final boolean useAdapter;
    /** True picks each unit's most likely cell instead of sampling. */
    private final boolean deterministic;
    /** When true, every adapter decision is kept for {@link #takeSamples()}. */
    private final boolean recording;

    private List<UnitSample> samples = new ArrayList<>();
    private AdapterObservation.Grid lastGrid;

    /** Tournament constructor: loads the model from {@value #DEFAULT_MODEL_FILE} or -Dadapter.model. */
    public AdapterBot(UnitTypeTable utt) {
        this(loadDefaultModel(), utt, true, false, false, System.nanoTime());
    }

    private static AdapterModel loadDefaultModel() {
        File file = new File(System.getProperty("adapter.model", DEFAULT_MODEL_FILE));
        if (!file.isFile()) {
            throw new IllegalStateException("No adapter model at " + file.getAbsolutePath() + ". A run writes "
                    + "adapter_final.ser only when its training finishes. In the GUI, put an adapter_final.ser in "
                    + "bots/ and pick it from the list (it is listed as \"(adapter)\"); elsewhere, pass "
                    + "-Dadapter.model=<path to adapter_final.ser>.");
        }
        return AdapterModel.load(file);
    }

    public AdapterBot(AdapterModel model, UnitTypeTable utt, boolean useAdapter, boolean deterministic,
                      boolean recording, long seed) {
        super(-1, -1);
        this.model = model;
        this.utt = utt;
        this.useAdapter = useAdapter;
        this.deterministic = deterministic;
        this.recording = recording;
        this.random = new Random(seed);
        this.base = model.newBasePolicy(random.nextLong());
    }

    @Override
    public void reset() {
        samples = new ArrayList<>();
        lastGrid = null;
    }

    @Override
    public AI clone() {
        return new AdapterBot(model, utt, useAdapter, deterministic, recording, random.nextLong());
    }

    /** The decisions recorded since the last call (empty when no unit was free to act), and clears them. */
    public List<UnitSample> takeSamples() {
        List<UnitSample> taken = samples;
        samples = new ArrayList<>();
        return taken;
    }

    /** The map as seen by the last {@link #getAction} call - the trainer builds the value observation from it. */
    public AdapterObservation.Grid lastGrid() {
        return lastGrid;
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        PlayerAction pa = new PlayerAction();
        int remainingResources = gs.getPlayer(player).getResources()
                - TreePolicy.reservedForInProgressProduce(gs, player);
        Set<Long> reservedPositions = TreePolicy.collectReservedPositions(gs);
        AdapterObservation.Grid grid = new AdapterObservation.Grid(gs, player);
        lastGrid = grid;

        for (Unit unit : gs.getUnits()) {
            if (unit.getPlayer() != player || gs.getUnitAction(unit) != null) {
                continue;
            }
            TreePolicy.UnitDecision decision = base.score(unit, gs, player, remainingResources, reservedPositions);
            UnitAction chosen;
            if (decision.logits == null) {
                // No output cell applies to this unit: the base's fallback, nothing to adapt.
                chosen = decision.action;
            } else {
                double[] observation = AdapterObservation.unit(unit, grid, decision, model.patchRadius, model.maxCycles);
                double[] logits = decision.logits.clone();
                if (useAdapter) {
                    double[] adjustment = model.policy.output(observation);
                    for (int cell = 0; cell < logits.length; cell++) {
                        logits[cell] += adjustment[cell];
                    }
                }
                double[] probabilities = TreePolicy.softmax(logits);
                int cell = deterministic ? argmax(probabilities) : sample(probabilities);
                chosen = decision.candidates[cell];
                if (recording) {
                    samples.add(new UnitSample(observation, decision.logits, cell, Math.log(probabilities[cell])));
                }
            }

            if (chosen != null) {
                pa.addUnitAction(unit, chosen);
                if (chosen.getType() == UnitAction.TYPE_PRODUCE && chosen.getUnitType() != null) {
                    remainingResources -= Math.max(0, chosen.getUnitType().cost);
                }
                if (chosen.getType() == UnitAction.TYPE_MOVE || chosen.getType() == UnitAction.TYPE_PRODUCE) {
                    reservedPositions.add(TreePolicy.destinationKey(unit, chosen));
                }
            }
        }
        pa.fillWithNones(gs, player, 10);
        return pa;
    }

    private static int argmax(double[] probabilities) {
        int best = 0;
        for (int i = 1; i < probabilities.length; i++) {
            if (probabilities[i] > probabilities[best]) {
                best = i;
            }
        }
        return best;
    }

    private int sample(double[] probabilities) {
        double r = random.nextDouble();
        double cumulative = 0.0;
        int last = -1;
        for (int i = 0; i < probabilities.length; i++) {
            if (probabilities[i] <= 0) {
                continue;
            }
            last = i;
            cumulative += probabilities[i];
            if (r < cumulative) {
                return i;
            }
        }
        return last;
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return new ArrayList<>();
    }

    @Override
    public String toString() {
        return "AdapterBot{" + model.baseMode + ", T=" + model.temperature + (useAdapter ? "" : ", base only") + "}";
    }
}
