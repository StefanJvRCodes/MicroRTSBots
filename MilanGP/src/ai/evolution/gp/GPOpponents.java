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
import ai.core.AI;
import mayariBot.mayari;
import rts.units.UnitTypeTable;

public class GPOpponents {
    private GPOpponents() {}

    public static AI build(String name, UnitTypeTable utt) {
        return build(name, utt, 0L);
    }

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
            case "mayariBot": return new mayari(utt);
            default: throw new IllegalArgumentException("Unknown GP opponent: " + name);
        }
    }
}
