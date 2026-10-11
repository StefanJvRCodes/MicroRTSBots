package gp.sbgp;

import ec.EvolutionState;
import ec.Individual;
import ec.Population;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.gp.koza.KozaFitness;
import ec.simple.SimpleBreeder;
import ec.util.Parameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Structure-based GP (Scheepers & Pillay, GPEM 27:2, 2026, section 4) as an ECJ Breeder.
 *
 * Everything else -- fitness, maps, opponents, role trees, parsimony selectors -- is the
 * ordinary-GP configuration untouched, so the Phase-4 comparison isolates the method.
 *
 * PHASES (decided once per generation, before breeding):
 *   FIRST_GLOBAL  canonical GP for Sg generations; then the top Dg levels of the best
 *                 individual of the phase become the global area -> LOCAL.
 *   LOCAL         every individual carries the area in its top Dg levels; operators act
 *                 below it (SBGPNodeSelector) and the area is re-grafted onto every child.
 *                 Ends when the phase-best has not improved for Wg generations -> GLOBAL.
 *   GLOBAL        operators act within the top Dg levels; children whose similarity to ANY
 *                 archived area reaches Tg are discarded and re-bred. After Sg generations
 *                 the phase-best's top becomes the next area -> LOCAL.
 *
 * INTERPRETATIONS, each to confirm with Prof. Pillay (all are params where possible):
 *   - Stagnation = phase-best not improved for Wg generations. The paper says "no change
 *     in fitness"; with a stochastic opponent, best-of-generation jitters every generation
 *     and a literal reading would never end a local search.
 *   - Entering LOCAL grafts: area on top, the child's own subtrees below wherever the same
 *     position exists, the area source's subtrees elsewhere.
 *   - Role trees: 'combine' = all | any | sum decides how per-tree similarities reject.
 *   - countTerminals / ercByValue: see GlobalArea.
 *
 * Requires breedthreads = 1 (phase state is read by the node selector without locking).
 */
public class SBGPBreeder extends SimpleBreeder {

    private static final long serialVersionUID = 1L;

    public enum Phase { FIRST_GLOBAL, LOCAL, GLOBAL }

    public static final String P_SBGP = "sbgp";

    public int sg, dg, tg, wg, maxRounds;
    public boolean countTerminals, ercByValue, tgCap;
    /** Trees that take part in similarity; null = all. Dead role trees should not steer the search. */
    public boolean[] treeMask;
    public double minImprovement;
    public String combine;

    public Phase phase = Phase.FIRST_GLOBAL;
    public GlobalArea current;
    public final List<GlobalArea> archive = new ArrayList<>();

    private int genInPhase = 0;
    private int stagnant = 0;
    private GPIndividual phaseBest = null;

    /** Incremented by SBGPNodeSelector when no node satisfies the depth rule. */
    public long selectorFallbacks = 0;

    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);
        Parameter b = base.push(P_SBGP);
        sg = state.parameters.getIntWithDefault(b.push("sg"), null, 10);
        dg = state.parameters.getIntWithDefault(b.push("dg"), null, 4);
        tg = state.parameters.getIntWithDefault(b.push("tg"), null, 6);
        wg = state.parameters.getIntWithDefault(b.push("wg"), null, 10);
        maxRounds = state.parameters.getIntWithDefault(b.push("max-rounds"), null, 20);
        tgCap = state.parameters.getBoolean(b.push("tg-cap"), null, true);
        minImprovement = state.parameters.getDoubleWithDefault(b.push("min-improvement"), null, 0.0);
        String ts = state.parameters.getStringWithDefault(b.push("trees"), null, "all").trim();
        if (!ts.equalsIgnoreCase("all")) {
            try {
                String[] parts = ts.split("\\s*,\\s*");
                int max = 0;
                for (String p : parts) max = Math.max(max, Integer.parseInt(p) + 1);
                treeMask = new boolean[max];
                for (String p : parts) treeMask[Integer.parseInt(p)] = true;
            } catch (RuntimeException e) {
                state.output.fatal("SBGP: trees must be 'all' or a comma list of tree indices (got '" + ts + "').", b.push("trees"));
            }
        }
        countTerminals = state.parameters.getBoolean(b.push("count-terminals"), null, true);
        ercByValue = state.parameters.getBoolean(b.push("erc-by-value"), null, false);
        combine = state.parameters.getStringWithDefault(b.push("combine"), null, "all").toLowerCase(Locale.ROOT);

        if (sg < 1 || dg < 1 || tg < 1 || wg < 1 || maxRounds < 1)
            state.output.fatal("SBGP: sg, dg, tg, wg and max-rounds must all be >= 1.", b);
        if (!combine.equals("all") && !combine.equals("any") && !combine.equals("sum"))
            state.output.fatal("SBGP: combine must be all, any or sum (got '" + combine + "').", b.push("combine"));
        int bt = state.parameters.getIntWithDefault(new Parameter("breedthreads"), null, 1);
        if (bt != 1)
            state.output.fatal("SBGP requires breedthreads = 1 (phase state is shared with the node selector).");

        state.output.message("SBGP: Sg=" + sg + " Dg=" + dg + " Tg=" + tg + " Wg=" + wg
                + " combine=" + combine + " count-terminals=" + countTerminals
                + " erc-by-value=" + ercByValue + " max-rounds=" + maxRounds + " tg-cap=" + tgCap + " trees=" + ts + " min-improvement=" + minImprovement);
    }

    @Override
    public Population breedPopulation(final EvolutionState state) {
        if (state.population.subpops.size() != 1)
            state.output.fatal("SBGP supports exactly one subpopulation.");

        updatePhase(state);

        Population next;
        switch (phase) {
            case LOCAL:
                next = super.breedPopulation(state);
                int grafted = impose(next);
                state.output.message("SBGP gen " + state.generation + ": grafted area onto "
                        + grafted + " of " + next.subpops.get(0).individuals.size() + " children"
                        + "; selector fallbacks so far " + selectorFallbacks);
                break;
            case GLOBAL:
                next = breedFiltered(state);
                break;
            default:
                next = super.breedPopulation(state);
        }
        return next;
    }

    // ---------------------------------------------------------------------------------
    // Phase control
    // ---------------------------------------------------------------------------------

    private void updatePhase(final EvolutionState state) {
        // In GLOBAL search the next area must come from an individual that actually escaped.
        // Back-filled rejects are still too similar to the archive; if one of them becomes
        // phase-best, the "new" area is the old one again (pilot 2026-10-11: area #2's worker
        // tree was identical to area #1's).
        boolean screen = (phase == Phase.GLOBAL && !archive.isEmpty());
        GPIndividual best = null, bestAny = null;
        int screened = 0;
        for (Individual i : state.population.subpops.get(0).individuals) {
            GPIndividual g = (GPIndividual) i;
            if (bestAny == null || g.fitness.betterThan(bestAny.fitness)) bestAny = g;
            if (screen && worstSimilarity(g) >= 0) { screened++; continue; }
            if (best == null || g.fitness.betterThan(best.fitness)) best = g;
        }
        if (best == null) {
            best = bestAny;
            state.output.warning("SBGP gen " + state.generation + ": every individual is still too"
                    + " similar to an archived area; phase-best taken from them anyway.");
        }
        if (screened > 0)
            state.output.message("SBGP gen " + state.generation + ": " + screened
                    + " individual(s) excluded from phase-best (too similar to the archive).");

        genInPhase++;
        if (phaseBest == null) {
            phaseBest = (GPIndividual) best.clone();
            stagnant = 0;
        } else if (best.fitness.betterThan(phaseBest.fitness)) {
            // Keep the better individual, but only a REAL gain resets stagnation. With a
            // stochastic opponent, tiny gains are often noise (pilot: LOCAL ran 71 generations
            // because 0.0003-sized improvements kept resetting the counter).
            boolean real = fit(phaseBest) - fit(best) > minImprovement;
            phaseBest = (GPIndividual) best.clone();
            stagnant = real ? 0 : stagnant + 1;
        } else {
            stagnant++;
        }

        switch (phase) {
            case FIRST_GLOBAL:
            case GLOBAL:
                if (genInPhase >= sg) {
                    current = new GlobalArea(phaseBest, dg, state.generation, fit(phaseBest));
                    archive.add(current);
                    state.output.message("SBGP gen " + state.generation + ": " + phase
                            + " search done -> LOCAL. Area #" + archive.size() + ": " + current);
                    enter(Phase.LOCAL);
                    return;
                }
                break;
            case LOCAL:
                if (stagnant >= wg) {
                    state.output.message("SBGP gen " + state.generation + ": LOCAL search converged"
                            + " (no improvement for " + wg + " generations, phase-best "
                            + round4(fit(phaseBest)) + ") -> GLOBAL.");
                    enter(Phase.GLOBAL);
                    return;
                }
                break;
        }

        state.output.message("SBGP gen " + state.generation + ": phase " + phase
                + ", generation " + genInPhase + " in phase, phase-best " + round4(fit(phaseBest))
                + ", stagnant " + stagnant + "/" + wg + ", archive " + archive.size());
    }

    private void enter(Phase p) {
        phase = p;
        genInPhase = 0;
        stagnant = 0;
        phaseBest = null;
    }

    // ---------------------------------------------------------------------------------
    // LOCAL: keep the area on top of every child
    // ---------------------------------------------------------------------------------

    /** Grafts the current area onto every child whose top differs. Returns how many changed. */
    private int impose(Population pop) {
        int changed = 0;
        for (Individual i : pop.subpops.get(0).individuals) {
            GPIndividual g = (GPIndividual) i;
            boolean any = false;
            int n = Math.min(g.trees.length, current.roots.length);
            for (int t = 0; t < n; t++) {
                if (topMatches(current.roots[t], g.trees[t].child, dg)) continue;
                GPNode root = graft(current.roots[t], g.trees[t].child, dg);
                g.trees[t].child = root;
                root.parent = g.trees[t];
                root.argposition = 0;
                any = true;
            }
            if (any) {
                g.evaluated = false;   // a reproduced copy would otherwise keep a stale fitness
                changed++;
            }
        }
        return changed;
    }

    /** True if b's top dg levels are identical to a's (exact, constants by value). */
    public static boolean topMatches(GPNode a, GPNode b, int dg) {
        return GlobalArea.similarity(a, b, dg, true, true) == countTop(a, 0, dg);
    }

    private static int countTop(GPNode n, int level, int dg) {
        if (n == null || level >= dg) return 0;
        int c = 1;
        if (n.children != null) for (GPNode k : n.children) c += countTop(k, level + 1, dg);
        return c;
    }

    /**
     * New tree = a deep copy of the area's source tree, with every subtree that starts at
     * level dg replaced by the individual's subtree at the same path, where that path
     * exists. Depth cannot grow: each transplanted subtree sits at the depth it had.
     */
    public static GPNode graft(GPNode areaRoot, GPNode indRoot, int dg) {
        return walk((GPNode) areaRoot.clone(), indRoot, 0, dg);
    }

    private static GPNode walk(GPNode x, GPNode r, int level, int dg) {
        if (level >= dg) return (r != null) ? (GPNode) r.clone() : x;
        if (x.children != null) {
            for (int i = 0; i < x.children.length; i++) {
                GPNode rc = (r != null && r.children != null && i < r.children.length) ? r.children[i] : null;
                GPNode c = walk(x.children[i], rc, level + 1, dg);
                x.children[i] = c;
                c.parent = x;
                c.argposition = (byte) i;
            }
        }
        return x;
    }

    // ---------------------------------------------------------------------------------
    // GLOBAL: discard children too similar to an archived area, re-breed to fill
    // ---------------------------------------------------------------------------------

    private Population breedFiltered(final EvolutionState state) {
        int target = state.population.subpops.get(0).individuals.size();
        List<Individual> keep = new ArrayList<>(target);
        List<Individual> rejects = new ArrayList<>();
        List<Integer> rejectScores = new ArrayList<>();
        Population first = null;
        long bred = 0;
        int rounds = 0;

        while (keep.size() < target && rounds < maxRounds) {
            // Guard: a breeder that recycles populations would have cleared the parents.
            if (state.population.subpops.get(0).individuals.size() != target)
                state.output.fatal("SBGP: parent population changed size during re-breeding."
                        + " Set breed.clone-pipeline-and-population = true.");
            Population p = super.breedPopulation(state);
            if (first == null) first = p;
            for (Individual i : p.subpops.get(0).individuals) {
                bred++;
                int score = worstSimilarity((GPIndividual) i);
                if (score < 0) {
                    keep.add(i);
                    if (keep.size() >= target) break;
                } else {
                    rejects.add(i);
                    rejectScores.add(score);
                }
            }
            rounds++;
        }

        int filled = 0;
        if (keep.size() < target) {
            // Least-similar rejects first.
            Integer[] order = new Integer[rejects.size()];
            for (int k = 0; k < order.length; k++) order[k] = k;
            java.util.Arrays.sort(order, (x, y) -> Integer.compare(rejectScores.get(x), rejectScores.get(y)));
            for (int k = 0; k < order.length && keep.size() < target; k++) {
                keep.add(rejects.get(order[k]));
                filled++;
            }
            state.output.warning("SBGP gen " + state.generation + ": only " + (target - filled)
                    + " dissimilar children after " + rounds + " rounds; filled " + filled
                    + " with the least-similar rejects. Consider lowering Tg or Dg.");
        }

        List<Individual> out = first.subpops.get(0).individuals;
        out.clear();
        out.addAll(keep);

        long accepted = bred - rejects.size();
        state.output.message("SBGP gen " + state.generation + ": GLOBAL filter bred " + bred
                + " in " + rounds + " round(s), rejected " + rejects.size()
                + " (" + Math.round(100.0 * rejects.size() / Math.max(1, bred)) + "%), accepted "
                + accepted + (filled > 0 ? ", back-filled " + filled : ""));
        return first;
    }

    /**
     * -1 if the individual is acceptable against every archived area; otherwise the
     * largest summed similarity among the areas it is too close to (for back-fill order).
     */
    private int worstSimilarity(GPIndividual g) {
        int worst = -1;
        for (GlobalArea a : archive) {
            int[] s = a.similarityPerTree(g, countTerminals, ercByValue);
            int sum = 0, sumThr = 0, atOrAbove = 0, used = 0;
            for (int t = 0; t < s.length; t++) {
                if (treeMask != null && (t >= treeMask.length || !treeMask[t])) continue;
                int thr = effectiveThreshold(a, t);
                used++;
                sum += s[t];
                sumThr += thr;
                if (s[t] >= thr) atOrAbove++;
            }
            if (used == 0) continue;
            boolean tooClose;
            switch (combine) {
                case "any": tooClose = atOrAbove > 0; break;
                case "sum": tooClose = sum >= sumThr; break;
                default:    tooClose = atOrAbove == used;   // "all" (of the selected trees)
            }
            if (tooClose && sum > worst) worst = sum;
        }
        return worst;
    }

    /**
     * Tg for one tree of one area. With tg-cap (default), capped at the number of nodes the
     * area's top actually holds: an area smaller than Tg could otherwise never be matched and
     * the filter goes silently inert. First smoke test (2026-10-11): the worker area was the
     * single terminal movesTowardEnemy (max similarity 1 < Tg 6), so combine=all rejected 0%.
     */
    private int effectiveThreshold(GlobalArea a, int tree) {
        if (!tgCap) return tg;
        return Math.max(1, Math.min(tg, topSize(a.roots[tree], 0)));
    }

    private int topSize(GPNode n, int level) {
        if (n == null || level >= dg) return 0;
        boolean leaf = n.children == null || n.children.length == 0;
        int c = (countTerminals || !leaf) ? 1 : 0;
        if (!leaf) for (GPNode k : n.children) c += topSize(k, level + 1);
        return c;
    }

    private static double fit(GPIndividual g) {
        return ((KozaFitness) g.fitness).standardizedFitness();
    }

    private static String round4(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}