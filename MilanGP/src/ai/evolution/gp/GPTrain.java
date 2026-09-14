package ai.evolution.gp;

import ai.evolution.gp.nodes.ActionNode;
import ai.evolution.gp.nodes.GPSExpression;
import rts.PhysicalGameState;
import rts.units.UnitTypeTable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Entry point for {@code make train}. Each generation: evaluate every individual on every
 * (map, opponent) case, log, stop if stagnant, breed. Writes to runs/<runId>/:
 * manifest.properties, metrics.jsonl, checkpoint-N.properties and best.txt.
 */
public class GPTrain {

    public static void main(String[] args) throws Exception {
        GPConfig cfg = GPConfig.fromArgs(args);
        UnitTypeTable utt = new UnitTypeTable(cfg.unitTypeTableVersion, cfg.conflictPolicy);
        List<PhysicalGameState> maps = new ArrayList<>();
        for (String mapPath : cfg.maps) maps.add(PhysicalGameState.load(mapPath, utt));
        List<GPMatch.EvaluationCase> cases = new ArrayList<>();
        for (int m = 0; m < cfg.maps.length; m++) {
            for (String opponent : cfg.opponents) cases.add(new GPMatch.EvaluationCase(m, opponent));
        }

        Path runDir = Paths.get(cfg.outputDirectory, cfg.runId);
        Files.createDirectories(runDir);
        try (BufferedWriter w = Files.newBufferedWriter(runDir.resolve("manifest.properties"), StandardCharsets.UTF_8)) {
            cfg.toProperties().store(w, "microRTS GP run manifest");
        }
        Path metricsPath = runDir.resolve("metrics.jsonl");
        System.out.println("Training | " + cases.size() + " cases (" + cfg.maps.length + " maps x "
                + cfg.opponents.length + " opponents) | population " + cfg.populationSize
                + " | " + cfg.threads + " threads | " + runDir);

        try (GPPopulation population = openPopulation(cfg, utt)) {
            double bestCombatSoFar = Double.NEGATIVE_INFINITY;
            int lastImprovement = population.getGeneration();

            while (true) {
                int gen = population.getGeneration();
                long started = System.currentTimeMillis();
                population.evaluate(maps, cases);
                GPIndividual best = population.getBest();

                System.out.printf("Generation %d | combat %.4f | win rate %.4f | worst case %.3f | size %d"
                                + " | mean combat %.4f | %ds%n",
                        gen, best.combatScore, best.winRate, best.worstCase, best.size(),
                        population.meanCombatScore(), (System.currentTimeMillis() - started) / 1000);
                System.out.println("  weakest: " + weakestCases(best, cfg));
                appendMetrics(metricsPath, gen, best, population.meanCombatScore());

                if (best.combatScore > bestCombatSoFar + cfg.stagnationImprovementThreshold) {
                    bestCombatSoFar = best.combatScore;
                    lastImprovement = gen;
                }
                if (gen + 1 >= cfg.generations) break;
                if (cfg.stagnationPatience > 0 && gen - lastImprovement >= cfg.stagnationPatience) {
                    System.out.println("No combat improvement of " + cfg.stagnationImprovementThreshold
                            + " in " + cfg.stagnationPatience + " generations, stopping.");
                    break;
                }

                population.nextGeneration();
                if (cfg.checkpointInterval > 0 && population.getGeneration() % cfg.checkpointInterval == 0) {
                    GPCheckpoint.save(runDir.resolve("checkpoint-" + population.getGeneration() + ".properties"),
                            population, cfg);
                }
            }

            GPIndividual best = population.getBest();
            ActionNode reduced = GPTreeOps.reduce(best.root);
            String expression = GPSExpression.write(reduced);
            System.out.printf("Final | win rate %.4f | worst case %.3f | size %d -> %d after reduce()%n",
                    best.winRate, best.worstCase, best.size(), GPTreeOps.size(reduced));
            System.out.println(expression);

            Path bestPath = runDir.resolve("best.txt");
            Files.writeString(bestPath, expression, StandardCharsets.UTF_8);
            if (!cfg.publishBotFile.isEmpty()) {
                Files.copy(bestPath, Paths.get(cfg.publishBotFile), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Published to " + cfg.publishBotFile);
            }
            GPCheckpoint.save(runDir.resolve("checkpoint-final.properties"), population, cfg);
        }
    }

    private static GPPopulation openPopulation(GPConfig cfg, UnitTypeTable utt) throws IOException {
        if (!cfg.resumeCheckpoint.isEmpty()) {
            GPPopulation population = GPCheckpoint.load(Paths.get(cfg.resumeCheckpoint), cfg, utt);
            System.out.println("Resumed from " + cfg.resumeCheckpoint + " at generation " + population.getGeneration());
            return population;
        }
        GPPopulation population = new GPPopulation(cfg, utt, new Random(cfg.randomSeed));
        population.initialize();
        return population;
    }

    private static String weakestCases(GPIndividual best, GPConfig cfg) {
        List<GPMatch.MatchupResult> sorted = new ArrayList<>(best.matchups);
        sorted.sort(Comparator.comparingDouble(GPMatch.MatchupResult::rawScore));
        List<String> parts = new ArrayList<>();
        for (GPMatch.MatchupResult m : sorted.subList(0, Math.min(cfg.weakestCasesToLog, sorted.size()))) {
            parts.add(String.format("%s=%.2f [p0=%.1f p1=%.1f shaped=%.2f limited=%d]",
                    m.evaluationCase().describe(cfg.maps), m.rawScore(), m.asPlayer0(), m.asPlayer1(),
                    m.score(), m.limitedGames()));
        }
        return String.join(", ", parts);
    }

    private static void appendMetrics(Path path, int generation, GPIndividual best, double meanCombat) throws IOException {
        String json = String.format("{\"generation\":%d,\"bestCombat\":%.6f,\"winRate\":%.6f,\"worstCase\":%.4f,"
                        + "\"bestSize\":%d,\"meanCombat\":%.6f}%n",
                generation, best.combatScore, best.winRate, best.worstCase, best.size(), meanCombat);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(json);
        }
    }
}
