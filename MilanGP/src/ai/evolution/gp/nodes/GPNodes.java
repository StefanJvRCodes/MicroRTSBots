package ai.evolution.gp.nodes;

import ai.evolution.gp.nodes.functions.And;
import ai.evolution.gp.nodes.functions.IfThenElse;
import ai.evolution.gp.nodes.functions.Not;
import ai.evolution.gp.nodes.functions.Or;
import ai.evolution.gp.nodes.terminals.actions.*;
import ai.evolution.gp.nodes.terminals.conditions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * The single table of every terminal the trees can use. Each entry says how to parse the terminal
 * from its s-expression parameters and how to draw a random instance. To add a terminal, write the
 * class and add one line here.
 */
public final class GPNodes {
    private GPNodes() {}

    private record Spec(String name, Function<List<String>, GPNode> parse, Function<Random, GPNode> random) {}

    // Value sets random terminals draw their constants from. Mutation later nudges them freely.
    private static final double[] ENEMY_RANGE = {0.02, 0.04, 0.06, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5};
    private static final double[] ENEMY_BASE_RANGE = {0.05, 0.1, 0.15, 0.25, 0.35, 0.5, 0.65, 0.8, 1.0};
    private static final double[] OWN_BASE_RANGE = {0.03, 0.06, 0.1, 0.15, 0.25, 0.35, 0.5, 0.65, 0.75};
    private static final double[] RESOURCE_RANGE = OWN_BASE_RANGE;
    private static final double[] RESOURCES = {0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.75, 1.0};
    private static final double[] HP = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};
    private static final double[] MILITARY = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0};
    private static final double[] ENEMY_WORKERS = {0.08, 0.17, 0.25, 0.33, 0.42, 0.5, 0.67, 0.83, 1.0};
    private static final int[] OWN_WORKERS = {2, 3, 4, 5, 6, 8};
    private static final int[] GAME_CYCLES = {50, 100, 200, 400, 800, 1200, 2000};
    private static final int[] WORKER_RANKS = {1, 2, 3};

    private static final List<Spec> CONDITIONS = List.of(
            constant(True.NAME, new True()),
            constant(CanHarvest.NAME, new CanHarvest()),
            constant(CanAttack.NAME, new CanAttack()),
            constant(IsMilitary.NAME, new IsMilitary()),
            constant(IsCarryingResources.NAME, new IsCarryingResources()),
            constant(OwnHasBarracks.NAME, new OwnHasBarracks()),
            constant(EnemyHasBarracks.NAME, new EnemyHasBarracks()),
            constant(EnemyInAttackRange.NAME, new EnemyInAttackRange()),
            constant(EnemyInSightRange.NAME, new EnemyInSightRange()),
            withDouble(EnemyInRange.NAME, EnemyInRange::new, ENEMY_RANGE),
            withDouble(EnemyBaseInRange.NAME, EnemyBaseInRange::new, ENEMY_BASE_RANGE),
            withDouble(NearOwnBase.NAME, NearOwnBase::new, OWN_BASE_RANGE),
            withDouble(ResourceInRange.NAME, ResourceInRange::new, RESOURCE_RANGE),
            withDouble(ResourcesAtLeast.NAME, ResourcesAtLeast::new, RESOURCES),
            withDouble(HPBelow.NAME, HPBelow::new, HP),
            withDouble(OwnMilitaryAtLeast.NAME, OwnMilitaryAtLeast::new, MILITARY),
            withDouble(EnemyMilitaryAtLeast.NAME, EnemyMilitaryAtLeast::new, MILITARY),
            withDouble(EnemyWorkersAtLeast.NAME, EnemyWorkersAtLeast::new, ENEMY_WORKERS),
            withInt(OwnWorkersAtLeast.NAME, OwnWorkersAtLeast::new, OWN_WORKERS),
            withInt(GameTimeAtLeast.NAME, GameTimeAtLeast::new, GAME_CYCLES),
            withInt(WorkerAttackRankAtMost.NAME, WorkerAttackRankAtMost::new, WORKER_RANKS));

    private static final List<Spec> ACTIONS = List.of(
            constant(Idle.NAME, new Idle()),
            constant(HarvestResources.NAME, new HarvestResources()),
            constant(AttackNearestEnemy.NAME, new AttackNearestEnemy()),
            constant(AttackWeakestEnemy.NAME, new AttackWeakestEnemy()),
            constant(AttackEnemyBase.NAME, new AttackEnemyBase()),
            constant(TrainWorker.NAME, new TrainWorker()),
            constant(TrainMilitary.NAME, new TrainMilitary()),
            constant(TrainLight.NAME, new TrainLight()),
            constant(TrainHeavy.NAME, new TrainHeavy()),
            constant(TrainRanged.NAME, new TrainRanged()),
            constant(BuildBase.NAME, new BuildBase()),
            constant(BuildBarracks.NAME, new BuildBarracks()),
            constant(MoveToEnemyBase.NAME, new MoveToEnemyBase()),
            constant(MoveToOwnBase.NAME, new MoveToOwnBase()),
            constant(MoveToNearestEnemy.NAME, new MoveToNearestEnemy()),
            constant(MoveToNearestResource.NAME, new MoveToNearestResource()),
            new Spec(TechAndTrain.NAME, p -> new TechAndTrain(p.get(0)),
                    r -> new TechAndTrain(TechAndTrain.TYPES[r.nextInt(TechAndTrain.TYPES.length)])));

    private static final Map<String, Spec> BY_NAME = new HashMap<>();
    static {
        for (Spec s : CONDITIONS) BY_NAME.put(s.name(), s);
        for (Spec s : ACTIONS) BY_NAME.put(s.name(), s);
    }

    /** Builds a node from its parsed s-expression parts. Used by {@link GPSExpression}. */
    public static GPNode build(String name, List<GPNode> children, List<String> params) {
        switch (name) {
            case IfThenElse.NAME:
                return new IfThenElse((BoolNode) children.get(0), (ActionNode) children.get(1), (ActionNode) children.get(2));
            case And.NAME: return new And((BoolNode) children.get(0), (BoolNode) children.get(1));
            case Or.NAME: return new Or((BoolNode) children.get(0), (BoolNode) children.get(1));
            case Not.NAME: return new Not((BoolNode) children.get(0));
            default:
                Spec spec = BY_NAME.get(name);
                if (spec == null) throw new IllegalArgumentException("Unknown GP node name: " + name);
                return spec.parse().apply(params);
        }
    }

    /** A uniformly drawn condition terminal with a random constant where one is needed. */
    public static BoolNode randomCondition(Random rnd) {
        return (BoolNode) CONDITIONS.get(rnd.nextInt(CONDITIONS.size())).random().apply(rnd);
    }

    /** A uniformly drawn action terminal with a random constant where one is needed. */
    public static ActionNode randomAction(Random rnd) {
        return (ActionNode) ACTIONS.get(rnd.nextInt(ACTIONS.size())).random().apply(rnd);
    }

    // ---- entry helpers

    private static Spec constant(String name, GPNode instance) {
        return new Spec(name, p -> instance, r -> instance);
    }

    private static Spec withDouble(String name, Function<Double, GPNode> make, double[] values) {
        return new Spec(name, p -> make.apply(Double.parseDouble(p.get(0))),
                r -> make.apply(values[r.nextInt(values.length)]));
    }

    private static Spec withInt(String name, Function<Integer, GPNode> make, int[] values) {
        return new Spec(name, p -> make.apply(Integer.parseInt(p.get(0))),
                r -> make.apply(values[r.nextInt(values.length)]));
    }
}
