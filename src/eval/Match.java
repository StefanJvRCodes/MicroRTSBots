package eval;

import bots.Chimera;
import ai.core.AI;
import ai.RandomBiasedAI;
import ai.abstraction.WorkerRush;
import ai.abstraction.LightRush;
import ai.abstraction.pathfinding.BFSPathFinding;
import rts.*;
import rts.units.UnitTypeTable;

/**
 * Headless match runner for Chimera.
 *
 *   java chimera.Match [map] [opponent] [games]
 *     map       default maps/8x8/basesWorkers8x8.xml
 *     opponent  WorkerRush | LightRush | RandomBiased   (default WorkerRush)
 *     games     number of games, sides swapped each game to cancel P0 advantage (default 2)
 *
 * Reports Chimera's win/draw/loss tally and the worst-case per-cycle decision time — an early
 * read on the G1 gate (must stay < 100 ms/cycle). This is the seed of the Phase-2 evaluation
 * harness; swapping in Coac/mayari (lib/bots) as the opponent is a one-line change later.
 */
public class Match {

    static final int MAXCYCLES = 5000;

    public static void main(String[] args) throws Exception {
        String map      = args.length > 0 ? args[0] : "maps/8x8/basesWorkers8x8.xml";
        String opponent = args.length > 1 ? args[1] : "WorkerRush";
        int games       = args.length > 2 ? Integer.parseInt(args[2]) : 2;

        UnitTypeTable utt = new UnitTypeTable();
        int wins = 0, draws = 0, losses = 0;
        long worstCycleNanos = 0;

        for (int g = 0; g < games; g++) {
            boolean chimeraIsP0 = (g % 2 == 0);
            PhysicalGameState pgs = PhysicalGameState.load(map, utt);
            GameState gs = new GameState(pgs, utt);

            AI chimera = new Chimera(utt);
            AI opp = makeOpponent(opponent, utt);
            AI p0 = chimeraIsP0 ? chimera : opp;
            AI p1 = chimeraIsP0 ? opp : chimera;

            boolean over = false;
            while (!over && gs.getTime() < MAXCYCLES) {
                long t0 = System.nanoTime();
                PlayerAction a0 = p0.getAction(0, gs);
                PlayerAction a1 = p1.getAction(1, gs);
                long dt = System.nanoTime() - t0;
                worstCycleNanos = Math.max(worstCycleNanos, dt);

                gs.issueSafe(a0);
                gs.issueSafe(a1);
                over = gs.cycle();
            }

            int winner = gs.winner();                       // -1 draw, else player id
            int chimeraId = chimeraIsP0 ? 0 : 1;
            if (winner == -1) draws++;
            else if (winner == chimeraId) wins++;
            else losses++;

            System.out.printf("game %d: Chimera as P%d -> %s (%d cycles)%n",
                    g, chimeraId,
                    winner == -1 ? "draw" : (winner == chimeraId ? "WIN" : "loss"),
                    gs.getTime());
        }

        System.out.printf("%n=== Chimera vs %s on %s ===%n", opponent, map);
        System.out.printf("W-D-L: %d-%d-%d over %d games%n", wins, draws, losses, games);
        System.out.printf("worst per-cycle decision time (both bots): %.2f ms  [G1 budget = 100 ms]%n",
                worstCycleNanos / 1_000_000.0);
    }

    private static AI makeOpponent(String name, UnitTypeTable utt) {
        switch (name) {
            case "LightRush":    return new LightRush(utt, new BFSPathFinding());
            case "RandomBiased": return new RandomBiasedAI(utt);
            case "WorkerRush":
            default:             return new WorkerRush(utt, new BFSPathFinding());
        }
    }
}
