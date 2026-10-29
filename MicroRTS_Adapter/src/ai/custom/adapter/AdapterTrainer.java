package ai.custom.adapter;

import ai.core.AI;
import ai.core.AIWithComputationBudget;
import ai.core.ContinuingAI;
import ai.core.InterruptibleAI;
import ai.custom.GP_bot.GP;
import ai.custom.GP_bot.GPOpponentLoader;
import ai.custom.GP_bot.TextTable;
import ai.custom.GP_bot.Tree;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.Unit;
import rts.units.UnitTypeTable;

/**
 * Trains an adapter for a fixed GP tree with PPO (Adapter-RL, Algorithm 1).
 *
 * <p>Each update plays {@code games per update} full games in parallel with
 * the current adapter (one decision step = one game cycle in which at least
 * one of our units was free to act), computes GAE advantages from the
 * shaped rewards, then runs {@code epochs} passes of clipped PPO over
 * {@code minibatches} minibatches. A step's action is the joint choice of
 * every unit that acted in it, so its log-probability is the sum of those
 * units' log-probabilities. The base logits are constants, so gradients
 * reach only the adapter (paper eq. 10/13).
 *
 * <p>Run: {@code java -cp "bin;lib/*" ai.custom.adapter.AdapterTrainer [adapter_parameters.txt]}.
 * Writes {@code training_log.csv}, {@code adapter_latest.ser} and a
 * resumable {@code checkpoint.ser} to the run folder every update, and
 * {@code adapter_final.ser} at the end.
 */
public final class AdapterTrainer {
    private final AdapterConfig config;
    private final UnitTypeTable utt = new UnitTypeTable();
    private final List<String> maps;
    private final List<AI> opponents;
    private final List<int[]> tasks = new ArrayList<>();  // {map index, opponent index}
    private final ExecutorService pool;
    private final File runFolder;
    private final Random random;

    private AdapterModel model;
    private Mlp.Adam policyAdam;
    private Mlp.Adam valueAdam;
    private int updatesDone = 0;

    /** Everything needed to continue training after the last finished update. */
    private static final class Checkpoint implements Serializable {
        private static final long serialVersionUID = 1L;
        final AdapterModel model;
        final Mlp.Adam policyAdam;
        final Mlp.Adam valueAdam;
        final int updatesDone;
        final Random random;

        Checkpoint(AdapterModel model, Mlp.Adam policyAdam, Mlp.Adam valueAdam, int updatesDone, Random random) {
            this.model = model;
            this.policyAdam = policyAdam;
            this.valueAdam = valueAdam;
            this.updatesDone = updatesDone;
            this.random = random;
        }
    }

    /** One decision step. */
    private static final class Step {
        final double[] valueObservation;
        final List<AdapterBot.UnitSample> units;
        final double oldLogProb;
        final double value;
        double reward;
        boolean done;
        double advantage;
        double returnValue;

        Step(double[] valueObservation, List<AdapterBot.UnitSample> units, double value) {
            this.valueObservation = valueObservation;
            this.units = units;
            double sum = 0.0;
            for (AdapterBot.UnitSample u : units) {
                sum += u.logProb;
            }
            this.oldLogProb = sum;
            this.value = value;
        }
    }

    private static final class GameResult {
        final int task;
        final List<Step> steps;
        /** 1 win, 0 draw, -1 loss. */
        final int outcome;
        final int cycles;
        final double totalReward;
        final String error;

        GameResult(int task, List<Step> steps, int outcome, int cycles, double totalReward, String error) {
            this.task = task;
            this.steps = steps;
            this.outcome = outcome;
            this.cycles = cycles;
            this.totalReward = totalReward;
            this.error = error;
        }
    }

    public static void main(String[] args) {
        AdapterConfig config = AdapterConfig.load(args.length > 0 ? args[0] : "adapter_parameters.txt");
        new AdapterTrainer(config).train();
        System.exit(0);
    }

    public AdapterTrainer(AdapterConfig config) {
        this.config = config;
        this.runFolder = new File(config.runFolder);
        this.random = new Random(config.seed);
        this.maps = resolveMaps(config);
        this.opponents = new ArrayList<>(GPOpponentLoader.loadBuiltinOpponents(config.builtinOpponents, utt, maps));
        if (!config.opponentClasses.isEmpty() || config.allJarOpponents) {
            for (AI ai : GPOpponentLoader.loadOpponentsFromFolder(config.opponentsFolder, utt, maps,
                    config.opponentClasses)) {
                // A bot can be both a built-in and found in a JAR (e.g. CoacAI: lib/Coac.jar and opponents/Coac.jar).
                if (opponents.stream().noneMatch(existing -> existing.getClass() == ai.getClass())) {
                    opponents.add(ai);
                }
            }
        }
        if (opponents.isEmpty()) {
            throw new IllegalStateException("No opponents loaded - set \"builtin opponents\" and/or \"opponent classes\".");
        }
        for (int m = 0; m < maps.size(); m++) {
            for (int o = 0; o < opponents.size(); o++) {
                tasks.add(new int[] {m, o});
            }
        }
        this.pool = Executors.newFixedThreadPool(config.threads, r -> {
            Thread t = new Thread(r, "adapter-worker");
            t.setDaemon(true);
            return t;
        });
        System.out.println("[Adapter] Maps: " + maps);
        System.out.println("[Adapter] Opponents: " + opponents);
    }

    static List<String> resolveMaps(AdapterConfig config) {
        if (!config.maps.isEmpty()) {
            return new ArrayList<>(config.maps);
        }
        File folder = new File(config.mapsFolder);
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".xml"));
        if (files == null || files.length == 0) {
            throw new IllegalStateException("No .xml maps in " + folder.getAbsolutePath());
        }
        Arrays.sort(files);
        List<String> maps = new ArrayList<>();
        for (File f : files) {
            maps.add(f.getPath());
        }
        return maps;
    }

    public void train() {
        File checkpointFile = new File(runFolder, "checkpoint.ser");
        File logFile = new File(runFolder, "training_log.csv");
        if (!runFolder.exists() && !runFolder.mkdirs()) {
            throw new IllegalStateException("Failed to create " + runFolder.getAbsolutePath());
        }

        if (config.resume && checkpointFile.exists()) {
            Checkpoint checkpoint = loadCheckpoint(checkpointFile);
            model = checkpoint.model;
            policyAdam = checkpoint.policyAdam;
            valueAdam = checkpoint.valueAdam;
            updatesDone = checkpoint.updatesDone;
            copyRandomState(checkpoint.random);
            trimLog(logFile, updatesDone);
            System.out.printf("[Adapter] Resuming from %s after update %d.%n", checkpointFile.getAbsolutePath(), updatesDone);
        } else {
            if (config.resume) {
                System.out.println("[Adapter] resume is on but there is no checkpoint - starting a new run.");
            }
            Tree base = GP.loadBaseTree(config.baseTree);
            model = new AdapterModel(base, config.baseMode, config.temperature, config.patchRadius, config.maxCycles,
                    config.hiddenSize, random);
            policyAdam = new Mlp.Adam(model.policy);
            valueAdam = new Mlp.Adam(model.value);
            writeLogHeader(logFile);
            System.out.printf("[Adapter] New run in %s: base %s (%s, T=%s), adapter %d->%d->%d->%d.%n",
                    runFolder.getAbsolutePath(), config.baseTree, config.baseMode, config.temperature,
                    model.policy.sizes[0], config.hiddenSize, config.hiddenSize, AdapterObservation.CELLS);
            if (config.baselineGames > 0) {
                runBaseline(logFile);
            }
        }

        for (int update = updatesDone + 1; update <= config.updates; update++) {
            long start = System.currentTimeMillis();
            double learningRate = config.annealLearningRate
                    ? config.learningRate * (1.0 - (update - 1.0) / config.updates)
                    : config.learningRate;

            List<GameResult> games = playGames(update, config.gamesPerUpdate, true);
            List<Step> steps = new ArrayList<>();
            for (GameResult game : games) {
                computeAdvantages(game.steps);
                steps.addAll(game.steps);
            }
            double rolloutSeconds = (System.currentTimeMillis() - start) / 1000.0;
            double[] stats = steps.isEmpty() ? new double[5] : optimize(steps, learningRate);

            updatesDone = update;
            saveCheckpoint(checkpointFile);
            saveModel(new File(runFolder, "adapter_latest.ser"));
            double seconds = (System.currentTimeMillis() - start) / 1000.0;
            logUpdate(logFile, update, games, steps, stats, learningRate, seconds);
            System.out.printf(Locale.ROOT, "[Adapter] Update %d/%d: %s | steps %d | pg %.4f v %.4f ent %.4f kl %.5f clip %.3f | %.1fs (%.1fs games)%n",
                    update, config.updates, summarize(games), steps.size(), stats[0], stats[1], stats[2], stats[3],
                    stats[4], seconds, rolloutSeconds);
        }

        saveModel(new File(runFolder, "adapter_final.ser"));
        System.out.println("[Adapter] Done. Model: " + new File(runFolder, "adapter_final.ser").getAbsolutePath());
        evaluateFinal();
        pool.shutdownNow();
    }

    /**
     * After training: the final adapter and the base tree alone vs {@code final
     * evaluation opponents} on every map (see {@link AdapterEvaluator}), saved
     * to final_evaluation.txt in the run folder.
     */
    private void evaluateFinal() {
        if (config.finalEvaluationOpponents.isEmpty() || config.finalEvaluationGames < 1) {
            return;
        }
        List<AI> evalOpponents = GPOpponentLoader.loadNamedOpponents(config.finalEvaluationOpponents,
                config.opponentsFolder, utt, maps);
        new AdapterEvaluator(model, utt, config.maxCycles, config.opponentTimeBudget, config.player)
                .evaluate(runFolder.getPath(), maps, evalOpponents, config.finalEvaluationGames, pool, random,
                        new File(runFolder, "final_evaluation.txt"));
    }

    // ------------------------------------------------------------------
    // Rollouts
    // ------------------------------------------------------------------

    private void runBaseline(File logFile) {
        long start = System.currentTimeMillis();
        List<GameResult> games = playGames(0, config.baselineGames * tasks.size(), false);
        System.out.println("[Adapter] Baseline (base tree alone): " + summarize(games));
        for (Map.Entry<String, String> e : perTaskSummary(games).entrySet()) {
            System.out.println("[Adapter]   " + e.getKey() + ": " + e.getValue());
        }
        logUpdate(logFile, 0, games, Collections.emptyList(), new double[5], 0.0,
                (System.currentTimeMillis() - start) / 1000.0);
    }

    /** Plays {@code count} games spread round-robin over every (map, opponent) task. */
    private List<GameResult> playGames(int update, int count, boolean useAdapter) {
        List<Future<GameResult>> futures = new ArrayList<>();
        for (int g = 0; g < count; g++) {
            int task = (int) (((long) Math.max(0, update - 1) * count + g) % tasks.size());
            long seed = random.nextLong();
            futures.add(pool.submit(() -> playGame(task, seed, useAdapter)));
        }
        List<GameResult> results = new ArrayList<>();
        for (Future<GameResult> f : futures) {
            try {
                GameResult result = f.get();
                if (result.error != null) {
                    System.out.println("[Adapter] Game dropped (" + taskName(result.task) + "): " + result.error);
                } else {
                    results.add(result);
                }
            } catch (Exception e) {
                throw new RuntimeException("Game task failed", e);
            }
        }
        return results;
    }

    private GameResult playGame(int task, long seed, boolean useAdapter) {
        int player = config.player;
        List<Step> steps = new ArrayList<>();
        try {
            PhysicalGameState pgs = PhysicalGameState.load(maps.get(tasks.get(task)[0]), utt);
            GameState gs = new GameState(pgs, utt);
            AdapterBot bot = new AdapterBot(model, utt, useAdapter, false, true, seed);
            AI opponent = GPOpponentLoader.freshInstance(opponents.get(tasks.get(task)[1]), utt);
            if (opponent instanceof AIWithComputationBudget) {
                ((AIWithComputationBudget) opponent).setTimeBudget(config.opponentTimeBudget);
                ((AIWithComputationBudget) opponent).setIterationsBudget(-1);
            }
            if (opponent instanceof InterruptibleAI) {
                opponent = new ContinuingAI(opponent);
            }
            bot.reset();
            opponent.reset();

            RewardTracker tracker = new RewardTracker(player);
            tracker.update(gs);
            Step previous = null;
            double total = 0.0;
            boolean gameover = false;
            while (!gameover && gs.getTime() < config.maxCycles) {
                double reward = tracker.update(gs);
                if (previous != null) {
                    previous.reward += reward;
                    total += reward;
                }
                PlayerAction ours = bot.getAction(player, gs);
                List<AdapterBot.UnitSample> samples = bot.takeSamples();
                if (!samples.isEmpty()) {
                    double[] valueObservation = AdapterObservation.value(bot.lastGrid(), model.maxCycles);
                    Step step = new Step(valueObservation, samples, model.value.output(valueObservation)[0]);
                    steps.add(step);
                    previous = step;
                }
                PlayerAction theirs = opponent.getAction(1 - player, gs);
                gs.issueSafe(ours);
                gs.issueSafe(theirs);
                gameover = gs.cycle();
            }

            int winner = gs.winner();
            int outcome = winner == player ? 1 : (winner == -1 ? 0 : -1);
            double finalReward = tracker.update(gs) + outcome * config.rewardWin;
            if (previous != null) {
                previous.reward += finalReward;
                previous.done = true;
                total += finalReward;
            }
            return new GameResult(task, steps, outcome, gs.getTime(), total, null);
        } catch (Exception e) {
            return new GameResult(task, steps, 0, 0, 0.0, e.toString());
        }
    }

    /**
     * Shaped reward from what changed since the last call: new own units
     * (worker / combat / building), a worker picking up or delivering
     * resources (harvest), and hit points the enemy lost (attack). The
     * win/loss reward is added by the caller at the end of the game.
     */
    private final class RewardTracker {
        private final int player;
        /** unit id -> {player, hit points, carried resources, category}. */
        private Map<Long, int[]> previous;

        RewardTracker(int player) {
            this.player = player;
        }

        double update(GameState gs) {
            Map<Long, int[]> now = new HashMap<>();
            double reward = 0.0;
            for (Unit u : gs.getUnits()) {
                int category = AdapterObservation.category(u.getType());
                if (u.getPlayer() < 0 || category < 0) {
                    continue;
                }
                int[] state = {u.getPlayer(), u.getHitPoints(), u.getResources(), category};
                now.put(u.getID(), state);
                if (previous == null) {
                    continue;
                }
                int[] before = previous.get(u.getID());
                if (u.getPlayer() == player) {
                    if (before == null) {
                        reward += category == 0 ? config.rewardWorker
                                : category == 1 ? config.rewardCombatUnit : config.rewardBuilding;
                    } else if (category == 0 && (before[2] == 0) != (state[2] == 0)) {
                        reward += config.rewardHarvest;  // picked up or delivered
                    }
                } else if (before != null && before[1] > state[1]) {
                    reward += config.rewardAttack * (before[1] - state[1]);
                }
            }
            if (previous != null) {
                for (Map.Entry<Long, int[]> e : previous.entrySet()) {
                    int[] before = e.getValue();
                    if (before[0] != player && !now.containsKey(e.getKey())) {
                        reward += config.rewardAttack * before[1];  // killed: its remaining hit points
                    }
                }
            }
            previous = now;
            return reward;
        }
    }

    /** GAE (paper: lambda 0.95) over one game; the game's last step is terminal. */
    private void computeAdvantages(List<Step> steps) {
        double nextAdvantage = 0.0;
        double nextValue = 0.0;
        for (int t = steps.size() - 1; t >= 0; t--) {
            Step s = steps.get(t);
            double notDone = s.done ? 0.0 : 1.0;
            double delta = s.reward + config.gamma * nextValue * notDone - s.value;
            s.advantage = delta + config.gamma * config.gaeLambda * notDone * nextAdvantage;
            s.returnValue = s.advantage + s.value;
            nextAdvantage = s.advantage;
            nextValue = s.value;
        }
    }

    // ------------------------------------------------------------------
    // PPO update
    // ------------------------------------------------------------------

    /** Returns averages over the last epoch: {policy loss, value loss, entropy per unit, approx KL, clip fraction}. */
    private double[] optimize(List<Step> steps, double learningRate) {
        int n = steps.size();
        int minibatchSize = Math.max(1, n / config.minibatches);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        double[] epochStats = new double[5];
        for (int epoch = 0; epoch < config.epochs; epoch++) {
            Collections.shuffle(order, random);
            double[] totals = new double[6];  // pg, v, entropy, unit count, kl, clipped
            for (int start = 0; start < n; start += minibatchSize) {
                List<Step> batch = new ArrayList<>();
                for (int i = start; i < Math.min(n, start + minibatchSize); i++) {
                    batch.add(steps.get(order.get(i)));
                }
                double[] batchTotals = minibatchStep(batch, learningRate);
                for (int k = 0; k < totals.length; k++) {
                    totals[k] += batchTotals[k];
                }
            }
            epochStats = new double[] {totals[0] / n, totals[1] / n, totals[2] / Math.max(1, totals[3]),
                    totals[4] / n, totals[5] / n};
        }
        return epochStats;
    }

    private double[] minibatchStep(List<Step> batch, double learningRate) {
        double mean = 0.0;
        for (Step s : batch) {
            mean += s.advantage;
        }
        mean /= batch.size();
        double variance = 0.0;
        for (Step s : batch) {
            variance += (s.advantage - mean) * (s.advantage - mean);
        }
        double std = Math.sqrt(variance / batch.size()) + 1e-8;
        final double advMean = mean;
        final double advStd = std;

        int chunks = Math.min(config.threads, batch.size());
        int chunkSize = (batch.size() + chunks - 1) / chunks;
        List<Future<Object[]>> futures = new ArrayList<>();
        for (int from = 0; from < batch.size(); from += chunkSize) {
            List<Step> chunk = batch.subList(from, Math.min(batch.size(), from + chunkSize));
            futures.add(pool.submit(() -> chunkGradients(chunk, batch.size(), advMean, advStd)));
        }

        Mlp.Grads policyGrads = model.policy.newGrads();
        Mlp.Grads valueGrads = model.value.newGrads();
        double[] totals = new double[6];
        for (Future<Object[]> f : futures) {
            try {
                Object[] result = f.get();
                policyGrads.add((Mlp.Grads) result[0]);
                valueGrads.add((Mlp.Grads) result[1]);
                double[] t = (double[]) result[2];
                for (int k = 0; k < totals.length; k++) {
                    totals[k] += t[k];
                }
            } catch (Exception e) {
                throw new RuntimeException("Gradient task failed", e);
            }
        }

        double norm = Math.sqrt(policyGrads.squaredNorm() + valueGrads.squaredNorm());
        if (norm > config.maxGradNorm) {
            double scale = config.maxGradNorm / norm;
            policyGrads.scale(scale);
            valueGrads.scale(scale);
        }
        policyAdam.step(model.policy, policyGrads, learningRate);
        valueAdam.step(model.value, valueGrads, learningRate);
        return totals;
    }

    /**
     * Gradients of the minibatch-mean PPO loss over {@code chunk}:
     * clipped surrogate on the joint (all units of a step) probability ratio,
     * minus entropy bonus, plus value loss. Read-only on the networks.
     */
    private Object[] chunkGradients(List<Step> chunk, int batchSize, double advMean, double advStd) {
        Mlp.Grads policyGrads = model.policy.newGrads();
        Mlp.Grads valueGrads = model.value.newGrads();
        double[] totals = new double[6];
        int cells = AdapterObservation.CELLS;

        for (Step step : chunk) {
            int unitCount = step.units.size();
            double[][][] activations = new double[unitCount][][];
            double[][] probabilities = new double[unitCount][];
            double[][] logProbabilities = new double[unitCount][];
            double[] entropies = new double[unitCount];
            double newLogProb = 0.0;

            for (int u = 0; u < unitCount; u++) {
                AdapterBot.UnitSample sample = step.units.get(u);
                activations[u] = model.policy.forward(sample.observation);
                double[] adjustment = activations[u][activations[u].length - 1];
                double[] logits = new double[cells];
                double max = Double.NEGATIVE_INFINITY;
                for (int c = 0; c < cells; c++) {
                    logits[c] = sample.baseLogits[c] + adjustment[c];
                    max = Math.max(max, logits[c]);
                }
                double sum = 0.0;
                for (int c = 0; c < cells; c++) {
                    if (logits[c] != Double.NEGATIVE_INFINITY) {
                        sum += Math.exp(logits[c] - max);
                    }
                }
                double logSum = max + Math.log(sum);
                double[] p = new double[cells];
                double[] logP = new double[cells];
                double entropy = 0.0;
                for (int c = 0; c < cells; c++) {
                    if (logits[c] == Double.NEGATIVE_INFINITY) {
                        logP[c] = Double.NEGATIVE_INFINITY;
                        continue;
                    }
                    logP[c] = logits[c] - logSum;
                    p[c] = Math.exp(logP[c]);
                    entropy -= p[c] * logP[c];
                }
                probabilities[u] = p;
                logProbabilities[u] = logP;
                entropies[u] = entropy;
                newLogProb += logP[sample.action];
            }

            double advantage = (step.advantage - advMean) / advStd;
            double logRatio = Math.max(-20.0, Math.min(20.0, newLogProb - step.oldLogProb));
            double ratio = Math.exp(logRatio);
            double clipped = Math.max(1 - config.clip, Math.min(1 + config.clip, ratio));
            double unclippedObjective = ratio * advantage;
            double clippedObjective = clipped * advantage;
            boolean useUnclipped = unclippedObjective <= clippedObjective;
            // d(loss)/d(newLogProb): loss = -min(...), only the unclipped branch depends on the ratio.
            double dLogProb = useUnclipped ? -advantage * ratio : 0.0;

            totals[0] += -Math.min(unclippedObjective, clippedObjective);
            totals[4] += (ratio - 1) - logRatio;
            totals[5] += Math.abs(ratio - 1) > config.clip ? 1 : 0;

            for (int u = 0; u < unitCount; u++) {
                AdapterBot.UnitSample sample = step.units.get(u);
                double[] p = probabilities[u];
                double[] logP = logProbabilities[u];
                double[] grad = new double[cells];
                for (int c = 0; c < cells; c++) {
                    if (p[c] == 0.0 && c != sample.action) {
                        continue;
                    }
                    double indicator = c == sample.action ? 1.0 : 0.0;
                    // d(log p_a)/dz_c = 1[c=a] - p_c; d(-coef*H)/dz_c = coef * p_c * (log p_c + H)
                    grad[c] = dLogProb * (indicator - p[c]);
                    if (p[c] > 0) {
                        grad[c] += config.entropyCoef * p[c] * (logP[c] + entropies[u]);
                    }
                    grad[c] /= batchSize;
                }
                model.policy.backward(activations[u], grad, policyGrads);
                totals[2] += entropies[u];
                totals[3] += 1;
            }

            double[][] valueActivations = model.value.forward(step.valueObservation);
            double v = valueActivations[valueActivations.length - 1][0];
            double error = v - step.returnValue;
            totals[1] += 0.5 * error * error;
            model.value.backward(valueActivations, new double[] {config.valueCoef * error / batchSize}, valueGrads);
        }
        return new Object[] {policyGrads, valueGrads, totals};
    }

    // ------------------------------------------------------------------
    // Logging and files
    // ------------------------------------------------------------------

    private String taskName(int task) {
        int[] t = tasks.get(task);
        return new File(maps.get(t[0])).getName() + " vs " + opponents.get(t[1]).getClass().getSimpleName();
    }

    private static String summarize(List<GameResult> games) {
        int wins = 0, draws = 0, losses = 0;
        double reward = 0.0;
        for (GameResult g : games) {
            if (g.outcome > 0) wins++;
            else if (g.outcome == 0) draws++;
            else losses++;
            reward += g.totalReward;
        }
        return String.format(Locale.ROOT, "W/D/L %d/%d/%d (win rate %.3f), mean return %.2f", wins, draws, losses,
                games.isEmpty() ? 0.0 : wins / (double) games.size(), games.isEmpty() ? 0.0 : reward / games.size());
    }

    private Map<String, String> perTaskSummary(List<GameResult> games) {
        Map<String, List<GameResult>> byTask = new LinkedHashMap<>();
        for (GameResult g : games) {
            byTask.computeIfAbsent(taskName(g.task), k -> new ArrayList<>()).add(g);
        }
        Map<String, String> summary = new LinkedHashMap<>();
        for (Map.Entry<String, List<GameResult>> e : byTask.entrySet()) {
            summary.put(e.getKey(), summarize(e.getValue()));
        }
        return summary;
    }

    private void writeLogHeader(File logFile) {
        try (PrintWriter w = new PrintWriter(new FileWriter(logFile, false))) {
            w.println("update,games,wins,draws,losses,win_rate,mean_return,mean_cycles,steps,unit_decisions,"
                    + "policy_loss,value_loss,entropy,approx_kl,clip_fraction,learning_rate,seconds");
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + logFile, e);
        }
    }

    /** Keeps the header plus updates 0..{@code updatesDone}, dropping rows from an update that never got checkpointed. */
    private void trimLog(File logFile, int updatesDone) {
        if (!logFile.exists()) {
            writeLogHeader(logFile);
            return;
        }
        try {
            List<String> kept = new ArrayList<>();
            for (String line : Files.readAllLines(logFile.toPath())) {
                String first = line.split(",", 2)[0];
                if (!first.matches("\\d+") || Integer.parseInt(first) <= updatesDone) {
                    kept.add(line);
                }
            }
            Files.write(logFile.toPath(), kept);
        } catch (IOException e) {
            throw new RuntimeException("Failed to trim " + logFile, e);
        }
    }

    private void logUpdate(File logFile, int update, List<GameResult> games, List<Step> steps, double[] stats,
                           double learningRate, double seconds) {
        int wins = 0, draws = 0, losses = 0;
        double reward = 0.0, cycles = 0.0;
        for (GameResult g : games) {
            if (g.outcome > 0) wins++;
            else if (g.outcome == 0) draws++;
            else losses++;
            reward += g.totalReward;
            cycles += g.cycles;
        }
        int unitDecisions = 0;
        for (Step s : steps) {
            unitDecisions += s.units.size();
        }
        int count = Math.max(1, games.size());
        try (PrintWriter w = new PrintWriter(new FileWriter(logFile, true))) {
            w.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%.4f,%.4f,%.1f,%d,%d,%.6f,%.6f,%.6f,%.6f,%.4f,%.8f,%.1f%n",
                    update, games.size(), wins, draws, losses, wins / (double) count, reward / count, cycles / count,
                    steps.size(), unitDecisions, stats[0], stats[1], stats[2], stats[3], stats[4], learningRate, seconds);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + logFile, e);
        }
    }

    private void saveModel(File file) {
        writeAtomically(file, model);
    }

    private void saveCheckpoint(File file) {
        writeAtomically(file, new Checkpoint(model, policyAdam, valueAdam, updatesDone, random));
    }

    /** Temp file + move, so Ctrl+C mid-write never leaves a broken file. */
    private static void writeAtomically(File file, Object object) {
        File temp = new File(file.getPath() + ".tmp");
        try {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(temp))) {
                oos.writeObject(object);
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Adapter] Failed to save " + file + ": " + e.getMessage());
        }
    }

    private static Checkpoint loadCheckpoint(File file) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (Checkpoint) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to load " + file.getAbsolutePath(), e);
        }
    }

    /** Continues the checkpoint's RNG stream in this trainer's (final) Random. */
    private void copyRandomState(Random saved) {
        random.setSeed(saved.nextLong());
    }
}
