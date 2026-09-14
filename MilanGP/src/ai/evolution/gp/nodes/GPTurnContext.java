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

/**
 * Everything a node may look at while deciding one unit's action. One instance is created per turn
 * and shared by every unit decided in that turn, so per-turn lookups can be cached here.
 */
public class GPTurnContext {
    public final AbstractionLayerAI ai;
    public final GameState gs;
    public final PhysicalGameState pgs;
    public final Player player;
    public final int playerID;
    public final UnitTypeTable utt;
    /** Tiles already claimed by a build order this turn, so two workers do not pick the same one. */
    public final List<Integer> reservedBuildPositions = new ArrayList<>();
    /** The unit currently being decided. */
    public Unit unit;

    private final Map<Long, Unit> nearestEnemyByUnit = new HashMap<>();
    private Map<Long, Integer> workerAttackRanks;

    public GPTurnContext(AbstractionLayerAI ai, GameState gs, int playerID, UnitTypeTable utt) {
        this.ai = ai;
        this.gs = gs;
        this.pgs = gs.getPhysicalGameState();
        this.playerID = playerID;
        this.player = gs.getPlayer(playerID);
        this.utt = utt;
    }

    /** Nearest enemy unit to {@code u}, cached for the rest of the turn. Null when no enemy exists. */
    public Unit nearestEnemy(Unit u) {
        if (!nearestEnemyByUnit.containsKey(u.getID())) {
            nearestEnemyByUnit.put(u.getID(), GPUtil.nearestEnemy(pgs, u, playerID));
        }
        return nearestEnemyByUnit.get(u.getID());
    }

    /**
     * 1 for the friendly worker closest to an enemy, 2 for the next closest, and so on. Ties break on
     * unit ID so the ranking is deterministic. {@code Integer.MAX_VALUE} for non-workers and when
     * there is no enemy on the map.
     */
    public int workerAttackRank(Unit u) {
        if (workerAttackRanks == null) workerAttackRanks = computeWorkerAttackRanks();
        return workerAttackRanks.getOrDefault(u.getID(), Integer.MAX_VALUE);
    }

    private Map<Long, Integer> computeWorkerAttackRanks() {
        List<Unit> workers = new ArrayList<>();
        Map<Long, Integer> distanceToEnemy = new HashMap<>();
        for (Unit w : pgs.getUnits()) {
            if (w.getPlayer() != playerID || !w.getType().canHarvest) continue;
            Unit enemy = nearestEnemy(w);
            if (enemy == null) continue;
            workers.add(w);
            distanceToEnemy.put(w.getID(), GPUtil.manhattan(w, enemy));
        }
        workers.sort(Comparator.comparingInt((Unit w) -> distanceToEnemy.get(w.getID()))
                .thenComparingLong(Unit::getID));
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < workers.size(); i++) ranks.put(workers.get(i).getID(), i + 1);
        return ranks;
    }
}
