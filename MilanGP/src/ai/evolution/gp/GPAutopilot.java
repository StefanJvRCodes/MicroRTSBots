package ai.evolution.gp;

import rts.PhysicalGameState;
import rts.units.UnitTypeTable;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class GPAutopilot {

    private static class Solution {
        final String expression;
        final String label;
        final double winRate;
        final double worstCase;

        Solution(String expression, String label, double winRate, double worstCase) {
            this.expression = expression;
            this.label = label;
            this.winRate = winRate;
            this.worstCase = worstCase;
        }
    }

    public static void main(String[] args) throws Exception {
        GPConfig cfg = GPConfig.fromArgs(args);
        UnitTypeTable utt = new UnitTypeTable(cfg.unitTypeTableVersion, cfg.conflictPolicy);
        List<PhysicalGameState> maps = GPTrain.loadMaps(cfg, utt);
        Path baseDirectory = Paths.get(cfg.outputDirectory, cfg.runId);
        Files.createDirectories(baseDirectory);

        List<GPMatch.EvaluationCase> rootCases =
                GPMatchupSampler.allCases(cfg.maps, cfg.maps, cfg.opponents);
        List<String> rootSeeds = GPTrain.readSeedExpressions(cfg.seedBotFiles);

        System.out.println("Autopilot | " + cfg.opponents.length + " opponent(s) x "
                + cfg.maps.length + " map(s) = " + rootCases.size() + " case(s)"
                + " | max depth " + cfg.autopilotMaxDepth
                + " | output " + baseDirectory);
        if (cfg.curriculumGenerations > 0) {
            System.out.println("Note: curriculum settings are ignored by autopilot — case ejection"
                    + " covers the same ground automatically.");
        }

        List<String> phaseLog = new ArrayList<>();
        Solution best = solve(cfg, utt, maps, baseDirectory, rootCases, rootSeeds, "root",
                new HashMap<>(), 0, phaseLog);

        Path bestPath = baseDirectory.resolve("best.txt");
        Files.write(bestPath, best.expression.getBytes(StandardCharsets.UTF_8));
        writeSummary(baseDirectory.resolve("summary.txt"), phaseLog, best, rootCases, cfg);
        System.out.println();
        System.out.println("=== autopilot complete ===");
        for (String line : phaseLog) System.out.println("  " + line);
        System.out.println("Winner: " + best.label + " | win rate " + best.winRate
                + " | worst case " + best.worstCase);
        System.out.println("Wrote " + bestPath);
        if (!cfg.publishBotFile.isEmpty()) {
            Files.copy(bestPath, Paths.get(cfg.publishBotFile),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Published to " + cfg.publishBotFile);
        }
    }

    private static Solution solve(GPConfig cfg, UnitTypeTable utt, List<PhysicalGameState> maps,
                                  Path baseDirectory, List<GPMatch.EvaluationCase> cases,
                                  List<String> seedExpressions, String label,
                                  Map<String, Integer> attemptsByCaseSet, int depth,
                                  List<String> phaseLog) throws Exception {
        String caseSetKey = caseSetKey(cases);
        int attempt = attemptsByCaseSet.merge(caseSetKey, 1, Integer::sum);
        if (attempt > cfg.autopilotMaxAttemptsPerCaseSet) {
            System.out.println("[" + label + "] this exact case set has already been trained "
                    + cfg.autopilotMaxAttemptsPerCaseSet + " time(s); not recursing again.");
            phaseLog.add(label + ": skipped (attempt cap reached for this case set)");
            return null;
        }

        System.out.println();
        System.out.println("=== phase " + label + " | depth " + depth + " | " + cases.size()
                + " case(s) | " + seedExpressions.size() + " seed(s) ===");
        System.out.println("    " + GPTrain.describeCases(cases, cfg.maps));

        GPTrain.PhaseResult phase = GPTrain.runPhase(phaseConfig(cfg, label), utt, maps, cases,
                Collections.<GPMatch.EvaluationCase>emptyList(), seedExpressions,
                baseDirectory.resolve(label), label);
        Solution phaseSolution = new Solution(phase.bestExpression, label, phase.winRate, phase.worstCase);
        phaseLog.add(label + ": win rate " + round(phase.winRate) + ", worst case "
                + round(phase.worstCase) + " (on " + phase.activeCases.size() + " of "
                + phase.requestedCaseCount + " case(s), " + phase.ejectedCases.size() + " ejected)");

        List<GPMatch.EvaluationCase> hard = phase.ejectedCases;
        if (hard.isEmpty()) {
            System.out.println("[" + label + "] no cases ejected — solved as far as this phase goes.");
            return phaseSolution;
        }
        if (hard.size() == cases.size()) {
            System.out.println("[" + label + "] every case stalled; nothing to split off, stopping here.");
            return phaseSolution;
        }
        if (depth + 1 >= cfg.autopilotMaxDepth) {
            System.out.println("[" + label + "] depth cap " + cfg.autopilotMaxDepth
                    + " reached; returning this phase's best without specialising.");
            return phaseSolution;
        }

        Solution hardSolution = solve(cfg, utt, maps, baseDirectory, hard,
                Collections.singletonList(phase.bestExpression), label + "-hard",
                attemptsByCaseSet, depth + 1, phaseLog);

        List<String> mergeSeeds = new ArrayList<>();
        mergeSeeds.add(phase.bestExpression);
        if (hardSolution != null) mergeSeeds.add(hardSolution.expression);
        Solution mergeSolution = solve(cfg, utt, maps, baseDirectory, cases, mergeSeeds,
                label + "-merge", attemptsByCaseSet, depth + 1, phaseLog);

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(phase.bestExpression);
        if (hardSolution != null) candidates.add(hardSolution.expression);
        if (mergeSolution != null) candidates.add(mergeSolution.expression);
        if (candidates.size() == 1) return phaseSolution;

        GPTrain.PhaseResult chosen = GPTrain.pickBest(new ArrayList<>(candidates), cfg, utt, maps, cases);
        System.out.println("[" + label + "] best of " + candidates.size() + " candidate(s), scored on"
                + " all " + cases.size() + " case(s): win rate " + chosen.winRate
                + ", worst case " + chosen.worstCase);
        phaseLog.add(label + " (selected): win rate " + round(chosen.winRate)
                + ", worst case " + round(chosen.worstCase)
                + " (on all " + cases.size() + " case(s))");
        return new Solution(chosen.bestExpression, label + " (selected)", chosen.winRate, chosen.worstCase);
    }

    private static GPConfig phaseConfig(GPConfig cfg, String label) {
        GPConfig phase = cfg.copy();
        phase.randomSeed = cfg.randomSeed + label.hashCode();
        phase.resumeCheckpoint = "";
        phase.publishBotFile = "";
        phase.curriculumGenerations = 0;
        return phase;
    }

    private static String caseSetKey(List<GPMatch.EvaluationCase> cases) {
        List<String> keys = new ArrayList<>();
        for (GPMatch.EvaluationCase c : cases) keys.add(c.key());
        Collections.sort(keys);
        return String.join("|", keys);
    }

    private static String round(double value) {
        return String.format("%.4f", value);
    }

    private static void writeSummary(Path path, List<String> phaseLog, Solution best,
                                     List<GPMatch.EvaluationCase> rootCases, GPConfig cfg)
            throws java.io.IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("autopilot run: " + cfg.runId);
            writer.newLine();
            writer.write("cases: " + GPTrain.describeCases(rootCases, cfg.maps));
            writer.newLine();
            writer.newLine();
            writer.write("phases:");
            writer.newLine();
            for (String line : phaseLog) {
                writer.write("  " + line);
                writer.newLine();
            }
            writer.newLine();
            writer.write("winner: " + best.label);
            writer.newLine();
            writer.write("win rate: " + best.winRate);
            writer.newLine();
            writer.write("worst case: " + best.worstCase);
            writer.newLine();
            writer.write("program: " + best.expression);
            writer.newLine();
        }
    }
}
