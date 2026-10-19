package eval;

import ai.RandomBiasedAI;
import ai.core.AI;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.UnitTypeTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Check that bots resolve AND play: construct each by name through eval.Bots, then play it
 * as player 0 against RandomBiasedAI for a few hundred cycles. Reports the resolved class,
 * the cycle reached, the winner (-1 = unfinished or draw) and the worst decision time.
 *
 *   java -cp "out;lib/*;lib/bots/*" eval.ProbeBots Coac mayari Tiamat ... [-map m.xml] [-cycles N]
 */
public final class ProbeBots {

    private ProbeBots() {}

    public static void main(String[] args) {
        String map = "maps/8x8/basesWorkers8x8A.xml";
        int cycles = 300;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-map")) map = args[++i];
            else if (args[i].equals("-cycles")) cycles = Integer.parseInt(args[++i]);
            else names.add(args[i]);
        }
        for (String n : names) {
            UnitTypeTable utt = new UnitTypeTable();
            AI bot;
            try {
                bot = Bots.make(n, utt);
            } catch (Throwable e) {
                System.out.printf("%-18s CONSTRUCT FAILED: %s%n", n, firstLine(e));
                continue;
            }
            try {
                GameState gs = new GameState(PhysicalGameState.load(map, utt), utt);
                AI opp = new RandomBiasedAI();
                bot.reset();
                opp.reset();
                long worst = 0;
                boolean over = false;
                while (!over && gs.getTime() < cycles) {
                    long t0 = System.nanoTime();
                    PlayerAction a0 = bot.getAction(0, gs);
                    worst = Math.max(worst, (System.nanoTime() - t0) / 1_000_000);
                    PlayerAction a1 = opp.getAction(1, gs);
                    gs.issueSafe(a0);
                    gs.issueSafe(a1);
                    over = gs.cycle();
                }
                System.out.printf("%-18s OK  %-45s cycles %4d  winner %2d  worst %4d ms%n",
                        n, bot.getClass().getName(), gs.getTime(), over ? gs.winner() : -1, worst);
            } catch (Throwable e) {
                System.out.printf("%-18s PLAY FAILED (%s): %s%n", n, bot.getClass().getName(), firstLine(e));
            }
        }
    }

    private static String firstLine(Throwable e) {
        String m = String.valueOf(e);
        int i = m.indexOf('\n');
        return i < 0 ? m : m.substring(0, i);
    }
}