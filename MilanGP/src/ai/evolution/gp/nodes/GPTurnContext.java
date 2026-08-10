package ai.evolution.gp.nodes;

import ai.abstraction.AbstractionLayerAI;
import rts.GameState;
import rts.PhysicalGameState;
import rts.Player;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.util.ArrayList;
import java.util.List;

public class GPTurnContext {
    public final AbstractionLayerAI ai;
    public final GameState gs;
    public final PhysicalGameState pgs;
    public final Player player;
    public final int playerID;
    public final UnitTypeTable utt;
    public final List<Integer> reservedBuildPositions = new ArrayList<>();
    public Unit unit;

    public GPTurnContext(AbstractionLayerAI ai, GameState gs, int playerID, UnitTypeTable utt) {
        this.ai = ai;
        this.gs = gs;
        this.pgs = gs.getPhysicalGameState();
        this.playerID = playerID;
        this.player = gs.getPlayer(playerID);
        this.utt = utt;
    }
}
