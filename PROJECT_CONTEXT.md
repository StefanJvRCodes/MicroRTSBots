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


# PROJECT_CONTEXT.md — session update 2026-10-03 / 2026-10-04

**One file for the whole session.** It replaces the three separate additions files
(2026-10-03, 2026-10-04, 2026-10-04b); those can be deleted once this is merged.

How to merge it into PROJECT_CONTEXT.md:
- **Section 0** goes at the very top, directly under the title, so every new session reads
  it first.
- **Section A** replaces the "Current status" paragraph at the end of section 1.
- **Sections B–J** are appended above the "Keep this file current" line.
- **Section K** lists the fixes to the older sections (§4, §6, §7, §10, §11).

---

## 0. Instructions for the next assistant session (read first)

This file is used by a fresh assistant window that has none of the earlier conversations.
It describes the project's state, but **not the contents of the source code**.

- **Ask for the source files before writing code; never assume their contents.** Before
  changing or writing code that touches an existing class (e.g. `Features.java`,
  `EvolvedBot.java`, `MicroRTSProblem.java`, `BenchmarkStatistics.java`, `Pruner.java`,
  `IndividualLoader.java`, `Bench.java`, `FeatureNode.java`, `Panel.java`, `ScoreData.java`,
  or a params file), ask the user to attach the current version. Guessed code has cost this
  project before: the August file shuffles, and BenchmarkStatistics silently missing code
  the notes said it had.
- **Phase 4 needs the Scheepers & Pillay paper.** Structure-based GP means specifically
  Scheepers & Pillay (proposal ref [18]); Prof. Pillay is a supervisor and co-author. Do
  not design or implement it from general knowledge. If the paper (or Prof. Pillay's
  description of the method) is not attached, ask for it first.
- **The user places files manually.** Give each new or changed file with its exact path
  relative to the repo root (e.g. `src\gp\Bench.java`), and say whether it is new or a
  replacement.
- **Commands are PowerShell, run from the repo root** (MICRORTSBOTS\). Follow the
  rebuild rules and command sheet in section K.
- **Keep the methodology rules in section I.** In particular: never tune against the test
  maps, Coac or mayari; parsimony is identical across configurations; report seed spread
  and the selected individual separately.

---

## A. Current status (replaces the paragraph at the end of §1)

**Current status:** Ordinary-GP pipeline complete for this phase. The best bot (stage 7,
seed 8242, selected on validation) beats 15 of the 18 scripted panel bots in every game on
8 unseen validation maps (RandomBiasedAI 100%), and beats **WorkerRush 69%,
WorkerRushPlusPlus 81% and WorkerDefense 75%**, including WorkerRush from both sides on
a 16x16 map, which nothing in the engine manages, not even WorkerRush's own mirror. Pruned
54 → 34 nodes and verified equivalent. Explained: an all-in, better-timed version of
WorkerRush's own strategy. Across five seeds the results vary widely (25–69% against
WorkerRush), so many seeds are needed for Phase 6. In a single sanity check, a different
seed's 10-node bot beat Coac and mayari 10/16 each; they are held out from now on. Worst
decision time ≤ 20 ms warm (44 ms cold) against the 100 ms G1 gate. Nothing reported on
test maps yet. **Next: Phase 4 (structure-based GP), blocked on the Scheepers & Pillay
method description.**

---

## B. Where the session started and where it ended

**Start (stage 3, September):** a 17-node bot trained on one 8x8 map. It beat 13 of 18
panel bots on that map and split WorkerRush by side. A single map meant only 2 distinct
games per opponent, so no statistics were possible.

**End (stage 7):**
- A proper train / validation / test map split, 8x8 and 16x16.
- 55 terminals and one evolved tree per unit role.
- Working parsimony pressure.
- Five seeds, with the best bot beating the whole scripted panel on unseen maps except the
  worker bots, and beating those 69–81%.
- Tools to benchmark, prune, inspect and watch any saved bot.

---

## C. Findings that belong in the write-up

**1. The engine's action order was playing part of the game.**
`Unit.getUnitActions()` lists legal actions in a fixed order, checked against the engine
source:

ATTACK > HARVEST > RETURN > PRODUCE > MOVE > NONE

- Attacks are listed up, right, down, left, then any other target in range.
- Produce follows UnitTypeTable order (Worker; Light, Heavy, Ranged; Base, Barracks), each
  up/right/down/left.
- Moves are listed up, right, down, left.

EvolvedBot's argmax keeps the first maximum, so every tied score is settled by this order.
Much of the stage-3 bot's non-move behaviour came from it: "attack whenever possible", "the
base always trains a worker". Every bot since still relies on it for part of its policy,
and the write-up must say so.

EvolvedBot now counts tied decisions per role, split into all ties and **cross-type ties**
(tied actions of different types, i.e. where list order chose *what* the unit does). This
is reported every generation as the "tie rate" line.

**2. The stage-3 WorkerRush "side split" was a tie-break artefact.**
The 17-node stage-3 tree was re-implemented by hand, and it reproduced its benchmark.
Adding a symmetric tie-break (mirrored paths on the 180°-symmetric map) turned WorkerRush
1W-1L into 0W-2L. That bot never genuinely beat WorkerRush.

**3. The engine's wait action lasted 10 cycles: a handicap for every bot in stages 1–5.**
- `getUnitActions(gs)` offers NONE (wait) with a 10-cycle duration; a worker attack takes 5.
- Any unit that chose to wait was frozen and could not take the first strike, and workers
  have 1 HP, so the first strike decides every worker fight.
- Scripted bots never lock units like this.
- It is now the parameter `eval.problem.none-duration`. The default is 10, so archived bots
  replay exactly as evolved; stage 6 onward uses 1.

**4. The 16x16 WorkerRush wall.** Tested directly against the engine on six 16x16 maps,
both sides, 12 games each:

| Challenger vs WorkerRush | Wins / 12 |
|---|---|
| LightRush, HeavyRush, RangedRush, LightDefense, RangedDefense, EconomyRush, EconomyMilitaryRush | 0 each |
| WorkerDefense | 1 |
| WorkerRush (mirror), WorkerRushPlusPlus | 6 (player 0 only) |
| Hand-written scorers over gp.Features (rush / economy+defend / split), wait = 10 | 0 / 0 / 1 |
| Same, with BFS path-following | 0 / 0 / 0 |
| Same, "patient" (penalise stepping into threat) | 0 / 0 / 0 |
| Same, wait = 1 | 2 / 1 / 3 (vs WR++: 2 / 1 / 4) |

What this showed:
- An army is not the answer on these maps; evolution was right never to build barracks.
- Movement and pathfinding were not the problem.
- The wait duration was the lever that worked.

**5. Duplicate maps.**
- basesWorkers8x8 ≡ basesWorkers8x8A, and basesWorkers16x16 ≡ basesWorkers16x16A
  (identical units and terrain).
- basesWorkers8x8Obstacle has the same starting units as basesWorkers8x8, with different
  terrain.

**6. Parsimony fixed overfitting.**
- Stage 6 won its own 16x16 training maps but none of the unseen ones, with a 514-node
  tree pinned at depth 12.
- With parsimony (stage 7, seed 4242) the bot shrank to 10 nodes and won its first unseen
  16x16 game.
- Smaller trees generalised better: the classic overfitting story.

**7. Large seed variance.**
- With an identical config, results against WorkerRush ranged from 25% to 69%.
- Training Hits mostly predict validation, but not always: seed 6242 was second on Hits
  and last on validation.
- Parsimony can collapse a run: seed 5242 ended at 4 nodes, one terminal per tree.

**8. The best bot: an all-in WorkerRush** (seed 8242). See section F.

---

## D. Map split (to be frozen after agreement with the team and supervisors)

| Set | Maps | Purpose |
|---|---|---|
| TRAIN | 8x8 (≡A), 8x8C, 8x8E, 16x16B, 16x16D, 16x16G, 16x16F, 16x16H | fitness |
| VALIDATION | 8x8B, 8x8D, 8x8F, 8x8G, 8x8K, 16x16 (≡A), 16x16C, 16x16E | choosing between runs; never reported |
| TEST | 8x8H, 8x8I, 8x8J, 8x8L, 16x16I, 16x16J, 16x16K, 16x16L | touched once, at the end |
| EXCLUDED | 8x8Obstacle | same start as a training map |

How it got here:
- Stage 4 defined it.
- Stage 5 moved 16x16D and 16x16G from validation to train.
- Stage 7 moved 16x16F and 16x16H from test to train. No test result had ever been looked
  at, so that move was legitimate. **No further changes.**

With deterministic bots, distinct games = maps × 2 sides, so validation and test each give
16 distinct games per opponent. That is what makes Phase 6 statistics possible.

---

## E. Training stages (one config file each; each header lists exactly what changed)

| Stage | Config | Change from previous | Training Hits | Validation vs WR / WR++ / WD | Tree | Worst cycle |
|---|---|---|---|---|---|---|
| 4 A | stage4 + numtrees=1 | 55 terminals, 4 maps, 6 opponents, 1 tree | 50/56 | 40 / 45 / 45% (10 maps) | 225 nodes, depth 12 | 45 ms |
| 4 B | stage4 | same, 4 role trees | 50/56 | 30 / 35 / 35% (10 maps) | 252 nodes | 6 ms |
| 5 | stage5 | 16x16 weight 1/2 (+16x16D,G); subtree mutation 0.1 | 66/84 | 38 / 31 / 31% | 236 nodes | 16 ms |
| 6 | stage6 | + wait duration 1 | 71/84 | 44 / 44 / 44% | 514 nodes, depth 12 | 30 ms |
| 7 (4242) | stage7 | + working parsimony; +16x16F,H to train | 79/112 | 50 / 56 / 56% | 10 nodes | 16 ms |

All seed 4242. Validation is 8 maps from stage 5 on.

Notes:
- In stages 4–6, every training non-win was a 16x16 game against the three worker bots.
- No bot in any stage built an army; the barracks and combat tie rates read n/a.
- Stage 4 A vs B (one seed each) is noise: the role-tree effect is untested.
- The baseline: the stage-3 September bot on the stage-4 validation maps also got
  30 / 35 / 35% against the worker bots. Stage 4's gain was converting 16x16 draws against
  the other bots into wins.
- The fitness scale changed between stages (cycle cap 3000 → 4000, map lists). Compare Hits
  and benchmark tables only, never fitness values across stages.

**Stage 7 across five seeds** (validation maps):

| Seed | Training Hits /112 | Tree | vs WorkerRush | vs WR++ | vs WorkerDefense |
|---|---|---|---|---|---|
| 4242 | 79 | 10 | 50% | 56% | 56% |
| 5242 | 74 | 4 | 31% | 44% | 44% |
| 6242 | 87 | 76 | 25% | 31% | 31% |
| 7242 | 88 | 70 | 44% | 44% | 44% |
| **8242** | **103** | 54 | **69%** | **81%** | **75%** |
| Mean | | | 44% | 51% | 50% |

RandomBiasedAI was 96–100% for every seed, and the other 15 panel bots 100% wherever
checked. Each seed took about 1.3–1.5 hours.

---

## F. The best bot (stage 7, seed 8242), pruned and explained

**Files:** results/best-seed8242-20261004-070118{,-pruned}.{ind,params}

**Validation results:**
- 15/18 panel bots: 100%.
- RandomBiasedAI: 100%.
- WorkerRush: 11W-2T-3L.
- WorkerRushPlusPlus: 13W-0T-3L.
- WorkerDefense: 12W-3T-1L.

Against WorkerRush per map (side 0 then side 1):
`8x8B:LT 8x8D:WW 8x8F:WW 8x8G:LW 8x8K:WW 16x16:WL 16x16C:WW 16x16E:WT`

**Pruning** (role-aware Pruner, its first real run):
- 54 → 34 nodes [27, 5, 1, 1].
- Traced on training maps and opponents only, then verified on all 8 validation maps.
- Every deterministic row was identical, and decisions were identical over 80 sampled
  games per stochastic opponent: "Equivalent across the whole panel."

```
tree 0 (worker):
(if> (+ distEnemyBase (- uIsWorker movesTowardEnemyMajor)) myResources
     (if> (+ distEnemyBase (- movesTowardEnemyMajor movesTowardEnemyBase)) myResources
          (+ (- tgtKillable (- uIsWorker movesTowardEnemyMajor)) movesTowardEnemy)
          (/ aIsHarvest (- placesTowardEnemy gameTimeK)))
     placesTowardEnemy)
tree 1 (base):     (/ uIsWorker (- placesTowardEnemy gameTimeK))   -- constant 0 for a base
tree 2 (barracks): uIsWorker                                       -- never runs
tree 3 (combat):   aIsMove                                         -- never runs
```

**Reading it.** Each worker decides every cycle by comparing its distance to the enemy base
with the resources we have banked:

- **Far from the enemy base relative to the bank → combat mode.** Either:
  - attack killable targets and step toward the enemy along the long axis; or
  - when closing on the base, make harvesting negative, so the engine order takes over:
    attack > return > produce > move.
- **Close relative to the bank → everything scores 0 except producing a building on the
  enemy-facing side.** Possibly a forward Base, but this branch is dormant in practice:
  games end long before enough resources are banked.
- The base trains workers by list order. No barracks or army is ever built.

**Watched** (eval.Watch, 16x16C vs WorkerRush):
- Wins as player 0 at cycle 426 and as player 1 at cycle 295; only workers on both sides.
- **WorkerRush keeps one worker home harvesting; the evolved bot commits every worker to
  the fight.** So it has one more fighter at every clash, and with 1-HP workers and
  kill-first targeting the edge snowballs.

**For the write-up:** evolution found a better-timed, all-in version of WorkerRush's own
strategy, and the full commitment is what lets it beat WorkerRush from both sides.

**Earlier stage-7 bot (seed 4242, 10 nodes):**
```
worker:   (min movesTowardEnemy movesTowardEnemyMajor)
base:     (* placesTowardEnemy distEnemyBase)
barracks: (min movesTowardEnemy movesTowardEnemyMajor)     -- never runs
combat:   movesTowardEnemy                                  -- never runs
```
A plain worker rush: march at the nearest enemy, train workers on the enemy-facing side.

---

## G. Competition bots: one sanity check, then held out

On the seed-4242 bot (10 nodes), validation maps, gp.Bench with `ai.coac.CoacAI` and
`mayariBot.mayari`:

```
vs ai.coac.CoacAI   10W 0T 6L  8x8B:LL 8x8D:LW 8x8F:LW 8x8G:LL 8x8K:WW 16x16:WW 16x16C:WW 16x16E:WW
vs mayariBot.mayari 10W 1T 5L  8x8B:WW 8x8D:WW 8x8F:LL 8x8G:WW 8x8K:TW 16x16:LL 16x16C:WL 16x16E:WW
```

Verified in-session against the real jars, with the engine built from a clean clone:
1. An exact hand re-implementation of the 10-node tree reproduced both grids character for
   character, so the harness and loader are consistent.
2. Calling `preGameAnalysis(gs, 1000)`, as the competition harness does, changed nothing.
3. The bots play properly in our harness: Coac beats LightRush 14/16 and WorkerRush 11/16;
   mayari beats LightRush 15/16 and WorkerRush 9/16, on the same maps.
4. Plausibility: Coac itself loses to WorkerRush on 16x16C and 16x16E.

**Decision:** Coac and mayari are held-out opponents. They were checked once and are not
run on seed 8242 or any later individual. Their numbers come from the final test-map
evaluation only; repeated checks would turn them into a selection signal.

**Caveat to resolve:** we use `new UnitTypeTable()` = VERSION_ORIGINAL. Check which version
the 2025 benchmark used. If it was VERSION_ORIGINAL_FINETUNED, the comparison must be
re-run on that table.

---

## H. Code changed or added this session

| File | What changed |
|---|---|
| `src/gp/Features.java` | 26 new terminals; all old ones unchanged in behaviour (details below). |
| `src/bots/EvolvedBot.java` | Role trees (≥4 trees: 0 worker, 1 base, 2 barracks, 3 combat; 1 tree as before); tie counters (all / cross-type); wait duration taken from the problem. |
| `src/gp/MicroRTSProblem.java` | Startup check that every map exists and every opponent constructs (otherwise every individual silently scores 3.0); tie-rate report; `none-duration` parameter. |
| `src/gp/BenchmarkStatistics.java` | Writes the missing `best-<stamp>.params` snapshot (`ParameterDatabase.list`, including -p overrides and inherited defaults); "distinct games" per row; role-labelled trees. |
| `src/gp/IndividualLoader.java` | Finds the params snapshot beside a .ind automatically (then the unpruned original's, then config/microrts.params with a warning); role-labelled output. |
| `src/gp/Pruner.java` | Role-aware TraceBot (it used trees[0] for every unit); same wait duration as EvolvedBot; `-verify-maps`; writes `<out>-pruned.params`. |
| `src/gp/Bench.java` (new) | Benchmark any .ind outside a run, with a per-map, per-side grid. `-maps train / validation / validation-of:<params> / list`, `-opponents`, `-samples`, `-maxcycles`, `-csv`. No "test" keyword on purpose. |
| `config/microrts-stage4..7.params` (new) | One per stage. `microrts.params` (stage 3) untouched. |

**New terminals in gp.Features:**
- Produce type split: aProducesLight, aProducesHeavy, aProducesRanged, aProducesBarracks,
  aProducesBase.
- Attack target: tgtWounded, tgtKillable, tgtIsWorker, tgtIsCombat, tgtIsBuilding.
- Produce placement: placesTowardEnemy, placesTowardResource.
- threatAtDest.
- Symmetric major-axis moves: movesTowardEnemyMajor, movesTowardEnemyBaseMajor.
- Context gates: myCombatCount, enemyCombatCount, enemyWorkerCount, enemiesNearMyBase,
  gameTimeK.
- Released from the held-back list: aIsNone, uIsBarracks, uHpFrac, myBarracksCount,
  distEnemyBase, mapArea.
- The function set is now 55 entries.

**Bugs found and fixed:**
- **The Pruner would have collapsed role-tree bots.** It scored every unit with trees[0], so
  edits to trees 1–3 never changed a decision and every candidate would have been accepted.
- **The `.params` snapshot and the distinct-games warning were both missing** from
  BenchmarkStatistics, despite the 2026-09-12 notes describing them (probably lost in a file
  shuffle).
- **The commented-out parsimony block was non-functional.** `select.tournament = ...` only
  sets defaults for ordinary TournamentSelection. Koza breeding takes its selector from
  `breed.reproduce.source.0`, `gp.koza.xover.source.0` and `gp.koza.mutate.source.0`; stage
  7 sets those (DoubleTournamentSelection, size 7, size2 1.4, do-length-first false).
- **A fourth flat landscape:** a typo in a map path or opponent name made every individual
  score 3.0. It is now caught at startup.

---

## I. Methodology rules adopted

- **Parsimony must be identical for ordinary GP and SBGP** in Phase 4 (on since stage 7).
- **Role trees, terminals, fitness and map split** are shared by all configurations.
- **Report seed spread and selected individual separately:** the mean and range over seeds,
  and the validation-selected individual's test result.
- **Selection uses validation only.** Test maps and the competition bots are used once, at
  the end.
- **Compare across stages with Hits and benchmark tables only**, never fitness values.
- **Stochastic opponents limit reproducibility.** Runs with RandomBiasedAI are reproducible
  only in distribution.

---

## J. What to do next, in order

1. **Phase 4: structure-based GP (Scheepers & Pillay). Blocked on the paper.** Get the paper
   or Prof. Pillay's description of the method. Then design SBGP to reuse role trees, the 55
   terminals, the fitness, the map split and identical parsimony, so the ordinary-GP vs SBGP
   comparison isolates the method.
2. **Supervisor and team meeting:**
   - freeze the map split (section D);
   - confirm which unit-table version the 2025 benchmark used;
   - present the findings in section C and the seed-8242 result.
3. **Many seeds per configuration** (10+, about 1.5 h each) for both ordinary GP and SBGP,
   to support the Phase 6 statistics (Mann-Whitney U on per-seed validation/test results).
4. **List-order ablation:** replay the best bots with legal actions shuffled under a fixed
   seed, and measure the drop. This quantifies how much of the policy is evolved and how
   much is the engine's order.
5. **Final evaluation, once:** the selected individual of each configuration on the TEST
   maps against the full scripted panel plus Coac and mayari (and the other held-out bots in
   the proposal: TMA, the PPO agent).
6. **Phase 5:** the adapter wrapping the frozen GP policy. The per-unit `scores[]` hook in
   EvolvedBot is unchanged.
7. **Still-open housekeeping:**
   - ScoreERC round-trip test: no evolved tree yet contains a constant;
   - finish the src/gp/nodes/ folder move;
   - delete src/gp/symreg/;
   - quarantine pre-snapshot .ind files.

---

## K. Fixes to older sections of PROJECT_CONTEXT.md

- **§4 folder tree.** Add:
  - src/gp/Bench.java, src/gp/IndividualLoader.java, src/gp/Pruner.java;
  - config/microrts-stage4.params to microrts-stage7.params;
  - the best-*.params, -pruned.ind and prune-*.log artefacts in results/.
- **§6 important files.** Add short entries for Bench, IndividualLoader and Pruner, as in
  section H.
- **§7 build & run.** Add the working conventions and the command sheet below.
- **§10 workplan.** Update:
  - 3b done; M1 met; G1 green (≤ 20 ms warm);
  - Phase 2 map split defined, pending freeze;
  - Phase 4 next, blocked on the paper.
- **§11 backlog.** Tick: ECJ, function set, persistence, map split (pending freeze), harness
  (gp.Bench). Add: ERC round-trip test, multi-seed runs, list-order ablation, unit-table
  version check, parsimony-uniformity rule.

### Working conventions and command sheet (for §7)

**Conventions:**
- All commands assume PowerShell in the repo root (MICRORTSBOTS\).
- The user places new and changed files manually; each session gives exact paths.
- Branch: HeinrichFirstBot_Chimera.

**Rules:**
- A .java file changed → rebuild.
- A .java file added → regenerate sources.txt, then rebuild.
- Only a .params file changed → no rebuild.
- Never rebuild while a run is going.

```powershell
# regenerate sources.txt (UTF-8, no BOM) and build
$src = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object FullName
[System.IO.File]::WriteAllLines("$PWD\sources.txt", $src, (New-Object System.Text.UTF8Encoding $false))
javac -cp "lib/microrts.jar;lib/*" -d out '@sources.txt'

# quick check of a config (minutes)
java -cp "out;lib/*;lib/bots/*" ec.Evolve -file config/microrts-stage7.params -p generations=2 -p pop.subpop.0.size=50 -p stat.bench-games=2 -p 'stat.file=$results/check7.stat' 2>&1 | Tee-Object results\check7.log

# one full run (own .stat file per run; single quotes because of the $)
java -cp "out;lib/*;lib/bots/*" ec.Evolve -file config/microrts-stage7.params -p 'stat.file=$results/stage7-seed4242.stat' 2>&1 | Tee-Object results\stage7-seed4242.log

# several seeds back to back (each sets seed.0-7 = base..base+7)
foreach ($s in 5,6,7,8) {
  $b = $s*1000 + 242
  $seeds = 0..7 | ForEach-Object { "-p"; "seed.$_=$($b + $_)" }
  java -cp "out;lib/*;lib/bots/*" ec.Evolve -file config/microrts-stage7.params $seeds -p ('stat.file=$results/stage7-seed' + $b + '.stat') 2>&1 | Tee-Object "results\stage7-seed$b.log"
}

# summarise seeds
foreach ($b in 5242,6242,7242,8242) { "===== seed $b"; Select-String -Path "results\stage7-seed$b.log" -Pattern "best fitness of run|tree: |^vs WorkerRush |^vs WorkerRushPlusPlus |^vs WorkerDefense |^vs RandomBiasedAI " | ForEach-Object Line }

# progress of a running job (second window)
Select-String -Path results\stage7-seed4242.log -Pattern "best fitness of generation" | Select-Object -Last 1

# inspect / benchmark / prune / watch a saved bot
java -cp "out;lib/*;lib/bots/*" gp.IndividualLoader <path.ind>
java -cp "out;lib/*;lib/bots/*" gp.Bench <path.ind> -opponents WorkerRush,WorkerRushPlusPlus,WorkerDefense
java -cp "out;lib/*;lib/bots/*" gp.Bench <path.ind> -maps train -opponents WorkerRush
java -cp "out;lib/*;lib/bots/*" gp.Pruner <path.ind> -verify -verify-maps maps/8x8/basesWorkers8x8B.xml,maps/8x8/basesWorkers8x8D.xml,maps/8x8/basesWorkers8x8F.xml,maps/8x8/basesWorkers8x8G.xml,maps/8x8/basesWorkers8x8K.xml,maps/16x16/basesWorkers16x16.xml,maps/16x16/basesWorkers16x16C.xml,maps/16x16/basesWorkers16x16E.xml
java -cp "out;lib/*;lib/bots/*" eval.Watch maps/16x16/basesWorkers16x16C.xml evolved:<path.ind> WorkerRush 20 4000

# competition bots by class name (held out: final evaluation only)
java -cp "out;lib/*;lib/bots/*" gp.Bench <path.ind> -opponents ai.coac.CoacAI,mayariBot.mayari

# overnight runs: keep the laptop awake on AC, restore afterwards (admin terminal)
powercfg /change standby-timeout-ac 0
powercfg /change standby-timeout-ac 30
```

The `java : NativeCommandError` block at the top of every ECJ log is harmless: ECJ prints
its banner to stderr and `2>&1` makes PowerShell wrap it as an error.

## ALSO WORTH FIXING when you next open the file

- **§4 folder tree** is missing `src/gp/IndividualLoader.java`, `src/gp/Pruner.java`, and the
  `best-*.params` artifact in `results/`.
- **§6 important files** should gain short entries for the loader and the pruner.
- **§10 workplan table**: 3b is no longer "next up" (done), M1 is no longer pending (met),
  and Phase 2 should be flagged as blocking Phase 6 because of the map split.
- **§11 TODO backlog**: ECJ is confirmed, the function set is defined, persistence is done.
  Add the map split, the non-MOVE sufficiency gap, and the RandomBiasedAI reproducibility
  caveat.


  # PROJECT_CONTEXT.md — session update 2026-10-11 (Phase 4: SBGP built and piloted)

How to merge: append above the "Keep this file current" line. Add the two lines under
"For section 0" to section 0. Replace the "Current status" paragraph with the one under
"For section A".

---

## For section 0 (instructions to the next assistant session)

- **Code delivery format.** This chat setup has no file-download tool. Deliver new or changed
  files as ONE PowerShell block that writes each file with
  `[System.IO.File]::WriteAllText(path, @'...'@, (New-Object System.Text.UTF8Encoding $false))`
  (UTF-8 without BOM, which javac requires), then regenerates sources.txt, builds and runs the
  tests. Edits to existing files use `.Replace()` / `[regex]::Replace` followed by a check that
  prints PATCH APPLIED or fails loudly. Monitoring and one-off commands should be SINGLE LINES:
  long multi-line pastes have been cut off mid-string in the terminal.
- **Long runs use a snapshot of out/** (`Copy-Item out out-<name> -Recurse`, then run with
  `-cp "out-<name>;lib/*;lib/bots/*"`), so `out/` can be rebuilt while runs are going.

## For section A (replaces "Current status")

**Current status:** Ordinary-GP pipeline complete (stage 7; best seed 8242). Phase 4 SBGP is
**implemented, unit-tested and piloted** (Scheepers & Pillay, GPEM 27:2, 2026) as a breeder plus
node selector on top of the unchanged stage-7 configuration. Three full-size pilots (seed 4242)
show the mechanism working as the paper describes. The decisive design issue: with parsimony
keeping trees at 3–5 levels, the paper's **Dg = 4 freezes whole trees and switches local search
off**. **Dg = 2 restores it** (pilot 3: real local-search gains, and a global search that escaped
to a better area). Validation results of all SBGP pilots (44–63% vs the worker bots) lie inside
the ordinary-GP seed spread, so no performance claim yet. Next: settle the open questions with
Prof. Pillay, freeze the SBGP configuration, run 10+ seeds of each method for Phase 6.

---

## 1. The method (from the paper; GPEM 27:2, doi 10.1007/s10710-025-09528-3)

Also in Scheepers' MSc dissertation (UP, 2024, hdl.handle.net/2263/100614, Chapter 9).
We use SBGP-HH only; the transfer-learning variant is not part of this project.

- **First global search:** canonical GP for Sg generations. The top Dg levels of the best
  individual become the **global area**.
- **Local search:** the area is frozen in every individual; operators act from level Dg+1 down.
  It ends when fitness has not changed for Wg generations.
- **Later global searches:** operators act within the top Dg levels for Sg generations.
  Offspring whose **similarity index** to any previous area is ≥ Tg are discarded.
- **Similarity index (Figure 3):** nodes matched **top-down from the root**. A node counts only if
  it AND every ancestor match. Figure 3's answer is 3; a naive positional count gives 4.
- **Paper parameters (Table 5):** Sg 10, Dg 4, Tg 6, Wg 10 (GP: pop 500, depth 8, tournament 4).
  The paper reports SBGP is not very sensitive to these parameters (Table 27).

## 2. Code added

| File | What it does |
|---|---|
| `src/gp/sbgp/GlobalArea.java` | Stores an area (deep copy, one root per role tree) and computes the top-down similarity index. Terminals are keyed by feature name, because every terminal is the same class (FeatureNode) and ECJ's node comparison would treat them all as equal. |
| `src/gp/sbgp/SBGPBreeder.java` | Phase controller (FIRST_GLOBAL → LOCAL → GLOBAL → LOCAL …). LOCAL: re-grafts the area onto every child and re-evaluates reproduced copies that change. GLOBAL: over-breeds and keeps only dissimilar children; after `max-rounds` it back-fills with the least-similar rejects and warns. Phase-best during GLOBAL comes only from children that passed the filter. Logs every transition, area and rejection rate. Requires breedthreads = 1. |
| `src/gp/sbgp/SBGPNodeSelector.java` | KozaNodeSelector with a depth rule: LOCAL picks only level ≥ Dg, GLOBAL only level < Dg (root = level 0). Falls back to ordinary selection when nothing qualifies and counts it ("selector fallbacks"). |
| `src/gp/sbgp/SimilarityTest.java` | 12 checks including Figure 3 (= 3). `java -cp "out;lib/*" gp.sbgp.SimilarityTest` |
| `src/gp/sbgp/SBGPUnitTest.java` | Graft and top-match checks. `java -cp "out;lib/*" gp.sbgp.SBGPUnitTest` |
| `config/microrts-sbgp.params` | `parent.0 = microrts-stage7.params`; changes ONLY the breeder and the node selectors. Fitness, maps, opponents, role trees, terminals and parsimony are inherited unchanged, so the comparison isolates the method. |

**SBGP parameters** (`breed.sbgp.*`, as in the file): sg 10, dg 4 (pilot 3 overrode it to 2 on
the command line; **set the final value after the meeting**), tg 6, wg 10, count-terminals true,
erc-by-value false, combine any, trees 0,1, tg-cap true, min-improvement 0.001, max-rounds 20.
Plus `breed.clone-pipeline-and-population = true`, and `gp.koza.xover.ns.0/1` and
`gp.koza.mutate.ns.0` set to SBGPNodeSelector.

EvolvedBot needed no change: SBGP only changes breeding, so both methods play through
identical code.

## 3. Interpretations we made (each is a parameter; confirm with Prof. Pillay)

1. **Stagnation** = phase-best has not improved by more than `min-improvement` (0.001) for Wg
   generations. The paper says "no change in fitness", but our fitness is noisy (RandomBiasedAI).
2. **Entering LOCAL:** each child keeps its own subtrees below the area wherever the same
   position exists, and takes the area source's subtrees elsewhere (graft). The alternative,
   in the style of Kapoor & Pillay, replaces non-matching trees with offspring.
3. **Role trees:** similarity is computed per tree and only over the trees that execute
   (`trees = 0,1`: worker, base). `combine = any` means a child is too similar if ANY live tree
   is. Barracks and combat trees never run (their tie rate reads n/a in nearly every generation).
4. **Terminals count toward similarity** (`count-terminals = true`), following the journal text.
   Pillay's 2004 original counted function nodes only.
5. **tg-cap:** Tg per tree is capped at that tree's area size. Otherwise an area with fewer than
   Tg nodes (e.g. a 1-node worker tree) can never be matched and the filter is silently inert.
6. **Phase-best during GLOBAL** is chosen only from individuals that pass the filter.
   Back-filled rejects are excluded.

## 4. Smoke tests (pop 40, Sg = Wg = 2, 10 generations)

| Config | GLOBAL rejection | Worker area changed? | Base changed? | Fitness |
|---|---|---|---|---|
| all trees, combine=all | 39% → 0% | no | yes | flat |
| all trees, combine=any | 100% → 100% (back-filled) | no | no | flat |
| live trees, combine=all | 63% → 0% | no | yes | flat |
| **live trees, combine=any** | 100% → 83% → real escape | **yes** | **yes** | **improved** |

- Under combine=all, the worker area never changed in 3 of 3 runs. Escape happened through
  base or dead trees only, so the playing policy stayed the same.
- Under combine=any over all 4 trees, nothing could escape, because each breeding operation
  changes only one tree.

## 5. Full-size pilots (pop 500, 100 generations, seed 4242, validation maps)

| | Ordinary GP 4242 | SBGP pilot 1 | SBGP pilot 2 | SBGP pilot 3 | Ordinary mean (5 seeds) |
|---|---|---|---|---|---|
| Config | stage 7 | Dg 4, before fixes | Dg 4 + phase-best screen + min-improvement 0.001 | pilot 2 with **Dg 2** | |
| Best fitness of run | – | 0.236 | 0.465 | 0.309 | – |
| Training Hits /112 | 79 | 94 | 81 | 91 | – |
| vs WorkerRush | 50% | 56% | 44% | 44% | 44% |
| vs WR++ | 56% | 63% | 44% | 50% | 51% |
| vs WorkerDefense | 56% | 63% | 44% | 50% | 50% |
| Other 15 panel bots | 100% | 100% | 100% (RBAI 96%) | 100% (RBAI 98%) | 100% |
| Tree size | 10 | 166 | 12 | 78 (depth 11) | – |
| Areas / cycles | – | 2 / 1 | 5 / 4 | 4 / 3 | – |
| Worst decision time | ≤ 20 ms | 2 ms | 15 ms | 16 ms | – |

Result files: `results\sbgp-seed4242.*`, `results\sbgp2-seed4242.*`,
`results\sbgp3-dg2-seed4242.*`, plus `best-seed4242-<stamp>.{txt,ind,params}` per run
(pilot 3: `best-seed4242-20261011-155225`).

**Caution:** same ECJ seed means the same initial population, but RandomBiasedAI is not seeded by
ECJ, so the runs diverge from generation 0 (gen-0 best fitness 0.682 / 0.686 / 0.693). Single
runs per configuration are not evidence of which method is better. Training fitness and
validation also disagree (pilot 3 has the 2nd-best training fitness but mid-pack validation),
as with ordinary seed 6242.

## 6. Findings

1. **The global search behaves as the paper describes.** It escapes within 2 generations (the
   first GLOBAL generation is always ~100% back-filled, because the whole population carries the
   old area; the second rejects ~80%; then ~1–5%). Fitness can get worse during exploration
   (pilot 1: 0.236 → 0.345 → 0.285; Figure 4) or successive areas can get worse (pilot 2:
   0.478 → 0.613; Figure 5). **Pilot 3's first global search escaped to a BETTER area**
   (0.488 → 0.372, below the 0.421 where the local search had stalled), which is the method's
   claimed benefit.
2. **Bug found and fixed: phase-best leak.** In pilot 1, area #2 was the old area again, because
   phase-best came from back-filled rejects. Fixed by interpretation 6.
3. **Decisive issue: Dg versus tree depth under parsimony.**
   - Parsimony (on for both methods since stage 7, required to be identical) keeps trees at
     3–5 levels. The paper's Dg = 4 assumes deeper trees.
   - Pilot 2 (Dg 4): areas ≤ 4 levels, so entire trees were frozen. About 470 of 500 selector
     fallbacks per generation, a flat worker tie rate, and almost no gain in any local phase.
   - Pilot 1 (Dg 4) only worked because its first area happened to be deep.
   - **Pilot 3 (Dg 2): fallbacks dropped to ~120 per generation (mostly the dead 1-node barracks
     tree), and local phases gained 0.550 → 0.421 and 0.371 → 0.318.**
   - "Grafted area onto 0 of 500" in pilot 3, generations 70–79, is expected: every tree of that
     area extended past depth 2, so the selector never fell back and no child lost its top.
4. **Frozen areas can lock in bloat.** Pilot 1's area was taken at generation 10, before parsimony
   had shrunk anything, and parsimony cannot touch frozen nodes: 166 nodes vs 10 for the ordinary
   twin. Pilot 3 ended at 78 nodes, depth 11 (cap 12). Prune before interpreting either.
5. **Known minor gap:** if every individual in a GLOBAL generation is too similar, phase-best
   falls back to the best of them. A later dissimilar individual replaces it only if it is
   better. Consider leaving phase-best unset in that case.

## 7. Questions for Prof. Pillay (meeting 2026-10-12), with evidence and our proposals

1. **Dg when parsimony keeps trees shallow** (finding 3). *Proposal: Dg = 2* (pilot 3 evidence),
   or Dg relative to each tree's depth (always leave ≥ 1 level free). Switching parsimony off
   during LOCAL is ruled out, because parsimony must stay identical across methods.
2. **Role trees.** One area per role tree, similarity only over live roles, rejected if ANY live
   role is too similar (section 4 evidence). Is this faithful to the method?
3. **Do terminals count toward similarity?** Journal text vs her 2004 original. It matters here
   because evolved trees have terminals near the root.
4. **Tg with arity-4 `if>`**, and our tg-cap for small areas (interpretation 5). With Dg = 2 an
   area holds at most 5 nodes, so Tg = 6 is always capped. Should Tg scale with Dg?
5. **Stagnation with a noisy fitness:** is a minimum improvement acceptable (interpretation 1)?
6. **Entering LOCAL:** graft vs replace (interpretation 2).
7. **Training/validation gap:** better training fitness has not reliably meant better validation
   (pilot 3, ordinary seed 6242). Is selecting the reported individual on validation (our current
   rule) acceptable for both methods?
8. **Also on the agenda:** freeze the map split; the unit-table version used by the 2025
   benchmark; the stage-7 seed-8242 result.

9. **Sg and Wg for a 100-generation budget with a noisy fitness.** The paper's Sg = Wg = 10 gave
   pilot 3 three cycles, with about 30% of generations spent in global search. Should Wg be larger
   (or min-improvement 0) so local search can run as long as it keeps gaining, as in pilot 1?
   Should the generation budget be raised for SBGP, and then for ordinary GP too, to keep the
   comparison fair?

## 8. Next steps

1. Apply Prof. Pillay's answers as parameter values; write the final Dg into
   `microrts-sbgp.params` and freeze it.
2. Run 10+ seeds per method (ordinary stage 7 and SBGP), identical except breeder and node
   selector. Report seed spread and the validation-selected individual separately.
   **Stage-7 seeds 9242–13242 were started the evening of 2026-10-11** (logs:
   `results\stage7-seed<seed>.log`). Check them with:
   `foreach ($b in 9242,10242,11242,12242,13242) { $log = "results\stage7-seed$b.log"; if (Test-Path $log) { "seed $b : finished=" + (Select-String -Path $log -Pattern "best fitness of run" -Quiet) + ", last written " + (Get-Item $log).LastWriteTime } else { "seed $b : NO LOG" } }`
3. Prune and inspect the pilot SBGP bots (`gp.Pruner`, `gp.IndividualLoader`).
4. Still open from before: list-order ablation, ERC round-trip test, unit-table version check,
   `src/gp/nodes/` folder move, delete `src/gp/symreg/`.

## 9. Housekeeping

- A stale pre-role-tree copy of `EvolvedBot.java` was found in `lib\bots\` and removed. The live
  file is `src\bots\EvolvedBot.java` (role trees, tie counters, none-duration).
- Snapshot folders `out-sbgp`, `out-sbgp2`, `out-sbgp3` can be deleted. Keep `out-stage7` until
  the seed runs finish. Keep all snapshot folders git-ignored.
- Commit today's work: `src/gp/sbgp/`, `config/microrts-sbgp.params`, the pilot results.
# PROJECT_CONTEXT.md — session update 2026-10-12 (supervisor meeting; SBGP frozen; ordinary GP 10 seeds complete)

How to merge: replace the "Current status" paragraph in section 1 with the one under
"For section A". Append everything from "Session update" down above the "Keep this file
current" line. Apply the one-line fixes under "For section 10".

---

## For section A (replaces "Current status"  UPDATED SEE END OF FILE FOR NEW GP +ADAPTER STAUS)

**Current status:** Ordinary GP complete: 10 seeds of stage 7 on frozen config and frozen
map split. Selected individual is seed 8242 (69 / 81 / 75% vs WorkerRush / WR++ /
WorkerDefense on validation); the seed mean is 40 / 49 / 48%. SBGP configuration frozen
after the 2026-10-12 meeting with Prof. Pillay (Dg = 2). The 10 final SBGP seeds were
launched 2026-10-12 ~12:30 (about 15 h, logs `results\sbgpF-seed<seed>.log`). The adapter
(Phase 5) is NOT built yet; only the `scores[]` hook in EvolvedBot exists. Next: compare the
SBGP and ordinary-GP seed distributions, then build the Java–Python bridge, which serves both
the adapter and the PPO baseline.

## For section 10 (workplan table)

- Phase 2: map split **frozen** (2026-10-12).
- Phase 4: configuration **frozen** (Dg = 2); final seed runs in progress.
- Phase 5: **not started**. Do not assume an adapter exists.

---

Session update (2026-10-12): supervisor meeting, SBGP frozen, ordinary-GP seeds complete.

### 1. Decisions from the meeting with Prof. Pillay

| Question | Decision |
|---|---|
| Dg under parsimony | Our choice. **Dg = 2**, justified by pilots 2 vs 3 (Dg 4 froze whole trees, ~470/500 selector fallbacks per generation; Dg 2 restored local-search gains). |
| Parsimony fairness | Our choice. **Identical parsimony for both methods**; no extra no-parsimony arm. |
| Budget | **Equal generations** for both methods; report fitness-evaluation counts per run alongside. (As we understood her answer; confirm with the team if unsure.) |
| Map split | **Frozen** as in section D, on condition that the final evaluation compares against state-of-the-art methods. |

All other SBGP interpretations (Phase 4 notes, section 3, items 1–6: min-improvement 0.001,
graft on entering LOCAL, role trees with combine = any over live trees 0,1, terminals counted,
tg-cap, phase-best from filtered children only) are **ours by discretion**. They must appear in
the write-up as a "interpretations of the method" subsection, each with its reason and pilot
evidence.

**Her condition, in practice:** the final test-map evaluation must include the
state-of-the-art opponents from the proposal: Coac, mayari, TMA and the pre-trained PPO agent.
- Coac and mayari load already (`ai.coac.CoacAI`, `mayariBot.mayari`).
- **TMA:** check whether it is one of the jars in `lib/bots/`; it is not obviously there.
- **PPO agent:** runs in Python (MicroRTS-Py), so it needs the same Java–Python bridge as the
  adapter. Building the bridge serves both.

### 2. Frozen SBGP configuration

`config\microrts-sbgp.params` (patched 2026-10-12, line 21: `breed.sbgp.dg = 2`):
sg 10, **dg 2**, tg 6, wg 10, count-terminals true, erc-by-value false, combine any,
trees 0,1, tg-cap true, min-improvement 0.001, max-rounds 20. Inherits everything else from
`microrts-stage7.params` unchanged. **Do not edit this file again** before the final
evaluation.

### 3. Final SBGP runs (in progress)

- Seeds 4242, 5242, …, 13242 (10 runs), the same seed bases as ordinary GP, one run per seed
  per method. Pilot 3 (also Dg 2, seed 4242) is NOT reused; all 10 runs are under the
  formally frozen config.
- Build snapshot: `out-sbgpfinal` (so `out/` can be rebuilt for bridge work).
- Logs and stats: `results\sbgpF-seed<seed>.{log,stat}`.
- Started ~12:30 on 2026-10-12, about 1.5 h per seed, expected to finish early 2026-10-13.

Progress check:
```powershell
Get-ChildItem results\sbgpF-seed*.log | ForEach-Object { $_.Name + " : " + ((Select-String -Path $_.FullName -Pattern "best fitness of generation" | Select-Object -Last 1).Line) }
```

Summary once finished:
```powershell
foreach ($s in 4..13) { $b = $s*1000 + 242; "===== SBGP seed $b"; Select-String -Path "results\sbgpF-seed$b.log" -Pattern "best fitness of run|tree: |^vs WorkerRush |^vs WorkerRushPlusPlus |^vs WorkerDefense |^vs RandomBiasedAI " | ForEach-Object Line }
```

Rest-of-panel check (empty output = every other panel bot at 100%):
```powershell
foreach ($s in 4..13) { $b = $s*1000 + 242; Select-String -Path "results\sbgpF-seed$b.log" -Pattern "^vs " | Where-Object { $_.Line -notmatch "win rate 100%" -and $_.Line -notmatch "^vs (WorkerRush|WorkerRushPlusPlus|WorkerDefense|RandomBiasedAI) " } | ForEach-Object { "seed $b : " + $_.Line } }
```

### 4. Ordinary GP (stage 7): all 10 seeds, validation maps

| Seed | Hits /112 | Nodes | vs WorkerRush | vs WR++ | vs WorkerDefense | vs RandomBiasedAI |
|---|---|---|---|---|---|---|
| 4242 | 79 | 10 | 50% | 56% | 56% | 96–100% |
| 5242 | 74 | 4 | 31% | 44% | 44% | 96–100% |
| 6242 | 87 | 76 | 25% | 31% | 31% | 96–100% |
| 7242 | 88 | 70 | 44% | 44% | 44% | 96–100% |
| **8242** | **103** | 54 | **69%** | **81%** | **75%** | 100% |
| 9242 | 93 | 70 | 44% | 44% | 44% | 98% |
| 10242 | 86 | 12 | 25% | 50% | 50% | 100% |
| 11242 | 74 | 246 | 38% | 38% | 38% | 99% |
| 12242 | 91 | 50 | 44% | 50% | 50% | 98% |
| 13242 | 80 | 6 | 31% | 50% | 50% | 94% |
| **Mean** | | | **40%** | **49%** | **48%** | |
| **Median** | | | 41% | 47% | 47% | |

Rest of the panel (the other 14 scripted bots): **100% for 9 of 10 seeds.** Seed 11242 is the
exception: 56–88% against all 11 non-worker bots, with 10–25 draws each and a few losses
(LightRush, EconomyRush, LightDefense). Draws come in multiples of 5 (each distinct game is
replayed 5 times), so they are whole deterministic games, probably 16x16 games not finished
within the cycle cap. Not yet confirmed per map.

### 5. Interpretation (for the write-up)

- **Ordinary GP is strong but unreliable.** It typically beats nearly the whole scripted panel
  on unseen maps, and its worker-bot results (~40–50%) exceed every engine bot on 16x16 (§C.4:
  every other engine bot won 0/12 vs WorkerRush there). But results vary widely by seed (25–69%
  vs WorkerRush).
- **The selected individual is an outlier.** Seed 8242 is 19 points above the next seed vs
  WorkerRush. Report the seed distribution (typical ≈ 40%) and the selected individual
  separately, as the methodology rules require.
- **Failure modes across 10 seeds:** 2 collapsed to near-trivial trees under parsimony (5242:
  4 nodes, 13242: 6 nodes); 1 bloated and overfit (11242: 246 nodes, 241 in the worker tree,
  lowest Hits, the only seed below 100% on the rest of the panel).
- **Training Hits predict validation loosely.** The top-Hits seed is best on validation, but
  9242 and 12242 (Hits 93, 91) are only average.
- **What this means for the SBGP comparison:** the question is not whether SBGP can beat the
  panel (ordinary GP already does) but whether it produces good bots more **reliably**: higher
  median, fewer collapsed or bloated runs. The paper's claim that global search escapes poor
  areas maps directly onto the 3-in-10 failure rate. A null result is also reportable.

### 6. Corrections to earlier assumptions

- **The adapter does not exist.** Phase 5 has not started. What exists is the per-unit
  `scores[]` hook in EvolvedBot, designed in at M0. If a teammate has started an adapter or
  bridge outside these sessions, record it here.
- **The 2×2 design** (section 2) is: ordinary GP, SBGP, ordinary GP + adapter, SBGP + adapter.
  The adapter wraps a **frozen** evolved bot and is trained afterwards with RL, so the hybrid
  configurations come after the final bots of each method are selected.

### 7. Next steps, in order

1. **When SBGP finishes:** run the summaries in section 3, build the same 10-seed table, and
   compare the two distributions (median, spread, collapsed/bloated runs). Select the SBGP
   individual on validation only.
2. **Evaluation counts per run:** the budget decision requires reporting them. Check whether
   the logs already contain them; if not, a counter needs adding (ask for the source files
   first).
3. **Java–Python bridge** (Phase 5 groundwork and the PPO baseline). Decide first whether to
   plug into MicroRTS-Py's own engine copy or build our own bridge. Open question: does anyone
   on the team have MicroRTS-Py installed? Develop against seed 8242 as a stand-in policy;
   swap in the final bots later.
4. **Check TMA** is available as a jar or in some form, for the final evaluation.
5. **Seed 11242 per-map grid** (after the SBGP runs, to avoid CPU contention):
```powershell
   java -cp "out;lib/*;lib/bots/*" gp.Bench (Get-ChildItem results\best-seed11242-*.ind | Where-Object Name -notmatch "pruned" | Select-Object -First


Possible names for hybrid -> Darwin or Baldwin( after the Baldwin effect)


Session update (2026-10-12, afternoon): Phase 5 plan: SBGP + adapter hybrid, paper obtained.

### Ownership
- Heinrich builds the SBGP + adapter hybrid with his OWN adapter on his branch.
  Teammates have built GP + adapter and GP + SBGP on their branches.
- Build first, align later: every adapter design choice below is a PARAMETER, so if the team
  settles on a different design, it is a setting change plus retraining, not a rewrite.
- For the 2×2 to be clean, GP + adapter and SBGP + adapter must eventually use the SAME
  adapter design and training budget. Agree this with the team before final training.

### The paper
Jin, Slabaugh & Lucas, "ADAPTER-RL: Adaptation of Any Agent using Reinforcement Learning",
arXiv:2311.11537 (2023). https://arxiv.org/abs/2311.11537 (free PDF).

- Base agent (any agent, including rule-based) picks an action -> one-hot -> scaled by 1/τ.
- Adapter = actor-critic network trained with PPO; outputs an adjustment vector over actions.
- Final distribution: p(a_i) = softmax_i( a_i^base / τ + a_i^adj ). Sample during training.
- Base agent is never changed (frozen).
- Their network: 2-layer CNN -> MLP, 3 layers × 512 units. PPO: γ 0.99, GAE λ 0.95,
  clip 0.2, value coef 1, Adam lr 2.5e-4. 3 seeds. Control group trained 500 iterations ×
  8192 samples.
- τ: stable across a wide range; good results for τ in [1/1000, 1/10].
- Experiments in nanoRTS (Python microRTS), one adapter trained PER MAP.

### Design for our hybrid (defaults; each is a parameter)
1. Base agent = frozen EvolvedBot (the selected SBGP individual; seed 8242 as stand-in
   during development). Loaded via gp.IndividualLoader.
2. Base distribution = ONE-HOT of the action EvolvedBot actually takes (after the
   resource-consistency fallback), scaled by 1/τ. Faithful to the paper, so the τ range
   transfers. Softmax-over-scores is an optional ablation (our scores have arbitrary scale,
   so the paper's τ range does not apply to them).
3. Fixed per-unit action vocabulary for the adapter head, illegal actions masked:
   NONE 1, MOVE 4, HARVEST 4, RETURN 4, PRODUCE 4 dirs × 6 unit types = 24,
   ATTACK 7×7 relative grid = 49  -> 86 actions. (Gym-µRTS uses the same idea.)
4. Network: fully convolutional, outputting 86 logits per map cell (each unit reads its
   own cell) plus a value head, to handle 8x8 and 16x16 in one network. Departure from
   the paper's CNN -> MLP, which assumes a fixed map size. Alternative: pad to 16x16.
5. Training in Python (PyTorch, GPU), driving the Java engine directly via JPype.
   MicroRTS-Py is NOT needed for the adapter (only for the PPO baseline later).
6. Playing in Java: export trained weights; forward pass implemented in Java inside
   EvolvedBot, so final evaluation (scripted panel, Coac, mayari) needs no Python and stays
   within the 100 ms budget.
7. Evaluation picks the ARGMAX of the combined distribution (deterministic, comparable to
   the GP bots). The paper does not say what it does at test time.
8. Train on TRAIN maps vs training opponents only. Reward: win/loss, optionally shaped
   (decide; the GP fitness samples SimpleSqrtEvaluationFunction3 during play).

### Departure to state in the write-up
The paper trains one adapter per map (adaptation to a known task). We train on training
maps and evaluate on held-out maps, so our adapter must GENERALISE. The paper itself notes
RL agents generalise poorly across microRTS maps. This may limit adapter gains, and it is
part of what our results test.

### EvolvedBot hook (current state, from the source)
- Legal actions: u.getUnitActions(gs, noneDuration); scores[] per unit; best-first walk
  with resource-consistency fallback; NONE if nothing affordable.
- lastScores holds only the LAST unit's scores, so it is not enough for the adapter. The
  adapter needs, per idle unit: the unit, its legal list and the action actually chosen.
  Plan: an optional adjuster interface called per unit between scoring and selection;
  null = current behaviour, so GP runs and archived bots are unaffected.

### Next steps
1. Confirm the Python environment (Python, PyTorch with CUDA, JPype).
2. Java side: action-vocabulary encoder (UnitAction <-> 0..85), observation encoder
   (map -> feature planes), adjuster hook in EvolvedBot.
3. Python side: JPype environment wrapper, PPO trainer, adapter network.
4. Smoke test on one 8x8 training map vs PassiveAI/WorkerRush with seed 8242 as base.
5. Weight export + Java forward pass; verify Java and Python give identical outputs.
6. Swap in the selected SBGP bot when the runs finish.

small note. Henirich uses a 4050 Laptop GPU

---

# Session update 2026-10-14: SBGP results; SBGP + adapter pipeline built, verified and first-trained

## Current status (supersedes earlier "Current status" paragraphs)

Ordinary GP and SBGP are both complete: 10 seeds each, frozen configs, frozen map split.

- **SBGP vs ordinary GP:** SBGP averages 48 / 51 / 51% against WorkerRush / WR++ / WorkerDefense on validation, against ordinary GP's 40 / 49 / 48%. The difference is not significant. SBGP has a higher floor and a lower peak.
- **Selected SBGP individual:** seed 5242, pruned 46 → 14 nodes. It is the purest all-in worker rush so far.
- **The SBGP + adapter hybrid is wired end to end.** The EvolvedBot hook, Java RL environment, PPO trainer and argmax evaluator are each verified to reproduce the base bot exactly.
- **No trained adapter improves on the base yet.** 20-iteration runs at τ 0.1 / 0.2 / 0.3 left the argmax unchanged.
- **Overnight 2026-10-14:** 500-iteration runs at τ 0.2, then τ 0.1.
- **Next:**
  1. evaluate the overnight checkpoints;
  2. implement the tie-aware base distribution (section 7);
  3. align the adapter design with the GP + adapter teammates.

## Instructions for the next assistant session (additions)

- **Adapter runs use `out/` directly.** The Python scripts put `ROOT/out` on the JPype classpath. While an adapter run is going **or queued** (for example, a foreach over several τ values), do not rebuild `out/` and do not edit `adapter/*.py`. A later run in the queue loads both fresh and would silently change.
- **Python:** use plain `python` (3.10.11). There are three Pythons on PATH, and only 3.10 has torch and JPype.
- **Terminal:** Heinrich runs commands in the VS Code terminal at the repo root, so no `cd` is needed. Give each command as a single line where possible.

## 1. SBGP final results (10 seeds, frozen config Dg = 2, validation maps)

| Seed | Hits /112 | Nodes [per tree] | vs WR | vs WR++ | vs WD | vs RBAI | Rest of panel | Areas |
|---|---|---|---|---|---|---|---|---|
| 4242 | 84 | 250 [81,27,139,3] | 44% | 44% | 44% | 100% | SimpleEconomyRush 94% | 4 |
| **5242** | 94 | 46 [7,29,1,9] | **63%** | **75%** | **75%** | 100% | 100% | 5 |
| 6242 | 90 | 22 | 44% | 50% | 50% | 99% | 100% | 5 |
| 7242 | 91 | 58 | 44% | 44% | 44% | 98% | 100% | 4 |
| 8242 | 90 | 20 | 50% | 50% | 50% | 100% | 100% | 5 |
| 9242 | **106** | 140 | 44% | 44% | 44% | **76%** | **69–86% across the panel** | **3** |
| 10242 | 78 | **6** [3,1,1,1] | 38% | 44% | 44% | 94% | Light/Heavy/RangedRush 75% (draws) | 5 |
| 11242 | 89 | 18 | 56% | 63% | 63% | 96% | RandomAI 99% | 5 |
| 12242 | 91 | 144 | 50% | 56% | 56% | 95% | 100% | 4 |
| 13242 | 82 | 16 | 44% | 44% | 44% | 93% | 100% | 4 |
| **Mean** | | | **48%** | **51%** | **51%** | | | |
| **Median** | | | 44% | 47% | 47% | | | |

Every run ended mid-phase (Sg = Wg = 10 in 100 generations).

| | Ordinary GP (stage 7) | SBGP |
|---|---|---|
| vs WR: mean / median | 40% / 41% | 48% / 44% |
| vs WR: range | 25–69% | 38–63% |
| vs WR++ / WD: mean | 49% / 48% | 51% / 51% |
| Rest of panel at 100% | 9 of 10 seeds | 6 of 10 (7 ignoring a RandomAI 99%) |
| Collapsed (≤ 6 nodes) | 2 (5242, 13242) | 1 (10242) |
| Bloated / overfit | 1 (11242) | 1 (9242) |
| Validation-selected | 8242: 69 / 81 / 75% | 5242: 63 / 75 / 75% |

**Findings for the write-up:**

1. **No significant difference.** Mann-Whitney U on per-seed WorkerRush rates gives U = 28, against a critical value of 23 (n = 10, 10, two-sided α = 0.05). SBGP's advantage is a raised floor (worst seed 38% vs 25%), not a higher peak. SBGP is also weaker on the wider panel (3 seeds below 100% vs 1).
2. **Training Hits are a poor selector, again.** SBGP 9242 had the best training Hits of any run in the project (106/112), yet it is the worst seed on the wider panel. It also found the fewest areas (3), so it spent the longest stretches in local search. One seed only, so this is a hypothesis, not a claim: long local exploitation of one area overfits.
3. **The two validation-selected bots converge.** Ordinary 8242 and SBGP 5242 play 14 of 16 WorkerRush validation games identically:
```
   ordinary 8242:  8x8B:LT 8x8D:WW 8x8F:WW 8x8G:LW 8x8K:WW 16x16:WL 16x16C:WW 16x16E:WT
   SBGP     5242:  8x8B:LL 8x8D:WW 8x8F:WW 8x8G:LW 8x8K:WT 16x16:WL 16x16C:WW 16x16E:WT
```
4. **There is no shared 7/16 attractor.** Many seeds of both methods win exactly 7 of 16 WorkerRush games, but a different 7. This was checked on ordinary and SBGP seeds 7242, 9242 and 13242. The only shared structure is coarse: small 8x8 maps are mostly winnable, and 16x16 and 16x16E are mostly lost. Suggested phrasing: "the top individuals of both methods converge; weaker seeds differ in *which* games they win, not just how many."
5. **Evaluation counts are nominal.** "Evaluations So Far" reads 49,500 at generation 99 for every run of both methods, which is just generations × 500. It ignores ECJ skipping unchanged reproduced individuals and SBGP's LOCAL-phase re-evaluations. Report: "equal nominal budget, 100 × 500 = 50,000 evaluation slots; actual game counts not logged." If real counts are needed, add a counter in `MicroRTSProblem.evaluate()` and do a short run of each method to get the ratio.

## 2. The selected SBGP bot (seed 5242), pruned and explained

**Files:** `results\best-seed5242-20261012-160727{,-pruned}.{ind,params}` and `results\prune-sbgp-seed5242.log`.

**Pruning:** 46 → 14 nodes, depth 3, [5, 7, 1, 1] per tree. It was traced on training maps and opponents, then verified on all 8 validation maps. All 18 panel rows are equivalent, and stochastic opponents are decision-identical over 80 sampled games.

```
tree 0 (worker):   (+ (+ tgtKillable movesTowardEnemy) movesTowardEnemyMajor)
tree 1 (base):     (/ aIsNone (if> gameTimeK aProducesCombat tgtIsBuilding distNearestResource))
tree 2 (barracks): aProducesBuilding        -- never runs
tree 3 (combat):   movesTowardEnemyBase     -- never runs
```

**Workers.**
- Scores: a move toward the nearest enemy along the long axis scores 2, other moves toward it score 1, and attacking a killable target scores 1. Everything else scores 0.
- Play: every worker charges the nearest enemy. When an enemy is adjacent, the move into its cell is illegal, so the attack wins. Next to the enemy base nothing scores above 0, and list order (attack first) makes the worker hit the base. No worker ever harvests.

**Base.** Two facts were checked in the source: `gameTimeK` is continuous (`time/1000.0`), and protected division returns 1.0 on a zero divisor.
- At cycle 0, waiting scores 1/distance > 0, so the base **waits one cycle**.
- From cycle 1 the divisor is `tgtIsBuilding` = 0, so every action scores 1.0. List order (PRODUCE before NONE) then makes it **train workers whenever it can**.
- So the tree amounts to "wait one cycle, then defer to engine list order". The pruner correctly kept it, because the delay changes decisions.

**Watched** (16x16C vs WorkerRush): the base pumps workers, no worker ever harvests or returns, and it wins.

This is the **purest all-in worker rush** in the project: ordinary 8242's combat branch without the resource gate.

## 3. Infrastructure bug fixed: SBGP `.params` snapshots were not self-contained

**The bug.** BenchmarkStatistics' snapshot (written via `ParameterDatabase.list`) kept the line `parent.0 = microrts-stage7.params` from `microrts-sbgp.params`. ECJ resolves a relative parent path from the snapshot's own folder, so it looked for `results\microrts-stage7.params` and loading failed. Ordinary-GP snapshots were unaffected, because stage 7's parent is the jar reference `@ec.gp.koza.GPKozaDefaults`.

**Fixed in place.** The parent lines are commented out in all 18 affected snapshots in `results\` (the 10 final SBGP runs, the pilots and the smoke runs). The originals are kept as `*.params.orig`. The loader round trip and benchmarks reproduce exactly.

**Source fix still pending.** BenchmarkStatistics should skip `parent.*` keys when writing the snapshot. Pruner copies whatever snapshot it loaded, so it inherits the fix.

## 4. Adapter (Phase 5): pipeline built and verified

**Environment.**
- Python 3.10.11, PyTorch 2.7.1+cu118 with CUDA, JPype 1.7.1, JDK Temurin 21.0.9 (`JAVA_HOME` set).
- **GPU: RTX 4050 Laptop, 6 GB.** This corrects the earlier note of a 4070.
- Package list saved to `results\python-env-20261014.txt`.

**JPype smoke test** (`adapter\smoke_jpype.py`): Python drives the engine cycle by cycle at about 9,000 cycles/s.

**EvolvedBot hook** (`src\bots\EvolvedBot.java`, replaced). `getAction` is now split in two:
- `propose(player, gs)` runs exactly the old getAction body and also returns, per idle unit, a `UnitDecision`: the unit, role, legal list, scores, and `pick` (the index of the action the base policy took, -1 = NONE fallback). `pick` is the paper's one-hot base action.
- `static commit(gs, player, decisions, chosen, executed)` builds a PlayerAction from chosen indices. Per unit, in order: the chosen action if affordable, else the base pick if affordable, else NONE.
- `getAction` is now `return propose(...).action`. A new accessor `noneDuration()` was added.
- **Known approximation:** base picks reserve resources for earlier units' *base* picks, and are not recomputed if the adapter changes an earlier unit.
- **Verified:**
  - `rl.HookTest`: commit(base picks) reproduces propose() on 12 games, 6,756 decisions, 0 mismatches. Random overrides: games complete, 274 affordability fallbacks.
  - Bench grids of pruned 5242 and pruned 8242 are unchanged.

**Package `rl`** (new; depends on eval, bots and gp; nothing depends on it):
- **`rl.ActionCodec`:** a fixed 86-code vocabulary per unit.

  | Codes | Action |
  |---|---|
  | 0 | NONE |
  | 1–4 | MOVE up / right / down / left |
  | 5–8 | HARVEST, by direction |
  | 9–12 | RETURN, by direction |
  | 13–36 | PRODUCE: 6 types (Worker, Light, Heavy, Ranged, Base, Barracks) × 4 directions |
  | 37–85 | ATTACK: 7×7 relative offsets |

  Decoding only looks codes up in the unit's own legal list, so a decoded action is always legal.
- **`rl.AdapterEnv`:** `reset(map, opponent, side)` and `step(int[] codeByCell)`. Each step advances to the next decision point, skipping cycles with no idle units. The game loop matches Panel.play call for call. It exposes:
  - `obs()`: 29 planes, channel-major. Mine, enemy, 7 unit types, HP, carrying, resource amount, busy, 5 current-action types, wall, decide-here, 6 base-action types, and broadcast planes for my resources, enemy resources and time.
  - `mask()`: H×W×86.
  - `baseCode()`: -1 where no unit decides.
  - `executedCode()`.
  - `evalNow()`: normalised SimpleSqrtEvaluationFunction3 value, as in Panel.
  - `diagnostics()`.

  Sending a cell's baseCode back reproduces the exact base pick. Illegal codes fall back to the base pick and are counted.
- **`rl.EnvTest`:**
  - Identity: base codes reproduce Panel.play on 64 games (8 training maps × WorkerRush/LightRush/WorkerDefense/PassiveAI × 2 sides), 0 mismatches over 36,585 decisions, 0 unencodable, 0 duplicate codes.
  - Random codes: 48 games complete, 0 illegal; **53,000 decision steps/s in Java**.

**Python** (`adapter\`):
- `env_check.py`: about **10,700 decision steps/s** driven from Python with numpy conversion, so the bridge costs about 5×.
- **`ppo_adapter.py`:** the trainer.
  - Per unit, p(a) = softmax(onehot(base)/τ + adj(s)), masked to legal codes (the paper's Eq. 3, checked against arXiv:2311.11537).
  - PPO with the paper's settings: γ 0.99, λ 0.95, clip 0.2, value coefficient 1, Adam lr 2.5e-4, 8,192 samples per iteration (8 envs × 1,024 decision steps).
  - Our additions: 4 epochs × 8 minibatches, entropy coefficient 0.01, gradient clip 0.5.
  - Reward: +1 win, −1 loss, 0 draw, plus `--shaping` (0.5) × Δ`evalNow`.
  - Episodes sample uniformly over the 8 TRAINING maps × 6 TRAINING opponents × side, mirroring stage 7.
  - Output goes to `results\adapter\<run>\`: `args.json`, `log.csv`, and `adapter-NNNN.pt` every 10 iterations plus a final checkpoint (Ctrl+C also saves).
- **Network** (departure from the paper's CNN → MLP): fully convolutional. 3 × conv3x3(64), a global-context broadcast, then a conv mix. The policy head is a 1x1 conv to 86 logits per cell, **zero-initialised so iteration 0 IS the base bot**. The value head pools mean+max into an MLP. Boards are padded to 16×16 with the padding marked as wall.
- **`eval_adapter.py`:** argmax play on the 8 VALIDATION maps against the panel (default: `eval.Panel.FULL_PANEL`), in Bench format with per-map grids. With no `--ckpt` it evaluates the bare base bot. **Verified:** base-only output matches Bench exactly (WR 10W-2T-4L, WR++ 12W-0T-4L, WD 12W-2T-2L, same grids, 0.00% argmax differences).

**Departures from the paper, to state in the write-up:**
- fully convolutional per-cell adapter instead of CNN → MLP;
- one adapter across all training maps and opponents (the paper trains one per map), evaluated on held-out maps;
- joint action = one code per idle unit, with a step's log-prob summed over units;
- argmax at evaluation (the paper does not say what it uses at test time).

## 5. First adapter runs (20 iterations each, base = SBGP 5242 pruned)

| τ | Override rate (sampled ≠ base), iteration 1 → 20 | Entropy | Cost while exploring | Argmax change at iteration 20 |
|---|---|---|---|---|
| 0.1 | 0.02% → ~0% | 0.001 → 0.0005 | none | 0.00% |
| 0.2 | 1.3% → ~1% (fluctuating) | 0.07 → 0.05 | small | 0.00% |
| 0.3 | 6.7% → 0.7% (falling) | 0.28 → 0.02 | RBAI 88%, LR 90% | 0.01% |

Findings:
- **At the paper's τ = 0.1 there is no exploration at all.** The base logit is 10. The paper itself warns that low τ suppresses exploration.
- **At τ = 0.3 the adapter first learns to *suppress* deviations,** because random ones are mostly harmful.
- **PPO barely updated** (kl ≈ 0.0001, clipfrac ≈ 0). 20 iterations is about 4% of the paper's budget.
- **Do not judge progress from the trainer's rolling W/D/L.** Short games finish first, so early windows are full of quick wins, and the rates fall even at τ = 0.1, where the policy *is* the base bot. Use `eval_adapter.py` on validation.
- **Throughput:** about 900–1,400 decision steps/s with the network in the loop, and 8–15 s per iteration.

## 6. Overnight runs (started 2026-10-14 ~20:20)

```powershell
foreach ($t in 0.2,0.1) { python adapter\ppo_adapter.py --tau $t --iters 500 --run "tau$t-500" 2>&1 | Tee-Object "results\adapter\tau$t-500.log" | Select-String "^it |^run |^saved" }
```

τ 0.2 is our best guess and τ 0.1 is the paper-faithful control. They take about 2 h and 1.5 h. Power settings were changed to stop sleep on AC; restore them afterwards (admin PowerShell):
`powercfg /change standby-timeout-ac 30; powercfg /change hibernate-timeout-ac 180; powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 1; powercfg /setactive SCHEME_CURRENT`

**Morning check** (every 100th checkpoint, worker bots only, about 7 minutes):
```powershell
foreach ($t in 0.2,0.1) { foreach ($i in "0100","0200","0300","0400","0500") { $c = "results\adapter\tau$t-500\adapter-$i.pt"; if (Test-Path $c) { python adapter\eval_adapter.py --ckpt $c --opponents WorkerRush,WorkerRushPlusPlus,WorkerDefense 2>&1 | Select-String "^vs |^TOTAL|^ckpt" } } }
```

How to read it:
- **~0% argmax differences at iteration 500:** exploration is the binding problem.
- **Differences appear but the score drops:** the adapter overfits the training maps.
- **The score rises above 34W:** run the full panel.

The base reference on validation is 34W-4T-10L against the three worker bots.

## 7. Plan: making the hybrid stronger, more consistent and faster to train

**Tie-aware base distribution (main idea).** Give logit 1/τ to every action in the GP's top-score **tie set**, not only the list-order pick, plus a small bonus on the actual pick so the initial argmax is still exactly the base bot.
- Exploration then happens only where the GP was indifferent: the decisions currently made by engine list order, such as attack-target choice, the direction around blocked cells, and production placement. Deliberate GP decisions are never overridden at random.
- The paper explicitly allows the base agent to output an action *distribution*, so this is faithful to the method.
- Narrative: "GP decides what matters; the adapter learns the choices GP left to the engine's action order."
- **Needs:** AdapterEnv exposing each cell's tie set (scores are already in `UnitDecision`), plus about 10 lines in the trainer.
- **Report it as a clean comparison:** paper-faithful one-hot vs tie-aware.

**Supporting changes:**
- **Opponent weighting:** sample WorkerRush, WR++ and WorkerDefense more often; all the headroom is there. This must be identical for GP + adapter.
- **16 envs instead of 8:** rollouts are per-step bound, so this should roughly double throughput.
- **3+ adapter seeds,** selected on validation (the same rule as GP).

**Team alignment (required for a clean 2×2).** GP + adapter and SBGP + adapter must use the same adapter design, τ, reward, opponent weighting and training budget. Agree before final training.

## 8. Code added or changed this session

| File | New / replaced | What |
|---|---|---|
| `src\bots\EvolvedBot.java` | replaced | propose/commit hook, UnitDecision, Proposal, `noneDuration()`; play unchanged (verified) |
| `src\rl\HookTest.java` | new | propose/commit identity + random-override test |
| `src\rl\ActionCodec.java` | new | 86-code action vocabulary |
| `src\rl\AdapterEnv.java` | new | RL environment for JPype |
| `src\rl\EnvTest.java` | new | env identity vs Panel.play + random-code test |
| `adapter\smoke_jpype.py` | new | JPype bridge smoke test |
| `adapter\env_check.py` | new | env driven from Python, throughput |
| `adapter\ppo_adapter.py` | new | PPO trainer (Adapter-RL) |
| `adapter\eval_adapter.py` | new | argmax evaluation on validation maps |

Test commands:
- `java -cp "out;lib/*;lib/bots/*" rl.HookTest`
- `java -cp "out;lib/*;lib/bots/*" rl.EnvTest`
- `python adapter\env_check.py`
- `python adapter\ppo_adapter.py --smoke`

## 9. Housekeeping and open items

- The **original ordinary-GP 9242 training log was overwritten** on 2026-10-13 by an accidental restart, killed in generation 0. Its numbers survive in the 2026-10-12 table, and `best-seed9242-20261011-182142.{ind,params}` still benches at 44% against WorkerRush.
- **File-name trap:** `best-seed<N>-*` exists for both methods (and for the pilots, for seed 4242). Pick by timestamp: ordinary stage 7 is 2026-10-04 or 2026-10-11/12 morning; final SBGP is 2026-10-12 afternoon to 2026-10-13.
- **Still open:**
  - BenchmarkStatistics `parent.*` source fix;
  - real evaluation counts (optional);
  - Java-side adapter forward pass, for final evaluation inside the 100 ms budget without Python;
  - the PPO baseline (needs the bridge, now largely built);
  - TMA availability;
  - unit-table version check for the 2025 benchmark;
  - list-order ablation (now cheap: the adapter env can shuffle or override ties);
  - ERC round-trip test;
  - `src/gp/nodes/` folder move;
  - delete `src/gp/symreg/`.
- **Commit tonight's work:** `src\rl\`, `src\bots\EvolvedBot.java`, `adapter\`, the pruned SBGP 5242 files, and `results\prune-sbgp-seed5242.log`. Add `adapter\__pycache__\` to `.gitignore`, and decide whether `results\adapter\*.pt` checkpoints go into git (probably not; archive the selected ones only).

---

# Session update 2026-10-16 / 2026-10-17: adapter evaluated against a null control; tie-aware mode built and tested

## Current status (supersedes earlier "Current status" paragraphs)

Ordinary GP and SBGP are complete (10 seeds each; no significant difference). The SBGP + adapter hybrid is trained and evaluated in two modes: the paper's one-hot base distribution, and a new tie-aware mode.

- **No adapter configuration reliably improves on the base bot.** Tie-aware mode is a null result.
- **Every run overfits the training maps:** late checkpoints score worse on validation than random perturbation does, while training win rates rise.
- **There is one candidate signal.** One-hot τ 0.2, seed 1, iterations 390–420 scored 36–38W, above the null maximum of 34W.
- **A reproduction test is running:** two more training seeds (2, 3) of that exact configuration, finished 2026-10-17, with every-10th-checkpoint evals running overnight into `results\adapter\grid-<run>.txt`.
- **A new null control** (random tie-breaking) now defines what counts as an adapter effect. It also gives the list-order ablation number: about +3 wins for 5242 on validation.

**Next:** apply the pre-declared selection rule (section 6) to the three seeds, then decide how to report the adapter.

## Instructions for the next assistant session (additions)

- **Never compare an adapter checkpoint with the base bot's validation score alone.** The base (SBGP 5242) was *selected* as the best of 10 seeds on these validation maps, so its 34W is inflated by selection. Any perturbation regresses toward the mean. Compare with the random-tie null distribution (section 5) instead.
- **Use the pre-declared selection rule in section 6** for any adapter run, including the GP + adapter teammates' runs. Do not pick single peak checkpoints.
- Validation outcomes are chaotic: changing about 2% of decisions moves the result by about ±3 wins out of 48. Treat differences under about 4 wins as noise.

## 1. Overnight one-hot runs (2026-10-14), evaluated

Base = SBGP 5242 pruned. Validation maps, against WorkerRush + WR++ + WorkerDefense (48 games). **Base reference: 34W-4T-10L.**

| Checkpoint | 100 | 200 | 300 | 400 | 500 |
|---|---|---|---|---|---|
| one-hot τ 0.2 (seed 1) | 24W | 24W | 27W | **38W** | 27W |
| one-hot τ 0.1 (seed 1) | 26W | 30W | 21W | 21W | 26W |

Neighbours of τ 0.2 / 400: 380 = 21W, 390 = 36W, 400 = 38W, 410 = 30W, 420 = 36W.

Findings:
- **Most checkpoints score below the base.** Exploration did happen (argmax differs from base on 0.1–1.6% of decisions), but most deviations hurt.
- **Wins track game length.** The good checkpoints (390, 400, 420) play about 10–14k decisions against 21–37k for the others, with 0–1 draws instead of 4–7. They win by breaking stalemates, mostly against WorkerDefense.
- **Every deviation in the one-hot runs was off-tie.** The adapter overrode deliberate GP choices, never list-order ones.
- **Training metrics at iteration 500:** KL about 0.0003–0.001, clipfrac below 1.5%, entropy near 0. PPO takes small steps and does not converge.

## 2. Tie-aware base distribution (new, `--base-dist ties`)

**Idea:** give logit 1/τ to every action in the GP's top-score *tie set*, plus a small bonus β on the actual pick. With zero adapter the argmax is still exactly the base bot, so exploration happens only where the GP was indifferent and engine list order decided. The paper allows a base *distribution*, so this stays within the method.

**Tie set definition** (in `rl.AdapterEnv`): the base pick plus every *later* legal action with exactly the pick's score.
- Equal-score actions *before* the pick are excluded, because `propose()` walks ties in list order, so those were tried and found unaffordable.
- After an affordability fallback, the same rule applies relative to the pick's score.
- After a NONE fallback, the tie set is {base code}.

**Logits:** pick 1/τ + β, other tie actions 1/τ, everything else 0. Default β = 1, so a 2-way tie samples the pick 73% of the time. In ties mode τ only controls how hard deliberate GP choices are protected.

**Verified:**
- `rl.EnvTest`: still 0 mismatches.
- `tie_check.py`: invariants OK (the base code is always in the tie set, and the tie set is always within the legal codes).
- Ties-mode zero-adapter eval reproduces the base exactly: WR 10W 2T 4L, same grid, 0.00% differences.
- Smoke training runs with off-tie at 0.000% in early iterations.

**Headroom** (`tie_check.py`, 32 training games against WorkerRush and PassiveAI):

| Base action | Decisions | Tied | Mean tie size |
|---|---|---|---|
| NONE | 15,117 (77%) | 0% | 1.00 |
| MOVE | 3,527 | 0% | 1.00 |
| ATTACK | 798 | 37.5% | 1.38 |
| PRODUCE | 145 | 100% | 3.84 |
| HARVEST | 4 | 100% | 2.00 |
| **All** | 19,591 | **2.3%** | 1.04 |

Findings:
- **5242's movement is entirely deliberate** (0% MOVE ties): the major-axis terminal breaks every symmetry.
- **The tie-set headroom is only attack-target choice and production placement.**
- **77% of all "decisions" are a broke base waiting one cycle** (none-duration 1). These dilute every per-decision percentage we report, override rates included. Report rates per action type where it matters.

## 3. Tie-aware runs (500 iterations, seed 1)

| Checkpoint | 50 | 100 | 150 | 200 | 250 | 300 | 350 | 400 | 450 | 500 |
|---|---|---|---|---|---|---|---|---|---|---|
| ties τ 0.1 | 27 | 30 | 30 | 33 | 30 | 33 | 21 | 24 | 21 | 21 |
| ties τ 0.2 | 29 | 32 | 24 | 29 | 29 | 26 | 24 | 24 | 21 | 33 |

(Wins out of 48.)

Findings:
- **No checkpoint beats the base.** Variance between checkpoints stayed large.
- **Changing only tied decisions already changes outcomes.** τ 0.1 checkpoints 50 and 100 had 0.00% off-tie deviations yet lost 4–7 wins against the base.
- **The adapter pushes past the GP even at τ 0.1.** Training off-tie rate was 0.44–0.69%, about 25× what a zero adapter would sample (≈ e⁻¹⁰ per alternative), so it learned adjustments of about +10 logits on some off-tie actions.
- **Overfitting:** after iteration 300, τ 0.1 validation fell to 21W with 9 draws and long games, while training win rates against WR++ and WD rose to 52–58%.

## 4. Training win rates (last 3 iterations; biased toward short games, comparable across runs only)

| Run | WR | WR++ | WD |
|---|---|---|---|
| one-hot τ 0.2 | 30–34 | 40–44 | 30–32 |
| one-hot τ 0.1 | 48–52 | 40–42 | 44–50 |
| ties τ 0.1 | 44–50 | 52–54 | 56–58 |
| ties τ 0.2 | 42–46 | 52–54 | 58–60 |

Ties mode looked best in training and was no better on validation. **Training win rates do not predict validation for the adapter**, the same lesson as GP training Hits.

## 5. Null control and list-order ablation (`adapter\random_ties.py`)

The base bot with ties broken **uniformly at random** instead of by list order, 10 RNG seeds, on the same 48 validation games. About 2% of decisions change.

| Seed | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| Wins | 28 | 34 | 32 | 28 | 33 | 28 | 28 | 33 | 34 | 29 |

**Mean 30.7, sd 2.7, min 28, median 30.5, max 34.**

Findings for the write-up:
1. **List-order ablation:** list order is worth about **+3 wins** (34 vs 30.7, about 1.2 sd) for SBGP 5242 on validation. The base sits at the top of its own null. Part of this is likely selection luck, since 5242 was chosen on these maps.
2. **Noise floor:** perturbing about 2% of decisions moves outcomes by ±2.7 wins. Adapter checkpoints in 28–34 are indistinguishable from harmless perturbation; below 28 is harmful; above 34 is a candidate signal.
3. **Re-reading the adapter runs against the null:**
   - Ties mode is a null result: early checkpoints sit inside the null, late ones fall below it (overfitting).
   - One-hot τ 0.1 never rises above the null.
   - One-hot τ 0.2 seed 1 at 390 / 400 / 420 (36 / 38 / 36) is the only result above the null maximum. That region was found by searching checkpoints, so it is itself selected and needs reproduction.
   - Strictly, the fair null for one-hot (off-tie deviations) would be random *off-tie* overrides at a matched rate. This null perturbs ties only. Note it as a limitation, or build the matched null if the one-hot signal reproduces.

## 6. Pre-declared selection rule (fixed 2026-10-17, before seeing seeds 2 and 3)

- Evaluate **every 10th checkpoint** on the validation maps against WR + WR++ + WD (48 games).
- A run **improves on the base** only if its **best 3-consecutive-checkpoint window averages above 34W** (the null maximum).
- The reported checkpoint is the **centre of that window**.
- Apply the same rule to every adapter run, including the GP + adapter teammates' runs, for a clean 2×2.

## 7. Reproduction test (in progress)

- **Training:** one-hot τ 0.2, seeds 2 and 3, 500 iterations, otherwise identical to seed 1. Done 2026-10-17. Output: `results\adapter\onehot-tau0.2-s{2,3}-500\`.
- **Evaluation:** every 10th checkpoint for seeds 2, 3 and 1, running overnight 2026-10-17. Output: `results\adapter\grid-<run>.txt`. Re-evaluating seed 1's 380–420 also confirms the evals are deterministic.
- **Summary command:**

```powershell
$runs = "onehot-tau0.2-s2-500","onehot-tau0.2-s3-500","tau0.2-500"; foreach ($r in $runs) { $w = @(Get-Content "results\adapter\grid-$r.txt" | Where-Object { $_ -match '^TOTAL (\d+)W' } | ForEach-Object { [int]($_ -replace '^TOTAL (\d+)W.*','$1') }); $best = 0; $at = 0; for ($k = 1; $k -lt $w.Count - 1; $k++) { $m = ($w[$k-1] + $w[$k] + $w[$k+1]) / 3; if ($m -gt $best) { $best = $m; $at = ($k + 1) * 10 } }; "$r : $($w.Count) ckpts, best 3-window mean {0:N1} centred at iter $at, max single $(($w | Measure-Object -Maximum).Maximum), run mean {1:N1}" -f $best, ($w | Measure-Object -Average).Average }
```

- **How to read it:**
  - **Windows above 34 in seeds 2 and 3 as well:** the one-hot adapter genuinely helps for a while before overfitting. Report the result with early stopping by validation, then run the full panel on the selected checkpoint.
  - **No such windows:** seed 1's region was luck. The conclusion is that Adapter-RL gives no reliable gain on this base, demonstrated against a proper null. This is fully reportable.

## 8. Code added or changed

| File | New / replaced | What |
|---|---|---|
| `src\rl\AdapterEnv.java` | replaced | `tieMask()` (tie set per cell), tie diagnostics in `diagnostics()`; `step()` unchanged |
| `adapter\ppo_adapter.py` | replaced | `--base-dist {onehot,ties}`, `--pick-bonus`, `base_logits()`; new log columns `off_tie` and `tied`; one-hot mode algorithmically identical to before |
| `adapter\eval_adapter.py` | replaced | reads `base_dist`/`pick_bonus` from the checkpoint (old checkpoints → onehot); reports off-tie and tied rates |
| `adapter\tie_check.py` | new | tie-set invariants plus headroom per action type |
| `adapter\random_ties.py` | new | null control / list-order ablation, random tie-breaking over N seeds |

Backups of the replaced files: `*.bak-20261016`. Delete them once committed.

**Run folders:**
- `results\adapter\tau0.2-500`, `tau0.1-500` (one-hot, seed 1)
- `ties-tau0.1-500`, `ties-tau0.2-500`
- `onehot-tau0.2-s2-500`, `onehot-tau0.2-s3-500`

Eval CSVs sit beside each checkpoint folder.

**Test commands:**
- `java -cp "out;lib/*;lib/bots/*" rl.EnvTest`
- `python adapter\tie_check.py`
- `python adapter\eval_adapter.py --base-dist ties --opponents WorkerRush` (must give 10W 2T 4L, 0.00%)
- `python adapter\random_ties.py`

## 9. Write-up notes from this session

- **Departure:** a tie-aware base distribution as a variant of the paper's one-hot (Eq. 3), reported as a clean comparison. Result: null.
- **Method:** a null control by random tie-breaking, and the selection-bias argument for why the selected base's validation score is not a fair reference.
- **Finding:** the adapter overfits the training maps in every configuration. This is consistent with the paper's own warning that RL generalises poorly across microRTS maps, and with our departure of evaluating on held-out maps rather than training one adapter per map.
- **Finding:** list order contributes about +3 wins of 5242's 34 on validation, the list-order ablation (backlog item 4) for the SBGP bot. Run `random_ties.py --base <8242 pruned .ind>` for the ordinary-GP bot too.

## 10. Next steps

1. **Read the reproduction summary** (section 7) and apply the rule.
2. **If the signal reproduces:**
   - run the full panel on the selected checkpoint(s);
   - consider the matched off-tie null;
   - consider early stopping or a lower learning rate to fight overfitting.
3. **If it does not:** freeze the adapter conclusion as "no reliable gain on this base" with the null evidence. Spend the remaining effort on the final test-map evaluation and the write-up.
4. **Team alignment, required for the 2×2:**
   - share the null-control method and the selection rule with the GP + adapter teammates;
   - agree on τ, base distribution, budget, opponent weighting and seeds.
5. **Still open from before:**
   - list-order ablation for ordinary 8242;
   - Java-side adapter forward pass (needed only if an adapter is kept);
   - PPO baseline;
   - TMA availability;
   - unit-table version;
   - BenchmarkStatistics `parent.*` fix;
   - ERC round-trip;
   - `src/gp/nodes/` move;
   - delete `src/gp/symreg/`.
6. **Commit:** the five files above, `adapter\tie_check.py`, `adapter\random_ties.py`, the `grid-*.txt` files and eval CSVs. Keep `.pt` checkpoints out of git except selected ones.

---

---

# Session update 2026-10-18: adapter result explained and frozen; deadline plan

## Current status (supersedes earlier "Current status" paragraphs)

Ordinary GP and SBGP are complete (10 seeds each; no significant difference). **The SBGP + adapter result is frozen.**

- **Selected checkpoint:** `results\adapter\onehot-tau0.2-s3-500\adapter-0270.pt` (one-hot, τ 0.2, training seed 3, iteration 270), chosen by the pre-declared rule.
- **Validation result:** 42W 1T 5L against the three worker bots (base 34W 4T 10L). Full panel: WR, WR++ and WD each 88% (base 63 / 75 / 75%); the other 15 bots 100%, except RandomBiasedAI 96% (3 draws).
- **It is a learned effect:** the matched off-tie null never exceeds 34.
- **What it learned:** one opening decision, the placement of the first worker at cycle 0.
- **It is unreliable:** 1 of 3 adapter seeds, transient, selected on validation.

**Next:** the deadline plan (section 1), starting with the unit-table check and the test-map evaluation mode.

## Instructions for the next assistant session (additions)

- **There is a deadline.** The target is to submit by the **end of October 2026** (exact date to be confirmed with Prof. Pillay; asked by email 2026-10-18). **All experimental numbers freeze on 2026-10-21.** After that, no new experiments unless Prof. Pillay requires the PPO baseline. Prioritise accuracy and efficiency, and do not open new lines of investigation.
- **Heinrich's working pattern:** focused work in the evenings only; daytime is low-focus. Plan focused tasks (analysis, decisions, reviewing drafts) for evenings, and machine runs, collecting tables, figures, references and proofreading for the daytime.
- **The assistant drafts the report prose** section by section from this file; Heinrich reviews and corrects.
- Still unknown: whether the report is individual or shared with the team. Ask before drafting.

## 1. Deadline and plan

| Date | Evening (focus) | Daytime (low focus / machine) |
|---|---|---|
| Sun 18 Oct | Adapter null + breakdown (**done**); email Prof. Pillay (**sent**) | — |
| Mon 19 | Team message (selection rule, nulls, placement finding); unit-table version check | 8242 list-order ablation (`random_ties.py --base <8242 pruned>`) |
| Tue 20 | Test-map mode for Bench / eval_adapter (assistant writes, Heinrich verifies) | Final test evaluation runs |
| **Wed 21** | Read test results; **freeze all numbers** | Collect tables and grids into one results file |
| Thu 22 – Sun 25 | Review drafts: background, method, GP vs SBGP, adapter | Figures, references, formatting |
| Mon 26 – Wed 28 | Results discussion, intro, conclusion, abstract | Proofread earlier sections |
| Thu 29 – Fri 31 | Revision and buffer | Final formatting, submit |

**Agreed cuts:**
- **PPO baseline:** dropped unless Prof. Pillay requires it. Proposed instead: Coac and mayari as the state-of-the-art comparison, with PPO discussed from the literature.
- **Java adapter forward pass:** dropped. Evaluate the adapter through the Python bridge and report measured inference time against the 100 ms budget.
- **Team 2×2:** best effort. If the GP + adapter design isn't aligned in time, report it with the differences stated.

**Final test evaluation (run once, on the TEST maps):**
- Bots: ordinary 8242 (pruned), SBGP 5242 (pruned), SBGP + adapter s3/270, and the teammates' GP + adapter if available.
- Opponents: the full scripted panel plus Coac and mayari (plus TMA, if Prof. Pillay confirms it is TiamatBot).

**Open questions sent to Prof. Pillay:**
1. Is Coac + mayari acceptable as the state-of-the-art comparison without PPO?
2. Is TMA the TiamatBot jar in `lib/bots/`?
3. The exact submission date, and whether she wants a draft first.

## 2. Reproduction test result (one-hot τ 0.2, 3 training seeds)

The pre-declared rule: the best 3-consecutive-checkpoint window (every 10th checkpoint, 48 validation games against WR + WR++ + WD) must average above 34W.

| Run | Best 3-window | Centre | Max single | Run mean |
|---|---|---|---|---|
| seed 1 (`tau0.2-500`) | 34.7 | 400 | 39 | 28.4 |
| seed 2 | 33.7 | 430 | 37 | 26.8 |
| **seed 3** | **40.7** | **270** | 42 | 31.6 |

- **Evaluation is deterministic:** seed 1's 380–420 reproduced 21 / 36 / 38 / 30 / 36 exactly.
- **Reading:** by the letter of the rule, 2 of 3 seeds pass. But the threshold (the null maximum over 10 single evaluations) is lenient for a maximum over about 48 windows: chance alone plausibly gives 33–35. So the result is **one clear signal (seed 3), one marginal (seed 1) and one miss (seed 2)**.
- **Seed 3's window:** 240–300 scored 33, 35, 38, **42, 42**, 33, 27. The good checkpoints play shorter games (about 14k decisions against 22–40k) with fewer draws.
- **All run means sit at or below the null mean.** Most of training is neutral to harmful.

## 3. Matched off-tie null (`random_ties.py --mode offtie`)

At each decision with an off-tie legal option, with probability `rate`, a uniformly random off-tie legal action replaces the base pick. 5 RNG seeds per rate, the same 48 validation games.

| Rate | Realised off-tie change | Wins: mean | sd | min | max |
|---|---|---|---|---|---|
| 0.005 | 0.14% | 32.0 | 1.6 | 30 | 34 |
| 0.01 | 0.25% | 32.2 | 1.8 | 30 | 34 |
| 0.02 | 0.54% | 31.0 | 3.0 | 27 | 34 |
| 0.04 | 1.10% | 30.2 | 2.4 | 27 | 33 |
| 0.08 | 1.82% | 25.8 | 2.5 | 22 | 29 |

The rate brackets matching checkpoints 270 / 280 (0.23% / 0.63% off-tie) give a mean of 31–32 and a maximum of 34. **The adapter's 42 is about 4 sd above the matched null.**

Findings:
- Random off-tie perturbation never helps; it hurts more as the rate rises.
- Seed 3's effect is learned. Seeds 1 and 2 are consistent with the null.

Regression check: ties mode is unchanged (seeds 1 and 2 still give 28W and 34W).

## 4. What the adapter learned (`eval_adapter.py --breakdown --log-overrides`, `first_wait.py`)

**Overrides of checkpoint 270:** about one per game. Every one at t = 0 is the base's cycle-0 `NONE` becoming **`PRODUCE:Worker/left`**. The only other overrides are two at t = 100: one change of produce direction, one NONE→MOVE. Checkpoint 280 adds about 56 NONE→MOVE overrides without changing a single result.

**Why there is a cycle-0 NONE:** in base tree 1, `if> gameTimeK aProducesCombat ...` is false at cycle 0 (0 > 0), so waiting is the only action scoring above zero. From cycle 1 on, every action ties and list order trains workers (see the 2026-10-14 notes, section 2).

**Games flipped against the base:** 8x8B s1 L→W, 8x8G s0 L→W, 8x8K s1 T→W, 16x16 s1 L→W, 16x16E s1 T→L. They are identical against WR, WR++ and WD, which play the same opening.

**Rule ablations** (`first_wait.py`, the 48 validation games):

| Rule at cycle 0 | Wins |
|---|---|
| Base (wait) | 34 |
| Produce first-legal direction (≈ up), both sides | 21 |
| … side 0 only / side 1 only | 25 / 30 |
| Produce up / right / down / left, both sides | 21 / 24 / 18 / 33 |
| **Adapter s3/270** | **42** |

**The adapter's choice is state-dependent and nearly optimal:**
- Producing left is legal for the side-0 base on most maps, yet the adapter applied it on side 0 only on 8x8B and 8x8G.
- Per game against WorkerRush, it picked the winning option of {wait, left} on **15 of 16 games**, on maps it never trained on. Its only miss is 16x16E side 1.
- Its overrides fit a simple post-hoc pattern: as player 1, always left; as player 0, left only on 8x8 maps. **Do not present this as a method;** it is fitted to validation outcomes.

**Findings for the write-up:**
1. **The adapter learned one opening decision:** the state-dependent placement of the first worker. The GP tree had left this decision to a one-cycle wait followed by engine list order.
2. **Extreme sensitivity:** against deterministic worker bots, one opening decision moves the result between 18 and 42 of 48. This also accounts for the large GP seed variance and the ±3 noise floor.
3. **Limits:** 1 of 3 adapter seeds; transient (overfits within about 20 iterations); selected on validation, so the unbiased estimate comes from the test maps.
4. **For the 2×2:** ordinary 8242 has no cycle-0 wait, but its base tree scores a constant, so its first production direction is also decided by list order (a tie). The placement lever exists for GP + adapter as well; only the wait doesn't.

## 5. Code added or changed

| File | New / replaced | What |
|---|---|---|
| `adapter\random_ties.py` | replaced | `--mode ties` (unchanged, reproduces earlier numbers) / `--mode offtie` (matched null), `--rate`, `--rates` sweep; reports realised change and eligible share |
| `adapter\eval_adapter.py` | replaced, then patched | `--breakdown` (per-side override counts in the grid; transition table base type → adapter type); `--log-overrides` (every override with map, side, cycle and decoded code); helpers `ctype()` and `cname()`; unchanged without the flags (base-only regression: 34W 4T 10L, identical grids) |
| `adapter\first_wait.py` | new | Rule ablation of the cycle-0 wait: `--sides both/0/1/none`, `--dir first/up/right/down/left` |

Backups: `adapter\random_ties.py.bak-20261018` and `adapter\eval_adapter.py.bak-20261018`. Delete them once committed.

**Result files:**
- `results\adapter\null-offtie-sweep.txt`
- `results\adapter\fullpanel-s3-0270.txt`
- `results\adapter\breakdown-s3-0270.txt`, `results\adapter\breakdown-s3-0280.txt`
- `results\adapter\grid-*.txt` (three runs)
- eval CSVs beside the checkpoints

## 6. Next steps (in deadline order)

1. **Team message:** the selection rule, both nulls, the placement finding, and a request for their GP + adapter design and status.
2. **Unit-table version check:** which `UnitTypeTable` version the 2025 benchmark used. If it was VERSION_ORIGINAL_FINETUNED, the Coac/mayari comparison must use it.
3. **8242 list-order ablation:** `python adapter\random_ties.py --base <8242 pruned .ind>`.
4. **Test-map mode:** an explicit, deliberately awkward flag in Bench and eval_adapter, run once per bot.
5. **Freeze on 2026-10-21,** then drafting.
6. **Commit** today's files and results; keep `.pt` checkpoints out of git except `adapter-0270.pt` (the selected one).

## Session update 2026-10-19: team protocol adopted; configs built; first team run started

**Team protocol (agreed by team; research freeze 2026-10-25):**
- Training: pop 1200, 200 generations, max depth 15, 2000-cycle cap; record training time.
- Evaluation: 9 maps (8x8A, basesWorkersBarracks8x8, 8x8Obstacle, 12x12, 12x12F, SixBasesWorkers12x12, melee16x16Mixed12, TwoBasesBarracks16x16, EightBasesWorkers16x16) vs 13 bots (RandomBiasedAI, WorkerRush, LightRush, HeavyRush, RangedRush, mayari, Rojo, EconomyLightRush, Droplet, Izanagi, Tiamat, ImprovedTiamat, Coac).
- Metrics: win rate, SD over population, SD per run, SD across 50 runs, training time.
- Adapter parameters and training setup are our choice. The old map split is superseded for the team comparison.
- Prof. Pillay: no PPO needed. Compare with about 3 best published results from the literature, without reimplementing them.

**Our training split** (`config\microrts-team.params`; duplicates checked by size + terrain + units):
- **TRAIN:** 8x8C, 8x8E, TwoBases8x8, FourBases8x8, melee8x8Mixed4, melee16x16Mixed8, 16x16B, 16x16D.
- **VALIDATION** (selection only): 8x8B, 8x8G, ThreeBases8x8, melee8x8Mixed6, melee8x8light4, 16x16C, 16x16E.
- **Excluded:** basesWorkers8x8 (≡ eval map 8x8A).
- All 9 eval maps are unseen. Barracks starts, obstacles and 12x12 are pure transfer.
- **Opponents:** WR, WR++, WD, LightRush, RandomBiasedAI (×2 repeats), EconomyLightRush; 112 games per individual. All competition bots held out.
- `microrts-team-sbgp.params` = the team config + the frozen SBGP settings (Dg 2).

**Timing:** generation 0 took 229 s on the laptop (8 threads), so about 8–13 h per run. 50 runs per configuration needs the CHPC cluster.

**Code changes (built, tested):**
- **`BenchmarkStatistics`:** training time (wall clock, evolution only), per-generation population stats (`gens-<stamp>.csv`: best/mean/SD fitness, mean/SD hits, mean size, elapsed), and `parent.*` lines commented out in `.params` snapshots, so SBGP snapshots load directly.
- **`Bots`:** aliases fixed. The old ones for Droplet, Izanagi, Tiamat, GRojoA3N and MixedBot pointed to non-existent classes. Added:
  - `rojo` → `ai.competition.rojobot.Rojo`
  - `improvedtiamat` → `ai.JZ.competition.tiamat.ImprovedTiamat` (in MixedBot.jar)
  - `economylightrush` → `GNS.EconomyLightRush` (in Droplet.jar)
- **New `eval.ProbeBots`:** builds bots by name and plays 300 cycles. All 13 panel bots verified.
- **CMAB classpath clash:** GRojoA3N.jar and Izanagi.jar ship different `ai.CMAB` classes. With `lib/bots/*`, Izanagi silently ran Rojo's MCTS code. `Bots` now refuses unless the classes come from the bot's own jar. **Izanagi must run in its own JVM** with `-cp "out;lib/*;lib/bots/Izanagi.jar;lib/bots/*"`.
- **New maps:** `maps\12x12\` (3 maps).

**CHPC:** registered under programme **CSCI0806** (`#PBS -P CSCI0806`). Since June 2026, access needs portal MFA, then the WireGuard VPN, then SSH keys only. The VPN setup page showed "VPN server connection issue", so the cluster is not reachable yet. If it persists, log a helpdesk ticket and ask for the cluster username and DTN access.

**Running:** team SBGP seed 4242 (`results\team-sbgp-seed4242.log`, snapshot `out-team`).

**Open:**
- Team answers: Rojo vs GuidedRojoA3N; SD definitions; meaning of "50 runs".
- Bench and adapter scripts for the new split, cycle cap and stochastic competition bots (needs `Panel.java`).
- PBS job script.
- Literature comparison papers.
- Unit-table version.

### Update 2026-10-19 (evening)

- **First team run finished:** SBGP seed 4242 (`results\team-sbgp-seed4242.log`, `best-seed4242-20261019-185206.*`).
  - Training time **2h 56m** (8 threads). Gen 0 took 216 s; later generations about 45 s once parsimony shrank the trees, so **about 3 h per run**, not the 8–13 h estimated.
  - Best fitness 0.3247 at gen 23 (first LOCAL phase). The 8 later areas never beat it; they settled around 0.36. Best-of-run is the gen-23 individual.
  - Every GLOBAL phase starts at 100% rejection with back-fill, then escapes in 1–2 gens. Same as the October pilots; expected.
  - Trees are 8–10 nodes; about 87/112 training games won in converged phases.
- **Running:** ordinary GP seed 4242 (`config\microrts-team.params`, `results\team-seed4242.log`).
- **Team removed ImprovedTiamat, Tiamat and Izanagi** from the evaluation panel. None were training opponents, so no rerun is needed.
  - **Eval panel is now 10 bots:** RandomBiasedAI, WorkerRush, LightRush, HeavyRush, RangedRush, EconomyLightRush, mayari, Rojo, Droplet, Coac.
  - With Izanagi gone there's no CMAB clash; all bots run in one JVM with `lib/bots/*`. The guard stays in `Bots.java`.
  - Still open: **plain `Rojo` or `GuidedRojoA3N`?**
- **CHPC:** the VPN page failure was cluster maintenance. Next: WireGuard profile, upload SSH key, find cluster username, `ssh`, module checks. Then write the PBS job and setup scripts (one `qsub` per seed).
- **Budget:** at about 3 h per run, the laptop can do about 6–7 runs per day; 50 runs per configuration still needs the cluster.

# Session update 2026-10-21: team evaluation harness built; CHPC access set up (blocked on group sync)

## Current status (supersedes earlier "Current status" paragraphs)

Team protocol in force (research freeze **Sun 25 Oct 2026**).
- **Laptop:** evolving seeds 5242, 6242, 7242 for both methods (`team` then `team-sbgp` per seed), started 2026-10-21 ~13:35 from the `out-team` snapshot. Expected done Thu 22 Oct morning. Logs `results\team-seed<B>.log` / `results\team-sbgp-seed<B>.log`. Seed 4242 done for both methods (19 Oct).
- **Evaluation harness `gp.TeamEval`:** built and verified. 9 eval maps × 10-bot panel, both sides, 2000-cycle cap, per-game CSV, resumable.
- **CHPC:** login works; everything is unpacked and ready. **Blocked:** the portal lists hniebuhr as a CSCI0806 member, but the cluster group isn't synced (`groups` shows only `hniebuhr`; qsub says "not a member of project CSCI0806"). Helpdesk ticket logged (due 22 Oct); Prof. Pillay emailed.
- **Next:** when `groups` shows the programme, submit the check job, then the two arrays (section 3).

## Instructions for the next assistant session (additions)

- **Three terminals:** laptop PowerShell (repo root), a second laptop PowerShell for monitoring, and the **ssh window** (`ssh lengau`, prompt `[hniebuhr@login2 ...]$`). Always say which terminal a command goes in.
- `ssh lengau` is an alias in `~\.ssh\config` pointing at **10.128.15.127**, because `ssh` can't resolve the hostname on this laptop even though `nslookup` can. WireGuard must be **Active**; only one VPN connection per user is allowed.
- Cluster commands must be single lines where possible. Files go laptop → cluster with `scp <file> lengau:/mnt/lustre/users/hniebuhr/`.
- **Windows `tar` archives mark folders read-only.** Always extract on the cluster with `tar xzf X -C dir --delay-directory-restore --warning=no-unknown-keyword && chmod -R u+w dir`.
- Job scripts written on Windows need LF line endings (`.Replace("`r`n","`n")`).

## 1. gp.TeamEval (new file `src\gp\TeamEval.java`)

- **Usage:** `java -cp "out;lib/*;lib/bots/*" gp.TeamEval -method <name> -inds a.ind,b.ind|'@list.txt' -csv results/teameval-<name>.csv [-threads N] [-reps N] [-maxcycles 2000] [-maps ...] [-opponents ...] [-deterministic a,b|none] [-utt original|finetuned] [-dry]`
- **Defaults:**
  - Maps: 8x8A, basesWorkersBarracks8x8, 8x8Obstacle, 12x12, 12x12F, SixBasesWorkers12x12, melee16x16Mixed12, TwoBasesBarracks16x16, EightBasesWorkers16x16. Names are resolved by searching `maps/`, and all 9 resolve.
  - Panel: RandomBiasedAI, WorkerRush, LightRush, HeavyRush, RangedRush, EconomyLightRush, mayari, Rojo, Droplet, Coac.
  - 2000 cycles, 5 reps for stochastic opponents.
- **CSV columns:** method, run, seed, opponent, map, side, rep, result, cycles, worst_ms, game_s, error. Rerunning with the same `-csv` resumes; error rows are retried.
- **Summary:** per run, the *macro* win rate (each opponent weighted equally; draws are non-wins). Across runs: mean, sd, median, min and max, plus a per-opponent table and `-summary.csv`.
- **Startup checks:** every opponent must construct (otherwise nothing runs); deterministic opponents are checked by replaying one game twice.
- **Fixed during the session:** the `-deterministic` parser skipped its value (so the next option was read as unknown); a `game_s` column was added.

## 2. Determinism and cost (measured 2026-10-21)

**Deterministic:** only WorkerRush, LightRush, HeavyRush and RangedRush, confirmed by replay.

**Stochastic:** RandomBiasedAI, EconomyLightRush, mayari, Droplet, GRojoA3N, and **also Coac and Rojo**. Coac and Rojo varied on 5–6 of 18 (map, side) pairs over 3 reps, all on the multi-base 12x12 and 16x16 maps. The variation is identical with `-threads 1`, so it's genuine randomness, not thread interference. The defaults in TeamEval reflect this.

**Statistics note:** the 4 engine rushes give only 18 distinct games per run (9 maps × 2 sides). Rows for stochastic opponents are independent samples.

**Cost per game** (measured next to a running evolution, so upper bounds):

| Opponent | Mean time per game |
|---|---|
| Droplet | 7.5 s (max 13.7 s) |
| mayari | 0.4 s |
| Rojo | 0.2 s |
| everything else | ≤ 0.1 s |

At 5 reps that's about 612 games and ~13 min per run on one thread, so 100 runs is an overnight job.

**Rojo** costs ~0.3 ms per cycle, so it isn't doing time-budgeted search: probably scripted. The search bot is likely **GRojoA3N** (alias `grojoa3n`; it constructs fine with `lib/bots/*`). Which one the protocol means is still open with the team.

**Final evaluation rule:** one JVM per run, each `-threads 1`, several processes in parallel, each with its own CSV, then merge. This avoids static-state sharing between competition bots and gives time-budgeted bots (Droplet) a full core. **Never run the evaluation while evolution is running** on the same machine.

**Discipline:** the timing and determinism runs played on the eval maps. Their win rates were not used for anything, and the CSVs were deleted.

## 3. CHPC (Lengau)

- **Account and paths:**
  - Username hniebuhr; programme CSCI0806.
  - Login node login2.
  - Scratch `/mnt/lustre/users/hniebuhr/microrts/` (contains `out-team/`, `lib/`, `maps/`, `config/`, `run_seed.pbs`).
- **Java:** no JDK 21 module; we use `chpc/java/24.0.1`, which runs our Java-21 classes. **Methods note:** laptop JDK 21.0.9, cluster 24.0.1. Runs include RandomBiasedAI, so they're reproducible only in distribution either way.
- **Queues:**
  - `serial`: ≤ 24 cpus, 48 h walltime, max 10 running jobs per user, 240 cpus.
  - `smp`: full 24-core node, 96 h, max 5 per user.
  - Nodes have 24 cores.
- **`run_seed.pbs`:** serial queue, `select=1:ncpus=8`, walltime 12 h, `-Xmx8g`. Seed base B = idx × 1000 + 242, with `seed.0–7 = B..B+7`. Writes `results/<METHOD>-seed<B>.{log,stat}` plus BenchmarkStatistics' best-* files. Skips runs that already finished. Supports `CHECK=1` (2 generations, pop 50, prefix `check-`). PBS logs go to `pbs-logs/`.
- **Commands** (ssh window, in `/mnt/lustre/users/hniebuhr/microrts`):
  - check job: `qsub -v METHOD=team-sbgp,SEEDIDX=99,CHECK=1 -l walltime=00:30:00 run_seed.pbs`
  - real runs: `qsub -v METHOD=team -J 8-53 run_seed.pbs` and `qsub -v METHOD=team-sbgp -J 8-53 run_seed.pbs`
  - seeds 8242–53242 give 46 per method, which with the laptop's 4242–7242 makes **50 per method**.
- **Throughput:** 10 concurrent jobs means 92 runs take about 2 days if each run is ~4–5 h on Haswell (not yet measured). `smp` (5 nodes × 3 seeds) is an optional booster.
- **Training time is not comparable across machines** (laptop vs Haswell). Report it per platform. `team-seed5242`'s laptop training time is inflated by the test runs that shared its CPU (generations 0–~15).

## 4. Open items

- **CHPC group sync:** helpdesk ticket and email to Prof. Pillay.
- **Team:** Rojo vs GRojoA3N; SD definitions; meaning of "50 runs"; fallback n if CHPC isn't ready by Sunday.
- **Adapter on the team protocol:** `eval_adapter.py` still uses the old validation maps and panel.
- **Literature comparison:** about 3 papers.
- **Housekeeping:**
  - delete the stray `lib\bots\EvolvedBot.java` on the laptop (already removed on the cluster);
  - delete `microrts-cluster.tgz` after confirming the cluster copy;
  - commit `src\gp\TeamEval.java` and `run_seed.pbs`.



  # Session update 2026-10-21 (evening) / 2026-10-22: CHPC working; 40 cluster runs submitted; unit-table answer found

## Current status (supersedes earlier "Current status" paragraphs)

Team protocol in force (research freeze **Sun 25 Oct 2026**). Evolution now runs on two platforms:
- **CHPC Lengau:** working since 2026-10-22 16:45. Two arrays submitted, seeds 21242–40242 for both methods (40 runs, 10 running at a time). Expected to finish around Friday midday.
- **Laptop:** two loops running at once (see section 3). Seeds 4242, 5242, 6242 and 8242 are complete for both methods; 7242 and 9242 are in progress. Queue continues through seed 20242.
- **Expected n by the freeze:** about 30 per method (about 10–12 laptop + 20 cluster).
- **Next:** monitor; copy cluster results to the laptop as they finish; build the evaluation run plan for Sunday (no evaluation while evolution runs on the same machine).

## Instructions for the next assistant session (additions)

- **ssh does not need to stay open** for cluster jobs; PBS batch jobs run independently. Laptop PowerShell windows running loops **must** stay open, with the laptop awake on AC.
- Cluster commands are given with their own `cd /mnt/lustre/users/hniebuhr/microrts && ...` so they work from any folder.
- Heinrich has said he finds setbacks discouraging ("this always happens to me"). When something breaks, separate clearly what is his responsibility from what isn't, and point out what still stands.

## 1. CHPC: what went wrong and how it was fixed

- **Symptom:** qsub rejected jobs with "not a member of project CSCI0806", although the portal listed hniebuhr as an approved member (portal status "Candidate user", same as all other users per Prof. Pillay).
- **Diagnosis:** `getent group CSCI0806` returned nothing for either spelling, while other programme groups (CSCI1007, CSCI1059, CSCI1142, CSCI1344) were visible; 7,355 groups total. The group was missing on the cluster for every member, not just this account.
- **Resolution:** CHPC (Kevin Colville, ticket network-53029) reported a technical issue with PBSPro on their side. After it was fixed, `qsub -I -q serial -P CSCI0806` was accepted (job 7564845, cancelled). `qstat -Bf` now lists `p:CSCI0806`.
- **Our job script was correct throughout:** checked against the CHPC Quick Start Guide (`#PBS -P <shortname>`, serial queue, walltime and core limits).
- **Reply to Kevin** (confirming success) and the one-liner to Prof. Pillay: send if not yet done.

## 2. CHPC setup as used

**Live limits** (`qstat -Qf serial`, `qstat -Bf`; these differ from the wiki table):

| Limit (per user) | Value |
|---|---|
| `max_queued` (serial) | 40 subjobs, including array subjobs |
| `max_run` (serial) | 10 |
| `max_run_res.ncpus` | 240 |
| `resources_max.ncpus` | 24 per job |
| walltime | 48 h |
| server | no extra per-user queued limit; `max_array_size` 10000 |

The first attempt to submit 2 × 20 appeared to fail ("would exceed queue generic's per-user limit"), but both arrays had actually been accepted. The error came from a later attempt that would have exceeded 40. **Always check `qstat -u hniebuhr` before resubmitting.**

**`run_seed.pbs` header** (patched 2026-10-22: `mem=12gb` added, because the JVM uses `-Xmx8g` and serial jobs can share nodes):
```
#PBS -P CSCI0806
#PBS -q serial
#PBS -l select=1:ncpus=8:mem=12gb
#PBS -l walltime=12:00:00
#PBS -N mrts
#PBS -j oe
#PBS -o /mnt/lustre/users/hniebuhr/microrts/pbs-logs/
```

**Check job:** `CHECK=1`, seed index 99 → seed 99242. Ran on cnode0012, exit 0 in 30 s, all output files written. Its `best-seed99242-*` files are throwaway.

**Submitted:**

| Array | Method | Seeds |
|---|---|---|
| 7564916[] | `team` (ordinary GP) | 21242–40242 |
| 7564917[] | `team-sbgp` | 21242–40242 |

At submission all 10 running slots were ordinary-GP subjobs; SBGP subjobs start as slots free. Early pace: about 3 minutes per generation (generation 0 slightly faster than the laptop's 216 s). No run-time projection yet.

**Seed ranges by platform (no overlap):** laptop 4242–20242; cluster 21242–40242.

**Name collision:** `best-seed<N>-<stamp>.*` doesn't include the method, and on the cluster both methods run each seed. Nothing is overwritten (timestamps differ). To tell them apart: `grep -l SBGPBreeder results/best-seed*.params` lists the SBGP ones. `.log` and `.stat` names include the method.

**Lustre deletes files older than 90 days without warning.** Copy results to the laptop, into a separate folder (`results-chpc\`), as soon as runs finish. Per the CHPC guide, transfers should go via `scp.chpc.ac.za`, not the login node; check whether the VPN setup reaches it.

**The portal's job list** most likely shows jobs only after they finish (accounting records). Use `qstat` for live status; use the portal later for core-hours used.

**Commands** (ssh window):
```
qstat -u hniebuhr
qstat -t -u hniebuhr | grep " R " | wc -l
cd /mnt/lustre/users/hniebuhr/microrts && for m in team team-sbgp; do for i in $(seq 21 40); do b=$((i*1000+242)); f=results/$m-seed$b.log; [ -f $f ] && echo "$m $b: $(grep -q 'best fitness of run' $f && echo DONE || grep -o 'Generation [0-9]*' $f | tail -1)"; done; done
```

## 3. Laptop: two loops ran concurrently

- **Loop 1** (seeds 5, 6, 7; output to `Out-Null`, started 2026-10-21 ~13:35).
- **Loop 2**, the queued loop (seeds 8–20, alternating `team` / `team-sbgp` per seed). Meant to wait for loop 1, but started immediately (the wait condition misfired, probably because `team-sbgp-seed7242.log` didn't exist yet). It ran alongside loop 1 overnight.
- **State at 2026-10-22 08:28:** done 5242, 6242, 8242 for both methods. Running: `team` 7242 (started 06:22) and `team` 9242 (started 07:51). Loop 1 ends after `team-sbgp` 7242; from then on loop 2 runs alone.
- **Consequences:**
  - Results are valid: training opponents aren't time-budgeted, so CPU sharing changes run time, not what evolves.
  - The **training-time metric is inflated** for every run that overlapped (from about the 6242 runs through 7242/9242). Report training time from solo runs only (4242, and loop-2 runs after loop 1 ends) and mark the overlapped ones. `team-seed5242` was already inflated by test runs.
  - **To do:** record start and end per log as evidence:
```powershell
    Get-ChildItem results\team-seed*.log, results\team-sbgp-seed*.log | Sort-Object CreationTime | ForEach-Object { "{0,-26} start {1:ddd HH:mm}  end {2:ddd HH:mm}" -f $_.Name, $_.CreationTime, $_.LastWriteTime }
```
- **Training time is not comparable across platforms** (laptop vs Haswell). Report the two separately.

## 4. Observed during the runs (normal; for the write-up)

- **First GLOBAL generation of every SBGP global phase: 100% rejected, back-filled with the least-similar rejects**, with a warning suggesting lower Tg/Dg. Expected: the whole population still carries the area just frozen. Rejection then falls to about 80% and then to single digits within 1–2 generations. Only a concern if 100% persists for several consecutive generations.
- **`meanEval … CLAMPING, recalibrate`:** mild ([0.03, 0.92] vs calibrated [0.05, 1.0]); same for both methods; configs frozen. One sentence in the methods.
- **Base tie rate 100%:** the base tree scores all actions equally, so engine list order decides (trains workers), as in SBGP 5242. Some runs show a combat tie rate around 2%: a combat tree making real choices.
- A team-sbgp run reached Hits = 112/112 (all training games won).

## 5. Open questions answered (2026-10-21, from public sources)

- **Unit-table version of the 2025 competition:** the rules (2025 edition) specify **`VERSION_ORIGINAL_FINETUNED`** for the classic track, with move conflict resolution **`MOVE_CONFLICT_RESOLUTION_CANCEL_BOTH`** ("Finetuned-Both" in the GUI). We use `new UnitTypeTable()` = VERSION_ORIGINAL. The rules also use a 5000-cycle cap on 16x16 maps (4000 + 1000 anti-draw gap) and garbage-collect before each bot's turn. **Decision for the team:** evaluate under VERSION_ORIGINAL (consistent with training; state the difference as a limitation), or under Finetuned-Both for comparability with competition results. TeamEval already has `-utt original|finetuned`; whether it sets the move conflict resolution is unchecked (ask for `TeamEval.java` before changing anything). Source: sites.google.com/site/micrortsaicompetition/rules.
- **Rojo vs GuidedRojoA3N:** two different bots (Gym-µRTS lists Rojo 5th and GuidedRojoA3N 7th in 2020). MicroRTS-Py's `rojo` is `ai.competition.rojobot.Rojo` (github.com/jr9Hernandez/RojoBot), which matches our alias, so **plain Rojo is the protocol's bot**. Our timing (~0.3 ms/cycle) suggests it doesn't do time-budgeted search, although Gym-µRTS labels it MCTS-based; don't characterise it in the write-up beyond citing it.
- **Literature comparison, candidates found (still to choose about 3 and read properly):**
  - Huang et al. 2021, Gym-µRTS (PPO + invalid action masking), arXiv:2105.13807: the RL state of the art against a bot panel very close to ours.
  - Goodfriend 2024, "A Competition Winning Deep RL Agent in microRTS" (RAISocketAI, 2023 winner), arXiv:2402.08112: per-map win rates against WorkerRush, LightRush, CoacAI and Mayari.
  - Moraes et al. 2023, "Choosing Well Your Opponents" (2L, programmatic strategy synthesis), arXiv:2307.04893; and InnateCoder (Moraes et al. 2025): programmatic-policy methods, the closest relatives of GP.
  - Note: published setups differ in maps, unit table, cycle caps and time budgets. Compare as reported results, without claiming identical conditions.

## 6. Open items

- Confirm replies sent to Kevin and Prof. Pillay; tell the team about n ≈ 30 per method.
- Run-time projection for the cluster (check when runs reach generation 30+).
- Copy cluster results to the laptop (`results-chpc\`) as they finish.
- Record laptop run start and end times (section 3).
- Team decision: unit table and move conflict resolution for evaluation (section 5).
- Choose and read the about 3 literature papers.
- Evaluation plan for Sunday: TeamEval on all runs (one JVM per run, `-threads 1`, never alongside evolution); possibly run part of it on the cluster.
- Still open from before: adapter on the team protocol (`eval_adapter.py` uses old maps and panel); SD definitions and the meaning of "50 runs" (team); housekeeping (`lib\bots\EvolvedBot.java`, `microrts-cluster.tgz`, commit `TeamEval.java` and `run_seed.pbs`).

## Addendum 2026-10-23 (Friday morning)

**Progress at 08:47:**
- Cluster: all 20 `team` runs (21242–40242) DONE, all logs complete. SBGP: 8 done (21242, 22242, 23242, 25242, 26242, 27242, 29242, 30242), 10 running, 2 queued (39242, 40242). Last SBGP runs expected Friday late afternoon/evening.
- Cluster run time: about 6–8 h per run (two waves of 10 took about 16 h). Exact times: `grep -h -E "^(start|end)" pbs-logs/*.OU` once everything is done.
- Laptop: seeds 4242–11242 DONE for both methods (8 per method); queued loop continues.
- **n per method by tonight: 28 (8 laptop + 20 cluster), growing as the laptop continues.**

**Safety copy of cluster results** (laptop PowerShell, repo root):
- First copy: `scp -r lengau:/mnt/lustre/users/hniebuhr/microrts/results results-chpc`
- Final copy once all done: `scp -r lengau:/mnt/lustre/users/hniebuhr/microrts/results/* results-chpc\`

**Laptop end-of-run benchmarks (validation maps, 8 seeds per method):**
- RandomAI 100% in every run; RandomBiasedAI 96–100%; WorkerRush 21–64%.
- Ordinary GP vs WorkerRush: 29, 29, 50, 43, 29, 29, 21, 50 (mean ~35%). SBGP: 36, 29, 29, 36, 43, 57, 64, 36 (mean ~41%).
- Validation only, used for selection; not a reported result. The reported comparison comes from TeamEval on the 9 evaluation maps.
- Watch out: the per-generation "tie rate … worker 29.8%" line is NOT a win rate (worker = worker units, % = tied decisions).

**Pruning decision:**
- `gp.Pruner` is behaviour-preserving by design, so it cannot raise win rates. No post-hoc changes to bots to improve results (that would be tuning after the fact).
- Prune only the validation-selected individual of each method, for interpretation in the write-up. Optionally report raw vs pruned sizes across runs if time allows.
- **Evaluate the original, unpruned trees** in TeamEval: pruner equivalence is verified only on traced games, not on the evaluation maps.

**Next:**
- Tonight: confirm all SBGP runs finished; final scp copy; record cluster start/end times.
- Saturday: let the laptop loop run until the freeze; plan the Sunday TeamEval (all runs, one JVM per run, `-threads 1`, never alongside evolution; consider running part on the cluster).
## Addendum 2026-10-23 ~23:05: Prof. Pillay approved Python packages on the cluster

- Prof. Pillay confirmed that installing Python libraries (numpy, PyTorch, etc.) on the cluster is fine. **The blocker in section 4 is lifted;** the n = 20 adapter plan can go ahead.
- **Next steps, in order:**
  1. Finish the venv install inside an interactive job (the login node kills it):
     `qsub -I -q serial -P CSCI0806 -l select=1:ncpus=4:mem=16gb -l walltime=01:00:00`, then
     `module load chpc/compmech/python/3.11.6-gcc-12.1.0 chpc/java/24.0.1 && cd /mnt/lustre/users/hniebuhr && source venv-adapter/bin/activate && pip install --no-index --no-cache-dir --find-links wheels-linux torch numpy JPype1 && python -c "import torch, jpype, numpy; print('OK torch', torch.__version__)"`, then `exit`.
  2. Attach `adapter\ppo_adapter.py` so it can be patched for the team training maps and opponents, plus a CPU device option.
  3. Fix the training budget **before** training (fixed iteration count, e.g. 300; final checkpoint evaluated; no checkpoint selection).
  4. Copy the patched adapter scripts and the 20 team-protocol SBGP `.ind`/`.params` files into place on the cluster; one PBS job per bot (≤ 10 running at once).
  5. Evaluate all 20 hybrids on the 9 eval maps with the patched `eval_adapter.py --maps team --maxcycles 2000 --samples 5`. Report SBGP vs SBGP + adapter as a paired comparison, n = 20.

  # Session update 2026-10-24 (Saturday morning): 23 Oct work recorded; adapter eval, laptop eval and final wave submitted

## Current status (supersedes earlier "Current status" paragraphs)

Research freeze: **Sun 25 Oct 2026**. All remaining work is running on CHPC; the laptop is idle.

- **Evolution:** 31 runs per method done (laptop 4242–14242 + cluster 21242–40242). A final wave of seeds
  15242–19242 (both methods) is running on the cluster → **36 per method** if all finish (expected tonight
  or early Sunday). Seed 20242 will not be run.
- **TeamEval:** done for the 40 cluster runs (23 Oct). The 22 laptop runs were uploaded and their TeamEval
  is queued. The wave runs still need TeamEval afterwards.
- **SBGP + adapter:** all 20 trained (cluster SBGP seeds 21242–40242). Their evaluation is running.
- **Next:** paired table (base vs hybrid), then TeamEval for the wave, then re-merge all summaries, then freeze.

## Instructions for the next assistant session (additions)

- 23 Oct work was done without updating this file. The scripts on the cluster
  (`run_adapter.pbs`, `run_eval.pbs`, `run_adapter_eval.pbs`) are the source of truth for what ran.
  `cat` them before assuming anything.
- **Job 7572640 (`tm-gp-adap`) is a TEAMMATE's GP + adapter run under hniebuhr's account.** Never qdel it or
  touch its files. It counts against our 10-running-job limit until it ends (48 h walltime, started 23 Oct 22:57).

## 1. Done on 2026-10-23 (previously unrecorded)

**Adapter scripts patched:**
- `ppo_adapter.py` gained `--split team`, `--device`, `--threads` and `--classes` (or env `MRTS_CLASSES`).
- `eval_adapter.py` gained `--maps team` (the 9 eval maps), a stochastic set that matches TeamEval, and a
  MACRO win-rate line. **Its defaults are still old** (`--opponents` = FULL_PANEL, `--maxcycles 4000`), so
  always pass `--maps team --maxcycles 2000 --samples 5 --opponents <10-bot panel>`.

**Cluster venv:** `/mnt/lustre/users/hniebuhr/venv-adapter`, installed in interactive jobs. Python 3.11.6
module, torch, numpy, JPype1.

**TeamEval of the 40 cluster runs:**
- `run_eval.pbs`, job 7571574: 24 cpus, `xargs -P 24`, one JVM per run, `-threads 1`, `-Xmx2g`,
  classes `out-eval`.
- It evaluates every `results/best-seed*.params` except `*pruned*` and seed 99242, decides the method by
  grepping for SBGPBreeder, and skips runs that have a `.done` file (resumable).
- Output in `results/teameval/`: per-run csv/log/summary/done, merged `all-team*.csv`, `SUMMARY.txt`.
- **The merge step that produced all-team*.csv and SUMMARY.txt is not recorded.** Find it before re-merging.

| (n = 20, macro win rate, 9 eval maps × 10-bot panel) | Ordinary GP | SBGP |
|---|---|---|
| mean / sd | 58.2% / 6.2 | 56.6% / 3.5 |
| median / range | 57.6% / 48.4–66.9 | 56.3% / 52.2–67.3 |
| vs WorkerRush (mean) | 37.8% | 30.0% |
| vs mayari / Rojo / Droplet / Coac | 38.7 / 45.0 / 34.8 / 30.6% | 42.5 / 43.9 / 31.1 / 27.9% |

- As in October: a similar mean, and SBGP has about half the spread.
- **Worst decision time exceeded 100 ms on 4 runs:** GP 33242 (192 ms) and 35242 (115); SBGP 39242 (231)
  and 35242 (127). Probably load from 24 JVMs on one node. Report it as a worst case under parallel load;
  no investigation before the freeze.

**SBGP + adapter training:**
- `run_adapter.pbs`, array 7572690[21–40]: 12 cpus, 16 GB.
- Settings: onehot, **τ 0.2, 300 iterations (fixed budget, final checkpoint, no checkpoint selection)**,
  `--maxcycles 2000`, `--split team`, CPU with 10 threads, classes `out-eval`, `--seed` = array index,
  save every 50.
- Base = the **unpruned** SBGP best-of-run `.ind` for that seed.
- Output: `results/adapter-team/sbgp-seed<B>/`. All 20 reached `adapter-final-0300.pt`; about 2.5 h wall
  each.
- Array subjob PBS logs overwrite one file, so only the last subjob's start/end line survives. Use each
  run's `log.csv` / `train-seed<B>.log` instead.

## 2. Done on 2026-10-24

- **Laptop loop stopped** during `team` 15242 (gen 142); its log was renamed `.aborted` and nothing else
  was written for it.
  - Laptop totals: seeds 4242–14242, 11 per method.
  - Laptop run times varied from 1.5 to 8.5 h, so report training time as a distribution, per platform.
- **Check-run files to EXCLUDE:** `best-seed4242-20261019-150340.*` (2 generations, pop 40) and
  `best-seed4242-20261019-154533.*` (1 generation). The real 4242 runs are `-185206` (SBGP) and
  `-233720` (ordinary). They were not uploaded.
- **eval_adapter verified against TeamEval:** with no adapter, on SBGP 21242 vs the 4 deterministic rushes,
  W/T/L matched exactly (5/2/11, 13/0/5, 16/0/2, 14/0/4), with 0.00% overrides.
- **Decision (24 Oct ~11:15, before any hybrid result was seen):** the adapter comparison uses **n = 20**
  (the cluster SBGP seeds 21242–40242), paired (each hybrid against its own base). No more adapters will be
  trained, so there is no optional stopping.
- **Jobs submitted:**

  | Job | What | Output |
  |---|---|---|
  | 7573664 `mrts-adeval` | eval of 20 hybrids, 20 in parallel, `OMP_NUM_THREADS=1`; started 11:19 | `results/adapter-team/eval/seed<B>.{log,done}`, CSV beside each checkpoint |
  | 7573718[15–19] | `team` evolution, seeds 15242–19242 | `results/team-seed<B>.log` |
  | 7573719[15–19] | `team-sbgp` evolution, same seeds | `results/team-sbgp-seed<B>.log` |
  | 7573727 `mrts-eval` | TeamEval of the 22 laptop runs (uploaded to cluster `results/`, 44 files) | `results/teameval/` |

- **Seed ranges by platform (final):** laptop 4242–14242; cluster 15242–19242 and 21242–40242.

## 3. Commands for when you're back (ssh window)

Status:
```
ls /mnt/lustre/users/hniebuhr/microrts/results/adapter-team/eval/*.done 2>/dev/null | wc -l; qstat -t -u hniebuhr
```

Paired table, once the done count is 20:
```
cd /mnt/lustre/users/hniebuhr/microrts && for i in $(seq 21 40); do b=$((i*1000+242)); n=$(grep -o '"base": "[^"]*"' results/adapter-team/sbgp-seed$b/args.json | sed 's/.*\///;s/\.ind"//'); base=$(grep "^$n " results/teameval/SUMMARY.txt | awk '{print $3}'); hyb=$(grep -o 'MACRO win rate[^%]*' results/adapter-team/eval/seed$b.log | grep -o '[0-9.]*$'); echo "$b base $base hybrid $hyb%"; done
```

Wave progress:
```
cd /mnt/lustre/users/hniebuhr/microrts && for m in team team-sbgp; do for i in $(seq 15 19); do b=$((i*1000+242)); f=results/$m-seed$b.log; [ -f $f ] && echo "$m $b: $(grep -q 'best fitness of run' $f && echo DONE || grep -io 'generation [0-9]*' $f | tail -1)"; done; done
```

After the wave is done, TeamEval for those 10 runs (it skips everything already done):
```
cd /mnt/lustre/users/hniebuhr/microrts && qsub run_eval.pbs
```

Find the merge step for SUMMARY.txt:
```
grep -h -E "all-team|SUMMARY|summar" ~/.bash_history | tail -10
```

## 4. Open items before and at the freeze

1. Paired statistics for SBGP vs SBGP + adapter (Wilcoxon signed-rank on the 20 per-seed differences),
   plus per-opponent deltas.
2. Re-merge all TeamEval results (n = 36 per method) and rerun the GP vs SBGP statistics (Mann-Whitney U).
3. Ask the teammate for the GP + adapter settings (τ, iterations, base distribution, maps, maxcycles,
   seeds), so the 2×2 differences can be stated.
4. Copy the cluster results to the laptop (`results-chpc\`); Lustre deletes files after 90 days:
   `scp -r lengau:/mnt/lustre/users/hniebuhr/microrts/results/* results-chpc\`
5. Still open: the unit-table decision (we evaluate under VERSION_ORIGINAL; state it as a limitation),
   the literature comparison (about 3 papers), and restoring the laptop power settings.


   # Session update 2026-10-26: all our numbers frozen; teammates' runs on our cluster account

## Current status (supersedes earlier "Current status" paragraphs)

**Every number for our configurations is final.**
- **Ordinary GP vs SBGP (n = 36 each, 9 eval maps × 10-bot panel):** 57.5% vs 57.1% mean macro win rate. Mann-Whitney U = 641, p ≈ 0.94: no difference in central performance. SBGP has about half the spread (sd 3.5 vs 5.9) and a higher floor (52.0% vs 48.4%).
- **SBGP + adapter (n = 20, paired):** 56.6% → 60.3% (+3.6 points), Wilcoxon p ≈ 0.0005. Confirmed: both evaluators give the same base numbers.
- **The cluster is now running teammates' work** under our account (Milan, Shy; section 3).
- **Next:** Brown-Forsythe test for the spread difference; per-opponent deltas and decision time of the hybrid; copy results to the laptop; write the report.

## Instructions for the next assistant session (additions)

- **Which window a command goes in:** commands with `Get-ChildItem`, `$env:` or `scp lengau:` go in laptop PowerShell. Commands with `/mnt/lustre/`, `qsub` or `qstat` go in the ssh window. (Mixing these up cost a round trip today.)
- **Teammates' code and results live in their own folders** under `/mnt/lustre/users/hniebuhr/` (section 3). Never point our scripts at them, and never delete anything there until the teammate confirms receipt.
- **Account limits are shared with the teammates' jobs:** 10 running, 40 queued (array subjobs count individually). Check `qstat -u hniebuhr` before submitting anything.
- **Still unknown:** whether the report is individual or shared, and whether there is a template or page limit. Ask before drafting.

## 1. SBGP + adapter: final result

Base = unpruned SBGP best-of-run (cluster seeds 21242–40242); adapter = one-hot, τ 0.2, 300 iterations, final checkpoint; evaluated on the 9 eval maps × 10 bots, 2000 cycles, 5 reps on stochastic opponents.

| Paired, n = 20 | Base | Hybrid | Difference |
|---|---|---|---|
| Mean macro | 56.6% | 60.3% | **+3.6 points** |
| Median difference | | | +4.3 points |
| Improved / equal / worse | | | 17 / 1 / 2 |

- **Wilcoxon signed-rank:** zero difference dropped, n = 19. W⁻ = 9, W⁺ = 181 (critical 46 at α = 0.05), z ≈ −3.46, **p ≈ 0.0005**. Sign test: 17 of 19, p ≈ 0.0007.
- **The two losses:** 24242 (−2.6 points) and 29242 (−0.4 points).
- **Per-seed values:** base 57.3/67.3/52.3/63.8/56.8/55.7/54.0/57.8/55.4/52.2/56.6/56.9/56.0/57.9/57.0/55.2/56.3/56.3/53.4/54.3, hybrid 63.2/67.3/59.0/61.2/62.4/61.3/56.3/58.3/55.0/57.1/63.0/57.1/60.2/67.6/58.8/60.6/60.3/58.6/59.2/58.7 (seeds 21242 → 40242).
- **The adapters change a lot:** argmax differs from the base on **14–24% of decisions** (22242: 24.0%; 34242: 13.5%), against about 1% in October. Seed 22242's equal base and hybrid (67.3%) is therefore a coincidence, not an identity.
- **Evaluator agreement** (jobs 7584280–82): `eval_adapter.py` with no adapter, same settings, 0.00% overrides:

  | Seed | TeamEval | eval_adapter, no adapter |
  |---|---|---|
  | 21242 | 57.3% | 57.8% |
  | 22242 | 67.3% | 67.2% |
  | 34242 | 57.9% | 57.7% |

  Mean difference +0.07 points, from the stochastic opponents. Logs: `results/adapter-team/eval/base-check-<seed>.log`. The `FileNotFoundError` at the end of each is harmless: base-only mode saves its CSV to `results/adapter/`, which doesn't exist on the cluster, after the results are printed.
- **Why this differs from October** (1 of 3 adapter seeds, selected by checkpoint search): here the budget was fixed in advance, all 20 runs are reported, and the comparison is paired. The team protocol also offers many more maps and opponents to learn from.
- **For the write-up:** the hybrid is the best configuration overall. Its mean (60.3%) is above both GP methods' n = 36 means, but that cross-comparison is not paired and covers the cluster seeds only. State it as descriptive.

## 2. Ordinary GP vs SBGP: final, n = 36 each

Seeds: laptop 4242–14242, cluster 15242–19242 and 21242–40242. Summary: `results/teameval/SUMMARY-n36.txt` (the n = 20 `SUMMARY.txt` is kept).

| Macro win rate | Ordinary GP | SBGP |
|---|---|---|
| Mean / sd | 57.5% / 5.9 | 57.1% / 3.5 |
| Median | 56.8% | 56.4% |
| Range | 48.4–67.6% | 52.0–67.3% |

| Opponent (mean) | GP | SBGP |
|---|---|---|
| RandomBiasedAI | 89.4% | 90.4% |
| WorkerRush | 34.4% | 32.7% |
| LightRush | 72.1% | 71.6% |
| HeavyRush | 80.7% | 78.9% |
| RangedRush | 80.4% | 76.9% |
| EconomyLightRush | 71.7% | 71.1% |
| mayari | 38.9% | 41.3% |
| Rojo | 44.0% | 45.8% |
| Droplet | 32.6% | 33.5% |
| Coac | 30.4% | 28.5% |

- **Mann-Whitney U = 641** (U_GP = 655 against 648 expected), z ≈ 0.08, p ≈ 0.94.
- **Spread:** variance ratio ≈ 2.8 on (35, 35) degrees of freedom, past the ~2.0 critical value. Report a Brown-Forsythe test (not yet computed; do it from `all-team-summary.csv` and `all-team-sbgp-summary.csv`). SBGP's sd is also smaller against nearly every opponent.
- **Interpretation:** the October finding holds at n = 36. Same mean, but SBGP is more reliable: higher floor, half the spread.
- **Worst decision time > 100 ms** on 4 runs, the same as before: GP 33242 (192 ms) and 35242 (115); SBGP 35242 (127) and 39242 (231). All others ≤ 90 ms. Report this as the worst case under 24-JVM parallel load.
- **How the merge works:** the per-run CSVs `results/teameval/<method>-best-seed*.csv` are concatenated into `all-<method>.csv`, then TeamEval is run on that file (it plays nothing, every game is already there). `jobs.txt` holds only the last batch, so the run list must come from the CSV names:

```
cd /mnt/lustre/users/hniebuhr/microrts && module load chpc/java/24.0.1 && for m in team team-sbgp; do inds=$(ls results/teameval/$m-best-seed*.csv | grep -v summary | sed "s#results/teameval/$m-#results/#;s#\.csv\$#.ind#" | paste -sd,); java -Xmx2g -cp "out-eval:lib/*:lib/bots/GRojoA3N.jar:lib/bots/*" gp.TeamEval -method $m -inds "$inds" -csv results/teameval/all-$m.csv -threads 1 2>&1 | sed -n '/=== Summary/,$p'; done > results/teameval/SUMMARY-n36.txt
```

## 3. Teammates' jobs on our account

| Teammate | Code | Results | Status |
|---|---|---|---|
| GP + adapter teammate | `/mnt/lustre/users/hniebuhr/teammate/MicroRTS_Adapter/` | `gp_populations/`, `adapter_runs/` | **Done** (job 7572640, exit 0, 40 h 49 min). Packed as `teammate-results-full-20261026.tgz` (1.2 GB, 1,220,231,740 bytes, verified); a copy in Downloads. |
| **Milan** (SBGP, batch `sbgp-16h`, 10 runs) | `/mnt/lustre/users/hniebuhr/milan/MilanGP/MilanGP/` | `runs/sbgp-16h/run-1..10` | Runs 1–2 done; runs 3–10 running in parallel (jobs 7587158–65) |
| **Shy** (gp / score / hybrid, 50 runs each) | `/mnt/lustre/users/hniebuhr/shy/microrts-hpc/` | `runs/2026-10_final/` | 33/150 done (runs 1–11 per bot); runs 12–21 queued (7587166–68) |

**Notes for the GP + adapter teammate:**
- There is no `base_run1.ser`; the base is `gp_populations/checkpoint.ser`.
- The trained adapter is in `adapter_runs/default/`, not `base_run1/`.
- `adapter_runs/test_t025/` was trained but never evaluated.

**Milan:**
- **Setup:** the zip contains a nested `MilanGP/` folder. CRLF line endings were fixed with `sed`. Built on the login node with `chpc/java/24.0.1` (only deprecation warnings).
- **Runs take 2.5–6 h each on the cluster, not his estimated 50 min:** run 1 took 6 h 9 min, run 2 took 2 h 27 min. The sequential job (7584319) couldn't fit 10 runs in 24 h, so it was stopped during run 3. The partial run is kept as `runs/sbgp-16h/run-3.killed` (Milan can delete it). Runs 3–10 were resubmitted as separate 8-core, 12 h jobs with the same seeds as his Makefile (`--randomSeed=$i`). Job logs: `/mnt/lustre/users/hniebuhr/milan/milan-r<i>.OU`.
- **His training maps are 8 of the 9 team evaluation maps** (only EightBasesWorkers16x16 is held out). Raise this before any cross-method comparison.

**Shy:**
- **Setup:** relative paths only. CRLF fixed in `hpc/*.sh` and `hpc/*.pbs`. Smoke test passed (`run 1 done in 7.7s`).
- **Each run is a 24-core subjob with ≤ 6 h walltime.** His script's `-J 1-50` can't be submitted because of the 40-queued limit, so chunks are submitted with `-J` overridden on the command line. The first chunk (33 runs) finished within about 10 h.
- **Remaining chunks:** `22-31`, `32-41`, `42-50` (submit each once the previous one has mostly finished; finished runs are skipped).

## 4. Commands

**Status of everything** (ssh window):

```
qstat -u hniebuhr; echo "== Milan finished (want 10):"; ls /mnt/lustre/users/hniebuhr/milan/MilanGP/MilanGP/runs/sbgp-16h/*/summary.properties 2>/dev/null | wc -l; echo "== Shy:"; (cd /mnt/lustre/users/hniebuhr/shy/microrts-hpc && bash hpc/progress.sh)
```

**Shy's next chunk** (ssh window; change the range each time):

```
cd /mnt/lustre/users/hniebuhr/shy/microrts-hpc && for b in gp score hybrid; do qsub -J 22-31 -v BOT=$b hpc/pbs_train.pbs; done; qstat -u hniebuhr
```

**Getting Milan's results** (once 10/10):

1. Stats, size and archive (ssh window):

```
cd /mnt/lustre/users/hniebuhr/milan/MilanGP/MilanGP && module load chpc/java/24.0.1 && make stats BATCH=sbgp-16h > runs/sbgp-16h/stats.txt 2>&1; cat runs/sbgp-16h/stats.txt; du -sh runs/sbgp-16h; tar czf /mnt/lustre/users/hniebuhr/milan-results-sbgp-16h.tgz --exclude=run-3.killed runs/sbgp-16h && ls -lh /mnt/lustre/users/hniebuhr/milan-results-sbgp-16h.tgz
```

   If `du` shows several GB, pack it in a job instead (as for the GP + adapter teammate).

2. Download and verify (laptop PowerShell):

```
scp lengau:/mnt/lustre/users/hniebuhr/milan-results-sbgp-16h.tgz "$env:USERPROFILE\Downloads\"; tar tzf "$env:USERPROFILE\Downloads\milan-results-sbgp-16h.tgz" | Select-Object -Last 5
```

**Getting Shy's results** (once `progress.sh` shows 50 / 50 for all three bots):

1. Evaluation (ssh window; about 4 h per his notes):

```
cd /mnt/lustre/users/hniebuhr/shy/microrts-hpc && qsub hpc/pbs_eval.pbs
```

2. When that job has finished, check the size and pack (ssh window):

```
cd /mnt/lustre/users/hniebuhr/shy/microrts-hpc && ls runs/2026-10_final/summary.md && du -sh runs/2026-10_final && tar czf results.tgz runs/2026-10_final && ls -lh results.tgz
```

3. Download and verify (laptop PowerShell):

```
scp lengau:/mnt/lustre/users/hniebuhr/shy/microrts-hpc/results.tgz "$env:USERPROFILE\Downloads\shy-results.tgz"; tar tzf "$env:USERPROFILE\Downloads\shy-results.tgz" | Select-Object -Last 5
```

**Our own results to the laptop** (laptop PowerShell; Lustre deletes files after 90 days):

```
scp -r lengau:/mnt/lustre/users/hniebuhr/microrts/results/* results-chpc\
```

## 5. Open items

1. Brown-Forsythe test for GP vs SBGP spread (laptop, from the two summary CSVs).
2. Hybrid per-opponent deltas (from the eval CSVs beside each checkpoint), and the hybrid's worst decision time against the 100 ms budget.
3. Copy our results to the laptop.
4. Feed Shy's chunks; collect Milan's and Shy's results; tell Milan about the slowdown and the training-map overlap.
5. The literature comparison (about 3 papers; candidates in the 2026-10-21 update).
6. Unit-table limitation statement (we evaluate under VERSION_ORIGINAL; the 2025 competition used Finetuned-Both).
7. Report: individual or shared? Template? Page limit?
8. Restore the laptop power settings.

- **Done 2026-10-26 20:20:** item 3. Our results are on the laptop in `results-chpc\results\` (from `our-results-20261026.tgz`, 89 MB, verified; delete the `.tgz` from the repo root or git-ignore it).

## Update 2026-10-27

- **Milan:** all 10 runs done and results sent (`milan-results-sbgp-16h.tgz`, 3 MB). Run-3 stopped at generation 136 by his own early-stopping rule (no improvement of 0.005 in 100 generations; best at generation 35), not a crash. Mean win rate 0.674 (sd 0.036); total training time 25.5 h.
- **Shy:** 31/50 per bot. Chunk 32–41 submitted 19:16 (7589244–46). Remaining steps: chunk `42-50` at about 41/50, then `qsub hpc/pbs_eval.pbs` (about 4 h) at 50/50, then pack, download and send. Expected ready around Thursday 29 Oct.
- **Laptop:** power settings restored.
- **Our results:** no runs outstanding. Remaining items are analysis only: the Brown-Forsythe test, and the hybrid's per-opponent deltas and worst decision time.

# Session update 2026-10-28: final statistics done; results chapter drafted; Shy's runs finished

## Current status (supersedes earlier "Current status" paragraphs)

All numbers and statistics for our three configurations are final, and the results chapter for them is drafted as a Word document.

- **Ordinary GP vs SBGP (n = 36 each):** same mean (57.5% vs 57.1%, Mann-Whitney p ≈ 0.94), but SBGP is significantly less variable (Brown-Forsythe F(1, 70) = 14.42, p = 0.0003).
- **SBGP + adapter (n = 20, paired):** 56.6% → 60.3%. The mean change is positive against all 10 opponents, and significant after Holm correction against 5.
- **Shy:** all 150 runs done; his evaluation (job 7592051) is running and should finish about 21:10 on 28 Oct. Then pack, download and send it to him.
- **Report:** shared with the team, minimum 30 pages, worked on section by section. The teammates' draft has the abstract, introduction, literature survey, methodology and workplan (largely the proposal text). Our results chapter is generated by `analysis\make_results_docx.py`.

## Instructions for the next assistant session (additions)

- **The report is shared.** Teammates did their own research and built their own bots. Never write anything that invalidates their work: add our material in our own subsections, and flag any change to shared text for team agreement. Where protocols differ (e.g. Milan's training maps overlap the evaluation maps), state the differences neutrally beside the numbers.
- **Incognito chats can't create Claude Docs,** and this chat setup has no file-download tool. Documents are built locally with python-docx scripts, then uploaded to Google Drive and opened with Google Docs.
- **The poster figures show outdated numbers:** 58.2% ± 6.2 vs 56.6% ± 3.5 (the earlier n = 20 comparison) and 53.6% → 57.0% (a single bot). Do not reuse them. The report figures are regenerated from the frozen data in the same style.
- **Never commit files over 50 MB.** `wheels-linux/` and `*.tgz` are git-ignored.

## 1. Statistics completed

**Brown-Forsythe** (`analysis\bf_test.py`, from `all-team.csv` and `all-team-sbgp.csv`). The script recomputes every run's macro win rate from the per-game rows and reproduced the frozen n, means and sds exactly.
- F(1, 70) = 14.418, p = 0.0003; variance ratio GP/SBGP = 2.82.
- Result values in the CSVs: GP W 11680 / L 9642 / T 710; SBGP W 11747 / L 9607 / T 678; 0 error rows.

**Hybrid per opponent** (`analysis\hybrid_deltas.py`, which writes `analysis\hybrid_deltas.csv`):
- Paired n = 20. Macro 56.6 → 60.3 (+3.6, median +4.3), 17/1/2 better/equal/worse, Wilcoxon p = 0.0005 (self-check passed).
- Wilcoxon per opponent, Holm-corrected across 10 opponents:

| Opponent | Base | Hybrid | Δ | +/=/− | p (Holm) |
|---|---|---|---|---|---|
| RandomBiasedAI | 90.0 | 92.9 | +2.9 | 14/2/4 | 0.042 * |
| WorkerRush | 30.0 | 37.5 | +7.5 | 14/3/3 | 0.044 * |
| LightRush | 72.8 | 74.7 | +1.9 | 11/5/4 | 0.36 |
| HeavyRush | 80.3 | 80.8 | +0.6 | 6/10/4 | 0.56 |
| RangedRush | 76.7 | 80.3 | +3.6 | 13/5/2 | 0.028 * |
| EconomyLightRush | 71.2 | 72.7 | +1.6 | 13/0/7 | 0.48 |
| mayari | 42.5 | 47.2 | +4.7 | 15/2/3 | 0.012 * |
| Rojo | 43.9 | 46.4 | +2.5 | 12/1/7 | 0.48 |
| Droplet | 31.1 | 38.7 | +7.7 | 15/0/5 | 0.012 * |
| Coac | 27.9 | 31.3 | +3.4 | 10/8/2 | 0.21 |

- **Finding:** the mean Δ is positive against all 10 opponents. The two largest gains include two competition bots (Droplet and mayari) that the adapter never met in training.

**Adapter decision time.** `eval_adapter.py` now has a `--timing` flag. Python-side timing is always recorded and printed as an `ADAPTER TIME` line; the flag only renames the output CSV `...-timing-...`, so `hybrid_deltas.py` never mistakes a timing run for a final evaluation. Backup: `adapter\eval_adapter.py.bak-20261028`; the patched file was also copied to the cluster.
- **Run:** interactive job, 1 thread (`OMP_NUM_THREADS=1 MKL_NUM_THREADS=1`), seeds 21242 / 22242 / 34242, against WorkerRush on the 9 eval maps, 1 sample.
- **Determinism check:** 21242 reproduced 7W 2T 9L exactly, so the patch didn't change behaviour.
- **What is timed:** obs read + forward pass + argmax per decision step; engine and opponent excluded.
- **Results:**

| Seed | n | Mean | p99 | p99.9 | Max (excl. first) | > 100 ms |
|---|---|---|---|---|---|---|
| 21242 | 7716 | 2.95 ms | 4.19 | 40.69 | 210.64 (first step: 2577) | 3 |
| 22242 | 7875 | 2.64 ms | 4.37 | 15.18 | 143.30 | 2 |
| 34242 | 6646 | 2.60 ms | 3.93 | 13.78 | 156.48 | 2 |

- **For the report:** mean 2.6–3.0 ms, p99 ≤ 4.4 ms; 7 of 22,237 steps (0.03%) over 100 ms (max 211 ms); a one-off cold start of about 2.6 s on the first call. Games were not forced to stay within the time budget.

## 2. Results chapter (Word document)

`analysis\make_results_docx.py` writes `report\results-chapter.docx` and `report\figures\*.png` (300 dpi). It recomputes every number from the CSVs and refuses to write the document if any differs from the frozen values (9 checks, all OK on 28 Oct).

**Contents:**
- Intro: protocol recap; macro win rate defined (draws are non-wins; best-of-run individual, unpruned; 612 games per bot).
- X.1 GP vs SBGP: Table X.1 summary, Figure X.1 beeswarm with ±1 SD band (poster style), tests, Table X.2 per opponent, decision time (68 of 72 runs ≤ 90 ms; 4 over 100 ms under 24-JVM load), and a placeholder for training cost.
- X.2 SBGP + adapter: Table X.3 paired, Figure X.2 paired slope plot, Wilcoxon, override rates (13.5–24.0%), evaluator agreement, Table X.4 per opponent, Figure X.3 paired bars (poster style), adapter timing.
- X.3 (team comparison) and X.4 (published results): placeholders, deliberately italic in brackets.

**Workflow:** upload the .docx to Drive, open it with Google Docs, then copy it into the team doc.

**Deliberately not in the chapter:** comparing the hybrid's 60.3% with the n = 36 means. The hybrid covers the 20 cluster seeds only and isn't paired with those runs, so it goes in the discussion as descriptive only.

## 3. Review of the teammates' draft (for team agreement, not yet applied)

- **Proposal leftovers:** "this proposal" ×3 (2.1, 2.2, 2.5) and "will be" in the methodology. Needs a past-tense pass. Use UK/SA spelling (the draft mostly uses -ise already).
- **3.5:** the placeholder "(Pillay, [year])" should be Scheepers & Pillay, GPEM 27:2 (2026), doi 10.1007/s10710-025-09528-3 (ref [18]).
- **3.6:** the τ range is stated as [1/1000, 1/100]. Our notes from the paper say [1/1000, 1/10]. Check against the paper; it matters because we used τ = 0.2.
- **3.5 / 3.7.1:** "GP has not been applied to microRTS at all" needs checking or softening (programmatic-strategy synthesis: Moraes et al. 2L, Mariño et al.). The team's own issue list asks for GP in similar game environments.
- **Section 4 vs what was done:** PPO dropped (Prof. Pillay approved); evaluation = team protocol (9 maps × 10 bots, 2000 cycles), with no TMA and no NaiveMCTS training opponent; tests actually used (Mann-Whitney U, Brown-Forsythe, Wilcoxon, Holm); ECJ v27; hardware = RTX 4050 laptop + CHPC Lengau; bridge = JPype, not Gym-µRTS.
- **Proposed new chapters after section 4:** Design and Implementation (shared part + one subsection per member), Experimental Setup, Results, Discussion, Conclusion. The workplan becomes a short note or an appendix.

## 4. Shy

- **Last chunk** (42–50, 27 subjobs) submitted 28 Oct ~09:15; all three bots reached 50/50 by 17:06.
- **Evaluation** job 7592051 submitted 17:07; at 19:49 it was running (2 h 39 min elapsed).
- **Check:** `qstat -u hniebuhr; ls -l /mnt/lustre/users/hniebuhr/shy/microrts-hpc/runs/2026-10_final/summary.md`
- **Pack** (ssh): `cd /mnt/lustre/users/hniebuhr/shy/microrts-hpc && du -sh runs/2026-10_final && tar czf results.tgz runs/2026-10_final && ls -lh results.tgz`
- **Download** (laptop PowerShell): `scp lengau:/mnt/lustre/users/hniebuhr/shy/microrts-hpc/results.tgz "$env:USERPROFILE\Downloads\shy-results.tgz"`
- **Send:** over 25 MB → a Drive link, not a Gmail attachment. Leave his cluster folder intact until he confirms receipt.

## 5. Git

- **The push failed** because `wheels-linux/torch-2.3.1+cpu-cp311-cp311-linux_x86_64.whl` (181 MB) was in 2 unpushed commits. Fixed: `git reset --soft origin/HeinrichFirstBot_Chimera`, `git rm -r --cached wheels-linux`, `.gitignore` += `wheels-linux/`, recommit, push.
- **`our-results-20261026.tgz` (89 MB) was pushed**: under the 100 MB limit, but now permanently in the branch history. Untrack it and ignore archives: `git rm --cached our-results-20261026.tgz; Add-Content .gitignore "`n*.tgz"`, then commit and push. History was not rewritten, because teammates share the repo.

## 6. Code added or changed

| File | New / replaced | What |
|---|---|---|
| `analysis\bf_test.py` | new | Brown-Forsythe GP vs SBGP, with a self-check of n, means and sds |
| `analysis\hybrid_deltas.py` | new | Paired macro + per-opponent Wilcoxon with Holm; writes `analysis\hybrid_deltas.csv` |
| `analysis\figures.py` | new | Plain versions of the three figures (superseded by `make_results_docx.py`) |
| `analysis\make_results_docx.py` | new | Builds the results chapter `.docx` + poster-style figures; refuses on any mismatch |
| `adapter\eval_adapter.py` | patched | `report_timing()`, per-step timing, `--timing` (CSV suffix `-timing`); behaviour unchanged |

Python packages added on the laptop: python-docx 1.2.0 (matplotlib 3.10.3 was already installed).

## 7. Open items

1. **Shy:** downloaded and sent results to him.
2. **Training-time numbers** for the "Training cost" placeholder. Decide: laptop and cluster separately, or the cluster only (comparable hardware). Laptop runs that overlapped other jobs are inflated.
3. **X.4 Comparison with published results:** search and read about 3 papers (Gym-µRTS, Huang et al. 2021, arXiv:2105.13807; RAISocketAI, Goodfriend 2024, arXiv:2402.08112; 2L, Moraes et al. 2023, arXiv:2307.04893) and draft.
4. **X.3 Team comparison:** collect the teammates' summary numbers and settings (Milan, Shy, GP + adapter). Note Milan's training-map overlap neutrally.
5. **Agree with the team:** the chapter structure, names for our bots (e.g. "Darwin" / "Baldwin"), the fixes to sections 3–4 above, and the definitions of the team's SD metrics.
6. **Optional:** prune a representative team-protocol bot per method (e.g. the median run, chosen by a pre-stated rule) for the interpretation section.
7. **Still open:** the unit-table limitation statement (VERSION_ORIGINAL vs Finetuned-Both); untrack `our-results-20261026.tgz`.
*Keep this file current: when a decision is made, a file is added, or a milestone is hit, update the relevant section so the next session starts from truth.*