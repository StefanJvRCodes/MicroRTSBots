package ai.evolution.gp;

import ai.core.AI;
import gui.PhysicalGameStateJFrame;
import gui.PhysicalGameStatePanel;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Plays games. One matchup is a (map, opponent) pair played once from each side. */
public final class GPMatch {
    private GPMatch() {}

    public record EvaluationCase(int mapIndex, String opponentName) {
        public String describe(String[] mapPaths) {
            return opponentName + "@" + mapPaths[mapIndex];
        }
    }

    /** Outcome of one game from the candidate's point of view. */
    public record GameResult(double score, double margin, int cycles, boolean endedByLimit, boolean crashed) {}

    /**
     * Both games of one matchup, averaged.
     * {@code score} is the shaped value selection uses; {@code rawScore} is plain win=1 / draw=0.5 / loss=0.
     */
    public record MatchupResult(EvaluationCase evaluationCase, double score, double rawScore, double margin,
                                double asPlayer0, double asPlayer1, int limitedGames) {}

    /** Plays every case for one individual. Thread-safe: nothing here is shared between calls. */
    public static List<MatchupResult> evaluate(GPIndividual individual, UnitTypeTable utt,
                                               List<PhysicalGameState> maps, List<EvaluationCase> cases,
                                               GPConfig cfg) throws Exception {
        List<MatchupResult> results = new ArrayList<>(cases.size());
        for (EvaluationCase c : cases) {
            PhysicalGameState map = maps.get(c.mapIndex());
            long seed = mixSeed(cfg.evaluationSeed, c.opponentName(), c.mapIndex());
            GameResult asP0 = playOneGame(new StructuredGPAI(utt, individual.root),
                    GPOpponents.build(c.opponentName(), utt, seed), map, utt, cfg, 0, false);
            GameResult asP1 = playOneGame(GPOpponents.build(c.opponentName(), utt, seed ^ 0x9E3779B97F4A7C15L),
                    new StructuredGPAI(utt, individual.root), map, utt, cfg, 1, false);
            results.add(new MatchupResult(c,
                    (shapedScore(asP0, cfg) + shapedScore(asP1, cfg)) / 2,
                    (asP0.score() + asP1.score()) / 2,
                    (asP0.margin() + asP1.margin()) / 2,
                    asP0.score(), asP1.score(),
                    (asP0.endedByLimit() ? 1 : 0) + (asP1.endedByLimit() ? 1 : 0)));
        }
        return results;
    }

    /**
     * Turns win/draw/loss into a value with a gradient. Wins stay 1. A timed-out draw moves with
     * the material margin. A loss keeps a small share of credit for material, capped so that it
     * can never outscore a draw (enforced by GPConfig.validate). Crashes score a flat 0.
     */
    static double shapedScore(GameResult r, GPConfig cfg) {
        if (r.crashed()) return 0.0;
        if (r.score() == 1.0) return 1.0;
        if (r.score() == 0.0) return cfg.lossMarginWeight * (1 + r.margin()) / 2;
        if (r.endedByLimit()) return Math.max(0, Math.min(1, 0.5 + cfg.drawMarginWeight * r.margin()));
        return 0.5;
    }

    /**
     * Runs one headless (or visualised) game to completion. A game ends on a winner, at
     * {@code maxCycles}, or after {@code maxInactiveCycles} without any issued action.
     * If a bot throws, the game counts as a loss for the candidate.
     */
    public static GameResult playOneGame(AI ai1, AI ai2, PhysicalGameState map, UnitTypeTable utt,
                                         GPConfig cfg, int candidatePlayer, boolean visualize) throws Exception {
        ai1.reset(utt);
        ai2.reset(utt);
        GameState gs = new GameState(map.clone(), utt);
        PhysicalGameStateJFrame window = visualize ? PhysicalGameStatePanel.newVisualizer(gs, 600, 600, false) : null;
        int lastActionTime = 0;
        boolean gameover;
        try {
            do {
                PlayerAction pa1, pa2;
                try {
                    pa1 = ai1.getAction(0, gs);
                    pa2 = ai2.getAction(1, gs);
                } catch (RuntimeException e) {
                    reportCrash(ai1, ai2, gs, e);
                    return new GameResult(0.0, 0.0, gs.getTime(), false, true);
                }
                if (gs.issueSafe(pa1)) lastActionTime = gs.getTime();
                if (gs.issueSafe(pa2)) lastActionTime = gs.getTime();
                gameover = gs.cycle();
                if (window != null) {
                    window.setStateCloning(gs);
                    window.repaint();
                    Thread.sleep(Math.max(0, cfg.playVisualDelayMillis));
                }
            } while (!gameover && gs.getTime() < cfg.maxCycles
                    && gs.getTime() - lastActionTime < cfg.maxInactiveCycles);
        } finally {
            if (window != null) window.dispose();
        }
        int winner = gs.winner();
        ai1.gameOver(winner);
        ai2.gameOver(winner);
        double score = winner == -1 ? 0.5 : winner == candidatePlayer ? 1.0 : 0.0;
        return new GameResult(score, materialMargin(gs, candidatePlayer), gs.getTime(), !gameover, false);
    }

    private static final Set<String> reportedCrashes = ConcurrentHashMap.newKeySet();

    /** Prints each distinct crash once with its stack trace, so one broken matchup cannot flood the log. */
    private static void reportCrash(AI ai1, AI ai2, GameState gs, RuntimeException e) {
        StackTraceElement origin = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
        String signature = ai1.getClass().getSimpleName() + " vs " + ai2.getClass().getSimpleName()
                + " | " + e.getClass().getName() + " @ " + origin;
        if (!reportedCrashes.add(signature)) return;
        System.out.println("WARNING: bot threw at cycle " + gs.getTime() + " (" + signature
                + "); scored as a loss for the candidate. Identical failures are silent from now on.");
        e.printStackTrace(System.out);
    }

    /** (mine - theirs) / total, in [-1, 1], where material is banked resources plus the cost of every unit held. */
    private static double materialMargin(GameState gs, int candidatePlayer) {
        double mine = materialValue(gs, candidatePlayer);
        double theirs = materialValue(gs, 1 - candidatePlayer);
        return (mine - theirs) / (mine + theirs + 1e-6);
    }

    private static double materialValue(GameState gs, int player) {
        double value = gs.getPlayer(player).getResources();
        for (Unit u : gs.getPhysicalGameState().getUnits()) {
            if (u.getPlayer() == player) value += u.getType().cost;
        }
        return value;
    }

    /** A per-matchup opponent seed, so stochastic opponents play the same game for every individual. */
    private static long mixSeed(long seed, String opponentName, int mapIndex) {
        long x = seed ^ ((long) opponentName.hashCode() << 32) ^ mapIndex;
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        return x;
    }
}
