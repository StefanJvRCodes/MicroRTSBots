package ai.evolution.gp.nodes;

import ai.evolution.gp.nodes.functions.And;
import ai.evolution.gp.nodes.functions.IfThenElse;
import ai.evolution.gp.nodes.functions.Not;
import ai.evolution.gp.nodes.functions.Or;
import ai.evolution.gp.nodes.terminals.actions.AttackEnemyBase;
import ai.evolution.gp.nodes.terminals.actions.AttackNearestEnemy;
import ai.evolution.gp.nodes.terminals.actions.AttackWeakestEnemy;
import ai.evolution.gp.nodes.terminals.actions.BuildBarracks;
import ai.evolution.gp.nodes.terminals.actions.BuildBase;
import ai.evolution.gp.nodes.terminals.actions.HarvestResources;
import ai.evolution.gp.nodes.terminals.actions.Idle;
import ai.evolution.gp.nodes.terminals.actions.MoveToEnemyBase;
import ai.evolution.gp.nodes.terminals.actions.MoveToNearestEnemy;
import ai.evolution.gp.nodes.terminals.actions.MoveToNearestResource;
import ai.evolution.gp.nodes.terminals.actions.MoveToOwnBase;
import ai.evolution.gp.nodes.terminals.actions.TrainHeavy;
import ai.evolution.gp.nodes.terminals.actions.TrainLight;
import ai.evolution.gp.nodes.terminals.actions.TrainMilitary;
import ai.evolution.gp.nodes.terminals.actions.TrainRanged;
import ai.evolution.gp.nodes.terminals.actions.TrainWorker;
import ai.evolution.gp.nodes.terminals.conditions.CanAttack;
import ai.evolution.gp.nodes.terminals.conditions.CanHarvest;
import ai.evolution.gp.nodes.terminals.conditions.EnemyBaseInRange;
import ai.evolution.gp.nodes.terminals.conditions.EnemyInAttackRange;
import ai.evolution.gp.nodes.terminals.conditions.EnemyInRange;
import ai.evolution.gp.nodes.terminals.conditions.EnemyInSightRange;
import ai.evolution.gp.nodes.terminals.conditions.EnemyMilitaryAtLeast;
import ai.evolution.gp.nodes.terminals.conditions.EnemyWorkersAtLeast;
import ai.evolution.gp.nodes.terminals.conditions.EnemyHasBarracks;
import ai.evolution.gp.nodes.terminals.conditions.GameTimeAtLeast;
import ai.evolution.gp.nodes.terminals.conditions.HPBelow;
import ai.evolution.gp.nodes.terminals.conditions.IsCarryingResources;
import ai.evolution.gp.nodes.terminals.conditions.IsMilitary;
import ai.evolution.gp.nodes.terminals.conditions.NearOwnBase;
import ai.evolution.gp.nodes.terminals.conditions.OwnHasBarracks;
import ai.evolution.gp.nodes.terminals.conditions.OwnMilitaryAtLeast;
import ai.evolution.gp.nodes.terminals.conditions.OwnWorkersAtLeast;
import ai.evolution.gp.nodes.terminals.conditions.ResourceInRange;
import ai.evolution.gp.nodes.terminals.conditions.ResourcesAtLeast;
import ai.evolution.gp.nodes.terminals.conditions.True;
import ai.evolution.gp.nodes.terminals.conditions.WorkerAttackRankAtMost;

import java.util.Random;

public class GPNodeFactory {
    private static final double[] ENEMY_RANGE_FRACTIONS = {0.02, 0.04, 0.06, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5};
    private static final double[] RESOURCE_FRACTIONS = {1.0/20, 2.0/20, 3.0/20, 4.0/20, 5.0/20, 6.0/20, 8.0/20, 10.0/20, 15.0/20, 1.0};
    private static final double[] HP_FRACTIONS = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};
    private static final double[] BASE_RANGE_FRACTIONS = {0.03, 0.06, 0.1, 0.15, 0.25, 0.35, 0.5, 0.65, 0.75};
    private static final double[] ENEMY_BASE_RANGE_FRACTIONS = {0.05, 0.1, 0.15, 0.25, 0.35, 0.5, 0.65, 0.8, 1.0};
    private static final double[] MILITARY_FRACTIONS = {1.0/10, 2.0/10, 3.0/10, 4.0/10, 5.0/10, 6.0/10, 8.0/10, 1.0};
    private static final double[] RESOURCE_RANGE_FRACTIONS = {0.03, 0.06, 0.1, 0.15, 0.25, 0.35, 0.5, 0.65, 0.75};
    private static final double[] ENEMY_WORKER_FRACTIONS = {1.0/12, 2.0/12, 3.0/12, 4.0/12, 5.0/12, 6.0/12, 8.0/12, 10.0/12, 1.0};
    private static final int[] OWN_WORKER_COUNTS = {2, 3, 4, 5, 6, 8};
    private static final int[] GAME_CYCLES = {50, 100, 200, 400, 800, 1200, 2000};
    private static final int[] WORKER_ATTACK_RANKS = {1, 2, 3};

    private final double terminalProbability;

    public GPNodeFactory(double terminalProbability) {
        this.terminalProbability = terminalProbability;
    }

    public ActionNode randomAction(int maxDepth, Random rnd, boolean full) {
        if (maxDepth <= 0 || (!full && rnd.nextDouble() < terminalProbability)) {
            return randomActionTerminal(rnd);
        }
        int conditionDepth = Math.min(2, maxDepth - 1);
        return new IfThenElse(randomBool(conditionDepth, rnd, full), randomAction(maxDepth - 1, rnd, full), randomAction(maxDepth - 1, rnd, full));
    }

    public ActionNode randomActionTerminal(Random rnd) {
        switch (rnd.nextInt(19)) {
            case 0: return new Idle();
            case 1: return new AttackNearestEnemy();
            case 2: return new AttackWeakestEnemy();
            case 3: return new AttackEnemyBase();
            case 4: case 16: case 17: case 18: return new HarvestResources();
            case 5: return new TrainWorker();
            case 6: return new TrainMilitary();
            case 7: return new TrainLight();
            case 8: return new TrainHeavy();
            case 9: return new TrainRanged();
            case 10: return new BuildBase();
            case 11: return new BuildBarracks();
            case 12: return new MoveToEnemyBase();
            case 13: return new MoveToOwnBase();
            case 14: return new MoveToNearestEnemy();
            default: return new MoveToNearestResource();
        }
    }

    public BoolNode randomBool(int maxDepth, Random rnd, boolean full) {
        if (maxDepth <= 0 || (!full && rnd.nextDouble() < terminalProbability)) {
            return randomBoolTerminal(rnd);
        }
        switch (rnd.nextInt(3)) {
            case 0: return new And(randomBool(maxDepth - 1, rnd, full), randomBool(maxDepth - 1, rnd, full));
            case 1: return new Or(randomBool(maxDepth - 1, rnd, full), randomBool(maxDepth - 1, rnd, full));
            default: return new Not(randomBool(maxDepth - 1, rnd, full));
        }
    }

    public BoolNode randomBoolTerminal(Random rnd) {
        switch (rnd.nextInt(22)) {
            case 0: return new True();
            case 1: return new CanHarvest();
            case 2: return new CanAttack();
            case 3: return new EnemyInRange(ENEMY_RANGE_FRACTIONS[rnd.nextInt(ENEMY_RANGE_FRACTIONS.length)]);
            case 4: return new ResourcesAtLeast(RESOURCE_FRACTIONS[rnd.nextInt(RESOURCE_FRACTIONS.length)]);
            case 5: return new HPBelow(HP_FRACTIONS[rnd.nextInt(HP_FRACTIONS.length)]);
            case 6: return new NearOwnBase(BASE_RANGE_FRACTIONS[rnd.nextInt(BASE_RANGE_FRACTIONS.length)]);
            case 7: return new EnemyBaseInRange(ENEMY_BASE_RANGE_FRACTIONS[rnd.nextInt(ENEMY_BASE_RANGE_FRACTIONS.length)]);
            case 8: return new OwnMilitaryAtLeast(MILITARY_FRACTIONS[rnd.nextInt(MILITARY_FRACTIONS.length)]);
            case 9: return new EnemyMilitaryAtLeast(MILITARY_FRACTIONS[rnd.nextInt(MILITARY_FRACTIONS.length)]);
            case 10: return new EnemyWorkersAtLeast(ENEMY_WORKER_FRACTIONS[rnd.nextInt(ENEMY_WORKER_FRACTIONS.length)]);
            case 11: case 12: return new IsMilitary();
            case 13: return new ResourceInRange(RESOURCE_RANGE_FRACTIONS[rnd.nextInt(RESOURCE_RANGE_FRACTIONS.length)]);
            case 14: return new OwnWorkersAtLeast(OWN_WORKER_COUNTS[rnd.nextInt(OWN_WORKER_COUNTS.length)]);
            case 15: return new OwnHasBarracks();
            case 16: return new EnemyHasBarracks();
            case 17: return new GameTimeAtLeast(GAME_CYCLES[rnd.nextInt(GAME_CYCLES.length)]);
            case 18: return new IsCarryingResources();
            case 19: return new WorkerAttackRankAtMost(WORKER_ATTACK_RANKS[rnd.nextInt(WORKER_ATTACK_RANKS.length)]);
            case 20: return new EnemyInAttackRange();
            default: return new EnemyInSightRange();
        }
    }
}
