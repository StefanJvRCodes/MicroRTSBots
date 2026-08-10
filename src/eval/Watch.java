package eval;

import ai.core.AI;
import gui.PhysicalGameStatePanel;
import gui.PhysicalGameStateJFrame;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.UnitTypeTable;

/**
 * Watch two bots play, using the engine's own renderer.
 *
 *   java eval.Watch [map] [bot0] [bot1] [msPerCycle] [maxCycles]
 *
 * Deliberately a SEPARATE entry point from the headless runner: evolution must
 * never load Swing/AWT classes, and this loop is intentionally slowed down,
 * which would be poison inside a fitness evaluation.
 *
 * Timing note: the per-cycle decision time printed at the end EXCLUDES the
 * artificial sleep, so it stays a meaningful read on the G1 100 ms gate --
 * though the headless harness remains the number you should quote.
 */
public class Watch {

    public static void main(String[] args) throws Exception {
        String map   = args.length > 0 ? args[0] : "maps/8x8/basesWorkers8x8.xml";
        String n0    = args.length > 1 ? args[1] : "chimera";
        String n1    = args.length > 2 ? args[2] : "workerrush";
        int delayMs  = args.length > 3 ? Integer.parseInt(args[3]) : 50;
        int maxArg   = args.length > 4 ? Integer.parseInt(args[4]) : -1;

        UnitTypeTable utt = new UnitTypeTable();
        PhysicalGameState pgs = PhysicalGameState.load(map, utt);
        GameState gs = new GameState(pgs, utt);

        // Competition default: 8x8 -> 3000, larger maps get more room.
        int maxCycles = (maxArg > 0)
                ? maxArg
                : (Math.max(pgs.getWidth(), pgs.getHeight()) <= 8 ? 3000 : 5000);

        AI ai0 = Bots.make(n0, utt);
        AI ai1 = Bots.make(n1, utt);
        ai0.reset();
        ai1.reset();

        System.out.println("map        : " + map);
        System.out.println("player 0   : " + n0 + "  (" + ai0.getClass().getName() + ")");
        System.out.println("player 1   : " + n1 + "  (" + ai1.getClass().getName() + ")");
        System.out.println("maxCycles  : " + maxCycles + "   delay: " + delayMs + " ms/cycle");
        System.out.println();

        PhysicalGameStateJFrame window =
                PhysicalGameStatePanel.newVisualizer(gs, 640, 640);
        window.setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        long worstNanos0 = 0, worstNanos1 = 0;
        boolean gameOver = false;

        while (!gameOver && gs.getTime() < maxCycles) {
            long t0 = System.nanoTime();
            PlayerAction pa0 = ai0.getAction(0, gs);
            long t1 = System.nanoTime();
            PlayerAction pa1 = ai1.getAction(1, gs);
            long t2 = System.nanoTime();

            worstNanos0 = Math.max(worstNanos0, t1 - t0);
            worstNanos1 = Math.max(worstNanos1, t2 - t1);

            gs.issueSafe(pa0);
            gs.issueSafe(pa1);
            gameOver = gs.cycle();

            window.setStateCloning(gs);
            window.repaint();

            if (delayMs > 0) Thread.sleep(delayMs);
        }

        ai0.gameOver(gs.winner());
        ai1.gameOver(gs.winner());

        int w = gs.winner();
        String result = (w == -1) ? "DRAW" : (w == 0 ? n0 + " (player 0) WINS" : n1 + " (player 1) WINS");

        System.out.println();
        System.out.println("cycles     : " + gs.getTime() + (gs.getTime() >= maxCycles ? "  (timeout)" : ""));
        System.out.println("result     : " + result);
        System.out.printf ("worst cycle: p0 %.1f ms   p1 %.1f ms   (G1 budget 100 ms)%n",
                worstNanos0 / 1e6, worstNanos1 / 1e6);
        System.out.println();
        System.out.println("Window stays open. Close it to exit.");
        // Deliberately no System.exit: the frame is EXIT_ON_CLOSE, so the final
        // board state stays on screen until you dismiss it.
    }
}
