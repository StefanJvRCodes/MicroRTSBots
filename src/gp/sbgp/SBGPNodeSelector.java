package gp.sbgp;

import ec.EvolutionState;
import ec.gp.GPIndividual;
import ec.gp.GPNode;
import ec.gp.GPTree;
import ec.gp.koza.KozaNodeSelector;
import ec.util.Parameter;

import java.util.ArrayList;
import java.util.List;

/**
 * KozaNodeSelector with a depth rule driven by SBGPBreeder's phase:
 *   FIRST_GLOBAL (or no SBGPBreeder)  ordinary Koza selection
 *   LOCAL                             only nodes at level >= Dg (below the frozen area)
 *   GLOBAL                            only nodes at level <  Dg (within the area)
 * Root is level 0. Within the allowed set, the Koza 90/10 nonterminal/terminal split is
 * kept (gp.koza.ns.nonterminals / terminals). If no node is allowed -- a tree shallower
 * than Dg during LOCAL -- falls back to ordinary selection and counts it; the breeder's
 * re-graft keeps the area intact either way.
 */
public class SBGPNodeSelector extends KozaNodeSelector {

    private static final long serialVersionUID = 1L;

    private double pTerm = 0.1, pNonterm = 0.9;

    @Override
    public void setup(final EvolutionState state, final Parameter base) {
        super.setup(state, base);
        Parameter def = defaultBase();
        pTerm = state.parameters.getDoubleWithDefault(base.push("terminals"), def.push("terminals"), 0.1);
        pNonterm = state.parameters.getDoubleWithDefault(base.push("nonterminals"), def.push("nonterminals"), 0.9);
        if (pTerm + pNonterm <= 0.0) { pTerm = 0.1; pNonterm = 0.9; }
    }

    @Override
    public GPNode pickNode(final EvolutionState s, final int subpopulation, final int thread,
                           final GPIndividual ind, final GPTree tree) {
        if (!(s.breeder instanceof SBGPBreeder)) return super.pickNode(s, subpopulation, thread, ind, tree);
        SBGPBreeder b = (SBGPBreeder) s.breeder;
        if (b.phase == SBGPBreeder.Phase.FIRST_GLOBAL) return super.pickNode(s, subpopulation, thread, ind, tree);

        boolean below = (b.phase == SBGPBreeder.Phase.LOCAL);
        List<GPNode> terms = new ArrayList<>(), nonterms = new ArrayList<>();
        collect(tree.child, 0, b.dg, below, terms, nonterms);

        if (terms.isEmpty() && nonterms.isEmpty()) {
            b.selectorFallbacks++;
            return super.pickNode(s, subpopulation, thread, ind, tree);
        }
        boolean takeNonterm = !nonterms.isEmpty()
                && (terms.isEmpty() || s.random[thread].nextDouble() < pNonterm / (pNonterm + pTerm));
        List<GPNode> from = takeNonterm ? nonterms : terms;
        return from.get(s.random[thread].nextInt(from.size()));
    }

    private static void collect(GPNode n, int level, int dg, boolean below,
                                List<GPNode> terms, List<GPNode> nonterms) {
        if (n == null) return;
        boolean ok = below ? level >= dg : level < dg;
        boolean leaf = n.children == null || n.children.length == 0;
        if (ok) (leaf ? terms : nonterms).add(n);
        if (!below && level + 1 >= dg) return;   // nothing deeper can qualify
        if (!leaf) for (GPNode c : n.children) collect(c, level + 1, dg, below, terms, nonterms);
    }
}