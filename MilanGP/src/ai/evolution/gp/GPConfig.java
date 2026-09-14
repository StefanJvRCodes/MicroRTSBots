package ai.evolution.gp;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Every tunable value, as a plain public field. The field name is the setting name everywhere:
 * on the command line ({@code --populationSize=500} or {@code --population-size=500}), in a
 * properties file ({@code --config=my.properties}), in the run manifest and in checkpoints.
 * {@code --help} prints the current values in properties format.
 */
public class GPConfig {

    // ---- evolution
    public int populationSize = 1000;
    public int generations = 200;
    public int tournamentSize = 3;
    public int eliteSize = 5;
    /** Per offspring: crossover with this probability, else mutation with mutationRate, else a plain copy. */
    public double crossoverRate = 0.6;
    public double mutationRate = 0.3;
    /** When mutation lands on a parameterised terminal, nudge its constant with this probability instead of replacing the node. */
    public double ercPerturbRate = 0.5;
    /** How many times to redraw an offspring whose text already exists in the next generation. */
    public int maxDuplicateRetries = 5;
    public int minInitDepth = 2;
    public int maxInitDepth = 6;
    /** Hard cap on tree depth after crossover and mutation. */
    public int maxDepth = 10;
    /** Probability that a "grow" tree stops at a terminal before reaching its depth budget. */
    public double terminalProbability = 0.35;
    /** Share of the initial population wrapped as (If (CanHarvest) (HarvestResources) random). */
    public double harvestSeedFraction = 0.5;

    // ---- what to play against
    public String[] maps = {
            "maps/12x12/melee12x12Mixed12.xml",
            "maps/8x8/melee8x8Mixed6.xml",
//            "maps/16x16/basesWorkers16x16noResources.xml",
//            "maps/8x8/basesWorkersBarracks8x8.xml",
//            "maps/24x24/basesWorkers24x24.xml",
//            "maps/16x16/basesWorkers16x16C.xml",
//            "maps/BroodWar/(4)BloodBath.scmA.xml",
    };
    public String[] opponents = {
//            "WorkerRush", "WorkerRushPlusPlus", "LightRush", "HeavyRush", "RangedRush",
//            "SimpleEconomyRush", "EconomyRush", "EconomyRushBurster", "EconomyMilitaryRush", "EMRDeterministico",
//            "WorkerDefense", "LightDefense", "HeavyDefense", "RangedDefense",
            "mayariBot",
    };

    // ---- one game
    public int maxCycles = 10000;
    /** A game also ends when neither side has issued an action for this many cycles. */
    public int maxInactiveCycles = 300;
    public int unitTypeTableVersion = 2;
    public int conflictPolicy = 1;

    // ---- scoring
    /** A timed-out draw scores 0.5 + drawMarginWeight * materialMargin. */
    public double drawMarginWeight = 0.25;
    /** A loss scores lossMarginWeight * (1 + materialMargin) / 2. Must stay <= 0.5 - drawMarginWeight so a loss never beats a draw. */
    public double lossMarginWeight = 0.2;
    /** Added to every matchup score before the harmonic mean. Too small and one lost matchup swamps every other signal. */
    public double harmonicMeanEpsilon = 0.1;

    // ---- stopping
    /** Stop when the best combat score has not improved by stagnationImprovementThreshold for this many generations. 0 disables. */
    public int stagnationPatience = 100;
    public double stagnationImprovementThreshold = 0.005;

    // ---- run bookkeeping
    public long randomSeed = 42;
    /** Seeds the stochastic opponents. Fixed per matchup so evaluation is repeatable. */
    public long evaluationSeed = 4242;
    public int threads = Runtime.getRuntime().availableProcessors();
    public String runId = "gp-" + System.currentTimeMillis();
    public String outputDirectory = "runs";
    /** If set, the final best.txt is also copied here. */
    public String publishBotFile = "";
    public int checkpointInterval = 10;
    public String resumeCheckpoint = "";
    public int weakestCasesToLog = 5;

    // ---- GPPlay (benchmarking a saved bot)
    public String playBotFile = "./models/best_v2.txt";
    public String playMap = "maps/8x8/basesWorkers8x8Obstacle.xml";
    public String[] playOpponents = {
            "WorkerRush", "LightRush", "HeavyRush", "RangedRush", "WorkerRushPlusPlus",
            "EconomyRush", "EconomyRushBurster", "EconomyMilitaryRush", "EMRDeterministico", "SimpleEconomyRush",
            "LightDefense", "HeavyDefense", "RangedDefense", "WorkerDefense",
            "mayariBot",
            "RandomAI", "RandomBiasedAI", "RandomBiasedSingleUnitAI", "PassiveAI",
    };
    public int playIterations = 10;
    public boolean playVisualize = false;
    public int playVisualDelayMillis = 50;
    /** Play on holdoutMaps against holdoutOpponents instead; both must be disjoint from the training sets. */
    public boolean playHoldout = false;
    public String[] holdoutMaps = {
            "maps/12x12/melee12x12Mixed12.xml",
            "maps/8x8/melee8x8Mixed6.xml",
            "maps/16x16/basesWorkers16x16noResources.xml",
            "maps/8x8/basesWorkersBarracks8x8.xml",
            "maps/24x24/basesWorkers24x24.xml",
            "maps/16x16/basesWorkers16x16C.xml",
            "maps/BroodWar/(4)BloodBath.scmA.xml",
    };
    public String[] holdoutOpponents = {"RandomAI", "RandomBiasedAI", "RandomBiasedSingleUnitAI"};

    // ------------------------------------------------------------------ loading

    /**
     * Applies {@code --config=file} arguments first (in order), then every {@code --name=value}
     * override, then validates. {@code --help} prints the resulting values and exits.
     */
    public static GPConfig fromArgs(String[] args) throws IOException {
        GPConfig cfg = new GPConfig();
        boolean help = false;
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) { help = true; continue; }
            String[] kv = splitArg(arg);
            if (kv[0].equals("config")) cfg.applyProperties(loadProperties(kv[1]));
        }
        for (String arg : args) {
            if (arg.startsWith("--help") || arg.equals("-h")) continue;
            String[] kv = splitArg(arg);
            if (!kv[0].equals("config")) cfg.set(kv[0], kv[1]);
        }
        if (help) {
            System.out.println("# GP settings (pass as --name=value or in a --config=file.properties)");
            cfg.toProperties().store(System.out, null);
            System.exit(0);
        }
        cfg.validate();
        return cfg;
    }

    /** Sets one field by name. Accepts camelCase or kebab-case; lists are comma-separated. */
    public void set(String name, String value) {
        Field field = findField(name);
        try {
            Class<?> type = field.getType();
            if (type == int.class) field.setInt(this, Integer.parseInt(value.trim()));
            else if (type == long.class) field.setLong(this, Long.parseLong(value.trim()));
            else if (type == double.class) field.setDouble(this, Double.parseDouble(value.trim()));
            else if (type == boolean.class) field.setBoolean(this, Boolean.parseBoolean(value.trim()));
            else if (type == String.class) field.set(this, value.trim());
            else if (type == String[].class) field.set(this, splitList(value));
            else throw new IllegalStateException("Unsupported config field type: " + type);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("GPConfig fields must be public", e);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad value for --" + name + ": " + value, e);
        }
    }

    public void applyProperties(Properties p) {
        for (String key : new TreeMap<>(p).keySet().toArray(new String[0])) set(key, p.getProperty(key));
    }

    /** Every setting as name=value, lists comma-joined. Written to the run manifest and checkpoints. */
    public Properties toProperties() {
        Properties p = new Properties();
        try {
            for (Field field : settingFields()) {
                Object value = field.get(this);
                p.setProperty(field.getName(), value instanceof String[] list ? String.join(",", list) : String.valueOf(value));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("GPConfig fields must be public", e);
        }
        return p;
    }

    public void validate() {
        if (maps.length == 0) throw new IllegalArgumentException("maps must not be empty");
        if (opponents.length == 0) throw new IllegalArgumentException("opponents must not be empty");
        if (crossoverRate + mutationRate > 1.0) {
            throw new IllegalArgumentException("crossoverRate + mutationRate must be <= 1");
        }
        if (lossMarginWeight > 0.5 - drawMarginWeight) {
            throw new IllegalArgumentException("lossMarginWeight must be <= 0.5 - drawMarginWeight, "
                    + "otherwise a shaped loss can outscore a shaped draw");
        }
        if (minInitDepth < 1 || maxInitDepth < minInitDepth || maxDepth < maxInitDepth) {
            throw new IllegalArgumentException("need 1 <= minInitDepth <= maxInitDepth <= maxDepth");
        }
        if (eliteSize > populationSize) throw new IllegalArgumentException("eliteSize exceeds populationSize");
    }

    // ------------------------------------------------------------------ helpers

    private static List<Field> settingFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : GPConfig.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) fields.add(f);
        }
        return fields;
    }

    private static Field findField(String name) {
        String camel = toCamelCase(name);
        for (Field f : settingFields()) if (f.getName().equals(camel)) return f;
        throw new IllegalArgumentException("Unknown setting: " + name + " (see --help)");
    }

    private static String toCamelCase(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.trim().toCharArray()) {
            if (c == '-' || c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    private static String[] splitArg(String arg) {
        int eq = arg.indexOf('=');
        if (!arg.startsWith("--") || eq < 0) {
            throw new IllegalArgumentException("Expected --name=value, got: " + arg);
        }
        return new String[]{arg.substring(2, eq), arg.substring(eq + 1)};
    }

    private static String[] splitList(String value) {
        List<String> items = new ArrayList<>();
        for (String item : value.split(",")) if (!item.trim().isEmpty()) items.add(item.trim());
        return items.toArray(new String[0]);
    }

    private static Properties loadProperties(String path) throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return p;
    }
}
