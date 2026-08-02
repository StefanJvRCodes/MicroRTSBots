package gp.symreg;

import ec.gp.GPData;

/**
 * The evaluation "carrier" passed down a GP tree.
 *
 * ECJ trees do not return values from eval(); every node writes its result into a
 * shared mutable GPData object. A parent calls child.eval(...), then reads the field.
 * Here the field is a single double.
 *
 * THROWAWAY (Phase 3b smoke test). The microRTS analogue will carry a double score
 * plus read-only references to (Unit, UnitAction, GameState, player) so terminals in
 * gp.Features can be queried during evaluation.
 */
public class SymRegData extends GPData {
    /** The value produced by the subtree that was just evaluated. */
    public double x;

    @Override
    public void copyTo(final GPData gpd) {
        ((SymRegData) gpd).x = x;
    }
}
