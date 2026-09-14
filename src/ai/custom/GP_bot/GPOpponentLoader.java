package ai.custom.GP_bot;

import ai.core.AI;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
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
 * act, not when it's built. To catch those before they can crash a real
 * tournament, every bot that does construct successfully is also given one
 * trial {@code getAction()} call against a real game state (see
 * {@link #buildSmokeTestState}); a bot that throws anything at all during
 * that trial is dropped the same way as any other load failure.
 */
public class GPOpponentLoader {

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
            AI ai = instantiate(c, utt);
            if (ai == null) {
                continue;
            }
            if (smokeTestState != null && !passesSmokeTest(ai, c, smokeTestState)) {
                continue;
            }
            opponents.add(ai);
            System.out.println("[GPOpponentLoader] Loaded opponent: " + c.getName());
        }

        if (opponents.isEmpty()) {
            System.out.println("[GPOpponentLoader] No usable AI classes found in \"" + folderPath + "\".");
        }
        return opponents;
    }

    private static AI instantiate(Class<?> c, UnitTypeTable utt) {
        try {
            return construct(c, utt);
        } catch (Throwable e) {
            // Deliberately catching Throwable, not just Exception: resolving a
            // class's constructors (or running its static initializers via
            // newInstance) can throw NoClassDefFoundError / LinkageError when
            // a dependency the class needs is missing from the classpath -
            // those are Errors, not Exceptions, so "catch (Exception e)" alone
            // silently lets them crash the whole GP run instead of just
            // skipping this one bot, the same way LoadTournamentAIs already
            // has to guard against NoClassDefFoundError when loading classes
            // out of the JAR in the first place.
            System.out.println("[GPOpponentLoader] Skipping " + c.getName() + " - failed to instantiate: " + rootCause(e));
            return null;
        }
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
     * Gives a candidate bot one real decision to make before it's trusted.
     * Only catches what happens here, at load time - a bot that passes this
     * but crashes later, mid-game, on a different map or after many turns
     * isn't covered by this check.
     */
    private static boolean passesSmokeTest(AI ai, Class<?> c, GameState smokeTestState) {
        try {
            ai.reset();
            ai.getAction(0, smokeTestState);
            return true;
        } catch (Throwable e) {
            System.out.println("[GPOpponentLoader] Skipping " + c.getName()
                    + " - crashed on a trial decision (would otherwise have crashed mid-tournament): " + rootCause(e));
            return false;
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