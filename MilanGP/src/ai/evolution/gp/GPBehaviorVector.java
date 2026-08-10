package ai.evolution.gp;

import java.util.List;

public class GPBehaviorVector {
    private GPBehaviorVector() {}

    private static final double ARMY_NORMALIZER = 30.0;
    private static final double WORKER_NORMALIZER = 20.0;

    public static final int FIXED_FEATURES = 4;

    public static double[] build(List<GPBehaviorTrace> traces, int maxCycles, List<String> unitTypeNames) {
        double[] vector = new double[FIXED_FEATURES + unitTypeNames.size()];
        if (traces.isEmpty()) return vector;

        double firstAttack = 0, firstExpansion = 0, peakArmy = 0, peakWorkers = 0;
        double[] composition = new double[unitTypeNames.size()];
        for (GPBehaviorTrace t : traces) {
            firstAttack += t.firstAttackCycle < 0 ? 1.0 : (double) t.firstAttackCycle / maxCycles;
            firstExpansion += t.firstExpansionCycle < 0 ? 1.0 : (double) t.firstExpansionCycle / maxCycles;
            peakArmy += Math.min(1.0, t.peakArmySize / ARMY_NORMALIZER);
            peakWorkers += Math.min(1.0, t.peakWorkerCount / WORKER_NORMALIZER);

            int total = 0;
            for (int count : t.finalComposition.values()) total += count;
            if (total > 0) {
                for (int i = 0; i < unitTypeNames.size(); i++) {
                    Integer count = t.finalComposition.get(unitTypeNames.get(i));
                    composition[i] += (count == null ? 0 : count) / (double) total;
                }
            }
        }

        int n = traces.size();
        vector[0] = firstAttack / n;
        vector[1] = firstExpansion / n;
        vector[2] = peakArmy / n;
        vector[3] = peakWorkers / n;
        for (int i = 0; i < composition.length; i++) vector[FIXED_FEATURES + i] = composition[i] / n;
        return vector;
    }

    public static double distance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }
}
