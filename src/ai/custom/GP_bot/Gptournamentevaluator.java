package ai.custom;

import ai.core.AI;
import rts.units.UnitTypeTable;
import tournaments.RoundRobinTournament;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Thin bridge between GP and the microRTS {@link RoundRobinTournament}
 * machinery: runs every bot in {@code bots} against every other bot
 * (self-matches excluded) and returns each bot's win rate, in the same
 * order the bots were supplied in.
 *
 * {@link RoundRobinTournament#runTournament} unconditionally writes to its
 * {@code out} argument, and unconditionally calls {@code progress.flush()}
 * at the very end regardless of whether progress logging was requested, so
 * both writers must be non-null. Since GP calls this once per individual
 * per generation, both are backed by a no-op {@link Writer} rather than a
 * file, to avoid flooding disk with tournament logs nobody reads.
 */
public class GPTournamentEvaluator {

    /**
     * Directory under which each bot gets a scratch read/write folder.
     * RoundRobinTournament creates these unconditionally (even when
     * pre-analysis is disabled), so it must be a valid, writable path.
     */
    private static final String READ_WRITE_FOLDER = "gp_tournament_rw";

    /**
     * Runs a single round-robin tournament among {@code bots} and returns
     * each bot's average win rate (ties count as half a win), indexed the
     * same way as {@code bots}. A bot's win rate is {@code NaN} if it never
     * played a game (e.g. a population of size 1).
     *
     * @param bots             the individuals to evaluate; also serve as
     *                         each other's opponents
     * @param maps             maps to play the round robin on
     * @param utt              unit type table for the game
     * @param iterations       how many times to repeat the full round robin
     *                         on each map
     * @param maxGameLength    cycle cap before a game is declared a tie
     * @param timeBudget       per-decision time budget in ms
     * @param iterationsBudget per-decision search-iterations budget
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

        ensureReadWriteFolderExists();

        RoundRobinTournament tournament = new RoundRobinTournament(bots);
        Writer out = new NullWriter();
        Writer progress = new NullWriter();

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
                out,
                progress,
                READ_WRITE_FOLDER);

        return tournament.getAverageWinRates();
    }

    private static void ensureReadWriteFolderExists() {
        java.io.File folder = new java.io.File(READ_WRITE_FOLDER);
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