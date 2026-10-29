package ai.custom.GP_bot;

import ai.core.AI;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import rts.units.UnitTypeTable;
import tournaments.FixedOpponentsTournament;
import tournaments.RoundRobinTournament;

/**
 * Thin bridge between GP and the microRTS tournament machinery.
 *
 * <p>{@link #evaluateRoundRobinWinRates} plays a population against itself
 * (self-play). {@link #evaluateFixedOpponentsWinRates} plays a population
 * against a fixed, external roster of bots — e.g. loaded from JARs via
 * {@link GPOpponentLoader} — so evolution can be pushed against opponents
 * it can't simply co-evolve around.
 *
 * <p>Both {@link RoundRobinTournament#runTournament} and
 * {@link FixedOpponentsTournament#runTournament} unconditionally write to
 * their {@code out} argument, and unconditionally call
 * {@code progress.flush()} at the very end regardless of whether progress
 * logging was requested, so both writers must be non-null. Since GP calls
 * these once per generation, both are backed by a no-op {@link Writer}
 * rather than a file, to avoid flooding disk with tournament logs nobody
 * reads.
 */
public class GPTournamentEvaluator {

    /**
     * Directories under which each bot gets a scratch read/write folder.
     * The tournament classes create these unconditionally (even when
     * pre-analysis is disabled), so they must be valid, writable paths.
     */
    private static final String SELF_PLAY_READ_WRITE_FOLDER = "gp_tournament_rw/self_play";
    /** Base path GP builds unique per-opponent subfolders under when evaluating opponents concurrently. */
    public static final String FIXED_OPPONENTS_READ_WRITE_FOLDER_BASE = "gp_tournament_rw/fixed_opponents";

    /**
     * Runs a single round-robin tournament among {@code bots} and returns
     * each bot's average win rate (ties count as half a win), indexed the
     * same way as {@code bots}. A bot's win rate is {@code NaN} if it never
     * played a game (e.g. a population of size 1).
     */
    public static double[] evaluateRoundRobinWinRates(List<AI> bots,
                                                        List<String> maps,
                                                        UnitTypeTable utt,
                                                        int iterations,
                                                        int maxGameLength,
                                                        int timeBudget,
                                                        int iterationsBudget,
                                                        String traceOutputfolder) throws Exception {
        if (bots == null || bots.isEmpty()) {
            return new double[0];
        }

        ensureFolderExists(SELF_PLAY_READ_WRITE_FOLDER);
        if (traceOutputfolder != null) {
            ensureFolderExists(traceOutputfolder);
        }

        RoundRobinTournament tournament = new RoundRobinTournament(bots);
        tournament.runTournament(
                -1,                 // playOnlyGamesInvolvingThisAI: -1 = play every match-up
                maps,
                iterations,
                maxGameLength,
                timeBudget,
                iterationsBudget,
                0L,                 // preAnalysisBudgetFirstTimeInAMap (unused, preAnalysis disabled below)
                0L,                 // preAnalysisBudgetRestOfTimes
                true,               // fullObservability
                false,              // selfMatches: skip a bot playing itself
                false,              // timeoutCheck: GP trees evaluate near-instantly
                false,              // runGC: don't force a GC between every decision
                false,              // preAnalysis: GP trees don't do pre-game analysis
                utt,
                traceOutputfolder,  // traceOutputfolder: save traces to verify games are being played
                new NullWriter(),
                new NullWriter(),
                SELF_PLAY_READ_WRITE_FOLDER);

        return tournament.getAverageWinRates();
    }

    /**
     * Plays every bot in {@code bots} against every bot in {@code opponents}
     * (a fixed, external roster - e.g. loaded via
     * {@link GPOpponentLoader#loadOpponentsFromFolder}) and returns each
     * bot's average win rate against that roster, indexed the same way as
     * {@code bots}.
     *
     * <p>If {@code opponents} is empty, every entry in the returned array is
     * {@code NaN} (no games were played, so no rate is defined) rather than
     * throwing, since "no fixed opponents configured" is an expected,
     * ordinary state.
     *
     * <p>{@code readWriteFolder} lets each caller give its own call a unique
     * scratch folder. This matters once GP is evaluating multiple opponents
     * concurrently (see {@code GP.evaluateFixedOpponentsWithRecovery}): every
     * such call always has exactly one opponent, at local index 0, so
     * without a unique folder per call, concurrently-running evaluations for
     * different opponents would all read and write the same
     * {@code opponentAI0readWriteFolder} path at the same time.
     */
    public static double[] evaluateFixedOpponentsWinRates(List<AI> bots,
                                                            List<AI> opponents,
                                                            List<String> maps,
                                                            UnitTypeTable utt,
                                                            int iterations,
                                                            int maxGameLength,
                                                            int timeBudget,
                                                            int iterationsBudget,
                                                            String readWriteFolder,
                                                            String traceOutputfolder) throws Exception {
        if (bots == null || bots.isEmpty()) {
            return new double[0];
        }
        if (opponents == null || opponents.isEmpty()) {
            double[] undefined = new double[bots.size()];
            Arrays.fill(undefined, Double.NaN);
            return undefined;
        }

        ensureFolderExists(readWriteFolder);
        if (traceOutputfolder != null) {
            ensureFolderExists(traceOutputfolder);
        }

        FixedOpponentsTournament tournament = new FixedOpponentsTournament(bots, opponents);
        String label = "[GPTournamentEvaluator:" + Thread.currentThread().getName() + "]";
        System.out.println(label + " Starting fixed-opponent tournament vs " + describe(opponents));
        System.out.println(label + " GP bots: " + bots.size() + ", opponents: " + opponents.size()
                + ", maps: " + maps.size() + ", iterations: " + iterations + ", max game length: " + maxGameLength);
        // Per-game results go to <readWriteFolder>/game_results.tsv (appended
        // every generation, one small line per game) and are tallied for the
        // console summary below; the engine's per-game progress log (matchup,
        // winner, cycles, timing, and the stack trace of any bot that threw
        // from getAction) goes to <readWriteFolder>/latest_progress.log,
        // overwritten each call so it only ever holds the latest generation.
        // readWriteFolder is unique per opponent, so concurrent evaluations
        // never share these files.
        GameLog gameLog = new GameLog(new File(readWriteFolder, "game_results.tsv"), describe(opponents));
        Writer progressLog = new FileWriter(new File(readWriteFolder, "latest_progress.log"), false);
        try {
            tournament.runTournament(
                    maps,
                    iterations,
                    maxGameLength,
                    timeBudget,
                    iterationsBudget,
                    0L,                 // preAnalysisBudgetFirstTimeInAMap
                    0L,                 // preAnalysisBudgetRestOfTimes
                    true,               // fullObservability
                    false,              // timeoutCheck
                    false,              // runGC
                    false,              // preAnalysis
                    utt,
                    traceOutputfolder,  // was hard-coded null, so the trace folder GP passes in stayed empty
                    gameLog,
                    progressLog,
                    readWriteFolder);
        } finally {
            gameLog.close();
            progressLog.close();
        }
        System.out.println(label + " Fixed-opponent tournament FINISHED vs " + describe(opponents));
        System.out.println(label + " " + gameLog.summary());

        return tournament.getAverageWinRates();
    }

    /**
     * Parallel version of {@link #evaluateRoundRobinWinRates}: every pair of
     * bots is its own small round robin (both sides, every map) submitted to
     * {@code pool}. Each pair plays the same number of games, so a bot's
     * mean over its pairwise win rates equals its win rate over the whole
     * round robin. Traces go to {@code traceOutputfolder/<i>-vs-<j>/}, since
     * the engine names trace files by in-tournament index and every pair
     * would otherwise overwrite "0-vs-1-...".
     */
    public static double[] evaluateRoundRobinWinRatesParallel(List<AI> bots,
                                                                List<String> maps,
                                                                UnitTypeTable utt,
                                                                int iterations,
                                                                int maxGameLength,
                                                                int timeBudget,
                                                                int iterationsBudget,
                                                                String traceOutputfolder,
                                                                ExecutorService pool) throws Exception {
        int n = bots == null ? 0 : bots.size();
        List<int[]> allPairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                allPairs.add(new int[] {i, j});
            }
        }
        return evaluatePairsWinRatesParallel(bots, allPairs, maps, utt, iterations, maxGameLength, timeBudget,
                iterationsBudget, traceOutputfolder, pool);
    }

    /**
     * Like {@link #evaluateRoundRobinWinRatesParallel} but plays only the
     * given pairs {i, j} (i &lt; j). A bot's win rate is the mean over the
     * pairs it is in, so it stays comparable when bots play different
     * numbers of pairs; a bot in no pair gets NaN.
     */
    public static double[] evaluatePairsWinRatesParallel(List<AI> bots,
                                                         List<int[]> pairList,
                                                         List<String> maps,
                                                         UnitTypeTable utt,
                                                         int iterations,
                                                         int maxGameLength,
                                                         int timeBudget,
                                                         int iterationsBudget,
                                                         String traceOutputfolder,
                                                         ExecutorService pool) throws Exception {
        int n = bots == null ? 0 : bots.size();
        if (n == 0) {
            return new double[0];
        }
        List<int[]> pairs = new ArrayList<>();
        List<Future<double[]>> futures = new ArrayList<>();
        for (int[] ij : pairList) {
            int i = ij[0];
            int j = ij[1];
            List<AI> pair = new ArrayList<>(Arrays.asList(bots.get(i), bots.get(j)));
            String trace = traceOutputfolder == null ? null : traceOutputfolder + "/" + i + "-vs-" + j;
            pairs.add(new int[] {i, j});
            futures.add(pool.submit(() -> evaluateRoundRobinWinRates(
                    pair, maps, utt, iterations, maxGameLength, timeBudget, iterationsBudget, trace)));
        }

        double[] sum = new double[n];
        int[] count = new int[n];
        try {
            for (int k = 0; k < futures.size(); k++) {
                double[] rates = futures.get(k).get();
                int[] pair = pairs.get(k);
                for (int side = 0; side < 2; side++) {
                    if (Double.isFinite(rates[side])) {
                        sum[pair[side]] += rates[side];
                        count[pair[side]]++;
                    }
                }
            }
        } catch (Exception e) {
            cancelAll(futures);
            throw e;
        }

        double[] winRates = new double[n];
        for (int i = 0; i < n; i++) {
            winRates[i] = count[i] == 0 ? Double.NaN : sum[i] / count[i];
        }
        return winRates;
    }

    /** One individual's games against one opponent, buffered so results can be merged in order afterwards. */
    private static final class IndividualResult {
        final double winRate;
        final String gameRecords;
        final String progress;

        IndividualResult(double winRate, String gameRecords, String progress) {
            this.winRate = winRate;
            this.gameRecords = gameRecords;
            this.progress = progress;
        }
    }

    /**
     * Parallel version of {@link #evaluateFixedOpponentsWinRates} for a
     * single opponent: each individual's games against it are a separate
     * task on {@code pool}, so a slow opponent's ~100 games per generation
     * spread across every core instead of running back to back on one
     * thread. Each task gets its own scratch and trace subfolder
     * ({@code ind<i>}); game records and progress logs are buffered per
     * task and written to {@code readWriteFolder} in individual order once
     * all finish, so the files and the console summary look the same as the
     * sequential version. If any task throws, the rest are cancelled and the
     * exception propagates, so GP can drop the opponent as before.
     */
    public static double[] evaluateFixedOpponentWinRatesParallel(List<AI> bots,
                                                                   AI opponent,
                                                                   List<String> maps,
                                                                   UnitTypeTable utt,
                                                                   int iterations,
                                                                   int maxGameLength,
                                                                   int timeBudget,
                                                                   int iterationsBudget,
                                                                   String readWriteFolder,
                                                                   String traceOutputfolder,
                                                                   ExecutorService pool) throws Exception {
        if (bots == null || bots.isEmpty()) {
            return new double[0];
        }
        ensureFolderExists(readWriteFolder);
        String label = "[GPTournamentEvaluator:" + Thread.currentThread().getName() + "]";
        System.out.println(label + " Starting fixed-opponent tournament vs " + opponent + " ("
                + bots.size() + " GP bots in parallel, " + maps.size() + " maps)");

        List<Future<IndividualResult>> futures = new ArrayList<>();
        for (int i = 0; i < bots.size(); i++) {
            AI bot = bots.get(i);
            String taskFolder = readWriteFolder + "/ind" + i;
            String trace = traceOutputfolder == null ? null : traceOutputfolder + "/ind" + i;
            futures.add(pool.submit(() -> {
                ensureFolderExists(taskFolder);
                if (trace != null) {
                    ensureFolderExists(trace);
                }
                StringWriter records = new StringWriter();
                StringWriter progress = new StringWriter();
                // Own instance per task: clones of a shared instance can share
                // mutable internals (see GPOpponentLoader.freshInstance).
                AI taskOpponent = GPOpponentLoader.freshInstance(opponent, utt);
                FixedOpponentsTournament tournament = new FixedOpponentsTournament(
                        new ArrayList<>(Arrays.asList(bot)), new ArrayList<>(Arrays.asList(taskOpponent)));
                tournament.runTournament(maps, iterations, maxGameLength, timeBudget, iterationsBudget,
                        0L, 0L, true, false, false, false, utt, trace, records, progress, taskFolder);
                return new IndividualResult(tournament.getAverageWinRates()[0], records.toString(), progress.toString());
            }));
        }

        List<IndividualResult> results = new ArrayList<>();
        try {
            for (Future<IndividualResult> future : futures) {
                results.add(future.get());
            }
        } catch (Exception e) {
            cancelAll(futures);
            throw e;
        }

        double[] winRates = new double[bots.size()];
        GameLog gameLog = new GameLog(new File(readWriteFolder, "game_results.tsv"), String.valueOf(opponent));
        try (Writer progressLog = new FileWriter(new File(readWriteFolder, "latest_progress.log"), false)) {
            for (int i = 0; i < results.size(); i++) {
                IndividualResult result = results.get(i);
                winRates[i] = result.winRate;
                // Keep only the per-game records (each task's output also has
                // the engine's header and summary tables), with ai1 rewritten
                // to this individual's population index - in its own task it
                // was always index 0.
                for (String record : result.gameRecords.split("\n")) {
                    String[] f = record.trim().split("\t", -1);
                    if (f.length >= 8 && f[0].matches("\\d+")) {
                        f[2] = String.valueOf(i);
                        gameLog.write(String.join("\t", f) + "\n");
                    }
                }
                progressLog.write("=== GP individual " + i + " ===\n" + result.progress);
            }
        } finally {
            gameLog.close();
        }
        System.out.println(label + " Fixed-opponent tournament FINISHED vs " + opponent);
        System.out.println(label + " " + gameLog.summary());
        return winRates;
    }

    private static void cancelAll(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    private static String describe(List<AI> opponents) {
        if (opponents.size() == 1) {
            return String.valueOf(opponents.get(0));
        }
        return opponents.size() + " opponents";
    }

    private static void ensureFolderExists(String path) {
        File folder = new File(path);
        if (!folder.exists()) {
            folder.mkdirs();
        }
    }

    /**
     * Receives the engine's one-line-per-game result record
     * ({@code iteration, map, ai1, ai2, cycles, winner, crashed, timedout},
     * tab-separated), appends it to a file, and tallies the outcomes.
     *
     * <p>In a fixed-opponent tournament ai1 is always the GP bot (player 0)
     * and ai2 the opponent (player 1), so winner 0 = GP bot won, 1 = opponent
     * won, -1 = draw (hit the game-length cap). {@code crashed} is 0 or 1 for
     * the side whose getAction threw an Exception - the engine scores that as
     * a forfeit, so a high crash count means games weren't really played to a
     * conclusion, whatever the win rate says.
     */
    private static final class GameLog extends Writer {
        private final Writer file;
        private final StringBuilder line = new StringBuilder();
        private int games, gpWins, opponentWins, draws, gpCrashes, opponentCrashes;
        private long totalCycles;

        GameLog(File path, String opponentLabel) throws IOException {
            this.file = new FileWriter(path, true);
            file.write("# " + new java.util.Date() + " vs " + opponentLabel
                    + "   columns: iteration map ai1(GP) ai2(opponent) cycles winner crashed timedout\n");
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            file.write(cbuf, off, len);
            for (int i = off; i < off + len; i++) {
                if (cbuf[i] == '\n') {
                    tally(line.toString());
                    line.setLength(0);
                } else {
                    line.append(cbuf[i]);
                }
            }
        }

        private void tally(String record) {
            String[] f = record.trim().split("\t");
            if (f.length < 8) {
                return;
            }
            try {
                games++;
                totalCycles += Long.parseLong(f[4]);
                int winner = Integer.parseInt(f[5]);
                int crashed = Integer.parseInt(f[6]);
                if (winner == 0) gpWins++;
                else if (winner == 1) opponentWins++;
                else draws++;
                if (crashed == 0) gpCrashes++;
                if (crashed == 1) opponentCrashes++;
            } catch (NumberFormatException e) {
                games--; // not a result record; ignore
            }
        }

        String summary() {
            double avgCycles = games == 0 ? 0.0 : (double) totalCycles / games;
            return "games played: " + games + " | GP bot won " + gpWins + ", opponent won " + opponentWins
                    + ", draws " + draws + " | forfeits by crash - GP bot: " + gpCrashes
                    + ", opponent: " + opponentCrashes + " | avg length " + String.format("%.0f", avgCycles) + " cycles";
        }

        @Override
        public void flush() throws IOException {
            file.flush();
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    /** A {@link Writer} that discards everything written to it. */
    private static final class NullWriter extends Writer {
        @Override
        public void write(char[] cbuf, int off, int len) {
            // discard
        }

        @Override
        public void flush() throws IOException {
            // nothing to flush
        }

        @Override
        public void close() throws IOException {
            // nothing to close
        }
    }
}