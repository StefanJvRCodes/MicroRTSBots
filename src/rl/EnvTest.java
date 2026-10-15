package rl;

import bots.EvolvedBot;
import eval.Bots;
import eval.Panel;
import gp.IndividualLoader;
import rts.units.UnitTypeTable;

import java.util.Random;

/**
 * Checks AdapterEnv before Python depends on it.
 *
 * 1. IDENTITY: driving the env with the base codes must reproduce Panel.play exactly
 *    (same winner, same final cycle) on every training map x deterministic opponent x side.
 * 2. RANDOM: uniformly random masked codes; games must complete with 0 illegal requests,
 *    0 unencodable actions and 0 duplicate codes. Reports Java-side decision steps/s.
 *
 * usage: java -cp "out;lib/*;lib/bots/*" rl.EnvTest [path.ind]
 */
public final class EnvTest {

    private EnvTest() {}

    static final String[] TRAIN_MAPS = {
        "maps/8x8/basesWorkers8x8.xml", "maps/8x8/basesWorkers8x8C.xml", "maps/8x8/basesWorkers8x8E.xml",
        "maps/16x16/basesWorkers16x16B.xml", "maps/16x16/basesWorkers16x16D.xml",
        "maps/16x16/basesWorkers16x16G.xml", "maps/16x16/basesWorkers16x16F.xml",
        "maps/16x16/basesWorkers16x16H.xml",
    };

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "results/best-seed5242-20261012-160727-pruned.ind";
        String[] det = {"WorkerRush", "LightRush", "WorkerDefense", "PassiveAI"};
        int maxCycles = 4000;

        AdapterEnv env = new AdapterEnv(spec, maxCycles);

        // ---- 1. identity ----
        int games = 0, mismatches = 0;
        for (String map : TRAIN_MAPS) {
            for (String opp : det) {
                for (int side = 0; side < 2; side++) {
                    env.reset(map, opp, side);
                    while (!env.done()) env.step(env.baseCode());

                    UnitTypeTable utt = new UnitTypeTable();
                    EvolvedBot ref = IndividualLoader.makeBot(spec, utt);
                    Panel.Result r = Panel.play(ref, Bots.make(opp, utt), map, utt, side, maxCycles);
                    boolean same = r.winner == env.winner() && r.cyclesTaken == env.time();
                    if (!same) {
                        mismatches++;
                        System.out.println("MISMATCH " + map + " vs " + opp + " side " + side
                                + ": env winner " + env.winner() + " @" + env.time()
                                + ", Panel winner " + r.winner + " @" + r.cyclesTaken);
                    }
                    games++;
                }
            }
        }
        System.out.println("1 identity : " + games + " games, " + mismatches + " mismatches");
        System.out.println("             " + env.diagnostics());
        boolean identityOk = mismatches == 0 && env.overrides == 0 && env.illegalRequests == 0
                && env.unencodable == 0 && env.duplicateCodes == 0;

        // ---- 2. random ----
        String[] opps = {"WorkerRush", "LightRush", "RandomBiasedAI"};
        Random rnd = new Random(20261014L);
        long illegal0 = env.illegalRequests, unenc0 = env.unencodable, dup0 = env.duplicateCodes;
        long steps0 = env.steps;
        int rgames = 0, rwins = 0;
        long t0 = System.nanoTime();
        for (String map : TRAIN_MAPS) {
            for (String opp : opps) {
                for (int side = 0; side < 2; side++) {
                    env.reset(map, opp, side);
                    while (!env.done()) {
                        int cells = env.width() * env.height();
                        int[] codes = new int[cells];
                        int[] bc = env.baseCode();
                        byte[] m = env.mask();
                        for (int c = 0; c < cells; c++) {
                            codes[c] = -1;
                            if (bc[c] < 0) continue;
                            int count = 0;
                            for (int a = 0; a < ActionCodec.N; a++) if (m[c * ActionCodec.N + a] != 0) count++;
                            int pickN = rnd.nextInt(count);
                            for (int a = 0; a < ActionCodec.N; a++) {
                                if (m[c * ActionCodec.N + a] != 0 && pickN-- == 0) { codes[c] = a; break; }
                            }
                        }
                        float[] o = env.obs();
                        for (float v : o) if (Float.isNaN(v) || Float.isInfinite(v)) throw new IllegalStateException("non-finite obs");
                        env.step(codes);
                    }
                    if (env.winner() == side) rwins++;
                    rgames++;
                }
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        long rsteps = env.steps - steps0;
        System.out.println("2 random   : " + rgames + " games, " + rwins + " wins, " + rsteps
                + " decision steps in " + String.format("%.2f", secs) + " s ("
                + String.format("%.0f", rsteps / secs) + " steps/s in Java)");
        System.out.println("             illegal " + (env.illegalRequests - illegal0)
                + ", unencodable " + (env.unencodable - unenc0)
                + ", duplicate codes " + (env.duplicateCodes - dup0));
        boolean randomOk = env.illegalRequests == illegal0 && env.unencodable == unenc0
                && env.duplicateCodes == dup0;

        System.out.println(identityOk && randomOk ? "ENV OK" : "ENV BROKEN");
        if (!(identityOk && randomOk)) System.exit(1);
    }
}