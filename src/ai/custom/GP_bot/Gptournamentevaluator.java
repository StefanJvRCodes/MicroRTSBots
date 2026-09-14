package ai.custom.GP_bot;

import ai.core.AI;
import rts.units.UnitTypeTable;
import tournaments.FixedOpponentsTournament;
import tournaments.RoundRobinTournament;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

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
    private static final String FIXED_OPPONENTS_READ_WRITE_FOLDER = "gp_tournament_rw/fixed_opponents";

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
                                                        int iterationsBudget) throws Exception {
        if (bots == null || bots.isEmpty()) {
            return new double[0];
        }

        ensureFolderExists(SELF_PLAY_READ_WRITE_FOLDER);

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
                null,               // traceOutputfolder: don't write game traces
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
     */
    public static double[] evaluateFixedOpponentsWinRates(List<AI> bots,
                                                            List<AI> opponents,
                                                            List<String> maps,
                                                            UnitTypeTable utt,
                                                            int iterations,
                                                            int maxGameLength,
                                                            int timeBudget,
                                                            int iterationsBudget) throws Exception {
        if (bots == null || bots.isEmpty()) {
            return new double[0];
        }
        if (opponents == null || opponents.isEmpty()) {
            double[] undefined = new double[bots.size()];
            Arrays.fill(undefined, Double.NaN);
            return undefined;
        }

        ensureFolderExists(FIXED_OPPONENTS_READ_WRITE_FOLDER);

        FixedOpponentsTournament tournament = new FixedOpponentsTournament(bots, opponents);
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
                null,               // traceOutputfolder
                new NullWriter(),
                new NullWriter(),
                FIXED_OPPONENTS_READ_WRITE_FOLDER);

        return tournament.getAverageWinRates();
    }

    private static void ensureFolderExists(String path) {
        File folder = new File(path);
        if (!folder.exists()) {
            folder.mkdirs();
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