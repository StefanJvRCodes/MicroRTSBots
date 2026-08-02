package gp;

import ec.gp.GPData;
import rts.GameState;
import rts.UnitAction;
import rts.units.Unit;

/**
 * The carrier passed down an evolved scoring tree.
 *
 * ECJ nodes do not return values; they write into a shared mutable GPData. Here the
 * output field is {@link #score}, and the other four fields are the READ-ONLY context
 * that terminals query: "this unit, considering this action, in this state, for this
 * player". MicroRTSProblem sets the context once per candidate action, then evaluates
 * the tree; the root's value lands in score.
 *
 * Terminals must never mutate the context. If a terminal ever calls something that
 * advances the GameState, every evaluation after it is silently wrong.
 */
public class ScoreData extends GPData {

    /** Output: the value the tree assigns to this (unit, action) pair. */
    public double score;

    // ---- read-only evaluation context ----
    public Unit unit;
    public UnitAction action;
    public GameState gs;
    public int player;

    public void set(Unit unit, UnitAction action, GameState gs, int player) {
        this.unit = unit;
        this.action = action;
        this.gs = gs;
        this.player = player;
    }

    @Override
    public void copyTo(final GPData other) {
        ScoreData o = (ScoreData) other;
        o.score = score;
        o.unit = unit;
        o.action = action;
        o.gs = gs;
        o.player = player;
    }
}
