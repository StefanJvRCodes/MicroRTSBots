package ai.custom.GP_bot;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import rts.GameState;
import rts.PlayerAction;
import rts.UnitAction;
import rts.units.Unit;
import rts.units.UnitType;

/**
 * Turns a GP tree into one action per idle unit. This is the single copy of
 * the bot's decision logic, used both while evolving (fitness games) and by
 * the exported/competition bot, so the two always behave the same.
 *
 * <p>Per unit: the tree is evaluated on the unit's features, each of the
 * {@value #NUM_OUTPUT_CELLS} output cells is mapped to a concrete action
 * (or null when that cell's behavior doesn't apply to this unit right now),
 * and the cells are turned into base logits - -Infinity for inapplicable
 * cells - according to {@link Mode}. The chosen cell is the logits' argmax
 * or a sample from their softmax. {@link UnitDecision} exposes the
 * candidates, logits and probabilities so an adapter can later add its own
 * logits to the base ones (Adapter-RL's additive policy adjuster).
 *
 * <p>Output cells:
 * 0 move toward closest enemy, 1 attack closest enemy, 2 move toward closest
 * resource, 3 harvest, 4 return, 5 produce worker, 6 produce combat unit.
 */
public final class TreePolicy implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int NUM_OUTPUT_CELLS = 7;

    public enum Mode {
        /** Deterministic: the highest written, applicable cell (the original behavior). */
        ARGMAX,
        /**
         * Sample from softmax(output / temperature) over the written,
         * applicable cells. Cells the tree didn't write can't be chosen.
         * Tree outputs have no fixed scale, so the same temperature can be
         * sharp for one tree and flat for another.
         */
        SOFTMAX,
        /**
         * Adapter-RL eq. (1): the ARGMAX cell gets logit 1/temperature,
         * every other applicable cell (written or not) gets 0, then sample
         * from the softmax. Independent of the tree's output scale; small
         * temperatures (the paper uses 1/100) are close to ARGMAX.
         */
        ONEHOT;

        public static Mode parse(String value) {
            switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "argmax":
                    return ARGMAX;
                case "softmax":
                    return SOFTMAX;
                case "onehot":
                case "one-hot":
                    return ONEHOT;
                default:
                    throw new IllegalArgumentException(
                            "Unknown action selection \"" + value + "\" - expected argmax, softmax, or onehot.");
            }
        }
    }

    /** One unit's decision, kept whole so a learner can recompute probabilities for the same choice. */
    public static final class UnitDecision {
        /** Action each output cell maps to for this unit, or null where the cell doesn't apply. */
        public final UnitAction[] candidates;
        /** Base logits per cell; -Infinity = cannot be chosen. Null when the fallback was used. */
        public final double[] logits;
        /** Probability of each cell under the selection mode. Null when the fallback was used. */
        public final double[] probabilities;
        /** The cell that was chosen, or -1 when no cell applied and the fallback picked the action. */
        public final int chosenCell;
        /** The action to issue, or null for none. */
        public final UnitAction action;

        UnitDecision(UnitAction[] candidates, double[] logits, double[] probabilities, int chosenCell,
                     UnitAction action) {
            this.candidates = candidates;
            this.logits = logits;
            this.probabilities = probabilities;
            this.chosenCell = chosenCell;
            this.action = action;
        }
    }

    private final Tree tree;
    private final Mode mode;
    private final double temperature;
    private final Random random;

    public TreePolicy(Tree tree, Mode mode, double temperature, long seed) {
        if (mode != Mode.ARGMAX && !(temperature > 0)) {
            throw new IllegalArgumentException("temperature must be > 0 for " + mode + " action selection.");
        }
        this.tree = tree;
        this.mode = mode;
        this.temperature = temperature;
        this.random = new Random(seed);
        if (tree != null && (tree.outputVector == null || tree.outputVector.length != NUM_OUTPUT_CELLS)) {
            tree.outputVector = new double[NUM_OUTPUT_CELLS];
        }
    }

    /**
     * Independent copy for another game: its own tree (evaluate() writes into
     * the tree's output vector, so games running in parallel must not share
     * one) and its own RNG stream.
     */
    public TreePolicy copy() {
        Tree copy = (tree == null || tree.root == null) ? null : new Tree(tree.root.deepCopy(), NUM_OUTPUT_CELLS);
        return new TreePolicy(copy, mode, temperature, random.nextLong());
    }

    public Tree tree() {
        return tree;
    }

    public Mode mode() {
        return mode;
    }

    public double temperature() {
        return temperature;
    }

    public PlayerAction getAction(int player, GameState gs) {
        PlayerAction pa = new PlayerAction();
        int remainingResources = gs.getPlayer(player).getResources() - reservedForInProgressProduce(gs, player);
        Set<Long> reservedPositions = collectReservedPositions(gs);

        int idleCount = 0;
        int chosenCount = 0;
        for (Unit unit : gs.getUnits()) {
            if (unit.getPlayer() != player) {
                continue;
            }
            if (gs.getUnitAction(unit) != null) {
                // Mid-action (e.g. walking, or a Base producing a unit): the
                // engine expects no new order until it finishes.
                continue;
            }
            idleCount++;
            UnitAction chosen = decide(unit, gs, player, remainingResources, reservedPositions).action;
            if (chosen != null) {
                chosenCount++;
                pa.addUnitAction(unit, chosen);
                if (chosen.getType() == UnitAction.TYPE_PRODUCE && chosen.getUnitType() != null) {
                    remainingResources -= Math.max(0, chosen.getUnitType().cost);
                }
                if (chosen.getType() == UnitAction.TYPE_MOVE || chosen.getType() == UnitAction.TYPE_PRODUCE) {
                    reservedPositions.add(destinationKey(unit, chosen));
                }
            }
        }
        if (idleCount > 0 && chosenCount == 0 && tree != null && tree.root != null) {
            System.out.println("TreePolicy WARNING: " + idleCount + " idle unit(s) but chose 0 actions. outputVector: "
                    + java.util.Arrays.toString(tree.outputVector));
        }
        pa.fillWithNones(gs, player, 10);
        return pa;
    }

    /** Scores the unit's cells and picks one: the argmax cell in ARGMAX mode, a softmax sample otherwise. */
    public UnitDecision decide(Unit unit, GameState gs, int player, int remainingResources,
                               Set<Long> reservedPositions) {
        UnitDecision scored = score(unit, gs, player, remainingResources, reservedPositions);
        if (mode == Mode.ARGMAX || scored.logits == null) {
            return scored;
        }
        int chosen = sample(scored.probabilities);
        return new UnitDecision(scored.candidates, scored.logits, scored.probabilities, chosen, scored.candidates[chosen]);
    }

    /**
     * The base policy's distribution for this unit without sampling from it:
     * candidates, logits and probabilities, with {@code chosenCell} the
     * argmax cell. An adapter adds its own logits to these and samples itself.
     */
    public UnitDecision score(Unit unit, GameState gs, int player, int remainingResources,
                              Set<Long> reservedPositions) {
        List<UnitAction> legalActions = unit.getUnitActions(gs);
        UnitAction[] candidates = new UnitAction[NUM_OUTPUT_CELLS];
        if (legalActions.isEmpty() || tree == null || tree.root == null) {
            return fallback(candidates, unit, legalActions, remainingResources, reservedPositions);
        }

        tree.evaluate(extractFeatures(unit, gs, player));
        Unit closestEnemy = findClosestEnemy(unit, gs);
        Unit closestResource = findClosestResource(unit, gs);
        for (int cell = 0; cell < NUM_OUTPUT_CELLS; cell++) {
            candidates[cell] = mapOutputToAction(cell, unit, closestEnemy, closestResource, legalActions,
                    remainingResources, reservedPositions);
        }

        double[] logits = baseLogits(candidates);
        int best = argmax(logits);
        if (best < 0) {
            return fallback(candidates, unit, legalActions, remainingResources, reservedPositions);
        }

        double[] probabilities;
        if (mode == Mode.ARGMAX) {
            probabilities = new double[NUM_OUTPUT_CELLS];
            probabilities[best] = 1.0;
        } else {
            probabilities = softmax(logits);
        }
        return new UnitDecision(candidates, logits, probabilities, best, candidates[best]);
    }

    private UnitDecision fallback(UnitAction[] candidates, Unit unit, List<UnitAction> legalActions,
                                  int remainingResources, Set<Long> reservedPositions) {
        return new UnitDecision(candidates, null, null, -1,
                chooseSafeFallbackAction(unit, legalActions, remainingResources, reservedPositions));
    }

    /** Logits per cell for the current mode; -Infinity marks cells that cannot be chosen. */
    private double[] baseLogits(UnitAction[] candidates) {
        double[] logits = new double[NUM_OUTPUT_CELLS];
        double[] outputs = tree.outputVector;
        for (int cell = 0; cell < NUM_OUTPUT_CELLS; cell++) {
            boolean usable = candidates[cell] != null && tree.isCellWritten(cell);
            if (!usable) {
                logits[cell] = Double.NEGATIVE_INFINITY;
            } else if (mode == Mode.SOFTMAX) {
                logits[cell] = outputs[cell] / temperature;
            } else {
                logits[cell] = outputs[cell];
            }
        }
        if (mode != Mode.ONEHOT) {
            return logits;
        }

        // ONEHOT: what ARGMAX would pick gets 1/temperature, every other
        // applicable cell 0 - including cells the tree didn't write, so the
        // adapter (or exploration) can reach every applicable behavior.
        int best = argmax(logits);
        if (best < 0) {
            return logits;
        }
        for (int cell = 0; cell < NUM_OUTPUT_CELLS; cell++) {
            if (candidates[cell] == null) {
                logits[cell] = Double.NEGATIVE_INFINITY;
            } else {
                logits[cell] = cell == best ? 1.0 / temperature : 0.0;
            }
        }
        return logits;
    }

    /** First index of the largest logit, or -1 if every logit is -Infinity. */
    private static int argmax(double[] logits) {
        int best = -1;
        for (int i = 0; i < logits.length; i++) {
            if (logits[i] != Double.NEGATIVE_INFINITY && (best < 0 || logits[i] > logits[best])) {
                best = i;
            }
        }
        return best;
    }

    /** Softmax over the logits; -Infinity cells get 0. A +Infinity logit (overflowed output / temperature) takes all the mass. */
    public static double[] softmax(double[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : logits) {
            max = Math.max(max, v);
        }
        double[] probabilities = new double[logits.length];
        double sum = 0.0;
        for (int i = 0; i < logits.length; i++) {
            double weight;
            if (max == Double.POSITIVE_INFINITY) {
                weight = logits[i] == Double.POSITIVE_INFINITY ? 1.0 : 0.0;
            } else {
                weight = logits[i] == Double.NEGATIVE_INFINITY ? 0.0 : Math.exp(logits[i] - max);
            }
            probabilities[i] = weight;
            sum += weight;
        }
        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] /= sum;
        }
        return probabilities;
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
        return last; // floating-point safety net
    }

    // ---------------------------------------------------------------
    // Output cell -> concrete action
    // ---------------------------------------------------------------

    public static int reservedForInProgressProduce(GameState gs, int player) {
        int reserved = 0;
        for (Unit u : gs.getUnits()) {
            if (u.getPlayer() != player) {
                continue;
            }
            UnitAction inProgress = gs.getUnitAction(u);
            if (inProgress != null && inProgress.getType() == UnitAction.TYPE_PRODUCE && inProgress.getUnitType() != null) {
                reserved += inProgress.getUnitType().cost;
            }
        }
        return reserved;
    }

    public static Set<Long> collectReservedPositions(GameState gs) {
        Set<Long> reserved = new HashSet<>();
        for (Unit u : gs.getUnits()) {
            UnitAction inProgress = gs.getUnitAction(u);
            if (inProgress == null) {
                continue;
            }
            int type = inProgress.getType();
            if (type == UnitAction.TYPE_MOVE || type == UnitAction.TYPE_PRODUCE) {
                reserved.add(destinationKey(u, inProgress));
            }
        }
        return reserved;
    }

    private static UnitAction mapOutputToAction(int outputIndex, Unit unit, Unit closestEnemy, Unit closestResource,
                                                List<UnitAction> legalActions, int remainingResources,
                                                Set<Long> reservedPositions) {
        switch (outputIndex) {
            case 0:
                return chooseMoveTowardTarget(unit, legalActions, closestEnemy, reservedPositions);
            case 1:
                return chooseAttackAction(legalActions, closestEnemy);
            case 2:
                return chooseMoveTowardTarget(unit, legalActions, closestResource, reservedPositions);
            case 3:
                return chooseByType(legalActions, UnitAction.TYPE_HARVEST);
            case 4:
                return chooseByType(legalActions, UnitAction.TYPE_RETURN);
            case 5:
                return chooseProduceAction(unit, closestResource, legalActions, true, remainingResources, reservedPositions);
            case 6:
                return chooseProduceAction(unit, closestEnemy, legalActions, false, remainingResources, reservedPositions);
            default:
                return null;
        }
    }

    private static UnitAction chooseSafeFallbackAction(Unit unit, List<UnitAction> legalActions, int remainingResources,
                                                       Set<Long> reservedPositions) {
        for (UnitAction action : legalActions) {
            if (action.getType() != UnitAction.TYPE_PRODUCE
                    && (action.getType() != UnitAction.TYPE_MOVE
                        || !reservedPositions.contains(destinationKey(unit, action)))) {
                return action;
            }
        }

        for (UnitAction action : legalActions) {
            if (action.getType() == UnitAction.TYPE_PRODUCE
                    && action.getUnitType() != null
                    && action.getUnitType().cost <= remainingResources
                    && !reservedPositions.contains(destinationKey(unit, action))) {
                return action;
            }
        }

        for (UnitAction action : legalActions) {
            if (action.getType() == UnitAction.TYPE_MOVE
                    && !reservedPositions.contains(destinationKey(unit, action))) {
                return action;
            }
        }

        return null;
    }

    private static UnitAction chooseByType(List<UnitAction> legalActions, int type) {
        for (UnitAction action : legalActions) {
            if (action.getType() == type) {
                return action;
            }
        }
        return null;
    }

    private static UnitAction chooseAttackAction(List<UnitAction> legalActions, Unit closestEnemy) {
        if (closestEnemy != null) {
            for (UnitAction action : legalActions) {
                if (action.getType() == UnitAction.TYPE_ATTACK_LOCATION
                        && action.getLocationX() == closestEnemy.getX()
                        && action.getLocationY() == closestEnemy.getY()) {
                    return action;
                }
            }
        }
        return chooseByType(legalActions, UnitAction.TYPE_ATTACK_LOCATION);
    }

    private static UnitAction chooseMoveTowardTarget(Unit unit, List<UnitAction> legalActions, Unit target,
                                                     Set<Long> reservedPositions) {
        if (target == null) {
            return chooseByType(legalActions, UnitAction.TYPE_MOVE, unit, reservedPositions);
        }

        UnitAction bestMove = null;
        int bestDistance = Integer.MAX_VALUE;
        for (UnitAction action : legalActions) {
            if (action.getType() != UnitAction.TYPE_MOVE) {
                continue;
            }
            if (reservedPositions.contains(destinationKey(unit, action))) {
                continue;
            }
            int nextX = unit.getX() + UnitAction.DIRECTION_OFFSET_X[action.getDirection()];
            int nextY = unit.getY() + UnitAction.DIRECTION_OFFSET_Y[action.getDirection()];
            int distance = Math.abs(nextX - target.getX()) + Math.abs(nextY - target.getY());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestMove = action;
            }
        }
        return bestMove;
    }

    private static UnitAction chooseByType(List<UnitAction> legalActions, int type, Unit unit,
                                           Set<Long> reservedPositions) {
        for (UnitAction action : legalActions) {
            if (action.getType() != type) {
                continue;
            }
            if (type == UnitAction.TYPE_MOVE && reservedPositions.contains(destinationKey(unit, action))) {
                continue;
            }
            return action;
        }
        return null;
    }

    /** {@code target} is where the new unit should come out closest to: the nearest resource for workers, enemy otherwise. */
    private static UnitAction chooseProduceAction(Unit unit, Unit target, List<UnitAction> legalActions,
                                                  boolean wantWorker, int remainingResources,
                                                  Set<Long> reservedPositions) {
        UnitAction bestAction = null;
        int bestDistance = Integer.MAX_VALUE;

        for (UnitAction action : legalActions) {
            if (action.getType() != UnitAction.TYPE_PRODUCE) {
                continue;
            }
            UnitType producedType = action.getUnitType();
            if (!matchesProduceGoal(producedType, wantWorker)) {
                continue;
            }
            if (producedType.cost > remainingResources) {
                continue;
            }
            if (reservedPositions.contains(destinationKey(unit, action))) {
                continue;
            }
            if (target == null) {
                return action;
            }
            int nextX = unit.getX() + UnitAction.DIRECTION_OFFSET_X[action.getDirection()];
            int nextY = unit.getY() + UnitAction.DIRECTION_OFFSET_Y[action.getDirection()];
            int distance = Math.abs(nextX - target.getX()) + Math.abs(nextY - target.getY());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestAction = action;
            }
        }

        if (bestAction != null) {
            return bestAction;
        }

        for (UnitAction action : legalActions) {
            if (action.getType() == UnitAction.TYPE_PRODUCE
                    && action.getUnitType() != null
                    && matchesProduceGoal(action.getUnitType(), wantWorker)
                    && action.getUnitType().cost <= remainingResources
                    && !reservedPositions.contains(destinationKey(unit, action))) {
                return action;
            }
        }
        return null;
    }

    /** Unique key for the cell a MOVE or PRODUCE action targets, relative to the acting unit. */
    public static long destinationKey(Unit unit, UnitAction action) {
        int nextX = unit.getX() + UnitAction.DIRECTION_OFFSET_X[action.getDirection()];
        int nextY = unit.getY() + UnitAction.DIRECTION_OFFSET_Y[action.getDirection()];
        return (((long) nextX) << 32) ^ (nextY & 0xffffffffL);
    }

    private static boolean matchesProduceGoal(UnitType producedType, boolean wantWorker) {
        if (producedType == null) {
            return false;
        }
        if (wantWorker) {
            return "Worker".equals(producedType.name);
        }
        return !"Worker".equals(producedType.name) && !producedType.isResource && !producedType.isStockpile;
    }

    // ---------------------------------------------------------------
    // Feature extraction
    // ---------------------------------------------------------------

    public static double[] extractFeatures(Unit unit, GameState gs, int player) {
        Unit closestEnemy = findClosestEnemy(unit, gs);
        int enemyDistance = closestEnemy == null ? Integer.MAX_VALUE : manhattanDistance(unit, closestEnemy);
        int attackRange = Math.max(1, unit.getType().attackRange);

        int enemyCount = countUnitsNear(unit, gs, player, true, unit.getType().sightRadius);
        int allyCount = countUnitsNear(unit, gs, player, false, unit.getType().sightRadius);
        int resources = gs.getPlayer(player).getResources();

        double enemyFarAway = (closestEnemy == null || enemyDistance > attackRange) ? 1.0 : 0.0;
        double enemyInRange = (closestEnemy != null && enemyDistance <= attackRange) ? 1.0 : 0.0;
        double numEnemies = Math.min(enemyCount, 10) / 10.0;
        double enemyType = encodeUnitType(closestEnemy == null ? null : closestEnemy.getType());
        double numAlliesNear = Math.min(allyCount, 5) / 5.0;
        double myUnitType = encodeUnitType(unit.getType());
        double numResources = Math.min(resources, 200) / 200.0;

        return new double[] {
                enemyFarAway,
                enemyInRange,
                numEnemies,
                enemyType,
                numAlliesNear,
                myUnitType,
                numResources
        };
    }

    public static Unit findClosestEnemy(Unit unit, GameState gs) {
        Unit closest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit other : gs.getUnits()) {
            if (other.getPlayer() < 0 || other.getPlayer() == unit.getPlayer()) {
                continue;
            }
            int d = manhattanDistance(unit, other);
            if (d < bestDistance) {
                bestDistance = d;
                closest = other;
            }
        }
        return closest;
    }

    public static Unit findClosestResource(Unit unit, GameState gs) {
        Unit closest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit other : gs.getUnits()) {
            if (!other.getType().isResource) {
                continue;
            }
            int d = manhattanDistance(unit, other);
            if (d < bestDistance) {
                bestDistance = d;
                closest = other;
            }
        }
        return closest;
    }

    private static int countUnitsNear(Unit unit, GameState gs, int player, boolean enemies, int radius) {
        int count = 0;
        for (Unit other : gs.getUnits()) {
            if (other == unit || other.getPlayer() < 0) {
                continue;
            }
            boolean isEnemy = other.getPlayer() != player;
            if (isEnemy != enemies) {
                continue;
            }
            if (manhattanDistance(unit, other) <= radius) {
                count++;
            }
        }
        return count;
    }

    private static int manhattanDistance(Unit a, Unit b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }

    private static double encodeUnitType(UnitType type) {
        if (type == null) {
            return 0.0;
        }
        if ("Worker".equals(type.name)) return 0.0;
        if ("Light".equals(type.name)) return 0.25;
        if ("Heavy".equals(type.name)) return 0.50;
        if ("Ranged".equals(type.name)) return 0.75;
        if ("Base".equals(type.name)) return 0.85;
        if ("Barracks".equals(type.name)) return 0.95;
        if ("Resource".equals(type.name)) return 1.0;
        return 0.5;
    }
}
