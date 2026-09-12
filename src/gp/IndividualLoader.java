package gp;

import bots.EvolvedBot;
import ec.EvolutionState;
import ec.Evolve;
import ec.gp.ADFStack;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.gp.GPProblem;
import ec.gp.GPSpecies;
import ec.gp.GPTree;
import ec.util.Parameter;
import ec.util.ParameterDatabase;
import rts.units.UnitTypeTable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a saved best-*.ind back into a GPIndividual that can play games.
 *
 * WHY THIS EXISTS. Until now an evolved bot lived only inside the JVM that evolved it:
 * BenchmarkStatistics ran its panel in-process on the best-of-run individual and then the
 * bot was gone. That blocked three separate things at once -- re-benchmarking an archived
 * individual at a larger sample size, WATCHING one play to see what it actually does, and
 * freezing a GP policy for the Phase 5 adapter to wrap. All three need the same thing:
 * a .ind on disk becoming an ai.core.AI.
 *
 * HOW ECJ READS AN INDIVIDUAL, AND WHY IT NEEDS THE PARAMS FILE. A .ind is not
 * self-describing. It is a fitness line plus a Lisp-form tree whose node names mean
 * nothing without the function set that defined them -- "movesTowardEnemy" is only a node
 * because config/microrts.params says FeatureNode binds that name. So loading requires a
 * fully set-up EvolutionState: GP types, node constraints, function sets and tree
 * constraints all processed, and a GPSpecies whose prototype individual can be cloned and
 * read into. That is what setUpState() below assembles, without running any evolution.
 *
 * IT FOLLOWS THAT THE PARAMS FILE IS PART OF THE ARCHIVE. A .ind saved under a 29-entry
 * function set cannot be read by a params file listing 25. BenchmarkStatistics now writes
 * a best-<stamp>.params snapshot beside each individual for exactly this reason; older
 * archived individuals predate that and may no longer be readable at all.
 *
 * THE ROUND-TRIP CHECK IS NOT OPTIONAL. ECJ's read path is far less exercised than its
 * write path, and the specific hazard is ephemeral random constants. GPNode.printNode is
 * what printIndividual uses, and ERC subclasses are expected to override it along with
 * readNode, normally via encode()/decode(). An ERC that implements only the human-readable
 * form will WRITE a number and READ BACK something else -- most likely zero -- producing a
 * bot that loads without error, reports the right fitness (the fitness line is read
 * verbatim), and plays nothing like the individual that was evolved. That failure is
 * silent and would corrupt every downstream result.
 *
 * So load() re-prints whatever it read and compares it against the file. A mismatch throws
 * rather than returning a plausible-looking bot. If it ever fires, look at ScoreERC first.
 *
 * USAGE
 *   From code:   IndividualLoader.makeBot("results/best-seed4242-....ind", utt)
 *   By name:     any Bots.make() call site, as "evolved:results/best-....ind"
 *                optionally "evolved:&lt;ind&gt;@&lt;params&gt;" to override the params file
 *   Inspection:  java -cp "out;lib/*" gp.IndividualLoader results/best-....ind
 */
public final class IndividualLoader {

    /** Used when a spec does not name one explicitly. */
    public static final String DEFAULT_PARAMS = "config/microrts.params";

    private IndividualLoader() {}

    /** One fully set-up ECJ context plus the individual read into it. */
    public static final class Loaded {
        public final EvolutionState state;
        public final GPIndividual individual;
        public final GPProblem problem;
        public final String indPath;
        public final String paramsPath;

        Loaded(EvolutionState state, GPIndividual individual, GPProblem problem,
               String indPath, String paramsPath) {
            this.state = state;
            this.individual = individual;
            this.problem = problem;
            this.indPath = indPath;
            this.paramsPath = paramsPath;
        }

        /** Lisp form of the loaded tree(s) -- what the bot actually scores with. */
        public String lispForm() {
            if (individual.trees == null || individual.trees.length == 0) return "(no trees)";
            StringBuilder sb = new StringBuilder();
            for (GPTree t : individual.trees) {
                sb.append(t == null || t.child == null ? "(empty)" : t.child.makeLispTree());
                sb.append('\n');
            }
            return sb.toString();
        }

        public String describe() {
            int nodes = 0, depth = 0;
            if (individual.trees != null) {
                for (GPTree t : individual.trees) {
                    if (t == null || t.child == null) continue;
                    nodes += t.child.numNodes(GPNode.NODESEARCH_ALL);
                    depth = Math.max(depth, t.child.depth());
                }
            }
            return nodes + " nodes, depth " + depth + ", fitness "
                 + individual.fitness.fitnessToStringForHumans();
        }
    }

    /**
     * Setting up an EvolutionState is expensive -- it parses the params file, builds the
     * function set and instantiates the problem. Cache per (ind, params) pair so a
     * benchmark asking for the same bot 200 times pays for it once.
     *
     * The cached state and individual are READ-ONLY during play: tree evaluation walks
     * nodes without mutating them. The mutable per-game context (ScoreData, ADFStack) is
     * freshly cloned in makeBot() for every bot handed out, so two bots from one cache
     * entry never share scoring state.
     */
    private static final Map<String, Loaded> CACHE = new HashMap<>();

    /**
     * Parse "path/to.ind" or "path/to.ind@path/to.params" and build a playable bot.
     * Throws IllegalArgumentException with a diagnosis on any failure -- never returns a
     * bot that might be the wrong one.
     */
    public static EvolvedBot makeBot(String spec, UnitTypeTable utt) {
        Loaded loaded = load(spec);

        // Fresh per-bot scoring context. EvolvedBot writes into ScoreData on every action
        // it scores, so sharing one across bots (or threads) would corrupt evaluation.
        ScoreData data = (ScoreData) loaded.problem.input.clone();
        ADFStack stack = (ADFStack) loaded.problem.stack.clone();

        return new EvolvedBot(utt, loaded.individual, loaded.state, 0,
                              loaded.problem, data, stack);
    }

    /** Load (or fetch from cache) the individual named by a spec. */
    public static synchronized Loaded load(String spec) {
        String indPath = spec.trim();
        String paramsPath = DEFAULT_PARAMS;

        int at = indPath.lastIndexOf('@');
        if (at >= 0) {
            paramsPath = indPath.substring(at + 1).trim();
            indPath = indPath.substring(0, at).trim();
        }

        String key = indPath + "@" + paramsPath;
        Loaded cached = CACHE.get(key);
        if (cached != null) return cached;

        Loaded fresh = loadUncached(indPath, paramsPath);
        CACHE.put(key, fresh);
        return fresh;
    }

    private static Loaded loadUncached(String indPath, String paramsPath) {
        File indFile = new File(indPath);
        File paramsFile = new File(paramsPath);

        if (!indFile.isFile()) {
            throw new IllegalArgumentException("No such individual file: " + indFile.getAbsolutePath());
        }
        if (!paramsFile.isFile()) {
            throw new IllegalArgumentException(
                    "No such params file: " + paramsFile.getAbsolutePath()
                  + "\nA .ind cannot be read without the function set that defined its nodes."
                  + "\nName one explicitly as \"evolved:" + indPath + "@path/to.params\".");
        }

        EvolutionState state = setUpState(paramsFile);

        GPSpecies species;
        try {
            species = (GPSpecies) state.population.subpops.get(0).species;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Could not obtain a GPSpecies from " + paramsPath + ": " + e, e);
        }

        String original;
        try {
            original = new String(Files.readAllBytes(Paths.get(indPath)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read " + indPath + ": " + e, e);
        }

        // Read into an untyped local: ClassCastException is a RuntimeException, so casting
        // inside the try below would make a separate catch for it unreachable. The
        // instanceof check afterwards is also a better diagnosis, since it can report what
        // the file actually produced rather than only that the cast failed.
        Object raw;
        try (LineNumberReader reader = new LineNumberReader(
                new InputStreamReader(new FileInputStream(indFile), StandardCharsets.UTF_8))) {
            raw = species.newIndividual(state, reader);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(
                    "Failed to parse " + indPath + " against " + paramsPath + ": " + e
                  + "\nUsual cause: the function set in that params file differs from the one"
                  + "\nthat produced this individual. Every node name in the tree must exist"
                  + "\nin gp.fs.0 -- a terminal added or removed since the run makes the file"
                  + "\nunreadable.", e);
        }

        if (!(raw instanceof GPIndividual)) {
            throw new IllegalArgumentException(indPath + " did not read back as a GPIndividual"
                    + " (got " + (raw == null ? "null" : raw.getClass().getName()) + ").");
        }
        GPIndividual ind = (GPIndividual) raw;

        verifyRoundTrip(state, ind, original, indPath);

        GPProblem problem = (GPProblem) state.evaluator.p_problem;
        return new Loaded(state, ind, problem, indPath, paramsPath);
    }

    /**
     * Build an EvolutionState far enough to have a function set and a species, without
     * creating a population or running a generation.
     *
     * Evolve.initialize() instantiates the state and its RNGs but does NOT call setup();
     * setup() is what triggers GPInitializer to process types, node constraints, function
     * sets and tree constraints, and what instantiates the evaluator and its problem.
     * Initializer.setupPopulation() then builds the subpopulation and its species, which
     * is the object that knows how to read an individual. Individuals are only generated
     * by populate(), which is never called here.
     */
    private static EvolutionState setUpState(File paramsFile) {
        ParameterDatabase db;
        try {
            db = new ParameterDatabase(paramsFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not load params " + paramsFile + ": " + e, e);
        }

        // Do NOT let setting up a loader truncate the real run's .stat file. Statistics
        // opens its log during setup(), so redirect it before setup() happens. The $
        // keeps it relative to the working directory, matching the rest of the project.
        db.set(new Parameter("stat"), "ec.simple.SimpleStatistics");
        db.set(new Parameter("stat.file"), "$results/loader-scratch.stat");

        // Single-threaded: loading needs one RNG and one problem instance.
        db.set(new Parameter("evalthreads"), "1");
        db.set(new Parameter("breedthreads"), "1");

        EvolutionState state = Evolve.initialize(db, 0);
        state.setup(state, null);
        state.population = state.initializer.setupPopulation(state, 0);
        return state;
    }

    /**
     * Re-print what was read and compare it to the file.
     *
     * This is the check that catches an ERC whose encode/decode is missing or wrong: the
     * fitness line reads back verbatim regardless, and the tree structure survives, so a
     * broken constant produces a bot that looks entirely correct and plays differently.
     * Comparing the re-printed form against the original is the only cheap way to see it.
     */
    private static void verifyRoundTrip(EvolutionState state, GPIndividual ind,
                                        String original, String indPath) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ind.printIndividual(state, pw);
        pw.flush();

        String a = normalise(original);
        String b = normalise(sw.toString());
        if (a.equals(b)) return;

        throw new IllegalArgumentException(
                "ROUND-TRIP FAILED for " + indPath + "."
              + "\nThe individual was read without error but does not re-print identically,"
              + "\nwhich means something was lost or changed in the reading. The usual cause"
              + "\nis an ephemeral random constant: GPNode.printNode / readNode must both be"
              + "\noverridden in gp.ScoreERC (normally via encode()/decode()), otherwise the"
              + "\nconstants come back wrong and the loaded bot silently plays differently"
              + "\nfrom the one that was evolved."
              + "\n\n--- on disk ---\n" + trim(a)
              + "\n\n--- re-printed ---\n" + trim(b));
    }

    private static String normalise(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String trim(String s) {
        return s.length() <= 1200 ? s : s.substring(0, 1200) + "\n... (truncated)";
    }

    /** Inspect a saved individual: prints its size, fitness and scoring function. */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("usage: gp.IndividualLoader <path.ind> [path.params]");
            System.out.println("   or: gp.IndividualLoader <path.ind@path.params>");
            return;
        }
        String spec = (args.length >= 2) ? args[0] + "@" + args[1] : args[0];
        Loaded loaded = load(spec);
        System.out.println();
        System.out.println("Loaded  : " + loaded.indPath);
        System.out.println("Params  : " + loaded.paramsPath);
        System.out.println("Tree    : " + loaded.describe());
        System.out.println("Round-trip verified.");
        System.out.println();
        System.out.println(loaded.lispForm());
    }
}
