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

public class GPConfig {

    // ---- evolution
    public int populationSize = 1000;
    public int generations = 200;
    public int tournamentSize = 3;
    public int eliteSize = 5;
    public double crossoverRate = 0.6;
    public double mutationRate = 0.3;
    public double ercPerturbRate = 0.5;
    public int maxDuplicateRetries = 5;
    public int minInitDepth = 2;
    public int maxInitDepth = 6;
    public int maxDepth = 10;
    public double terminalProbability = 0.35;
    public double harvestSeedFraction = 0.5;

    // ---- structure-based GP (Scheepers and Pillay, doi:10.1007/s10710-025-09528-3)
    public boolean structureBased = true;
    public int globalAreaDepth = 4;
    public int globalAreaGenerations = 10;
    public int globalAreaWindow = 10;
    public int globalSimilarityThreshold = 6;

    // ---- what to play against
    public String[] maps = {
            "maps/8x8/basesWorkers8x8A.xml",
            "maps/12x12/basesWorkers12x12A.xml",
    };
    public String[] opponents = {
            "WorkerRush", "LightRush", "HeavyRush", "RangedRush", "mayariBot", "EconomyRushBurster"
    };

    // ---- one game
    public int maxCycles = 10000;
    public int maxInactiveCycles = 300;
    public int unitTypeTableVersion = 2;
    public int conflictPolicy = 1;

    // ---- scoring
    public double drawMarginWeight = 0.25;
    public double lossMarginWeight = 0.2;
    public double harmonicMeanEpsilon = 0.1;

    // ---- stopping
    public int stagnationPatience = 100;
    public double stagnationImprovementThreshold = 0.005;

    // ---- run bookkeeping
    public long randomSeed = 42;
    public long evaluationSeed = 4242;
    public int threads = Runtime.getRuntime().availableProcessors();
    public String runId = "gp-" + System.currentTimeMillis();
    public String outputDirectory = "runs";
    public String publishBotFile = "";
    public int checkpointInterval = 10;
    public String resumeCheckpoint = "";
    public int weakestCasesToLog = 5;

    // ---- GPPlay (benchmarking a saved bot)
    public String playBotFile = "./models/best_v3.txt";
    public String playMap = "maps/12x12/basesWorkers12x12A.xml";
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
    public boolean playHoldout = false;
    public String[] holdoutMaps = {
            "maps/8x8/basesWorkersBarracks8x8.xml",
            "maps/10x10/basesWorkers10x10.xml",
            "maps/16x16/basesWorkers16x16C.xml",
            "maps/24x24/basesWorkers24x24.xml",
    };
    public String[] holdoutOpponents = {"Coacai"};

    // ------------------------------------------------------------------ loading

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
        requireDisjoint(maps, holdoutMaps, "map");
        requireDisjoint(opponents, holdoutOpponents, "opponent");
        if (structureBased) {
            if (globalAreaDepth < 1 || globalAreaDepth >= maxDepth) {
                throw new IllegalArgumentException("need 1 <= globalAreaDepth < maxDepth, "
                        + "otherwise the exploit phase has no level left to search");
            }
            if (globalAreaGenerations < 1 || globalAreaWindow < 1) {
                throw new IllegalArgumentException("globalAreaGenerations and globalAreaWindow must be >= 1");
            }
            if (globalSimilarityThreshold < 1) {
                throw new IllegalArgumentException("globalSimilarityThreshold must be >= 1");
            }
        }
    }

    private static void requireDisjoint(String[] training, String[] holdout, String label) {
        for (String candidate : holdout) {
            for (String used : training) {
                if (used.equals(candidate)) {
                    throw new IllegalArgumentException("Holdout " + label + " also appears in training: "
                            + candidate + " (training and evaluation sets must be disjoint)");
                }
            }
        }
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
