# Structured GP bot

A genetic-programming (GP) system that evolves a microRTS bot. Every idle unit a player owns is
handed the same evolved decision tree; the tree inspects that unit and the game state through
boolean conditions and bottoms out in exactly one action for that unit this turn.

The code is meant to be read on its own. This file is the map, not the manual: where things
live, the few design rules, and how to run it.

## Files

| File | What it does |
|---|---|
| `GPTrain` | `make train`. The generation loop: evaluate everyone on every case, log, stop if stagnant, breed. |
| `GPPlay` | `make play` / `make holdout`. Benchmarks a saved bot file with win/tie/loss counts and confidence intervals. |
| `GPConfig` | Every setting as a public field. Command line, properties file, manifest and checkpoint all use the field names. |
| `GPPopulation` | Initialisation, parallel evaluation, scoring, ranking, elitism and reproduction. |
| `GPIndividual` | One tree plus the scores its last evaluation produced. |
| `GPTreeOps` | Tree walking, random growth, crossover, mutation and `reduce()`. |
| `GPMatch` | Plays one matchup from both sides and turns the outcome into a score. |
| `GPOpponents` | Opponent name to a fresh `AI` instance. |
| `GPCheckpoint` | Saves and restores a run (generation, RNG, individuals, config). |
| `StructuredGPAI` | Adapts a tree to the microRTS `AI` interface. `StructuredGPAIFromFile` loads `./models/best_v2.txt` (or `-Dgp.botFile=...`) for the GUI and tournaments. |
| `nodes/GPNode`, `BoolNode`, `ActionNode` | The type system. `BoolTerminal` / `ActionTerminal` are the bases for leaf nodes. |
| `nodes/GPNodes` | The one table of every terminal: how to parse it and how to draw a random one. **Add new terminals here.** |
| `nodes/GPSExpression` | Tree to text and back: `(If (EnemyInRange 0.2) (AttackNearestEnemy) (Idle))`. |
| `nodes/GPTurnContext` | What a node may look at while deciding one unit. Caches per-turn lookups. |
| `nodes/GPUtil` | Shared game-state queries and map-relative scaling. |
| `nodes/functions/` | `If`, `And`, `Or`, `Not`. |
| `nodes/terminals/conditions/` | The sensors. Each class has a one-line Javadoc saying when it is true. |
| `nodes/terminals/actions/` | The effectors. Each falls back to `Idle` when it cannot act. |

## The rules that matter

**Two types, never mixed.** A `BoolNode` evaluates to true/false; an `ActionNode` issues one
abstract action. The root is always an `ActionNode`. Crossover and mutation only ever replace a
subtree with one of the same type, so every tree is always valid.

**One tree, many units.** `StructuredGPAI.getAction` runs the same root for each idle unit. The
tree must tell units apart itself (`CanHarvest`, `IsMilitary`, `WorkerAttackRankAtMost`).

**Map-relative constants.** Distance and count terminals store a `0..1` fraction and scale it by
map size (`GPUtil.absoluteRange`, `absoluteCount`) so a threshold evolved on 8x8 still means
something on 24x24. `OwnWorkersAtLeast` and `GameTimeAtLeast` are deliberately absolute.

**Fitness.** Every individual plays every (map, opponent) case, once from each side.
A win scores 1, a timed-out draw `0.5 + drawMarginWeight * materialMargin`, a loss
`lossMarginWeight * (1 + materialMargin) / 2`, a crash 0. The combat score is the harmonic mean of
those shaped scores (plus `harmonicMeanEpsilon`), so the worst matchup dominates and a weakness
cannot be averaged away. Ranking is combat score, then smaller tree, then material margin.
`winRate` and `worstCase` are the plain unshaped numbers and are what gets reported.

**Reproduction.** The top `eliteSize` distinct trees are copied through. Each other slot is filled
by exactly one operator: crossover (`crossoverRate`), mutation (`mutationRate`) or a plain copy.
Mutation on a parameterised terminal nudges the constant with probability `ercPerturbRate`
instead of replacing the node. `maxDepth` is the only hard structural limit.

**`reduce()`.** Before `best.txt` is written the tree is simplified: branches the path conditions
already rule out are removed and constant boolean structure is folded. It only matches atomic
conditions by exact text, so it never removes a live branch.

**`TechAndTrain` is a hand-written macro.** It builds a barracks, then trains the requested unit,
and falls back to harvesting or attacking. It exists because the barracks-then-train chain only
pays off as a whole, which random search rarely assembles. Be aware that a champion leaning on it
is evolving around a hand-designed routine.

## Running it

```
make train                                 # defaults from GPConfig
make train GP_ARGS="--populationSize=500 --generations=100"
make train GP_ARGS="--config=my.properties --runId=exp1"
make train GP_ARGS="--help"                # print every setting as name=value
make train GP_ARGS="--resumeCheckpoint=runs/exp1/checkpoint-50.properties"
make play  PLAY_ARGS="--playBotFile=runs/exp1/best.txt --playIterations=20"
make holdout                               # unseen maps and opponents
```

Setting names are the `GPConfig` field names; `--population-size` works too. Lists are
comma-separated. Files in a `--config=` properties file use the same names. Each run writes
`runs/<runId>/manifest.properties` (the full config), `metrics.jsonl` (one line per generation),
`checkpoint-N.properties` and `best.txt`.

## Adding a terminal

1. Write the class in `terminals/conditions` or `terminals/actions`, extending `BoolTerminal` or
   `ActionTerminal`. Implement `getName()` and `eval`/`exec`. If it has a constant, also implement
   `getParams()` and `PerturbableTerminal.perturb()` with a sensible range and step.
2. Add one line to `CONDITIONS` or `ACTIONS` in `GPNodes`.

Saved bot files stay readable as long as node names and parameter order do not change.
