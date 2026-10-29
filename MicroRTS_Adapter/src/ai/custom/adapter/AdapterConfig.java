package ai.custom.adapter;

import ai.custom.GP_bot.TreePolicy;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Settings for adapter training, read from a "key: value" file (default
 * adapter_parameters.txt). Defaults follow the Adapter-RL paper where it
 * gives them: temperature 1/100, gamma 0.99, GAE lambda 0.95, clip 0.2,
 * value coefficient 0.5, Adam learning rate 2.5e-4, 500 updates, hidden
 * width 256. Reward weights follow gym-microrts' shaped reward (win 10,
 * harvest 1, worker 1, building 0.2, attack 1, combat unit 4), which the
 * paper's microRTS setup builds on.
 */
public final class AdapterConfig {
    // Base agent
    /** "stefan", or a .ser holding a Tree, a Tree[] population or a GP checkpoint (best tree is used). */
    public String baseTree = "stefan";
    public TreePolicy.Mode baseMode = TreePolicy.Mode.ONEHOT;
    public double temperature = 0.01;

    // Task: which maps and opponents the adapter is trained for
    public String mapsFolder = "training_maps";
    /** If set, used instead of mapsFolder. */
    public List<String> maps = new ArrayList<>();
    public List<String> builtinOpponents = new ArrayList<>(Arrays.asList("WorkerRush", "LightRush"));
    public String opponentsFolder = "opponents";
    /** Classes to load from the opponent JARs (empty = no JAR opponents). */
    public List<String> opponentClasses = new ArrayList<>();
    /** Load every AI class found in the opponents folder's JARs (when opponentClasses is empty). */
    public boolean allJarOpponents = false;
    public int maxCycles = 2000;
    /** Which side the adapter plays (0 or 1). */
    public int player = 0;
    /** Opponent time budget per decision in ms, as in tournaments. */
    public int opponentTimeBudget = 100;

    // PPO
    public int updates = 500;
    public int gamesPerUpdate = 16;
    public int threads = Runtime.getRuntime().availableProcessors();
    public int epochs = 4;
    public int minibatches = 4;
    public double learningRate = 2.5e-4;
    public boolean annealLearningRate = true;
    public double gamma = 0.99;
    public double gaeLambda = 0.95;
    public double clip = 0.2;
    public double valueCoef = 0.5;
    public double entropyCoef = 0.01;
    public double maxGradNorm = 0.5;
    public int hiddenSize = 256;
    public int patchRadius = 3;

    // Reward weights
    public double rewardWin = 10.0;
    public double rewardHarvest = 1.0;
    public double rewardWorker = 1.0;
    public double rewardBuilding = 0.2;
    public double rewardAttack = 1.0;
    public double rewardCombatUnit = 4.0;

    // Run management
    public String runFolder = "adapter_runs/default";
    public boolean resume = false;
    public long seed = 42;
    /** Play this many games per (map, opponent) with the base alone before training, as the comparison baseline. */
    public int baselineGames = 4;

    // Final evaluation (after the last update)
    /** Opponents the trained adapter (and the base alone) are evaluated against; "none" = skip. */
    public List<String> finalEvaluationOpponents = new ArrayList<>(Arrays.asList("Coac", "Rojo", "mayari"));
    /** Games per opponent per map, for each of base alone and adapter. */
    public int finalEvaluationGames = 20;

    public static AdapterConfig load(String fileName) {
        AdapterConfig config = new AdapterConfig();
        File file = new File(fileName);
        if (!file.exists()) {
            System.out.println("[Adapter] No " + file.getAbsolutePath() + " - using defaults.");
            return config;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                config.set(key, value);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file.getAbsolutePath(), e);
        }
        config.validate();
        return config;
    }

    private void set(String key, String value) {
        switch (key) {
            case "base tree": baseTree = value; break;
            case "base selection": baseMode = TreePolicy.Mode.parse(value); break;
            case "temperature": temperature = Double.parseDouble(value); break;
            case "maps folder": mapsFolder = value; break;
            case "maps": maps = parseList(value); break;
            case "builtin opponents": builtinOpponents = parseList(value); break;
            case "opponents folder": opponentsFolder = value; break;
            case "opponent classes": opponentClasses = parseList(value); break;
            case "max cycles": maxCycles = Integer.parseInt(value); break;
            case "player": player = Integer.parseInt(value); break;
            case "opponent time budget": opponentTimeBudget = Integer.parseInt(value); break;
            case "updates": updates = Integer.parseInt(value); break;
            case "games per update": gamesPerUpdate = Integer.parseInt(value); break;
            case "threads": threads = Integer.parseInt(value); break;
            case "epochs": epochs = Integer.parseInt(value); break;
            case "minibatches": minibatches = Integer.parseInt(value); break;
            case "learning rate": learningRate = Double.parseDouble(value); break;
            case "anneal learning rate": annealLearningRate = Boolean.parseBoolean(value); break;
            case "gamma": gamma = Double.parseDouble(value); break;
            case "gae lambda": gaeLambda = Double.parseDouble(value); break;
            case "clip": clip = Double.parseDouble(value); break;
            case "value coef": valueCoef = Double.parseDouble(value); break;
            case "entropy coef": entropyCoef = Double.parseDouble(value); break;
            case "max grad norm": maxGradNorm = Double.parseDouble(value); break;
            case "hidden size": hiddenSize = Integer.parseInt(value); break;
            case "patch radius": patchRadius = Integer.parseInt(value); break;
            case "reward win": rewardWin = Double.parseDouble(value); break;
            case "reward harvest": rewardHarvest = Double.parseDouble(value); break;
            case "reward worker": rewardWorker = Double.parseDouble(value); break;
            case "reward building": rewardBuilding = Double.parseDouble(value); break;
            case "reward attack": rewardAttack = Double.parseDouble(value); break;
            case "reward combat unit": rewardCombatUnit = Double.parseDouble(value); break;
            case "run folder": runFolder = value; break;
            case "resume": resume = Boolean.parseBoolean(value); break;
            case "seed": seed = Long.parseLong(value); break;
            case "baseline games": baselineGames = Integer.parseInt(value); break;
            case "final evaluation opponents":
                finalEvaluationOpponents = value.equalsIgnoreCase("none") ? new ArrayList<>() : parseList(value);
                break;
            case "final evaluation games": finalEvaluationGames = Integer.parseInt(value); break;
            default:
                throw new IllegalArgumentException("Unknown adapter parameter \"" + key + "\".");
        }
    }

    void validate() {
        if (baseMode == TreePolicy.Mode.ARGMAX) {
            throw new IllegalArgumentException("base selection must be onehot or softmax - the adapter adjusts a distribution.");
        }
        if (!(temperature > 0)) {
            throw new IllegalArgumentException("temperature must be > 0.");
        }
        if (player != 0 && player != 1) {
            throw new IllegalArgumentException("player must be 0 or 1.");
        }
        if (gamesPerUpdate < 1 || epochs < 1 || minibatches < 1 || threads < 1) {
            throw new IllegalArgumentException("games per update, epochs, minibatches and threads must be >= 1.");
        }
    }

    private static List<String> parseList(String value) {
        List<String> names = new ArrayList<>();
        for (String name : value.split(",")) {
            if (!name.trim().isEmpty()) {
                names.add(name.trim());
            }
        }
        return names;
    }
}
