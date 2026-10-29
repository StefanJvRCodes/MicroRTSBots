package ai.custom.adapter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Command line front end for adapter training, used by the {@code adapt}
 * scripts in MicroRTS_ResearchSetup:
 *
 * <pre>
 *   adapt &lt;bot.ser | stefan&gt; &lt;maps folder&gt; &lt;opponents folder&gt; [options]
 * </pre>
 *
 * Defaults come from adapter_parameters.txt; the arguments override them.
 * Paths are resolved against the directory the script was run from
 * (passed as -Dadapt.cwd), since the JVM itself runs inside MicroRTS_Adapter.
 */
public final class AdapterCli {
    private static final String USAGE = String.join("\n",
            "Usage: adapt <bot.ser | stefan> <maps folder> <opponents folder> [options]",
            "",
            "Trains one adapter for the GP tree in <bot.ser> against every opponent in",
            "<opponents folder> on every .xml map in <maps folder>.",
            "  <bot.ser>           a Tree (bestTree.ser), a Tree[] population or a GP checkpoint",
            "                      (its highest win-rate tree is used); \"stefan\" = hand-built tree",
            "  <opponents folder>  every AI class in its JARs is used (bots that crash in a trial",
            "                      game are skipped); pass \"none\" to use only --builtin opponents",
            "",
            "Options (defaults from MicroRTS_Adapter/adapter_parameters.txt):",
            "  --opponents a,b     only these classes from the opponent JARs (e.g. mayari,Coac)",
            "  --builtin a,b       add project bots (e.g. WorkerRush,LightRush)",
            "  --updates N         PPO updates",
            "  --games N           games per update",
            "  --temperature T     base temperature (1/T = logit of the tree's choice)",
            "  --max-cycles N      game length limit",
            "  --threads N         parallel games / gradient threads",
            "  --run DIR           run folder (default MicroRTS_Adapter/adapter_runs/<bot>__<maps>__<opponents>)",
            "  --resume            continue the run in the run folder (after Ctrl+C)",
            "  --config FILE       read defaults from FILE instead",
            "  --help              show this text");

    private AdapterCli() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("adapt: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            System.exit(2);
        } catch (IllegalStateException e) {
            // e.g. every opponent in the folder failed its trial game
            System.err.println("adapt: " + e.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run(String[] args) {
        File cwd = new File(System.getProperty("adapt.cwd", "."));
        List<String> positional = new ArrayList<>();
        String configFile = "adapter_parameters.txt";
        List<String[]> overrides = new ArrayList<>();
        boolean resume = false;
        String runFolder = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                positional.add(arg);
                continue;
            }
            switch (arg) {
                case "--help":
                case "-h":
                    System.out.println(USAGE);
                    return;
                case "--resume":
                    resume = true;
                    break;
                case "--config":
                    configFile = resolve(cwd, value(args, ++i, arg)).getPath();
                    break;
                case "--run":
                    runFolder = resolve(cwd, value(args, ++i, arg)).getPath();
                    break;
                case "--opponents":
                case "--builtin":
                case "--updates":
                case "--games":
                case "--temperature":
                case "--max-cycles":
                case "--threads":
                    overrides.add(new String[] {arg, value(args, ++i, arg)});
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + arg);
            }
        }
        if (positional.size() != 3) {
            throw new IllegalArgumentException("expected 3 arguments (bot, maps folder, opponents folder), got "
                    + positional.size());
        }

        AdapterConfig config = AdapterConfig.load(configFile);

        String bot = positional.get(0);
        String botName;
        if ("stefan".equalsIgnoreCase(bot)) {
            config.baseTree = "stefan";
            botName = "stefan";
        } else {
            File botFile = resolve(cwd, bot);
            requireExists(botFile, "bot file");
            config.baseTree = botFile.getPath();
            botName = stripExtension(botFile.getName());
        }

        File mapsFolder = resolve(cwd, positional.get(1));
        requireDirectory(mapsFolder, "maps folder");
        config.maps = new ArrayList<>();
        config.mapsFolder = mapsFolder.getPath();

        String opponentsArg = positional.get(2);
        String opponentsName;
        config.builtinOpponents = new ArrayList<>();
        config.opponentClasses = new ArrayList<>();
        if ("none".equalsIgnoreCase(opponentsArg)) {
            config.allJarOpponents = false;
            opponentsName = "builtin";
        } else {
            File opponentsFolder = resolve(cwd, opponentsArg);
            requireDirectory(opponentsFolder, "opponents folder");
            config.opponentsFolder = opponentsFolder.getPath();
            config.allJarOpponents = true;
            opponentsName = opponentsFolder.getName();
        }

        for (String[] o : overrides) {
            String v = o[1];
            switch (o[0]) {
                case "--opponents": config.opponentClasses = list(v); break;
                case "--builtin": config.builtinOpponents = list(v); break;
                case "--updates": config.updates = parseInt(o[0], v); break;
                case "--games": config.gamesPerUpdate = parseInt(o[0], v); break;
                case "--temperature": config.temperature = parseDouble(o[0], v); break;
                case "--max-cycles": config.maxCycles = parseInt(o[0], v); break;
                case "--threads": config.threads = parseInt(o[0], v); break;
                default: break;
            }
        }
        if (!config.allJarOpponents && config.builtinOpponents.isEmpty()) {
            throw new IllegalArgumentException("opponents \"none\" needs --builtin with at least one bot");
        }

        config.runFolder = runFolder != null ? runFolder
                : new File("adapter_runs", sanitize(botName) + "__" + sanitize(mapsFolder.getName()) + "__"
                        + sanitize(opponentsName)).getPath();
        config.resume = resume;
        config.validate();

        File run = new File(config.runFolder);
        if (!resume && new File(run, "checkpoint.ser").exists()) {
            throw new IllegalArgumentException("run folder " + run.getAbsolutePath() + " already has a run - add"
                    + " --resume to continue it, or --run <folder> to start a separate one");
        }

        System.out.println("[adapt] Bot:       " + config.baseTree);
        System.out.println("[adapt] Maps:      " + config.mapsFolder);
        System.out.println("[adapt] Opponents: " + (config.allJarOpponents ? config.opponentsFolder
                + (config.opponentClasses.isEmpty() ? " (all classes)" : " " + config.opponentClasses) : "-")
                + (config.builtinOpponents.isEmpty() ? "" : " + builtin " + config.builtinOpponents));
        System.out.println("[adapt] Run:       " + run.getAbsolutePath() + (resume ? " (resuming)" : ""));

        new AdapterTrainer(config).train();
        printSummary(new File(run, "training_log.csv"), new File(run, "adapter_final.ser"));
    }

    /** Baseline (base tree alone) vs the adapter's last 10 updates, from training_log.csv. */
    private static void printSummary(File log, File model) {
        try {
            List<String> lines = Files.readAllLines(log.toPath());
            double baseline = Double.NaN;
            List<Double> winRates = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split(",");
                int update = Integer.parseInt(f[0]);
                double winRate = Double.parseDouble(f[5]);
                if (update == 0) {
                    baseline = winRate;
                } else {
                    winRates.add(winRate);
                }
            }
            List<Double> last = winRates.subList(Math.max(0, winRates.size() - 10), winRates.size());
            double lastMean = last.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            System.out.println();
            System.out.printf(Locale.ROOT, "[adapt] Win rate, base tree alone:              %.3f%n", baseline);
            System.out.printf(Locale.ROOT, "[adapt] Win rate, with adapter (last %2d updates): %.3f%n", last.size(), lastMean);
            System.out.println("[adapt] Model: " + model.getAbsolutePath());
            System.out.println("[adapt] Log:   " + log.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            System.out.println("[adapt] Training finished; could not summarize " + log + ": " + e.getMessage());
        }
    }

    /** Resolves {@code path} against the caller's directory; also accepts Git Bash style /c/... paths. */
    private static File resolve(File cwd, String path) {
        if (path.matches("^/[a-zA-Z]/.*")) {
            path = path.charAt(1) + ":" + path.substring(2);
        }
        File file = new File(path);
        return (file.isAbsolute() ? file : new File(cwd, path)).getAbsoluteFile().toPath().normalize().toFile();
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    private static void requireExists(File file, String what) {
        if (!file.isFile()) {
            throw new IllegalArgumentException(what + " not found: " + file);
        }
    }

    private static void requireDirectory(File folder, String what) {
        if (!folder.isDirectory()) {
            throw new IllegalArgumentException(what + " not found: " + folder);
        }
    }

    private static int parseInt(String option, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects a whole number, got \"" + value + "\"");
        }
    }

    private static double parseDouble(String option, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects a number, got \"" + value + "\"");
        }
    }

    private static List<String> list(String value) {
        List<String> names = new ArrayList<>();
        for (String name : Arrays.asList(value.split(","))) {
            if (!name.trim().isEmpty()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
