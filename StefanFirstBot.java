/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ParameterSpecification;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import rts.GameState;
import rts.PlayerAction;
import rts.UnitAction;
import rts.units.Unit;
import rts.units.UnitTypeTable;



/**
 *
 * @author sjvr0
 */
public class StefanFirstBot extends AIWithComputationBudget implements Cloneable{
    UnitTypeTable m_utt = null;

    // This is the default constructor that microRTS will call:

    public StefanFirstBot(UnitTypeTable utt) {

        super(-1,-1);

        m_utt = utt;

    }

    // This will be called by microRTS when it wants to create new instances of this bot (e.g., to play multiple games).

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public AI clone() {
        try {
            Method cloneMethod = Object.class.getDeclaredMethod("clone");
            cloneMethod.setAccessible(true);
            StefanFirstBot clone = (StefanFirstBot) cloneMethod.invoke(this);
            return clone;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return new StefanFirstBot(m_utt);
        }

    }
    
    // This will be called once at the beginning of each new game:    

    @Override
    public void reset() {
        // No persistent state yet.

    }

       

    // Called by microRTS at each game cycle.

    // Returns the action the bot wants to execute.

    @Override
    public PlayerAction getAction(int player, GameState gs) {

        PlayerAction pa = new PlayerAction();

        for (Unit u : gs.getUnits()) {
            if (u.getPlayer() != player) continue;
            if (gs.getUnitAction(u) != null) continue;

            UnitAction chosen = chooseAction(u, gs);

            //In GP, the chosen action should be from decision tree
            //Decision should return action probability distribution
            //Base will not use adapter module.

            if (chosen != null) {
                pa.addUnitAction(u, chosen);
            }
        }

        pa.fillWithNones(gs, player, 10);

        return pa;

    }    

    private UnitAction chooseAction(Unit unit, GameState gs) {
        List<UnitAction> legalActions = unit.getUnitActions(gs);
        if (legalActions.isEmpty()) return null;

        List<UnitAction> attackActions = new ArrayList<>();
        List<UnitAction> harvestActions = new ArrayList<>();
        List<UnitAction> returnActions = new ArrayList<>();
        List<UnitAction> produceActions = new ArrayList<>();
        List<UnitAction> moveActions = new ArrayList<>();
        List<UnitAction> noneActions = new ArrayList<>();

        for (UnitAction ua : legalActions) {
            switch (ua.getType()) {
                case UnitAction.TYPE_ATTACK_LOCATION -> attackActions.add(ua);
                case UnitAction.TYPE_HARVEST -> harvestActions.add(ua);
                case UnitAction.TYPE_RETURN -> returnActions.add(ua);
                case UnitAction.TYPE_PRODUCE -> produceActions.add(ua);
                case UnitAction.TYPE_MOVE -> moveActions.add(ua);
                default -> noneActions.add(ua);
            }
        }

        if (!attackActions.isEmpty()) {
            return attackActions.get(0);
        }

        if (!returnActions.isEmpty()) {
            return returnActions.get(0);
        }

        if (!harvestActions.isEmpty()) {
            return harvestActions.get(0);
        }

        if (!produceActions.isEmpty()) {
            return produceActions.get(0);
        }

        if (!moveActions.isEmpty()) {
            Unit enemy = findClosestEnemy(unit, gs);
            if (enemy != null) {
                UnitAction bestMove = null;
                int bestDistance = Integer.MAX_VALUE;

                for (UnitAction move : moveActions) {
                    int nextX = unit.getX() + UnitAction.DIRECTION_OFFSET_X[move.getDirection()];
                    int nextY = unit.getY() + UnitAction.DIRECTION_OFFSET_Y[move.getDirection()];
                    int distance = Math.abs(nextX - enemy.getX()) + Math.abs(nextY - enemy.getY());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestMove = move;
                    }
                }

                if (bestMove != null) return bestMove;
            }

            return moveActions.get(0);
        }

        if (!noneActions.isEmpty()) {
            return noneActions.get(0);
        }

        return legalActions.get(0);
    }

    private Unit findClosestEnemy(Unit unit, GameState gs) {
        Unit closest = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Unit other : gs.getUnits()) {
            if (other.getPlayer() < 0 || other.getPlayer() == unit.getPlayer()) continue;

            int distance = Math.abs(other.getX() - unit.getX()) + Math.abs(other.getY() - unit.getY());
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = other;
            }
        }

        return closest;
    }

    

    // This will be called by the microRTS GUI to get the

    // list of parameters that this bot wants exposed

    // in the GUI.

    @Override
    public List<ParameterSpecification> getParameters()

    {

        return new ArrayList<>();

    }
}
