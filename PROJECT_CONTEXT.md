# PROJECT_CONTEXT.md — MICRORTSBOTS

**Purpose of this file:** paste it at the start of every session so the assistant has full context
without re-reading the proposal or re-discovering the codebase. It captures the goal, the decisions
already locked in, the folder structure, what every important file does, the microRTS engine API we
rely on, and where we are in the workplan. Keep it updated as the project moves.

---

## 1. One-paragraph orientation

We are building a microRTS game-playing bot with **structure-based genetic programming (SBGP)** for a
COS700 honours research project (University of Pretoria). The engine is Java; our code compiles
against it as a dependency. A hand-authored reference bot (**Chimera**) already plays complete games
(the M0 milestone). The next major step is wiring a GP library (**ECJ**) into `src/gp/` so we can
evolve the bot's decision logic instead of hand-writing it. Everything is designed around one seam:
each bot **scores every legal unit action and takes the argmax**, so an evolved tree can drop straight
into the place where the hand-written scorer currently sits.

**Current status:** M0 done. Chimera beats RandomBiased 4–0, loses to WorkerRush 0–4 (a weight-tuning
gap, not a structural one), ~13–20 ms per cycle against a 100 ms budget. ECJ not yet wired.

---

## 2. Research goal and the four configurations

The project evolves a **complete, autonomous** microRTS bot (resource gathering, production, combat in
one program) and asks two questions:

1. Does **structure-based GP** beat an ordinary-GP baseline, and existing RL and rule-based bots?
2. Does wrapping the evolved bot in an **Adapter-RL** module (Jin, Slabaugh & Lucas) further improve it?

That gives **four configurations**, all sharing the same machinery and differing only in how a unit's
actions get scored:

| Config | How actions are scored |
|---|---|
| Ordinary GP | an evolved tree over the terminal set |
| Structure-based GP | same, but tree *structure* is evolved separately from its contents |
| Ordinary GP + adapter | tree score → softmax(temperature) → adapter adjustment → argmax |
| SBGP + adapter | as above, with the SBGP tree |

**Evaluation:** win rate on **held-out** maps and opponents (Coac, mayari, TMA, a pre-trained PPO
agent), classic track, 100 ms/cycle, deterministic + fully observable. Train on built-in scripted
bots (WorkerRush, LightRush, NaiveMCTS) on training maps only; report on the disjoint held-out set.

**Terminology note:** "structure-based GP" here means specifically **Scheepers & Pillay** (ref [18] in
the proposal — Prof. Pillay is a supervisor and co-author). It is not a generic term; the Phase-4
extension should follow that method's separation of program structure from contents.

---

## 3. Design decisions already locked (do not re-litigate without reason)

- **Java + ECJ for the GP.** Every fitness evaluation is a full microRTS game, which is native Java.
  Running GP in Java means the evolved individual *is* an `ai.core.AI` and plays in-process with zero
  serialization. Python/DEAP would force every game across the JVM boundary — unaffordable at thousands
  of evals × many seeds. Python is reserved for the adapter (PyTorch/GPU) and the RL baseline
  (MicroRTS-Py), not the GP.
- **Score-then-argmax representation.** Bots never branch `if worker then harvest`. For each idle unit
  they enumerate legal `UnitAction`s, score each, and argmax. The hand-written `scoreAction()` in
  Chimera is the exact slot an evolved tree replaces.
- **G2 is already satisfied.** The workplan's G2 gate ("GP can emit an action distribution") is not a
  late surprise: the per-unit `scores[]` array in `Chimera.getAction()` *is* that distribution. The
  adapter softmaxes and adjusts it. This is why the representation choice above was made at M0.
- **Terminal set lives in `gp.Features`,** independent of any bot, so the GP vocabulary is defined once
  and every configuration reads the same information. Whatever is not a terminal, the GP cannot evolve.

---

## 4. Folder structure

```
MICRORTSBOTS/
├── PROJECT_CONTEXT.md       ← this file
├── README.md                ← quickstart + layout (user-facing)
├── build.sh                 ← compiles everything under src/ → out/
├── run.sh                   ← runs a headless match via eval.Match
├── .gitignore               ← ignores out/ and *.class
│
├── lib/                     ← DEPENDENCIES (not our code, do not edit)
│   ├── microrts.jar         ← the engine, built from santiontanon/microrts
│   ├── jdom.jar             ← engine runtime dep (map XML parsing)
│   ├── minimal-json-0.9.4.jar   ← engine runtime dep
│   └── bots/                ← held-out evaluation panel (competition bots as jars)
│       ├── Coac.jar         ← Coacai (2025 benchmark winner)
│       ├── mayariBot.jar    ← Mayari (2025 benchmark winner)
│       ├── Izanagi.jar
│       ├── TiamatBot.jar
│       ├── Droplet.jar
│       ├── GRojoA3N.jar
│       └── MixedBot.jar
│
├── maps/                    ← game maps (from the engine repo)
│   ├── 8x8/                 ← basesWorkers8x8.xml (default), + A–L variants, melee, obstacle, ...
│   └── 16x16/               ← basesWorkers16x16.xml + variants
│
├── src/
│   ├── bots/                ← every playable AI  (package `bots`)
│   │   ├── Chimera.java     ← M0 reference bot (score-then-argmax)
│   │   └── StefanFirstBot.java   ← (to be added by the team; needs `package bots;`)
│   ├── gp/                  ← the GP layer  (package `gp`)
│   │   └── Features.java    ← TERMINAL SET — bots import gp.Features
│   └── eval/                ← running & measuring  (package `eval`)
│       └── Match.java       ← headless runner; grows into the tournament harness
│
├── config/                  ← (empty) ECJ .params + experiment configs go here (Phase 3a+)
├── results/                 ← (empty) match logs, evolved trees, seeds — reproducibility archive
└── out/                     ← compiled classes (git-ignored, created by build.sh)
```

**Dependency direction:** `eval → bots → gp`. The harness runs bots; bots read the `gp` terminal set;
`gp` depends on nothing of ours. Keep it that way.

---

## 5. What is in each folder

- **`lib/`** — the microRTS engine (`microrts.jar`) plus the two runtime jars it needs to load maps
  (`jdom`, `minimal-json`), and `lib/bots/` holding the competition benchmark bots as compiled jars.
  This is third-party dependency code; we never edit it. (Note: the engine also ships a `weka.jar`, but
  our runtime path does **not** need it, so it is deliberately not bundled.)
- **`maps/`** — XML map files loaded at runtime by relative path. `maps/8x8/basesWorkers8x8.xml` is the
  default working map. Training vs held-out map splits will be drawn from here (Phase 2).
- **`src/bots/`** — every AI that can play. Each is a subclass of `ai.core.AI` (usually via
  `AIWithComputationBudget`). Chimera is here; the plain-GP, SBGP, and adapter bots will join it.
- **`src/gp/`** — the genetic-programming layer. Right now just the terminal set (`Features`). Will
  gain the function set, the ECJ problem/fitness classes, and the tree→scorer bridge.
- **`src/eval/`** — anything that runs games and measures outcomes. `Match` today; the full
  tournament harness (many seeds, side-swapping, held-out panel, CSV logging) grows from it.
- **`config/`** — will hold ECJ parameter files and experiment configs. Empty now (`.gitkeep`).
- **`results/`** — will hold match logs, evolved programs, and random seeds; this is the
  reproducibility archive the proposal commits to version-controlling. Empty now (`.gitkeep`).
- **`out/`** — build output, regenerated by `build.sh`, never committed.

---

## 6. Important files, in detail

### `src/gp/Features.java` — the terminal set
Static, side-effect-free methods returning `double`, each exposing one fact about a
`(unit, candidate action, state)` triple. This is the vocabulary the GP evolves over; the hand-written
Chimera scorer reads the same methods. Current terminals:
- **Action type:** `aIsNone/Move/Harvest/Return/Produce/Attack`.
- **Produce target:** `aProducesWorker/Combat/Building`.
- **Acting unit:** `uIsWorker/Base/Barracks/Combat`, `uCarrying`, `uHpFrac`.
- **Economy / army:** `myResources`, `myWorkerCount`, `myUnitCount`, `enemyUnitCount`, `myBarracksCount`.
- **Spatial (Manhattan; no pathfinding yet):** `distNearestEnemy`, `distNearestResource`,
  `distEnemyBase`, `mapArea`, and `movesToward(u,a,tx,ty)` (the only terminal that reads the action's
  direction to look one step ahead). Helper `nearestEnemy(...)` returns the unit, used by the scorer.
- **Open for revision (Phase 3a):** pathfinding-aware distance, "enemy attack power in range", one-hot
  unit types vs boolean predicates.

### `src/bots/Chimera.java` — the M0 reference bot
Extends `AIWithComputationBudget(100, -1)`. `getAction(player, gs)`:
1. bail if `!gs.canExecuteAnyAction(player)`;
2. reserve resources for units already mid-action (durative actions in flight);
3. for each of the player's **idle** units (`gs.getActionAssignment(u) == null`): enumerate
   `u.getUnitActions(gs)`, score each with `scoreAction(...)` into a `scores[]` array, then pick the
   highest-scoring action whose `resourceUsage(...).consistentWith(pa.getResourceUsage(), gs)` holds;
   fall back to `NONE`;
4. return the assembled `PlayerAction`.

`scoreAction(u, a, gs, player)` is a hand-authored economy+rush heuristic over `Features` (workers
harvest/return, build one barracks, produce a few workers then army, attack in range, move combat units
toward the nearest enemy). **It is intentionally not tuned to be strong** — those weights are exactly
what the GP will evolve. The `scores[]` vector is the adapter/G2 hook.

### `src/eval/Match.java` — the headless runner
`java eval.Match [map] [opponent] [games]`. Plays Chimera vs WorkerRush/LightRush/RandomBiased,
**swapping sides each game** to cancel first-player advantage, and prints W-D-L plus the worst
per-cycle decision time (an early read on the G1 100 ms gate). This is the seed of the Phase-2
evaluation harness; wiring the `lib/bots/*.jar` panel (Coac, mayari) in `makeOpponent` is a later
one-line-ish addition (they load via reflection).

### `build.sh` / `run.sh`
`build.sh`: `javac -cp "lib/microrts.jar:lib/*" -d out $(find src -name "*.java")`.
`run.sh`: `java -cp "out:lib/microrts.jar:lib/*" eval.Match "$@"`.
Both `cd` to their own directory first, so they work from anywhere.

### `README.md`
User-facing quickstart and layout. Overlaps this file but is shorter and less strategic.

---

## 7. Build & run

Requires a **JDK** (Java 21 used; `javac -version` must work). No Maven/Ant/Gradle needed — plain
`javac`. All other dependencies are bundled.

```
./build.sh
./run.sh maps/8x8/basesWorkers8x8.xml WorkerRush 10
```

The classpath pattern everywhere is `lib/microrts.jar:lib/*` (compile) and `out:lib/microrts.jar:lib/*`
(run). `lib/*` expands to the jars directly in `lib/` (jdom, minimal-json); `lib/bots/*` are added when
the harness loads competition bots.

---

## 8. microRTS engine API cheat-sheet (so we don't re-derive it each session)

**Base classes.** `ai.core.AI` is abstract with: `reset()`, `getAction(int player, GameState) throws
Exception`, `clone()`, `getParameters()` (return `new ArrayList<>()` if none), and a
`reset(UnitTypeTable)` hook. `ai.core.AIWithComputationBudget(int timeBudget, int iterationsBudget)`
adds the budget (`TIME_BUDGET` defaults 100 ms; `-1` = unlimited). Extend the latter.

**Building a legal `PlayerAction` (the raw pattern — see `ai.RandomBiasedAI` in the engine):**
```
PhysicalGameState pgs = gs.getPhysicalGameState();
PlayerAction pa = new PlayerAction();
if (!gs.canExecuteAnyAction(player)) return pa;
// reserve in-flight durative actions:
for (Unit u : pgs.getUnits()) {
    UnitActionAssignment uaa = gs.getActionAssignment(u);
    if (uaa != null) pa.getResourceUsage().merge(uaa.action.resourceUsage(u, pgs));
}
for (Unit u : pgs.getUnits()) {
    if (u.getPlayer() != player || gs.getActionAssignment(u) != null) continue;
    List<UnitAction> legal = u.getUnitActions(gs);   // engine returns only LEGAL actions
    // choose one 'ua' ...
    ResourceUsage ru = ua.resourceUsage(u, pgs);
    if (ru.consistentWith(pa.getResourceUsage(), gs)) { pa.getResourceUsage().merge(ru); pa.addUnitAction(u, ua); }
    else pa.addUnitAction(u, none);
}
```

**`rts.UnitAction`:** types `TYPE_NONE=0, TYPE_MOVE=1, TYPE_HARVEST=2, TYPE_RETURN=3, TYPE_PRODUCE=4,
TYPE_ATTACK_LOCATION=5`; directions `DIRECTION_NONE=-1, UP=0, RIGHT=1, DOWN=2, LEFT=3`. Getters:
`getType()`, `getDirection()`, `getUnitType()` (the produced type for PRODUCE, else null).

**`rts.units.UnitType` public fields:** `name`, `cost`, `hp`, `minDamage`, `maxDamage`, `attackRange`,
`produceTime`, `harvestAmount`, `sightRadius`, `isResource`, `isStockpile`, `canHarvest`, `canMove`,
`canAttack`. Unit-type names: `Resource, Base, Barracks, Worker, Light, Heavy, Ranged`.

**`rts.units.Unit`:** `getPlayer()`, `getType()`, `getX()`, `getY()`, `getHitPoints()`,
`getResources()` (>0 means a worker is carrying), `getID()`, `getUnitActions(GameState)`.

**`rts.PhysicalGameState` (pgs):** `getWidth()`, `getHeight()`, `getUnits()` (List<Unit>),
`getUnitAt(x,y)`, `getPlayer(int)`. **`rts.Player`:** `getResources()`, `getID()`.

**`rts.GameState`:** `getPhysicalGameState()`, `getActionAssignment(Unit)` (null = idle),
`canExecuteAnyAction(int player)`, `issueSafe(PlayerAction)`, `cycle()` (advances one frame, returns
true when game over), `winner()` (-1 draw, else player id), `getTime()` (current cycle).

**Headless game loop:**
```
UnitTypeTable utt = new UnitTypeTable();
PhysicalGameState pgs = PhysicalGameState.load("maps/8x8/basesWorkers8x8.xml", utt);
GameState gs = new GameState(pgs, utt);
boolean over = false;
while (!over && gs.getTime() < MAXCYCLES) {
    PlayerAction a0 = ai0.getAction(0, gs);
    PlayerAction a1 = ai1.getAction(1, gs);
    gs.issueSafe(a0); gs.issueSafe(a1);
    over = gs.cycle();
}
int winner = gs.winner();
```

**Stock opponents in the engine:** `ai.abstraction.WorkerRush(utt, new BFSPathFinding())`,
`ai.abstraction.LightRush(...)`, `ai.RandomBiasedAI()`. Higher-level scripted bots extend
`ai.abstraction.AbstractionLayerAI` (convenience `harvest/train/build/attack/move` + `translateActions`);
we work at the raw `UnitAction` level instead, because the GP needs per-action scores.

---

## 9. Environment & reproducibility

- **Engine origin:** built from a clean clone of `https://github.com/santiontanon/microrts` (ships the
  benchmark bots under its own `lib/bots/`, and the maps). Pin and record the commit hash.
- **Rebuild `microrts.jar`:**
  ```
  git clone --depth 1 https://github.com/santiontanon/microrts.git
  cd microrts
  find src -name "*.java" > sources.txt
  javac -cp "lib/*:lib/bots/*" -d out @sources.txt   # compiles clean, ~483 classes
  jar cf microrts.jar -C out .
  ```
- **RL side (later):** the pre-trained PPO baseline and the adapter's neural component use
  `Farama-Foundation/MicroRTS-Py` (Python + PyTorch), a separate install from the Java engine.
- **Hardware (per proposal):** single workstation, NVIDIA GTX/RTX 5070 (12 GB), CUDA + PyTorch. GP
  evolution is CPU-bound and embarrassingly parallel (each fitness eval is an independent Java game);
  the GPU is only for adapter training and any CNN state-evaluator.
- **Reproducibility commitments:** version-control seeds, configs, evolved programs, and match logs
  (that's what `config/` and `results/` are for).

---

## 10. Where we are in the workplan

| Phase / gate | What | State |
|---|---|---|
| 1 Proposal | approved | done |
| 2 Environment & baselines | engine building, harness, reproduce baseline win rates, fix train/eval splits | **partly done** (engine + jar + harness up; splits & baseline reproduction pending) |
| 3a GP design | function + terminal sets, fitness, evolutionary loop | terminal set drafted (`Features`); rest pending |
| **M0** hand-authored bot plays a full game | **DONE** (Chimera) | ✅ |
| 3b GP implementation & first evolved bot | ECJ loop, evolve ordinary GP bot | **next up** |
| **M1** evolved bot beats a weak baseline | e.g. WorkerRush | pending |
| **G1** ≤100 ms/cycle | apply parsimony/tree-depth caps if exceeded | early signal green (~13–20 ms for Chimera) |
| 4 Structure-based GP | evolve structure separately (Scheepers & Pillay) | pending |
| **G2** GP emits an action distribution | already satisfied by the `scores[]` design | ✅ by design |
| 5 Adapter module | PyTorch adapter wrapping the frozen GP policy, GPU-trained | pending |
| 6 Evaluation & analysis | full ablation over seeds, stats tests (Mann-Whitney U, α=0.05, multiple-comparison correction) | pending |
| 7 Write-up | | pending |

**Immediate next step:** wire ECJ into `src/gp/` — a trivial symbolic-regression run to prove it
evolves, then define the microRTS function set + fitness (play games vs training scripts) so a tree
maps to `scoreAction()`. Then the ordinary-GP bot is Chimera with `scoreAction` swapped for a tree.

---

## 11. Open decisions / TODO backlog

- [ ] Review and finalize the **terminal set** with the team/supervisors before building the GP
      (pathfinding distance? enemy-attack-power-in-range? one-hot types?).
- [ ] Confirm **ECJ** as the GP library (recommended) and add it to `lib/` + `config/`.
- [ ] Add **`StefanFirstBot.java`** to `src/bots/` with `package bots;`.
- [ ] Define the **function set** (`+ - *`, protected `/`, `IF-greater`, `min`, `max`, ERCs) in `src/gp/`.
- [ ] Choose the **fitness signal**: built-in `SimpleSqrtEvaluationFunction3`, a dense shaped signal
      for early evolution, or a learned CNN evaluator.
- [ ] Fix the **train/eval opponent + map splits** (Phase 2 deliverable).
- [ ] Extend `eval.Match` into the full **harness** (many seeds, side-swap, `lib/bots` panel, CSV logs).
- [ ] Decide how the frozen GP tree exposes its **score vector to the Python adapter** (socket vs file
      vs MicroRTS-Py bridge) — Phase 5.

---

## 12. People

- **Team (4 students):** u21746134 (Shahil Parbhoo Narsing), u22550055, u22555855 (Heinrich Niebuhr),
  u04948123 (Milan Kruger).
- **Supervisors:** Prof. Nelishia Pillay (co-author of the structure-based GP method this project
  builds on), Dr. Thambo Nyathi.
- **Primary contact in these sessions:** Heinrich.

---

Session update (2026-08-02): ECJ wired, Phase 3b implemented and run.

ECJ v27 added as lib/ecj-27.jar. No prebuilt jar exists — the GitHub v27 release ships source only and the project is Maven-based, so it was built from source with plain javac (572 files, 0 errors) after excluding four optional packages that need external libraries we don't use: ec/gp/push + ec/app/push (pshecj), ec/display + ec/app/gui (JFreeChart/iText), ec/eda/cmaes + ec/eda/amalgam (EJML). Critically, the 161 .params resources under src/main/resources must be copied into the jar — koza.params, simple.params and ec.params are loaded off the classpath at runtime. Full rebuild recipe is in ECJ_NOTES.md.

Two ECJ gotchas worth not rediscovering: parent params inherit from inside the jar via parent.0 = @ec.gp.koza.GPKozaDefaults koza.params (the class is GPKozaDefaults, not GPDefaults; a wrong name reports itself as a misleading parse error), and init must be ec.gp.GPInitializer, never ec.simple.SimpleInitializer, or setup throws a ClassCastException at "Initializing Generation 0".

Smoke test (config/symreg.params, src/gp/symreg/) confirms ECJ evolves in this setup: Koza quartic recovered perfectly at generation 6, 3500 evaluations, ~0.6 s. Seed 4242 reproduced bit-for-bit across two machines and two JDK 21 patch versions (21.0.9 and 21.0.11) — the reproducibility commitment holds. Evolved solution was the Horner form, not the literal target expression.

Phase 3b implemented: gp.ScoreData (GPData carrying score + unit/action/state/player), gp.nodes.* (function set + - * /prot min max if>, ScoreERC with ±10 range, and FeatureNode — one class that binds to any gp.Features method by name via reflection, so adding a terminal is three lines of params, not a new file), bots.EvolvedBot (Chimera's exact loop with scoreAction replaced by tree evaluation; scores[] and the G2 adapter hook intact), eval.Panel (opponent factory + headless runner), gp.MicroRTSProblem (fitness), gp.BenchmarkStatistics (end-of-run W/T/L table vs the 18 scripted bots). Config in config/microrts.params.

Two fitness findings, both discovered only by running it — these are results, not bugs to hide. (1) Penalising cycles-used on a loss pays evolution to lose faster; a bot suiciding at cycle 100 outscores one surviving to 1400. Speed is now a reward on wins only, full penalty on draws (the turtle case), and rewards survival among losses. (2) Scoring the final game state is worthless in microRTS: you lose when you have no units, so every losing final state is identical, the margin term sat at its worst value for the whole population, and the fitness landscape was exactly flat — every individual scoring 2.75 in every generation. Fitness now samples SimpleSqrtEvaluationFunction3 every 50 cycles during play and uses the mean. A monotone gradient appeared immediately.

Cost is far lower than assumed: ~190 games/second on 8x8 vs scripted bots. 6,000 evaluations (12,000 games) plus a 180-game benchmark ran in 34 seconds. Populations of 500 and 100+ generations are affordable; the earlier "hours per run" estimate was wrong.

Current behaviour: survival, not victory. Small runs (pop 200 × 30 gens) produce bots that draw against PassiveAI, RandomAI, HeavyDefense and RangedDefense but win nothing — a draw scores 1.5 against a loss's 2.0+, so "don't die" is the reachable local optimum. Next test is a curriculum starting on PassiveAI where a win is actually reachable. Worst per-cycle decision time 32 ms against the 100 ms G1 gate (headroom, but mean tree size grew 19 → 77 in four generations, so bloat is real here in a way it wasn't in symbolic regression; ec.parsimony.* is available if needed).

Not done: evolved trees are not persisted — the benchmark runs in-process on the best-of-run individual, deliberately sidestepping ECJ individual serialization. printIndividual plus a loader is needed before any result goes in the write-up. The FeatureNode ↔ Features.java binding is the one piece never verified against the real Features.java (a stand-in was used); a mismatch fails loudly at startup listing available methods. src/gp/symreg/ is throwaway and should be deleted once 3b is stable.

I'd left off mid-answer on your UI question — the short version is that the engine already ships the renderer (gui.PhysicalGameStatePanel.newVisualizer(gs, dx, dy) returning a PhysicalGameStateJFrame, then setStateCloning(gs) + repaint() per cycle), so a watch mode is roughly 40 lines wrapping Panel.play. And the competition bots load as ai.coac.CoacAI(UnitTypeTable) and mayariBot.mayari(UnitTypeTable) with lib/bots/* on the classpath. Say the word and I'll write both.

*Keep this file current: when a decision is made, a file is added, or a milestone is hit, update the
relevant section so the next session starts from truth.*
