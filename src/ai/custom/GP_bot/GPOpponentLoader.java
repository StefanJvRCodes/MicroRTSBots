package ai.custom.GP_bot;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import rts.GameState;
import rts.PhysicalGameState;
import rts.units.UnitTypeTable;
import tournaments.LoadTournamentAIs;

/**
 * Loads fixed opponent bots from a folder of JAR files, so GP fitness can
 * include matches against real, external bots (e.g. previous microRTS
 * competition winners) rather than only self-play.
 *
 * <h2>How to add opponents</h2>
 * Drop the bot JAR(s) into the configured opponents folder (default:
 * {@code opponents/} next to where GP is run from). Nothing needs to be
 * extracted or unzipped — {@link #loadOpponentsFromFolder} scans every
 * {@code .jar} directly in that folder (not subfolders) for classes that
 * extend {@code AIWithComputationBudget}, and instantiates one instance of
 * each. Most competition bots expose a {@code (UnitTypeTable)} constructor;
 * a no-arg constructor is tried as a fallback. A bot whose class can't be
 * instantiated either way is skipped with a message on stdout rather than
 * failing the whole run — one broken/incompatible JAR shouldn't stop GP.
 *
 * <p>Some bots construct fine but only fail once they actually play - e.g. a
 * jar that bundles its own copy of some {@code ai.abstraction.*} classes
 * while other classes of the same name resolve from this project's own
 * classpath causes an {@code IllegalAccessError} the moment the bot tries to
 * act, not when it's built. And some bots (or their static initializers)
 * simply never return at all - most often a search-based bot that treats an
 * unset/negative time or iteration budget as "search indefinitely" - which
 * throws nothing to catch and would otherwise hang the whole loader (and
 * the whole GP run) forever. To catch all of that before it can affect a
 * real tournament, constructing a candidate class AND giving it one trial
 * {@code getAction()} call against a real game state (see
 * {@link #buildSmokeTestState}) both happen together as a single task with
 * a hard wall-clock timeout around it; a bot that throws anything, or that
 * simply doesn't finish both steps in time, is dropped the same way as any
 * other load failure.
 */
public class GPOpponentLoader {

    /** Budget given to a bot for its one trial decision - matches what a real tournament configures. */
    private static final int SMOKE_TEST_TIME_BUDGET_MS = 100;
    private static final int SMOKE_TEST_ITERATIONS_BUDGET = 100;

    /**
     * Hard wall-clock cap on constructing a candidate bot AND running its
     * one trial decision, combined - well above the budget above to allow
     * for JVM/JIT warmup slack, in case a bot's constructor or its decision
     * logic ignores its configured budget entirely (a bug in the bot, not
     * in this loader).
     */
    private static final long LOAD_TIMEOUT_MS = 5000;

    private GPOpponentLoader() {
    }

    public static List<AI> loadOpponentsFromFolder(String folderPath, UnitTypeTable utt, List<String> maps) {
        List<AI> opponents = new ArrayList<>();

        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("[GPOpponentLoader] Opponent folder \"" + folderPath
                    + "\" not found - running without fixed opponents. Create it and drop bot JARs in to use this feature.");
            return opponents;
        }

        List<Class> classes;
        try {
            classes = LoadTournamentAIs.loadTournamentAIsFromFolder(folderPath);
        } catch (Exception e) {
            System.out.println("[GPOpponentLoader] Failed to scan \"" + folderPath + "\" for opponent JARs: " + e);
            return opponents;
        }

        GameState smokeTestState = buildSmokeTestState(maps, utt);

        for (Class<?> c : classes) {
            AI ai = tryLoadOpponent(c, utt, smokeTestState);
            if (ai != null) {
                opponents.add(ai);
                System.out.println("[GPOpponentLoader] Loaded opponent: " + c.getName());
            }
        }

        if (opponents.isEmpty()) {
            System.out.println("[GPOpponentLoader] No usable AI classes found in \"" + folderPath + "\".");
        }
        return opponents;
    }

    /**
     * Constructs one candidate bot and, if a trial game state is available,
     * gives it one real decision to make - both steps run as a single task
     * on its own daemon thread with a hard timeout around the whole thing,
     * since a hang can happen during construction (a blocking static
     * initializer, a bot spawning its own thread, etc.) just as easily as
     * during the decision itself. Only catches what happens here, at load
     * time - a bot that passes this but crashes or hangs later, mid-game,
     * on a different map or after many turns isn't covered by this check.
     */
    private static AI tryLoadOpponent(Class<?> c, UnitTypeTable utt, GameState smokeTestState) {
        ExecutorService executor = newDaemonSingleThreadExecutor();
        try {
            Future<AI> future = executor.submit((Callable<AI>) () -> {
                AI ai = construct(c, utt);
                if (smokeTestState != null) {
                    if (ai instanceof AIWithComputationBudget) {
                        // Give it the same budget a real tournament would,
                        // rather than whatever its constructor happened to
                        // default to - some search-based bots treat an
                        // unset/negative budget as "no limit" and would
                        // otherwise search forever below.
                        ((AIWithComputationBudget) ai).setTimeBudget(SMOKE_TEST_TIME_BUDGET_MS);
                        ((AIWithComputationBudget) ai).setIterationsBudget(SMOKE_TEST_ITERATIONS_BUDGET);
                    }
                    ai.reset();
                    ai.getAction(0, smokeTestState);
                }
                return ai;
            });
            return future.get(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.out.println("[GPOpponentLoader] Skipping " + c.getName() + " - did not finish constructing "
                    + "and/or making its trial decision within " + LOAD_TIMEOUT_MS + "ms (likely hangs during "
                    + "construction, or ignores its time/iteration budget and searches indefinitely) - it would "
                    + "otherwise have hung the whole run.");
            return null;
        } catch (Exception e) {
            // Covers InterruptedException and ExecutionException - the
            // latter is how the executor reports whatever Throwable
            // construction or the trial decision itself threw (including
            // Errors like NoClassDefFoundError, which are Throwable but not
            // Exception - the executor's FutureTask still captures them),
            // which rootCause() unwraps just like it already does for
            // reflection's InvocationTargetException.
            System.out.println("[GPOpponentLoader] Skipping " + c.getName()
                    + " - failed to load or crashed on a trial decision: " + rootCause(e));
            return null;
        } finally {
            // shutdownNow() is best-effort: it interrupts the worker thread,
            // but a bot stuck in a tight loop that never checks
            // Thread.interrupted() will keep running in the background
            // regardless. That's an acceptable trade-off here - one leaked
            // daemon thread from a broken bot is harmless and won't keep the
            // JVM alive, whereas blocking the loader on it would be exactly
            // the hang this method exists to prevent.
            executor.shutdownNow();
        }
    }

    private static ExecutorService newDaemonSingleThreadExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gp-opponent-load");
            t.setDaemon(true);
            return t;
        });
    }

    private static AI construct(Class<?> c, UnitTypeTable utt) throws ReflectiveOperationException {
        try {
            Constructor<?> ctor = c.getConstructor(UnitTypeTable.class);
            return (AI) ctor.newInstance(utt);
        } catch (NoSuchMethodException noUttConstructor) {
            Constructor<?> ctor = c.getConstructor();
            return (AI) ctor.newInstance();
        }
    }

    /**
     * Builds the game state every candidate bot gets trial-run against,
     * using the first configured tournament map. Built once and reused
     * across every bot - getAction() is expected to only read a GameState,
     * never mutate it, so sharing it is safe and avoids reloading the map
     * once per bot.
     *
     * <p>Returns null (rather than throwing) if this setup itself fails, so
     * an environment problem (e.g. a bad map path) disables crash-testing
     * entirely instead of rejecting every candidate opponent as if each one
     * were individually broken.
     */
    private static GameState buildSmokeTestState(List<String> maps, UnitTypeTable utt) {
        if (maps == null || maps.isEmpty()) {
            return null;
        }
        try {
            PhysicalGameState pgs = PhysicalGameState.load(maps.get(0), utt);
            return new GameState(pgs, utt);
        } catch (Throwable e) {
            System.out.println("[GPOpponentLoader] Could not build a trial game state from \"" + maps.get(0)
                    + "\" - opponents will not be crash-tested before use: " + rootCause(e));
            return null;
        }
    }

    /**
     * Reflection wraps any exception thrown by the constructor itself in an
     * InvocationTargetException, whose own toString() is just
     * "java.lang.reflect.InvocationTargetException" - useless for figuring
     * out why a bot's constructor actually failed. Unwraps down to the real
     * underlying cause instead.
     */
    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}