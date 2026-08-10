package ai.evolution.gp;

import java.util.HashMap;
import java.util.Map;

public class GPBehaviorTrace {
    public int firstAttackCycle = -1;
    public int firstExpansionCycle = -1;
    public int peakArmySize = 0;
    public int peakWorkerCount = 0;
    public final Map<String, Integer> finalComposition = new HashMap<>();
}
