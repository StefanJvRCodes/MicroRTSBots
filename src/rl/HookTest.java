package rl;

import ai.core.AI;
import bots.EvolvedBot;
import eval.Bots;
import gp.IndividualLoader;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.UnitAction;
import rts.units.Unit;
import rts.units.UnitTypeTable;
import util.Pair;

import java.util.List;
import java.util.Random;

/**
 * Checks the EvolvedBot adapter hook (propose/commit) before any RL code depends on it.
 *
 * A. IDENTITY: every cycle of every game, commit(base picks) must reproduce propose()'s
 *    PlayerAction exactly. The game is driven by the committed action, so any mismatch
 *    is reported at the cycle it happens.
 * B. RANDOM OVERRIDE: every unit takes a uniformly random legal action through commit().
 *    Games must complete without exceptions; counts how often affordability forced a
 *    fallback. This is the worst case an untrained adapter can produce.
 *
 * usage: java -cp "out;lib/*;lib/bots/*" rl.HookTest [path.ind]
 */
public final class HookTest {

    private HookTest() {}

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0]
                : "results/best-seed5242-20261012-160727-pruned.ind";
        String[] maps = {"maps/8x8/basesWorkers8x8.xml", "maps/16x16/basesWorkers16x16B.xml"};
        String[] opps = {"WorkerRush", "LightRush", "RandomBiasedAI"};
        int maxCycles = 4000;

        long cycles = 0, decisions = 0, mismatches = 0;
        long randDecisions = 0, randFallbacks = 0;
        int games = 0, randWins = 0;
        Random rnd = new Random(20261014L);

        for (String map : maps) {
            for (String opp : opps) {
                for (int side = 0; side < 2; side++) {
                    // ---- A: identity ----
                    UnitTypeTable utt = new UnitTypeTable();
                    EvolvedBot bot = IndividualLoader.makeBot(spec, utt);
                    AI them = Bots.make(opp, utt);
                    GameState gs = new GameState(PhysicalGameState.load(map, utt), utt);
                    bot.reset();
                    them.reset();
                    boolean over = false;
                    while (!over && gs.getTime() < maxCycles) {
                        EvolvedBot.Proposal p = bot.propose(side, gs);
                        PlayerAction viaCommit = EvolvedBot.commit(gs, side, p.decisions, null, null);
                        decisions += p.decisions.size();
                        if (!sameUnitActions(p.action, viaCommit)) {
                            mismatches++;
                            if (mismatches <= 5) {
                                System.out.println("MISMATCH " + map + " vs " + opp + " side " + side
                                        + " cycle " + gs.getTime() + "\n  propose: " + p.action
                                        + "\n  commit : " + viaCommit);
                            }
                        }
                        PlayerAction theirs = them.getAction(1 - side, gs);
                        if (side == 0) { gs.issueSafe(viaCommit); gs.issueSafe(theirs); }
                        else           { gs.issueSafe(theirs); gs.issueSafe(viaCommit); }
                        over = gs.cycle();
                        cycles++;
                    }
                    games++;

                    // ---- B: random override ----
                    utt = new UnitTypeTable();
                    bot = IndividualLoader.makeBot(spec, utt);
                    them = Bots.make(opp, utt);
                    gs = new GameState(PhysicalGameState.load(map, utt), utt);
                    bot.reset();
                    them.reset();
                    over = false;
                    while (!over && gs.getTime() < maxCycles) {
                        EvolvedBot.Proposal p = bot.propose(side, gs);
                        int n = p.decisions.size();
                        int[] chosen = new int[n];
                        int[] executed = new int[n];
                        for (int k = 0; k < n; k++) {
                            chosen[k] = rnd.nextInt(p.decisions.get(k).legal.size());
                        }
                        PlayerAction pa = EvolvedBot.commit(gs, side, p.decisions, chosen, executed);
                        randDecisions += n;
                        for (int k = 0; k < n; k++) if (executed[k] != chosen[k]) randFallbacks++;
                        PlayerAction theirs = them.getAction(1 - side, gs);
                        if (side == 0) { gs.issueSafe(pa); gs.issueSafe(theirs); }
                        else           { gs.issueSafe(theirs); gs.issueSafe(pa); }
                        over = gs.cycle();
                    }
                    if (gs.winner() == side) randWins++;
                }
            }
        }

        System.out.println();
        System.out.println("A identity : " + games + " games, " + cycles + " cycles, "
                + decisions + " unit decisions, " + mismatches + " mismatching cycles");
        System.out.println("B random   : " + games + " games completed, " + randDecisions
                + " unit decisions, " + randFallbacks + " affordability fallbacks, "
                + randWins + " wins (expect few)");
        System.out.println(mismatches == 0 ? "HOOK OK" : "HOOK BROKEN");
        if (mismatches > 0) System.exit(1);
    }

    static boolean sameUnitActions(PlayerAction a, PlayerAction b) {
        List<Pair<Unit, UnitAction>> x = a.getActions();
        List<Pair<Unit, UnitAction>> y = b.getActions();
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            if (x.get(i).m_a.getID() != y.get(i).m_a.getID()) return false;
            if (!x.get(i).m_b.toString().equals(y.get(i).m_b.toString())) return false;
        }
        return true;
    }
}