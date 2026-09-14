package ai.evolution.gp;

import ai.PassiveAI;
import ai.RandomAI;
import ai.RandomBiasedAI;
import ai.RandomBiasedSingleUnitAI;
import ai.abstraction.EMRDeterministico;
import ai.abstraction.EconomyMilitaryRush;
import ai.abstraction.EconomyRush;
import ai.abstraction.EconomyRushBurster;
import ai.abstraction.HeavyDefense;
import ai.abstraction.HeavyRush;
import ai.abstraction.LightDefense;
import ai.abstraction.LightRush;
import ai.abstraction.RangedDefense;
import ai.abstraction.RangedRush;
import ai.abstraction.SimpleEconomyRush;
import ai.abstraction.WorkerDefense;
import ai.abstraction.WorkerRush;
import ai.abstraction.WorkerRushPlusPlus;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.coac.CoacAI;
import ai.core.AI;
import ai.evaluation.SimpleSqrtEvaluationFunction3;
import ai.mcts.naivemcts.NaiveMCTS;
import mayariBot.mayari;
import rts.units.UnitTypeTable;

/** Opponent name (as used in GPConfig.opponents) to a fresh AI instance. Only EconomyMilitaryRush and mayariBot take the seed. */
public class GPOpponents {
    private GPOpponents() {}

    public static AI build(String name, UnitTypeTable utt, long seed) {
        switch (name) {
            case "WorkerRush": return new WorkerRush(utt, new AStarPathFinding());
            case "LightRush": return new LightRush(utt, new AStarPathFinding());
            case "HeavyRush": return new HeavyRush(utt, new AStarPathFinding());
            case "RangedRush": return new RangedRush(utt, new AStarPathFinding());
            case "WorkerRushPlusPlus": return new WorkerRushPlusPlus(utt, new AStarPathFinding());
            case "EconomyRush": return new EconomyRush(utt, new AStarPathFinding());
            case "EconomyRushBurster": return new EconomyRushBurster(utt, new AStarPathFinding());
            case "EconomyMilitaryRush": return new EconomyMilitaryRush(utt, new AStarPathFinding(), seed);
            case "EMRDeterministico": return new EMRDeterministico(utt, new AStarPathFinding());
            case "SimpleEconomyRush": return new SimpleEconomyRush(utt, new AStarPathFinding());
            case "LightDefense": return new LightDefense(utt, new AStarPathFinding());
            case "HeavyDefense": return new HeavyDefense(utt, new AStarPathFinding());
            case "RangedDefense": return new RangedDefense(utt, new AStarPathFinding());
            case "WorkerDefense": return new WorkerDefense(utt, new AStarPathFinding());
            case "RandomAI": return new RandomAI(utt);
            case "RandomBiasedAI": return new RandomBiasedAI(utt);
            case "RandomBiasedSingleUnitAI": return new RandomBiasedSingleUnitAI(utt);
            case "PassiveAI": return new PassiveAI(utt);
            // The held-out evaluation panel.
            case "mayariBot": return new mayari(utt, seed);
            case "Coacai": return new CoacAI(utt);
            /*
             * Budgeted by playouts rather than the 100 ms wall clock, so a training generation does
             * not take hours. Its playout policy is an unseeded RandomBiasedAI, so it plays a
             * different game every evaluation: keep it out of `opponents` unless you are willing to
             * lose the repeatable fitness that elitism depends on.
             */
            case "NaiveMCTS": return new NaiveMCTS(-1, 200, 100, 10, 0.3f, 0.0f, 0.4f,
                    new RandomBiasedAI(), new SimpleSqrtEvaluationFunction3(), true);
            default: throw new IllegalArgumentException("Unknown GP opponent: " + name);
        }
    }
}
