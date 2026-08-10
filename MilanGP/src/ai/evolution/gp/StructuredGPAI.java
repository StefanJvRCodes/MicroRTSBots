package ai.evolution.gp;

import ai.abstraction.AbstractionLayerAI;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.abstraction.pathfinding.PathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPSExpression;
import ai.evolution.gp.nodes.GPTurnContext;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class StructuredGPAI extends AbstractionLayerAI {
    protected UnitTypeTable utt;
    protected ActionNode root;

    public StructuredGPAI(UnitTypeTable utt, ActionNode root) {
        this(utt, new AStarPathFinding(), root);
    }

    public StructuredGPAI(UnitTypeTable utt, PathFinding pf, ActionNode root) {
        super(pf);
        this.utt = utt;
        this.root = root;
    }

    public StructuredGPAI(UnitTypeTable utt, String sExpressionFilePath) throws IOException {
        this(utt, new AStarPathFinding(), sExpressionFilePath);
    }

    public StructuredGPAI(UnitTypeTable utt, PathFinding pf, String sExpressionFilePath) throws IOException {
        super(pf);
        this.utt = utt;
        String expression = new String(Files.readAllBytes(Paths.get(sExpressionFilePath)));
        this.root = GPSExpression.parseAction(expression);
    }

    @Override
    public void reset() {
        super.reset();
    }

    @Override
    public void reset(UnitTypeTable a_utt) {
        utt = a_utt;
        reset();
    }

    @Override
    public AI clone() {
        return new StructuredGPAI(utt, pf, root);
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        PhysicalGameState pgs = gs.getPhysicalGameState();
        GPTurnContext ctx = new GPTurnContext(this, gs, player, utt);
        for (Unit u : pgs.getUnits()) {
            if (u.getPlayer() == player && gs.getActionAssignment(u) == null) {
                ctx.unit = u;
                root.exec(ctx);
            }
        }
        return translateActions(player, gs);
    }

    public String toSExpression() {
        return GPSExpression.write(root);
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return new ArrayList<>();
    }
}
