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

import java.util.List;

public class GPNodeRegistry {
    private GPNodeRegistry() {}

    public static GPNode build(String name, List<GPNode> children, List<String> params) {
        switch (name) {
            case IfThenElse.NAME: return new IfThenElse((BoolNode) children.get(0), (ActionNode) children.get(1), (ActionNode) children.get(2));
            case And.NAME: return new And((BoolNode) children.get(0), (BoolNode) children.get(1));
            case Or.NAME: return new Or((BoolNode) children.get(0), (BoolNode) children.get(1));
            case Not.NAME: return new Not((BoolNode) children.get(0));
            case True.NAME: return new True();
            case CanHarvest.NAME: return new CanHarvest();
            case CanAttack.NAME: return new CanAttack();
            case IsMilitary.NAME: return new IsMilitary();
            case EnemyInRange.NAME: return new EnemyInRange(Double.parseDouble(params.get(0)));
            case EnemyInAttackRange.NAME: return new EnemyInAttackRange();
            case EnemyInSightRange.NAME: return new EnemyInSightRange();
            case ResourcesAtLeast.NAME: return new ResourcesAtLeast(Double.parseDouble(params.get(0)));
            case ResourceInRange.NAME: return new ResourceInRange(Double.parseDouble(params.get(0)));
            case HPBelow.NAME: return new HPBelow(Double.parseDouble(params.get(0)));
            case NearOwnBase.NAME: return new NearOwnBase(Double.parseDouble(params.get(0)));
            case EnemyBaseInRange.NAME: return new EnemyBaseInRange(Double.parseDouble(params.get(0)));
            case OwnMilitaryAtLeast.NAME: return new OwnMilitaryAtLeast(Double.parseDouble(params.get(0)));
            case EnemyMilitaryAtLeast.NAME: return new EnemyMilitaryAtLeast(Double.parseDouble(params.get(0)));
            case EnemyWorkersAtLeast.NAME: return new EnemyWorkersAtLeast(Double.parseDouble(params.get(0)));
            case OwnWorkersAtLeast.NAME: return new OwnWorkersAtLeast(Integer.parseInt(params.get(0)));
            case OwnHasBarracks.NAME: return new OwnHasBarracks();
            case EnemyHasBarracks.NAME: return new EnemyHasBarracks();
            case GameTimeAtLeast.NAME: return new GameTimeAtLeast(Integer.parseInt(params.get(0)));
            case IsCarryingResources.NAME: return new IsCarryingResources();
            case WorkerAttackRankAtMost.NAME: return new WorkerAttackRankAtMost(Integer.parseInt(params.get(0)));
            case Idle.NAME: return new Idle();
            case AttackNearestEnemy.NAME: return new AttackNearestEnemy();
            case AttackWeakestEnemy.NAME: return new AttackWeakestEnemy();
            case AttackEnemyBase.NAME: return new AttackEnemyBase();
            case HarvestResources.NAME: return new HarvestResources();
            case TrainWorker.NAME: return new TrainWorker();
            case TrainMilitary.NAME: return new TrainMilitary();
            case TrainLight.NAME: return new TrainLight();
            case TrainHeavy.NAME: return new TrainHeavy();
            case TrainRanged.NAME: return new TrainRanged();
            case BuildBase.NAME: return new BuildBase();
            case BuildBarracks.NAME: return new BuildBarracks();
            case MoveToEnemyBase.NAME: return new MoveToEnemyBase();
            case MoveToOwnBase.NAME: return new MoveToOwnBase();
            case MoveToNearestEnemy.NAME: return new MoveToNearestEnemy();
            case MoveToNearestResource.NAME: return new MoveToNearestResource();
            default: throw new IllegalArgumentException("Unknown GP node name: " + name);
        }
    }
}
