package rl;

import ai.core.AI;
import ai.evaluation.EvaluationFunction;
import ai.evaluation.SimpleSqrtEvaluationFunction3;
import bots.EvolvedBot;
import eval.Bots;
import gp.IndividualLoader;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.UnitAction;
import rts.UnitActionAssignment;
import rts.units.Unit;
import rts.units.UnitTypeTable;

import java.util.Arrays;
import java.util.List;

/**
 * The RL environment for Adapter-RL, driven from Python via JPype.
 *
 * One game at a time. reset(map, opponent, side) starts it; step(codeByCell) applies one
 * ActionCodec code per map cell (ignored where no unit decides) and advances to the next
 * DECISION POINT -- the next cycle where the base policy has at least one idle unit.
 * Cycles with no decisions are played through, so every step is a real decision.
 *
 * GAME LOOP IS Panel.play's, call for call: both players' actions computed on the same
 * state, issued in player order, then cycle(). EnvTest checks that driving this env with
 * the base codes reproduces Panel.play exactly (winner and final cycle).
 *
 * After reset() and every step(), Python reads (row-major, cell = y*width + x):
 *   obs()          float[CHANNELS * H * W], channel-major
 *   mask()         byte[H * W * ActionCodec.N], 1 = legal code for the unit at that cell
 *   tieMask()      byte[H * W * ActionCodec.N], 1 = code in the base policy's TIE SET
 *   baseCode()     int[H * W], the base policy's code, -1 where no unit decides
 *   executedCode() int[H * W], what each unit actually got at the PREVIOUS step, -1 = none
 *
 * 2026-10-16: TIE SET. For a deciding unit: the base pick, plus every LATER legal action
 * (higher index) with exactly the same score as the pick. Equal-score actions BEFORE the
 * pick are excluded on purpose: propose() walks ties in list order, so those were tried
 * and were unaffordable. If the pick came from the affordability fallback (top score
 * unaffordable), the same rule applies relative to the pick's score. NONE fallback
 * (pick = -1): the tie set is {base code} only. The base code is always in the tie set.
 * These are the decisions engine list order made, not the evolved tree.
 *
 * Sending a cell's baseCode back means "do what the base policy did" and maps to the exact
 * base pick (including the rare NONE fallback). A code not in the mask falls back to the
 * base pick and is counted in illegalRequests. commit()'s affordability fallback is counted
 * in affordFallbacks.
 *
 * Reward is NOT computed here: done()/winner() and evalNow() (the same normalised
 * SimpleSqrtEvaluationFunction3 value the GP fitness samples) are exposed, and Python
 * decides how to combine them.
 */
public final class AdapterEnv {

    public static final int CHANNELS = 29;
    public static final int C_MINE = 0, C_ENEMY = 1, C_TYPE0 = 2, C_HP = 9, C_CARRY = 10,
            C_RESAMT = 11, C_BUSY = 12, C_CURACT0 = 13, C_WALL = 18, C_DECIDE = 19,
            C_BASEACT0 = 20, C_MYRES = 26, C_ENRES = 27, C_TIME = 28;
    public static final String[] TYPE_NAMES =
            {"Resource", "Base", "Barracks", "Worker", "Light", "Heavy", "Ranged"};

    private final UnitTypeTable utt = new UnitTypeTable();
    private final EvolvedBot base;
    private final EvaluationFunction ef = new SimpleSqrtEvaluationFunction3();
    private final int maxCycles;

    private GameState gs;
    private AI opp;
    private int side;
    private boolean over;
    private int w, h;
    private EvolvedBot.Proposal prop;
    private int[] cellOf = new int[0];
    private int[][] legalIdxByCode = new int[0][];

    private float[] obs = new float[0];
    private byte[] mask = new byte[0];
    private byte[] tie = new byte[0];
    private int[] baseCode = new int[0];
    private int[] executedCode = new int[0];

    private boolean done = true;
    private int winner = -1;

    /** Cumulative diagnostics. */
    public long steps, decisions, overrides, illegalRequests, unencodable, duplicateCodes, affordFallbacks;
    /** Tie-set diagnostics: decisions seen, decisions with tie set > 1, summed tie-set size. */
    public long tieDecisions, tiedDecisions, tieSizeSum;

    public AdapterEnv(String baseSpec, int maxCycles) {
        this.base = IndividualLoader.makeBot(baseSpec, utt);
        this.maxCycles = maxCycles;
    }

    // ---------------------------------------------------------------- game control

    public void reset(String mapPath, String opponent, int side) throws Exception {
        this.side = side;
        this.opp = Bots.make(opponent, utt);
        this.gs = new GameState(PhysicalGameState.load(mapPath, utt), utt);
        base.reset();
        opp.reset();

        PhysicalGameState pgs = gs.getPhysicalGameState();
        if (pgs.getWidth() != w || pgs.getHeight() != h || obs.length == 0) {
            w = pgs.getWidth();
            h = pgs.getHeight();
            obs = new float[CHANNELS * w * h];
            mask = new byte[w * h * ActionCodec.N];
            tie = new byte[w * h * ActionCodec.N];
            baseCode = new int[w * h];
            executedCode = new int[w * h];
        }
        Arrays.fill(executedCode, -1);
        over = false;
        done = false;
        winner = -1;
        advance();
    }

    public void step(int[] codeByCell) throws Exception {
        if (done) throw new IllegalStateException("step() after the game ended; call reset()");
        List<EvolvedBot.UnitDecision> ds = prop.decisions;
        int n = ds.size();
        int[] chosen = new int[n];
        int[] exec = new int[n];

        for (int k = 0; k < n; k++) {
            EvolvedBot.UnitDecision d = ds.get(k);
            int cell = cellOf[k];
            int code = codeByCell[cell];
            if (code == baseCode[cell]) {
                chosen[k] = d.pick;
                continue;
            }
            int idx = (code >= 0 && code < ActionCodec.N) ? legalIdxByCode[k][code] : -1;
            if (idx < 0) {
                illegalRequests++;
                chosen[k] = d.pick;
            } else {
                chosen[k] = idx;
                overrides++;
            }
        }

        PlayerAction ours = EvolvedBot.commit(gs, side, ds, chosen, exec);

        Arrays.fill(executedCode, -1);
        for (int k = 0; k < n; k++) {
            if (exec[k] != chosen[k]) affordFallbacks++;
            EvolvedBot.UnitDecision d = ds.get(k);
            executedCode[cellOf[k]] = exec[k] < 0 ? ActionCodec.NONE
                    : ActionCodec.encode(d.unit, d.legal.get(exec[k]));
        }
        steps++;
        decisions += n;

        PlayerAction theirs = opp.getAction(1 - side, gs);
        issue(ours, theirs);
        over = gs.cycle();
        advance();
    }

    /** Play through cycles with no decisions; stop at the next decision point or the end. */
    private void advance() throws Exception {
        while (true) {
            if (over || gs.getTime() >= maxCycles) {
                done = true;
                winner = gs.winner();
                prop = null;
                cellOf = new int[0];
                Arrays.fill(mask, (byte) 0);
                Arrays.fill(tie, (byte) 0);
                Arrays.fill(baseCode, -1);
                encodeObs();
                return;
            }
            prop = base.propose(side, gs);
            if (!prop.decisions.isEmpty()) {
                encodeDecisionPoint();
                return;
            }
            PlayerAction theirs = opp.getAction(1 - side, gs);
            issue(prop.action, theirs);
            over = gs.cycle();
        }
    }

    private void issue(PlayerAction ours, PlayerAction theirs) {
        if (side == 0) { gs.issueSafe(ours); gs.issueSafe(theirs); }
        else           { gs.issueSafe(theirs); gs.issueSafe(ours); }
    }

    // ---------------------------------------------------------------- encoding

    private void encodeDecisionPoint() {
        List<EvolvedBot.UnitDecision> ds = prop.decisions;
        int n = ds.size();
        Arrays.fill(mask, (byte) 0);
        Arrays.fill(tie, (byte) 0);
        Arrays.fill(baseCode, -1);
        cellOf = new int[n];
        legalIdxByCode = new int[n][];

        for (int k = 0; k < n; k++) {
            EvolvedBot.UnitDecision d = ds.get(k);
            int cell = d.unit.getY() * w + d.unit.getX();
            cellOf[k] = cell;
            int[] table = new int[ActionCodec.N];
            Arrays.fill(table, -1);
            int[] codeOf = new int[d.legal.size()];
            for (int i = 0; i < d.legal.size(); i++) {
                int code = ActionCodec.encode(d.unit, d.legal.get(i));
                codeOf[i] = code;
                if (code < 0) { unencodable++; continue; }
                if (table[code] >= 0) { duplicateCodes++; codeOf[i] = -1; continue; }
                table[code] = i;
                mask[cell * ActionCodec.N + code] = 1;
            }
            legalIdxByCode[k] = table;
            int bc = d.pick < 0 ? ActionCodec.NONE
                    : ActionCodec.encode(d.unit, d.legal.get(d.pick));
            baseCode[cell] = bc;

            // Tie set: the pick plus later legal actions with exactly the pick's score.
            int off = cell * ActionCodec.N;
            int nTie = 0;
            if (bc >= 0) { tie[off + bc] = 1; nTie = 1; }
            if (d.pick >= 0) {
                double ref = d.scores[d.pick];
                for (int i = d.pick + 1; i < d.legal.size(); i++) {
                    int c = codeOf[i];
                    if (c < 0 || d.scores[i] != ref || tie[off + c] != 0) continue;
                    tie[off + c] = 1;
                    nTie++;
                }
            }
            tieDecisions++;
            tieSizeSum += nTie;
            if (nTie > 1) tiedDecisions++;
        }
        encodeObs();
    }

    private void encodeObs() {
        Arrays.fill(obs, 0f);
        int hw = w * h;
        PhysicalGameState pgs = gs.getPhysicalGameState();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (pgs.getTerrain(x, y) == PhysicalGameState.TERRAIN_WALL) {
                    obs[C_WALL * hw + y * w + x] = 1f;
                }
            }
        }

        for (Unit u : pgs.getUnits()) {
            int cell = u.getY() * w + u.getX();
            int owner = u.getPlayer();
            if (owner == side) obs[C_MINE * hw + cell] = 1f;
            else if (owner >= 0) obs[C_ENEMY * hw + cell] = 1f;

            int t = typeIndex(u.getType().name);
            if (t >= 0) obs[(C_TYPE0 + t) * hw + cell] = 1f;

            obs[C_HP * hw + cell] = (float) u.getHitPoints() / Math.max(1, u.getType().hp);
            if (u.getType().isResource) obs[C_RESAMT * hw + cell] = Math.min(1f, u.getResources() / 25f);
            else if (u.getResources() > 0) obs[C_CARRY * hw + cell] = 1f;

            UnitActionAssignment uaa = gs.getActionAssignment(u);
            if (uaa != null) {
                obs[C_BUSY * hw + cell] = 1f;
                int at = uaa.action.getType();
                if (at >= UnitAction.TYPE_MOVE && at <= UnitAction.TYPE_ATTACK_LOCATION) {
                    obs[(C_CURACT0 + at - 1) * hw + cell] = 1f;
                }
            }
        }

        if (prop != null) {
            for (int k = 0; k < prop.decisions.size(); k++) {
                EvolvedBot.UnitDecision d = prop.decisions.get(k);
                int cell = cellOf[k];
                obs[C_DECIDE * hw + cell] = 1f;
                int bt = d.pick < 0 ? UnitAction.TYPE_NONE : d.legal.get(d.pick).getType();
                obs[(C_BASEACT0 + bt) * hw + cell] = 1f;
            }
        }

        float myRes = pgs.getPlayer(side).getResources() / 20f;
        float enRes = pgs.getPlayer(1 - side).getResources() / 20f;
        float time = gs.getTime() / (float) maxCycles;
        Arrays.fill(obs, C_MYRES * hw, (C_MYRES + 1) * hw, myRes);
        Arrays.fill(obs, C_ENRES * hw, (C_ENRES + 1) * hw, enRes);
        Arrays.fill(obs, C_TIME * hw, (C_TIME + 1) * hw, time);
    }

    private static int typeIndex(String name) {
        for (int i = 0; i < TYPE_NAMES.length; i++) if (TYPE_NAMES[i].equals(name)) return i;
        return -1;
    }

    // ---------------------------------------------------------------- accessors

    public float[] obs()          { return obs; }
    public byte[] mask()          { return mask; }
    public byte[] tieMask()       { return tie; }
    public int[] baseCode()       { return baseCode; }
    public int[] executedCode()   { return executedCode; }
    public int width()            { return w; }
    public int height()           { return h; }
    public int channels()         { return CHANNELS; }
    public int actions()          { return ActionCodec.N; }
    public boolean done()         { return done; }
    public int winner()           { return winner; }
    public int side()             { return side; }
    public int time()             { return gs == null ? 0 : gs.getTime(); }
    public int numDecisions()     { return prop == null ? 0 : prop.decisions.size(); }

    /** Normalised state value for our side in [0,1], as in Panel (0.5 = even). */
    public double evalNow() {
        float raw = ef.evaluate(side, 1 - side, gs);
        float bound = ef.upperBound(gs);
        if (bound <= 0) return 0.5;
        double v = (raw / bound + 1.0) / 2.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    public String diagnostics() {
        return "steps " + steps + ", decisions " + decisions + ", overrides " + overrides
             + ", illegal " + illegalRequests + ", unencodable " + unencodable
             + ", duplicate codes " + duplicateCodes + ", afford fallbacks " + affordFallbacks
             + ", tied " + tiedDecisions + "/" + tieDecisions
             + String.format(" (mean tie size %.2f)", tieDecisions == 0 ? 0.0 : (double) tieSizeSum / tieDecisions);
    }
}