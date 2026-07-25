# MICRORTSBOTS

Structure-based genetic programming for microRTS (COS700). This repo holds the bots, the GP
layer, and the evaluation harness. The microRTS engine itself is a bundled dependency, not part
of this source tree.

## Quickstart
```
./build.sh
./run.sh maps/8x8/basesWorkers8x8.xml WorkerRush 10
```
`build.sh` compiles everything under `src/` to `out/`; `run.sh` plays a headless match and
prints win/draw/loss plus the worst per-cycle decision time (the G1 budget is 100 ms).

## Layout
```
lib/                  DEPENDENCIES (not our code)
  microrts.jar        engine, built from santiontanon/microrts @ <pin your commit>
  jdom.jar, minimal-json-0.9.4.jar   engine runtime deps (map XML)
  bots/               held-out eval panel: Coac, mayari, Izanagi, Tiamat, Droplet, ...
maps/                 8x8/ and 16x16/
src/
  bots/               every playable AI
    Chimera.java      M0 reference bot (score-then-argmax)
    StefanFirstBot.java   <-- drop yours here; add `package bots;` as the first line
  gp/                 the GP layer
    Features.java     TERMINAL SET — bots import this (gp.Features)
  eval/               running & measuring
    Match.java        headless runner; grows into the tournament harness
config/               ECJ .params + experiment configs (later)
results/              match logs, evolved trees, seeds — reproducibility archive (later)
out/                  compiled classes (git-ignored)
```

## Dependency direction
`eval` → `bots` → `gp`. The `eval` harness runs bots; bots read the `gp` terminal set. Nothing
in `gp` depends on a bot, so the GP vocabulary stays independent of any one bot.

## Why the score-then-argmax shape matters
`Chimera` scores every legal `UnitAction` per unit and takes the argmax, rather than branching
on `if worker then ...`. That per-unit score vector is the seam the project pivots on:

| Configuration       | What changes                                                        |
|---------------------|--------------------------------------------------------------------|
| M0 (Chimera, now)   | `scoreAction()` is a hand-authored heuristic over `gp.Features`     |
| plain GP            | `scoreAction()` becomes an evolved tree over the **same** Features  |
| structure-based GP  | same, tree *structure* evolved separately from contents            |
| + adapter           | softmax the score vector, adapter adds its adjustment, then argmax  |

So the **G2 gate** (GP emits an action distribution) is already satisfied — the distribution is
the `scores[]` array in `Chimera.getAction()`, not a Phase-5 surprise.

## Terminal set (starter — revise in Phase 3a)
Action-type predicates, produce-target predicates, acting-unit condition, economy/army counts,
and Manhattan spatial terminals (`distNearestEnemy`, `distEnemyBase`, `movesToward`, ...). Open
questions: pathfinding-aware distance vs Manhattan; an "enemy attack power in range" term;
one-hot unit types vs boolean predicates. Whatever the GP can't read, it can't evolve.

## Current status
Chimera beats RandomBiased 4–0 and loses to WorkerRush 0–4 — a tuning gap in the hand-picked
weights, not a structural one, and exactly what the GP is meant to fix. ~13 ms/cycle.

## Rebuilding microrts.jar (reproducibility)
```
git clone --depth 1 https://github.com/santiontanon/microrts.git
cd microrts
find src -name "*.java" > sources.txt
javac -cp "lib/*:lib/bots/*" -d out @sources.txt
jar cf microrts.jar -C out .
```
Record the commit hash you build from.

## Adding an opponent from the eval panel
`eval.Match.makeOpponent` wires WorkerRush/LightRush/RandomBiased today. The `lib/bots/*.jar`
panel (Coac, mayari, ...) loads via reflection — a one-line addition when this runner becomes
the full Phase-2 evaluation harness.
