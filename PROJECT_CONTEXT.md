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

Session update (2026-08-10): watch mode built; a lost-file scare resolved.

Added src/eval/Watch.java (GUI runner: java eval.Watch [map] [bot0] [bot1] [msPerCycle] [maxCycles], wrapping gui.PhysicalGameStatePanel.newVisualizer + setStateCloning/repaint per cycle, printing winner and worst per-cycle decision time) and src/eval/Bots.java (single name→ai.core.AI factory). Bots splits into a compile-time tier for engine and our own bots, and a reflection tier for lib/bots/* competition jars plus any constructor we don't want to guess — so build.sh needs no change and a wrong guess fails at the moment you request that bot, not at build time. Deliberately a separate entry point from the headless path so evolution never loads Swing. Also run-watch.sh / run-watch.ps1, whose classpath adds lib/bots/* (note: run.sh does not). Confirmed working: Chimera vs PassiveAI renders and wins; Chimera vs WorkerRush loses at cycle 445, worst cycle 7.7 ms vs the 100 ms G1 gate — consistent with the recorded 0–4, no regression. Coac/mayari reflection path not yet exercised.

Most of the session went to recovering from a file-naming corruption in src/gp/, which is worth recording because it cost an hour. Eight files under src/gp/nodes/ had bodies written to the wrong filenames, shifted one slot (Add.java contained Min, Div.java contained Add, and so on), so the whole GP layer failed to compile and EvolvedBot reported as a missing symbol at every call site — a cascade, not a real absence. Fixed with fix-gp-names.ps1 (added to repo root; dry-run by default, -Apply to act, two-phase rename via git mv so cyclic shuffles can't collide). EvolvedBot.java genuinely was absent from every branch, and was recovered from stash@{0} — it had never been committed. Lesson for the reproducibility commitment: commit early and often, including broken work. Windows/PowerShell gotchas hit along the way: classpath separator is ; not :; @sources.txt must be quoted or PowerShell reads @ as splatting; git revisions need quoting ("stash@{0}"); Set-Content -Encoding utf8 in PS 5.1 writes a BOM that javac rejects as illegal character: '\ufeff' — use [System.IO.File]::WriteAllText with UTF8Encoding $false; and downloaded scripts need Unblock-File plus Set-ExecutionPolicy -Scope Process Bypass.

Two open items carried forward: gp.nodes.Max does not exist but config/microrts.params:82 sets gp.fs.0.func.5 = gp.nodes.Max — its file was overwritten during the shift, and ECJ will fail at setup on the next run (it resolves reflectively, so javac won't catch it). And the stashed Chimera.java differs from the branch version; worth a git diff "stash@{0}" -- src/bots/Chimera.java to check the recovered EvolvedBot doesn't expect something only the stashed Chimera has. Files still live in src/gp/ while declaring package gp.nodes; — javac places classes by package so this compiles correctly, but the folder move is half-done and should be finished. src/gp/symreg/ still present, still throwaway.

Next: verify the Coac/mayari reflection path, write gp.nodes.Max, then return to the fitness curriculum — the PassiveAI test is now visually confirmed as winnable (Chimera does it), so evolved bots drawing there is a fitness-landscape problem, not an action-space one.

Session update (2026-08-30): first evolved bot that generalises; terminal-set sufficiency gap identified.

Cleared the two blockers carried forward from 2026-08-10. gp.nodes.Max written (identical to Min bar the operation), which unstuck ECJ setup. FeatureNode rewritten to bind Features methods BY PARAMETER TYPE rather than by an enumerated table of signature orderings — the old table only tried Unit-first shapes and Features declares distNearestEnemy(GameState, Unit, int), so setup died on func.23. Binding by type means argument order is no longer load-bearing and future terminals cannot fail this way. Known non-terminal, deliberately: movesToward(Unit, UnitAction, int, int) takes target coordinates, not a player id, so its two ints are unresolvable from ScoreData; exposing it needs an arity-2 FUNCTION whose children supply tx and ty. Worth raising at the terminal-set review — "move toward a point the tree chooses" is a genuinely different capability from the rest of the set.

Fitness was rebuilt three times this session, each change driven by a run rather than a guess, and each is a result worth recording. (1) BANDS. The old outcome + speed + marginWeight*margin ordered win<draw<loss correctly but gave only 0.25 of shaping room inside a band against a gap of 1.0 between bands, so selection could barely rank two draws that played very differently and the population sat flat for eleven generations. Each outcome now owns a full unit interval: win 0-1 (faster better), draw 1-2 (quality of play), loss 2-3 (survived longer plus quality). (2) WIN BONUS. Bands fix one game; fitness averages many. Beating PassiveAI both sides and losing to RandomBiasedAI both sides averages ~1.40 against ~1.50 for drawing everything — a 0.1 lead inside a band spanning 1.0, so a marginally better draw outranked a real win and evolution correctly learned the safe uniform strategy (best-of-run 1.41, Hits=1). Subtracting 0.5 x win-fraction from the mean fixed it. (3) EVAL RANGE. SimpleSqrtEvaluationFunction3 is unbounded and its range is OPPONENT-DEPENDENT: bounds calibrated on PassiveAI were badly wrong once RandomBiasedAI joined and most of the distribution clamped, silently flattening the quality term — the flat-landscape failure wearing a different hat. eval-lo/eval-hi are now params and every generation prints the observed range with an ok / CLAMPING verdict. Recalibrate whenever the opponent list changes.

CURRICULUM. Training against WorkerRush was the single biggest thing wrong with the earlier runs: no random tree beats it, so the whole population lived in the loss band from generation 0 and the win band was unreachable. Stage 1 (PassiveAI alone, pop 500 x 100 gens) produced the project's first wins — 10W-0T-0L, best-of-run 0.324 — but traded draws for losses across the defense panel, which is the expected cost of a single easy opponent. Stage 2 is PassiveAI + RandomBiasedAI.

REPEATS. RandomBiasedAI is stochastic; with one game per side an individual could luck into a win, get selected on it, and fail to reproduce it. The tell was training Hits=2 alongside a benchmark of 0W over 10 games against that same opponent. Repeats are per-opponent by design — this bot and PassiveAI are both deterministic, so replaying that pair returns a byte-identical result and repeating it is pure waste. With repeats=3 on stochastic opponents only, 8 games per individual rather than 12.

Best result so far, and the first that GENERALISES: best-of-run 0.5945, Hits=4/8, and on the held-out panel 4W-6T-0L vs RandomAI and 4W-6T-0L vs RandomBiasedSingleUnitAI, with WorkerRush, LightRush, HeavyRush, LightDefense and HeavyDefense all converted from 10L to 5T-5L. Worst cycle 6 ms against the 100 ms G1 gate.

The open anomaly: this bot beats opponents that move and CANNOT beat PassiveAI (0W 10T, down from 5W at stage 1). Working hypothesis is that it wins reactively — it fights what comes to it, and against a passive opponent nothing ever approaches. That points at a SUFFICIENCY GAP, not a fitness problem: config/microrts.params lists 25 function-set entries but Features exposes seven more that are not in it — distEnemyBase, myUnitCount, myBarracksCount, uIsBarracks, uHpFrac, aIsNone, mapArea. distEnemyBase is the critical one. The GP can reference the nearest enemy UNIT but has no way to reference the enemy BASE, so it cannot navigate to a stationary target and destroy it — precisely the capability needed to beat PassiveAI. Whatever is not a terminal, evolution cannot use.

Persistence now works, after two wrong turns worth not repeating: genotypeToString() does NOT emit trees for a GPIndividual (it produced a 40-byte .ind that could never have been reloaded) and genotypeToStringForHumans() falls through to Object.toString() (it wrote the literal line "ec.gp.GPIndividual@143110009{1547381312}" where a 50,000-evaluation result should have been). The correct calls are GPNode.makeLispTree() for the readable form and Individual.printIndividual(state, PrintWriter) for the reloadable one. BenchmarkStatistics now writes results/best-<seed>-<timestamp>.{txt,ind} and bench-<...>.csv per run, records node count and depth, and marks training opponents [TRAINING] in both outputs so training performance cannot be mistaken for a result downstream. It also now benchmarks best_of_run rather than the best of the FINAL population — those diverge (1.4101 from gen 53 vs 1.4202 at gen 99 on one run), which is how a run recording Hits=1 benchmarked as 10T against the opponent it had supposedly beaten.

COST. The 190 games/second figure in the earlier notes no longer holds: this run took ~2 hours for 50,000 evaluations, roughly 55 games/second, because far more games now reach the 3000-cycle cap. evalthreads is still 1 and fitness evaluation is embarrassingly parallel, so raising it to the physical core count should bring this back to ~15 minutes. Also note that every fitness change invalidates cross-run fitness comparisons — only Hits and the benchmark table are comparable across versions, and the results archive needs to record which fitness version produced which number.

Next session, in order: (1) set evalthreads to the core count and confirm no shared-static problems in Features; (2) add the seven missing terminals, distEnemyBase first, and rerun stage 2 to test the sufficiency hypothesis directly — if PassiveAI wins come back, the anomaly was vocabulary, not pressure; (3) turn on ec.parsimony.* — the best tree is 165 nodes at depth 12, pinned against the maxdepth cap, so bloat is now binding in a way it was not at M0; (4) write the loader that reads a .ind back, which is needed before any evolved bot can be re-benchmarked out-of-process or frozen for the Phase 5 adapter; (5) verify the Coac/mayari reflection path, still never exercised; (6) finish the half-done src/gp/nodes/ folder move and delete src/gp/symreg/. Stage 3 of the curriculum (adding WorkerRush) should wait until stage 2 produces wins against both training opponents.
*Keep this file current: when a decision is made, a file is added, or a milestone is hit, update the
relevant section so the next session starts from truth.*

# PROJECT_CONTEXT.md additions — 2026-09-12

This file contains two separate pieces. Paste each into the place named below, then delete
this file.

---

## PIECE 1 — REPLACES the "Current status" paragraph at the end of section 1

Find the existing line beginning "**Current status:** M0 done. Chimera beats RandomBiased
4–0..." and replace the whole paragraph with this:

**Current status:** M1 met. The evolved bot beats 13 of the 18 scripted panel bots 10–0,
splits WorkerRush by side (wins as one player, loses as the other), and draws-or-loses to
WorkerRushPlusPlus and WorkerDefense. Worst per-cycle decision time 2 ms against the 100 ms
G1 gate; G2 satisfied by design. Training is at curriculum stage 3 (PassiveAI +
RandomBiasedAI + WorkerRush) with evalthreads at 8 and a multiplicative win reward. The
breakthrough was a terminal-set fix: every terminal in the old function set returned the
same value for all four MOVE directions, so movement was never under evolutionary control —
the four `movesToward*` terminals closed that gap. Evolved programs can now be saved,
reloaded (`gp.IndividualLoader`, `evolved:<path.ind>`) and reduced to a readable form
without changing behaviour (`gp.Pruner`); the current best is 17 nodes after pruning from
105. The open blocker is the **train/eval map split**: with two deterministic players on a
fixed map there are only two distinct games, so `bench-games=10` is n=2 and the Phase 6
statistical plan cannot run until more maps are in play.

---

## PIECE 2 — APPEND to the end of the file, above the "Keep this file current" line

Session update (2026-09-12): first bot produced by evolution rather than by the initial population; evolved program reduced to 17 readable nodes; three infrastructure pieces added.

TERMINAL-SET SUFFICIENCY, RESOLVED — AND THE 2026-08-30 HYPOTHESIS WAS RIGHT IN KIND BUT WRONG IN DETAIL. The carried-forward plan was to add distEnemyBase and rerun, on the theory that the GP could not navigate to a stationary target. The theory was correct; the proposed fix would not have worked, and would have produced a null result that looked like a refutation. distEnemyBase(GameState, Unit, int) takes no UnitAction, so it returns the same value for every candidate action of a unit — and under argmax any term constant across a unit's actions cannot change the choice. Checking the whole function set on that criterion showed that EVERY one of the 25 entries was constant across the four MOVE directions. The evolved tree scored all four moves identically and EvolvedBot's argmax fell through to getUnitActions() list order. Movement direction had never been under evolutionary control at any point in the project. That is the full explanation of the 2026-08-30 anomaly: the bot won reactively because aIsAttack IS action-discriminating and ATTACK_LOCATION only becomes legal when a target is already in range, so it could learn "attack what comes to me" perfectly well while being unable to go anywhere. Stage 1's transient PassiveAI wins were a fixed tie-break direction tracing the map perimeter until it blundered into the enemy base, not navigation.

The fix needed no new node class. movesToward(Unit, UnitAction, int, int) has read action direction since M0 but takes target COORDINATES, so its two ints are unresolvable from ScoreData and FeatureNode correctly rejects it. Four wrappers that pick the target from the state — movesTowardEnemy, movesTowardEnemyBase, movesTowardMyBase, movesTowardResource — leave one int (the player id) and bind by type with FeatureNode unchanged. Function set is now 29 entries. The other seven missing terminals (distEnemyBase, myUnitCount, myBarracksCount, uIsBarracks, uHpFrac, aIsNone, mapArea) were deliberately held back so the direction terminals could be tested alone; mapArea should stay out while training on a single map, since it is a literal constant and only dilutes the terminal draw.

Result was immediate and large: from 4W-6T-0L against two random opponents to 13 of 18 panel bots beaten, including 10W-0T-0L against PassiveAI, which had been unbeatable. The general principle is worth keeping: whatever is not a terminal, evolution cannot use — and under argmax, whatever is constant across a unit's candidate actions is effectively not a terminal either.

THE WIN BONUS SATURATED AND ONE ENTIRE RUN PRODUCED NOTHING. The first stage-2 run with direction terminals reported Standardized=0.0 Hits=8 on every one of 100 generations, and benchmarked at 13 of 18. That benchmark was real but was found by RANDOM SEARCH in generation 0; there was no selection pressure at any point and it must be reported that way. Cause: fitness was Math.max(0.0, mean - winBonus * winFrac). A win scores near zero by construction (win band 0.0–1.0, a fast win ~0.1), so subtracting 0.5 from an all-winning individual gives a negative number that the max() clamps to 0.0. Every all-winning individual scored exactly 0.0, a bot winning in 200 cycles was indistinguishable from one winning in 2900, hits saturated, and betterThan() never fired so best_of_run froze on the first winner seen. The 9-node depth-3 best-of-run was not parsimony working, it was the absence of any selection.

This is the same failure a third time, and the pattern is now explicit in MicroRTSProblem's header. At the BOTTOM: every losing final state is identical, so the margin term was constant — fixed by sampling quality during play. In the MIDDLE: 0.25 of shaping room against a 1.0 gap between bands, so draws were unrankable — fixed by bands. At the TOP: a subtracted reward larger than the entire win band, so wins were unrankable. Any term that can saturate will saturate exactly where the population ends up, which is precisely where the gradient is needed. Fix is multiplicative rather than subtractive: fitness x (1 - winBonus * winFraction), which is proportional and cannot saturate. win-bonus is now clamped to [0.0, 1.0) and stated explicitly in the params rather than left to a default, since its MEANING changed.

STAGE 3 AND THE FIRST REAL EVOLUTIONARY RESULT. Curriculum advanced to PassiveAI + RandomBiasedAI + WorkerRush (10 games per individual). WorkerRush was the single worst choice of training opponent at the start of the project and is the right one now, for the opposite reason: the bot already saturates every other opponent at a win, so WorkerRush is the only remaining source of gradient. Best-of-generation moved 0.3612 (gen 0) → 0.2728 (gen 2) → 0.1996 with Hits=9 at gen 17, the predicted cliff where one WorkerRush game converts from a ~2.1 loss to a ~0.22 win, then refined to 0.1789 by gen 78. Tree grew 11 → 105 nodes, depth pinned at the maxdepth cap of 12 — bloat is back now that selection exists. Run converged by gen 17 and coasted for 82 generations; parsimony pressure (ec.parsimony.DoubleTournamentSelection) is prepared and commented out in the params. M1 is properly met.

Panel: 13 of 18 beaten 10-0, WorkerRush 5W-0T-5L, WorkerRushPlusPlus and WorkerDefense 0W-5T-5L. Worst per-cycle time 2 ms against the 100 ms G1 gate. Note a regression: WorkerDefense was 0W-10T before and is 0W-5T-5L now — training on WorkerRush bought the WorkerRush side-win and cost half the WorkerDefense draws.

BENCHMARK SAMPLE SIZE IS NOT WHAT IT LOOKS LIKE — THIS BLOCKS PHASE 6. Against a deterministic opponent, on a fixed map, with a deterministic bot, there are only as many distinct games as (maps x sides) — two on a single map. bench-games=10 replays those two five times each. "5W-0T-5L / 10 games (win rate 50%)" is therefore NOT a coin flip: it means the bot wins every game as one player and loses every game as the other. Training Hits=9/10 confirms it independently (PassiveAI 2/2, RandomBiasedAI 6/6, WorkerRush 1/2). Phase 6 plans Mann-Whitney U at alpha=0.05; run on these counts it would compute significance from duplicate rows. More games do not help — only more MAPS do, since the map is the only available source of independent variation between two deterministic players. This makes the Phase-2 map-split deliverable a blocker for the evaluation methodology rather than housekeeping. BenchmarkStatistics now reports distinct-game counts per row and warns.

REPRODUCIBILITY CLAIM NARROWED. Two runs at identical seed.0=4242 and evalthreads=1 produced different populations. The only nondeterminism in that configuration is the opponent, so ai.RandomBiasedAI seeds its own RNG independently of ECJ's seed. Runs against deterministic opponents only remain bit-for-bit reproducible; any run including a stochastic opponent is reproducible only in distribution and must be reported across several seeds. Worth raising with Pillay/Nyathi before the write-up commits to a phrasing.

THREADING. evalthreads raised to 8. Features holds no static mutable state and Panel loads a fresh PhysicalGameState per game, so neither blocks it; the real hazard was that GPProblem.clone() is shallow for everything except GPData and ADFStack, leaving one shared UnitTypeTable and EvaluationFunction across all threads. MicroRTSProblem.clone() now hands each clone its own. The calibration accumulator moved to a synchronized static so one range report covers a whole generation instead of N partial ones. ECJ GOTCHA: it allocates max(evalthreads, breedthreads) RNGs and fatals on the first undefined seed, so seed.0 through seed.7 are now listed. breedthreads stays 1 so breeding is on one stream regardless of eval thread count. Second ECJ gotcha, learned the hard way: a leading $ on a path parameter means "relative to the working directory" and WITHOUT it the path resolves relative to the directory holding the params file — so stat.file = $results/... was always correct, and a -p override that drops the $ sends the log to config/results/ and fails. PowerShell also needs single quotes around any -p value containing a $.

THREE NEW TOOLS.

gp.IndividualLoader reads a saved .ind back into a playable bot, reachable from any runner as "evolved:<path.ind>" or "evolved:<path.ind>@<path.params>" through the single factory. This unblocked re-benchmarking archived individuals, watching one play, and freezing a policy for the Phase 5 adapter. A .ind is NOT self-describing: its node names only mean something relative to a function set, so loading needs a set-up EvolutionState, and an individual archived under a 29-entry set cannot be read by a params file listing 25. BenchmarkStatistics therefore now also writes best-<stamp>.params — the GATHERED parameter set via ParameterDatabase.list(), not a file copy, so it captures -p overrides and koza defaults inherited from inside the jar. Older archived .ind files predate this and may already be unreadable. The loader verifies the round trip by re-printing and comparing, because ECJ's read path is far less exercised than its write path and an ERC with a missing encode()/decode() would load cleanly, report the right fitness, and play with different constants. CAVEAT: the tree tested contains no ERCs at all, so that check has never actually exercised ScoreERC. The first individual containing a constant is the real test.

gp.Pruner reduces a finished tree without changing how it plays. This is a MEASUREMENT, explicitly not parsimony pressure: parsimony changes the search and produces different bots, which would confound the Phase 4 ordinary-GP vs SBGP comparison unless applied uniformly. Phase 1 applies only identities true for all inputs (max(x,x)→x, min(x,x)→x, if>(a,a,t,e)→e). Phase 2 greedily replaces nodes with their children, accepting only when the candidate picks the SAME UnitAction at EVERY decision point of traced games — including the resource-consistency fallback, since raw argmax would miss divergences that appear only when an unaffordable top choice is skipped. Trace opponents default to the TRAINING set: pruning is guided by the games it traces, so tracing against held-out bots would let them shape the artefact later evaluated on them.

Two bugs in it worth not repeating. (1) Restoring a rejected candidate with a CLONE rather than the original object detaches the original — and everything under it — from the tree while the scan's node list still points at it. The scan then edits orphaned subtrees, the live tree never changes, every candidate therefore plays identically and is accepted, and the loop never terminates. Symptom was a round counter in the thousands with the node count barely moving; rounds can never legitimately exceed the node count, since every real acceptance removes at least one node. assertProgress() now fails loudly on any acceptance that does not shrink the tree. (2) Verification compared W/T/L tables for original vs pruned, which is exact for a deterministic opponent and MEANINGLESS for a stochastic one — RandomBiasedAI seeds from the clock, so the two runs are different games. The first verification run flagged exactly that one row as DIFFERS, which was the verifier's bug, not a divergence. Stochastic opponents are now checked by decision equivalence over sampled games, which is the stronger property anyway: matching outcomes can hide divergent play, matching decisions cannot. Final verification: all 18 panel rows equivalent.

WHAT THE EVOLVED BOT ACTUALLY IS. 105 nodes → 17, depth 12 → 6, 84% of the tree doing nothing, verified equivalent across the panel:

    score = min(movesTowardEnemy + uIsWorker,
                max(movesTowardEnemy, movesTowardEnemyBase - movesTowardMyBase))
            - 2 x max(movesTowardResource, myResources)

Three readings, all of which matter more than the win rate.

(1) EVERY NON-MOVE ACTION SCORES IDENTICALLY. All movesToward* terms return 0 for non-MOVE actions, so harvest, return, produce, attack and NONE all collapse to the same value and the choice among them is decided by getUnitActions() list order. The bot has no deliberate policy about anything except movement. The direction-blindness problem was solved for moves and remains fully present everywhere else — the GP still cannot discriminate between producing a worker and producing a Light, or between attacking one target and another. This is the next sufficiency gap and it is bigger than the one just closed.

(2) COMBAT UNITS ARE PURE CHASERS. For a non-worker the expression reduces exactly to movesTowardEnemy, since min(x, max(x,y)) = x. Only workers weigh the enemy-base direction. aProducesCombat survived to the 105-node tree but not to the 17-node one: the bot never deliberately builds an army, and wins 13 of 18 by running everything at the enemy on an 8x8 map. Whether that survives a 16x16 map is an open question and a good one.

(3) THE RESOURCE TERM IS LIVE BUT NARROW. myResources is constant across a unit's actions, so max(movesTowardResource, myResources) cancels under argmax while the stockpile is non-zero. At exactly zero it falls through and the bot subtracts 2 from any move TOWARD a resource — broke workers walk away from resources. Pruning against real games kept the term, so this is real behaviour and not bloat. It is also a good illustration of why naive algebraic simplification is unsafe here: "myResources is constant, delete it" is the obvious rewrite and it is wrong.

NEXT, IN ORDER. (1) Fix the train/eval MAP split; it has stopped being housekeeping and is now the blocker for Phase 6 statistics, for separating the WorkerRush side asymmetry from map-specific geometry, and for answering whether this bot only works on 8x8. (2) Watch the pruned bot against WorkerRush from both sides — the loader now makes the asymmetry observable, and at 17 nodes the whole scoring function fits in your head while watching. (3) Add terminals that discriminate among non-MOVE actions — produce-target and attack-target properties — which is the sufficiency gap the pruned tree just exposed. (4) Turn on parsimony pressure, uniformly, when setting up the Phase 4 comparison and not before. (5) Re-benchmark against Coac and mayari properly; the 1W-1L over two games is still the least trustworthy number in the archive. (6) Delete or quarantine the pre-snapshot .ind files in results/, which have no params beside them and may no longer be readable. (7) Finish the half-done src/gp/nodes/ folder move and delete src/gp/symreg/, both still outstanding from August.

---

## ALSO WORTH FIXING when you next open the file

- **§4 folder tree** is missing `src/gp/IndividualLoader.java`, `src/gp/Pruner.java`, and the
  `best-*.params` artifact in `results/`.
- **§6 important files** should gain short entries for the loader and the pruner.
- **§10 workplan table**: 3b is no longer "next up" (done), M1 is no longer pending (met),
  and Phase 2 should be flagged as blocking Phase 6 because of the map split.
- **§11 TODO backlog**: ECJ is confirmed, the function set is defined, persistence is done.
  Add the map split, the non-MOVE sufficiency gap, and the RandomBiasedAI reproducibility
  caveat.