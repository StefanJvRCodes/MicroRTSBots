package ai.custom;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import rts.GameState;
import rts.PlayerAction;
import rts.UnitAction;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;

public class TreeGPBot extends AIWithComputationBudget implements Cloneable {
    private static final int OUTPUT_MOVE_TOWARDS_ENEMY = 0;
    private static final int OUTPUT_ATTACK = 1;
    private static final int OUTPUT_MOVE_TOWARDS_RESOURCE = 2;
    private static final int OUTPUT_HARVEST = 3;
    private static final int OUTPUT_RETURN_TO_BASE = 4;
    private static final int OUTPUT_PRODUCE_WORKER = 5;
    private static final int OUTPUT_PRODUCE_TROOP = 6;

    private final UnitTypeTable utt;
    private final Random random;
    private final GPTree tree;

    public TreeGPBot(UnitTypeTable utt) {
        this(utt, new Random());
    }

    public TreeGPBot(UnitTypeTable utt, Random random) {
        this(utt, random, GPTree.randomTree(random, 4, 0.18, GPFeatureExtractor.FEATURE_COUNT, 70));
    }

    private TreeGPBot(UnitTypeTable utt, Random random, GPTree tree) {
        super(-1, -1);
        this.utt = utt;
        this.random = random;
        this.tree = tree;
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public AI clone() {
        return new TreeGPBot(utt, random, tree.deepCopy());
    }

    @Override
    public void reset() {
        // Tree is immutable from the bot's perspective during a game.
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        PlayerAction pa = new PlayerAction();

        for (Unit unit : gs.getUnits()) {
            if (unit.getPlayer() != player) {
                continue;
            }
            if (gs.getUnitAction(unit) != null) {
                continue;
            }

            UnitAction chosen = chooseAction(unit, gs, player);
            if (chosen != null) {
                pa.addUnitAction(unit, chosen);
            }
        }

        pa.fillWithNones(gs, player, 10);
        return pa;
    }

    private UnitAction chooseAction(Unit unit, GameState gs, int player) {
        List<UnitAction> legalActions = unit.getUnitActions(gs);
        if (legalActions.isEmpty()) {
            return null;
        }

        double[] features = GPFeatureExtractor.extract(unit, gs, player, utt);
        double[] scores = tree.evaluate(features);

        List<Integer> rankedOutputs = rankOutputs(scores);
        for (int outputIndex : rankedOutputs) {
            UnitAction action = mapOutputToAction(outputIndex, unit, gs, legalActions);
            if (action != null) {
                return action;
            }
        }

        return legalActions.get(0);
    }

    private List<Integer> rankOutputs(double[] scores) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
        return order;
    }

    private UnitAction mapOutputToAction(int outputIndex, Unit unit, GameState gs, List<UnitAction> legalActions) {
        switch (outputIndex) {
            case OUTPUT_MOVE_TOWARDS_ENEMY:
                return chooseMoveTowardTarget(unit, gs, legalActions, GPFeatureExtractor.findClosestEnemy(unit, gs));
            case OUTPUT_ATTACK:
                return chooseAttackAction(unit, gs, legalActions);
            case OUTPUT_MOVE_TOWARDS_RESOURCE:
                return chooseMoveTowardTarget(unit, gs, legalActions, GPFeatureExtractor.findClosestResource(unit, gs));
            case OUTPUT_HARVEST:
                return chooseByType(legalActions, UnitAction.TYPE_HARVEST);
            case OUTPUT_RETURN_TO_BASE:
                return chooseByType(legalActions, UnitAction.TYPE_RETURN);
            case OUTPUT_PRODUCE_WORKER:
                return chooseProduceAction(unit, gs, legalActions, true);
            case OUTPUT_PRODUCE_TROOP:
                return chooseProduceAction(unit, gs, legalActions, false);
            default:
                return null;
        }
    }

    private UnitAction chooseByType(List<UnitAction> legalActions, int type) {
        for (UnitAction action : legalActions) {
            if (action.getType() == type) {
                return action;
            }
        }
        return null;
    }

    private UnitAction chooseAttackAction(Unit unit, GameState gs, List<UnitAction> legalActions) {
        Unit closestEnemy = GPFeatureExtractor.findClosestEnemy(unit, gs);
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

    private UnitAction chooseMoveTowardTarget(Unit unit, GameState gs, List<UnitAction> legalActions, Unit target) {
        if (target == null) {
            return chooseByType(legalActions, UnitAction.TYPE_MOVE);
        }

        UnitAction bestMove = null;
        int bestDistance = Integer.MAX_VALUE;

        for (UnitAction action : legalActions) {
            if (action.getType() != UnitAction.TYPE_MOVE) {
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

    private UnitAction chooseProduceAction(Unit unit, GameState gs, List<UnitAction> legalActions, boolean wantWorker) {
        Unit target = wantWorker ? GPFeatureExtractor.findClosestResource(unit, gs) : GPFeatureExtractor.findClosestEnemy(unit, gs);
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
            if (action.getType() == UnitAction.TYPE_PRODUCE && matchesProduceGoal(action.getUnitType(), wantWorker)) {
                return action;
            }
        }
        return null;
    }

    private boolean matchesProduceGoal(UnitType producedType, boolean wantWorker) {
        if (producedType == null) {
            return false;
        }
        if (wantWorker) {
            return "Worker".equals(producedType.name);
        }
        return !"Worker".equals(producedType.name)
                && !producedType.isResource
                && !producedType.isStockpile;
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return new ArrayList<>();
    }

    @Override
    public String toString() {
        return super.toString() + "{" + tree + "}";
    }
}
