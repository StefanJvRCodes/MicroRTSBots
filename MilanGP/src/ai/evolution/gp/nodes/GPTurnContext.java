package ai.evolution.gp.nodes;

import ai.abstraction.AbstractionLayerAI;
import rts.GameState;
import rts.PhysicalGameState;
import rts.Player;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class GPTurnContext {
    public final AbstractionLayerAI ai;
    public final GameState gs;
    public final PhysicalGameState pgs;
    public final Player player;
    public final int playerID;
    public final UnitTypeTable utt;
    public final List<Integer> reservedBuildPositions = new ArrayList<>();
    public Unit unit;

    private final Map<Long, Unit> nearestEnemyByUnit = new HashMap<>();
    private Map<Long, Integer> workerAttackRanks;
    private Map<Long, Integer> workerHarvestRanks;

    public GPTurnContext(AbstractionLayerAI ai, GameState gs, int playerID, UnitTypeTable utt) {
        this.ai = ai;
        this.gs = gs;
        this.pgs = gs.getPhysicalGameState();
        this.playerID = playerID;
        this.player = gs.getPlayer(playerID);
        this.utt = utt;
    }

    public Unit nearestEnemy(Unit u) {
        if (!nearestEnemyByUnit.containsKey(u.getID())) {
            nearestEnemyByUnit.put(u.getID(), GPUtil.nearestEnemy(pgs, u, playerID));
        }
        return nearestEnemyByUnit.get(u.getID());
    }

    public int workerAttackRank(Unit u) {
        if (workerAttackRanks == null) workerAttackRanks = rankWorkersByDistanceTo(this::nearestEnemy);
        return workerAttackRanks.getOrDefault(u.getID(), Integer.MAX_VALUE);
    }

    public int workerHarvestRank(Unit u) {
        if (workerHarvestRanks == null) workerHarvestRanks = rankWorkersByDistanceTo(w -> GPUtil.nearestResource(pgs, w));
        return workerHarvestRanks.getOrDefault(u.getID(), Integer.MAX_VALUE);
    }

    /** Own workers numbered 1, 2, ... by distance to their own target (ties by ID); workers with no target are unranked. */
    private Map<Long, Integer> rankWorkersByDistanceTo(Function<Unit, Unit> targetOf) {
        List<Unit> workers = new ArrayList<>();
        Map<Long, Integer> distance = new HashMap<>();
        for (Unit w : pgs.getUnits()) {
            if (w.getPlayer() != playerID || !w.getType().canHarvest) continue;
            Unit target = targetOf.apply(w);
            if (target == null) continue;
            workers.add(w);
            distance.put(w.getID(), GPUtil.manhattan(w, target));
        }
        workers.sort(Comparator.comparingInt((Unit w) -> distance.get(w.getID()))
                .thenComparingLong(Unit::getID));
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < workers.size(); i++) ranks.put(workers.get(i).getID(), i + 1);
        return ranks;
    }
}
