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

public class GPOpponents {
    private GPOpponents() {}

    public static AI build(String name, UnitTypeTable utt, long seed) {
        return switch (name) {
            case "WorkerRush" -> new WorkerRush(utt, new AStarPathFinding());
            case "LightRush" -> new LightRush(utt, new AStarPathFinding());
            case "HeavyRush" -> new HeavyRush(utt, new AStarPathFinding());
            case "RangedRush" -> new RangedRush(utt, new AStarPathFinding());
            case "WorkerRushPlusPlus" -> new WorkerRushPlusPlus(utt, new AStarPathFinding());
            case "EconomyRush" -> new EconomyRush(utt, new AStarPathFinding());
            case "EconomyRushBurster" -> new EconomyRushBurster(utt, new AStarPathFinding());
            case "EconomyMilitaryRush" -> new EconomyMilitaryRush(utt, new AStarPathFinding(), seed);
            case "EMRDeterministico" -> new EMRDeterministico(utt, new AStarPathFinding());
            case "SimpleEconomyRush" -> new SimpleEconomyRush(utt, new AStarPathFinding());
            case "LightDefense" -> new LightDefense(utt, new AStarPathFinding());
            case "HeavyDefense" -> new HeavyDefense(utt, new AStarPathFinding());
            case "RangedDefense" -> new RangedDefense(utt, new AStarPathFinding());
            case "WorkerDefense" -> new WorkerDefense(utt, new AStarPathFinding());
            case "RandomAI" -> new RandomAI(utt);
            case "RandomBiasedAI" -> new RandomBiasedAI(utt);
            case "RandomBiasedSingleUnitAI" -> new RandomBiasedSingleUnitAI(utt);
            case "PassiveAI" -> new PassiveAI(utt);
            case "mayariBot" -> new mayari(utt, seed);
            case "Coacai" -> new CoacAI(utt);
            case "NaiveMCTS" -> new NaiveMCTS(-1, 200, 100, 10, 0.3f, 0.0f, 0.4f,
                    new RandomBiasedAI(), new SimpleSqrtEvaluationFunction3(), true);
            default -> throw new IllegalArgumentException("Unknown GP opponent: " + name);
        };
    }
}
