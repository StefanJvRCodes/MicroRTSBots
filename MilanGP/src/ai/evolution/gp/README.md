# Structured GP bot

A genetic-programming (GP) system that *evolves* a microRTS bot instead of having someone
hand-write its rules. Every idle unit a player owns is handed the same evolved decision tree;
the tree inspects that unit and the game state through boolean condition nodes and bottoms
out in exactly one action for that unit this turn. Run training long enough and the population
converges on a tree that beats the chosen opponents more often than not — what the tree
actually looks like is decided by evolution, not by us.

The Java sources in this package carry **no comments by design**. This README is the
documentation of record: every design decision, threshold and non-obvious behaviour is
explained here. If you change behaviour in the code, change it here too.

## Contents

- [Reading order for newcomers](#reading-order-for-newcomers)
- [Where everything lives](#where-everything-lives)
- [How a program is represented](#how-a-program-is-represented)
- [Node reference](#node-reference)
- [Map-relative parameters](#map-relative-parameters)
- [Population and genetic operators](#population-and-genetic-operators)
- [Dead-branch analysis and `reduce()`](#dead-branch-analysis-and-reduce)
- [Evaluating one individual](#evaluating-one-individual)
- [From matchup scores to one fitness number](#from-matchup-scores-to-one-fitness-number)
- [Ranking individuals](#ranking-individuals)
- [Novelty search](#novelty-search)
- [Hard-case archive](#hard-case-archive)
- [The training loop](#the-training-loop)
- [Autopilot](#autopilot)
- [Checkpoints and resuming](#checkpoints-and-resuming)
- [Configuration reference](#configuration-reference)
- [Running it](#running-it)
- [Evaluating a trained bot](#evaluating-a-trained-bot)
- [Adding a new node](#adding-a-new-node)
- [Known behaviour worth knowing about](#known-behaviour-worth-knowing-about)

## Reading order for newcomers

1. `nodes/GPNode.java`, `nodes/BoolNode.java`, `nodes/ActionNode.java` — the whole type system,
   about 20 lines total.
2. `nodes/functions/IfThenElse.java` and one terminal from each family, e.g.
   `nodes/terminals/conditions/CanHarvest.java` and
   `nodes/terminals/actions/HarvestResources.java`.
3. `StructuredGPAI.java` — how a tree becomes a playable `AI`. This is the shortest path from
   "tree" to "game".
4. `GPTreeOps.java` — crossover, mutation, and the type rule that keeps trees valid.
5. `GPMatch.java` — how one individual is scored.
6. `GPPopulation.java` — selection and fitness aggregation.
7. `GPTrain.java` — the generation loop that ties it all together.

Everything else (`GPAutopilot`, `GPCheckpoint`, `GPEloTable`, `GPHardCaseArchive`,
`GPBehavior*`, `GPMatchupSampler`, `GPPlay`) is machinery around those seven.

## Where everything lives

### `ai.evolution.gp` — evolution and evaluation

| File | Responsibility |
|---|---|
| `GPTrain` | Entry point for `make train`. Runs one evolution phase: generation loop, sampling, curriculum, full evaluations, stagnation handling, metrics, checkpoints, final output. |
| `GPAutopilot` | Entry point for `make autopilot`. Drives multiple `GPTrain` phases, splitting off stalled matchups and reseeding results. |
| `GPPlay` | Entry point for `make play` / `make holdout`. Benchmarks a saved bot file with win/tie/loss counts and confidence intervals. |
| `GPConfig` | Every tunable value, plus `--key=value` parsing. Public mutable fields; `copy()` clones via reflection. |
| `GPPopulation` | Owns the individuals. Initialisation, parallel evaluation, fitness aggregation, selection, elitism, next generation. |
| `GPIndividual` | One program: root node plus the scores attached to it during evaluation. |
| `GPTreeOps` | Generic tree walking, crossover, mutation, depth/size, reachability analysis and `reduce()`. |
| `GPMatch` | Plays games. Runs an individual against a matchup on both sides, scores the result, samples behaviour traces. |
| `GPOpponents` | Name → opponent `AI` factory (`"WorkerRush"` → `new WorkerRush(...)`). |
| `GPMatchupSampler` | Builds the full (map, opponent) grid and picks a deterministic, stratified subset per generation. |
| `GPEloTable` | Per-opponent difficulty rating, used to weight matchups in the fitness. |
| `GPHardCaseArchive` | Bounded list of matchups that exposed a weakness; replayed every generation at extra weight. |
| `GPBehaviorTrace` | Raw per-game behaviour stats for one side of one game. |
| `GPBehaviorVector` | Turns a set of traces into one fixed-length normalised vector for novelty search. |
| `GPCheckpoint` | Saves/loads a whole run (population, RNG, Elo, archives, champions) as a `.properties` file. |
| `StructuredGPAI` | Adapts an evolved tree to the microRTS `AI` interface (extends `AbstractionLayerAI`). |
| `StructuredGPAIFromFile` | Zero-argument-constructible `StructuredGPAI` for GUI/tournament use. Loads `./models/best.txt` unless `-Dgp.botFile=...` is given. |
| `results.md` | Recorded benchmark output for a previous champion. Not read by any code. |

### `ai.evolution.gp.nodes` — the language the trees are written in

| File | Responsibility |
|---|---|
| `GPNode` | The interface every node implements: `getName`, `getParams`, `getChildren`, `setChild`, `copy`. Everything generic (crossover, printing, parsing) works only against this. |
| `BoolNode` | Abstract base for nodes that evaluate to `true`/`false` via `eval(ctx)`. |
| `ActionNode` | Abstract base for nodes that issue a unit action via `exec(ctx)`. |
| `GPTurnContext` | Per-turn scratch space handed to every node: the AI, game state, physical state, player, unit being decided, and the shared `reservedBuildPositions` list. |
| `GPUtil` | Shared queries: Manhattan distance, nearest enemy/resource/base, unit counts, map-relative scaling. |
| `GPNodeFactory` | Random node generation for initial population and mutation, including the value sets each parameterised terminal draws from. |
| `GPNodeRegistry` | Name → node constructor, used by the s-expression parser. |
| `GPSExpression` | Serialises a tree to `(If (EnemyInRange 0.2) (AttackNearestEnemy) (Idle))` and parses it back. |
| `PerturbableTerminal` | Implemented by parameterised terminals so mutation can nudge a constant instead of replacing the whole node. |
| `functions/` | Non-terminal nodes: `IfThenElse`, `And`, `Or`, `Not`. |
| `terminals/conditions/` | `BoolNode` terminals — the bot's sensors. |
| `terminals/actions/` | `ActionNode` terminals — the bot's effectors. |

The shared infrastructure stays directly under `nodes` because it isn't itself a node.

## How a program is represented

This is **structured** GP (also called strongly-typed GP), not classic Koza-style GP, because
of one rule: **a subtree may only ever be replaced by a subtree of the same type.** That rule
is what stops evolution from producing nonsense such as a boolean condition sitting where an
action was expected. `GPTreeOps` enforces it during crossover and mutation.

There are exactly two types, never mixed:

- **Bool** (`BoolNode`) — evaluates to `true`/`false`. Only ever used as an `If` condition or
  inside `And`/`Or`/`Not`.
- **Action** (`ActionNode`) — issues one abstract action through `AbstractionLayerAI`
  (`attack`, `harvest`, `train`, `build`, `move`, `idle`).

The root is always an `ActionNode`, so every unit always gets exactly one action. `IfThenElse`
is itself an `ActionNode` (it produces an action by delegating to one of its branches), which
is why "action terminal" means "an `ActionNode` with no children".

Programs are stored as s-expressions, one line per bot file:

```
(If (CanHarvest) (If (IsCarryingResources) (HarvestResources) (MoveToNearestResource)) (AttackNearestEnemy))
```

`StructuredGPAI.getAction` loops over every unit the player owns that has no action assigned
yet, sets `ctx.unit`, and executes the same root tree for each one. One tree, many units — the
tree must therefore *distinguish* units itself, via `CanHarvest`, `IsMilitary`,
`WorkerAttackRankAtMost` and friends.

## Node reference

### Function nodes — `nodes/functions`

| Node | Written as | Children | Behaviour |
|---|---|---|---|
| `IfThenElse` | `If` | `Bool cond, Action then, Action else` | The only branching node. Evaluates `cond`, then executes exactly one branch. |
| `And` | `And` | `Bool, Bool` | Short-circuiting logical AND. |
| `Or` | `Or` | `Bool, Bool` | Short-circuiting logical OR. |
| `Not` | `Not` | `Bool` | Logical NOT. |

### Condition terminals — `nodes/terminals/conditions`

"Unit" below means the unit currently being decided (`ctx.unit`); "player" means the player the
tree is playing for.

| Node | Params | True when |
|---|---|---|
| `True` | — | Always. Also used by `reduce()` as the boolean constant, with `(Not (True))` as constant false. |
| `CanHarvest` | — | The unit can harvest (worker-like). |
| `CanAttack` | — | The unit can attack. Note that Workers *can* attack in this ruleset, so this is not "is a soldier". |
| `IsMilitary` | — | The unit can attack but cannot harvest — i.e. a dedicated combat unit, excluding Workers. Exists because `CanAttack AND NOT CanHarvest` is a fragile composition that GP would otherwise have to rediscover in every branch that needs it. |
| `EnemyInRange` | `fraction` | Nearest enemy unit is within `absoluteRange(fraction)` tiles (Manhattan). |
| `EnemyInAttackRange` | — | Nearest enemy unit is within this unit's own `UnitType.attackRange`. Unlike `EnemyInRange`, this is per-unit-type aware (Ranged vs Heavy). |
| `EnemyInSightRange` | — | Nearest enemy unit is within this unit's own `UnitType.sightRadius`. Does **not** gate visibility: the GP AI always evaluates against the fully observable `PhysicalGameState`. It only lets trees tell long-sighted units (Base, Ranged) from short-sighted ones (Light, Heavy). |
| `EnemyBaseInRange` | `fraction` | Nearest enemy base/stockpile is within `absoluteRange(fraction)` tiles. |
| `ResourceInRange` | `fraction` | Nearest resource patch is within `absoluteRange(fraction)` tiles. |
| `NearOwnBase` | `fraction` | Nearest friendly base/stockpile is within `absoluteRange(fraction)` tiles. |
| `ResourcesAtLeast` | `fraction` | The player holds at least `absoluteCount(fraction, 256/20)` banked resources. |
| `HPBelow` | `fraction` | The unit's HP is below `fraction` of its max HP. |
| `OwnMilitaryAtLeast` | `fraction` | The player has at least `absoluteCount(fraction, 256/10)` military units (can attack, cannot harvest). |
| `EnemyMilitaryAtLeast` | `fraction` | The opponent has at least `absoluteCount(fraction, 256/10)` military units. |
| `EnemyWorkersAtLeast` | `fraction` | The opponent has at least `absoluteCount(fraction, 256/12)` workers. |
| `OwnWorkersAtLeast` | `count` (int) | The player currently has at least `count` workers. Absolute, not map-scaled. |
| `OwnHasBarracks` | — | The player owns a Barracks. |
| `EnemyHasBarracks` | — | The opponent owns a Barracks. |
| `GameTimeAtLeast` | `cycle` (int) | The game has reached this engine cycle. This is what lets a tree express opening / transition / late-game phases. |
| `IsCarryingResources` | — | The unit is carrying harvested resources. |
| `WorkerAttackRankAtMost` | `rank` (int) | The unit is a worker and is among the `rank` friendly workers closest to an enemy (ties broken by unit ID, so it is deterministic). |

`WorkerAttackRankAtMost` is the deterministic role-assignment primitive. `OwnWorkersAtLeast 3`
combined with `WorkerAttackRankAtMost 1` sends exactly one worker to pressure while the others
keep harvesting. Without it, a shared tree could only choose "all workers attack" or "no
workers attack" — the all-worker rush beats economy openings but collapses against sustained
WorkerRush.

### Action terminals — `nodes/terminals/actions`

Every action falls back to `Idle` when it cannot be performed, so a tree is never invalid — it
is only wasteful. That waste is real, though: an action that idles is a turn thrown away, which
is why the fitness ends up caring about *reachable* capability (see
[Ranking individuals](#ranking-individuals)).

| Node | Behaviour | Idles when |
|---|---|---|
| `Idle` | Does nothing. | — |
| `AttackNearestEnemy` | Attacks the nearest enemy unit. | Unit can't attack, or no enemy exists. |
| `AttackWeakestEnemy` | Attacks the enemy unit with the lowest HP anywhere on the map. | Unit can't attack, or no enemy exists. |
| `AttackEnemyBase` | Attacks the nearest enemy base/stockpile. | Unit can't attack, or no enemy base exists. |
| `HarvestResources` | If carrying resources, returns them to the nearest own base; otherwise harvests the nearest resource patch. | Unit can't harvest, or no resource/base found. |
| `TrainWorker` | Produces a `Worker`. | No `Worker` type, this unit can't produce one, or resources are short. |
| `TrainLight` / `TrainHeavy` / `TrainRanged` | Produces that specific unit type. | Type missing, not producible by this unit, or resources short. |
| `TrainMilitary` | Produces the *cheapest affordable* type this unit can produce that can attack but can't harvest. | No such producible, affordable type. |
| `BuildBase` | Worker constructs a `Base` near itself. | Unit can't harvest, no `Base` type, or resources short. |
| `BuildBarracks` | Worker constructs a `Barracks` near itself. | Unit can't harvest, no `Barracks` type, or resources short. |
| `MoveToEnemyBase` | Moves toward the nearest enemy base/stockpile. | Unit can't move, or no enemy base found. |
| `MoveToOwnBase` | Moves toward the nearest friendly base/stockpile. | Unit can't move, or no own base found. |
| `MoveToNearestEnemy` | Moves toward the nearest enemy unit. | Unit can't move, or no enemy found. |
| `MoveToNearestResource` | Moves toward the nearest resource patch. | Unit can't move, or no resource found. |

`BuildBase` and `BuildBarracks` share the per-turn `reservedBuildPositions` list on
`GPTurnContext`, so two workers deciding in the same turn cannot pick the same tile.

### Random generation weights

`GPNodeFactory` does not draw terminals uniformly. Two are deliberately over-weighted:

- **`HarvestResources`: 4 of 19 action-terminal draws.** A shared tree that stops harvesting can
  still beat weak or aggressive opponents by rushing off the map's starting resources, so
  nothing in the win/loss signal pushes selection back toward an economy once mutation drops
  it. Over-weighting the terminal keeps it from disappearing from the gene pool entirely.
- **`IsMilitary`: 2 of 22 condition-terminal draws.** It replaces the fragile
  `CanAttack AND NOT CanHarvest` composition, so it needs to be readily available rather than
  merely reachable by composition.

## Map-relative parameters

Most numeric terminals store a `0..1` fraction rather than an absolute tile count or unit
count, so a threshold evolved on 8x8 still means something on 24x24. `GPUtil` does the
conversion:

- `absoluteRange(pgs, fraction)` = `round(fraction * (width + height))`. Used by every distance
  terminal. On 16x16 a fraction of `0.2` is 6 tiles of Manhattan distance.
- `absoluteCount(pgs, fraction, areaPerUnit)` = `max(1, round(fraction * width * height / areaPerUnit))`.
  Used by every counting terminal. `areaPerUnit` is calibrated per terminal against the 16x16
  reference map (area 256) so that `fraction = 1.0` reproduces the ceiling the terminal used
  when these values were hardcoded counts.

`OwnWorkersAtLeast` and `GameTimeAtLeast` are deliberately **not** map-scaled: a worker count is
a build-order decision and a cycle count is wall-clock, neither of which scales with map area.

### Parameter ranges

Initial values are drawn from the seed sets in `GPNodeFactory`. Mutation may instead *perturb*
an existing value (see [Population and genetic operators](#population-and-genetic-operators)),
clamped to the min/max below.

| Terminal | Min | Max | Perturb step | `areaPerUnit` |
|---|---|---|---|---|
| `EnemyInRange` | 0.02 | 0.5 | ±0.05 | — |
| `EnemyBaseInRange` | 0.05 | 1.0 | ±0.08 | — |
| `NearOwnBase` | 0.03 | 0.75 | ±0.05 | — |
| `ResourceInRange` | 0.03 | 0.75 | ±0.05 | — |
| `HPBelow` | 0.05 | 0.95 | ±0.1 | — |
| `ResourcesAtLeast` | 0.05 | 1.0 | ±0.1 | 256/20 |
| `OwnMilitaryAtLeast` | 0.1 | 1.0 | ±0.1 | 256/10 |
| `EnemyMilitaryAtLeast` | 0.1 | 1.0 | ±0.1 | 256/10 |
| `EnemyWorkersAtLeast` | 1/12 | 1.0 | ±0.1 | 256/12 |
| `OwnWorkersAtLeast` | 1 | 12 | ±1 | — |
| `GameTimeAtLeast` | 0 | 10000 | ±25…200 | — |
| `WorkerAttackRankAtMost` | 1 | 6 | ±1 | — |

## Population and genetic operators

### Initialisation (`GPPopulation.initialize`)

Ramped half-and-half: individuals alternate between "full" trees (grow to the depth limit) and
"grow" trees (may stop early at a terminal with probability `terminalProbability`), with the
depth for each drawn uniformly from `[minInitDepth, maxInitDepth]`.

On top of that, a `harvestSeedFraction` share of the initial population is wrapped as
`(If (CanHarvest) (HarvestResources) <random action>)`. This guarantees the starting pool
contains individuals whose harvesters actually harvest, instead of relying on random search to
discover an economy. The random fallback still covers non-harvesters (bases, barracks,
military), and crossover/mutation are free to destroy the wrapper later.

Inside a generated `If`, the condition subtree depth is capped at `min(2, maxDepth - 1)` — the
budget is spent on action structure, not on deep boolean expressions.

### Filling the next generation (`GPPopulation.nextGeneration`)

Elite slots are filled in this order, skipping any duplicate s-expression:

1. The generalist champion (always, if one exists).
2. The curriculum champion.
3. User-supplied seeded specialists.
4. The best remaining individuals, until `eliteSize` slots are used.

Every remaining slot is filled by **exactly one** of three operators, chosen per slot:

| Operator | Probability | What it does |
|---|---|---|
| Crossover | `crossoverRate` (0.6) | Child of two tournament winners. With probability `specialistCrossoverRate` the donor is a protected seeded specialist rather than a tournament winner. |
| Mutation | `mutationRate` (0.3) | One tournament winner, mutated. |
| Reproduction | remainder (0.1) | One tournament winner, copied unchanged. |

Mutation does **not** piggyback on top of a crossover child. Each new individual is produced up
to `maxDuplicateRetries + 1` times until its s-expression is one not already in the next
generation; the last attempt is accepted regardless, so this is duplicate *pressure*, not a
guarantee of uniqueness.

Selection is tournament selection of size `tournamentSize`, using the same comparator as
`getBest()` (see [Ranking individuals](#ranking-individuals)).

### Crossover (`GPTreeOps.crossover`)

Nodes of the receiving tree are visited in random order. For each candidate, the donor tree is
searched for nodes of the *same type* (Bool vs Action), one is picked at random and spliced in.
The first splice whose result respects `maxDepth` is returned; otherwise the change is reverted
and the next candidate tried. If nothing fits, an unchanged copy of the first parent is
returned. Replacing the root is allowed and is trivially type-safe, since the root is always an
`ActionNode` and only an `ActionNode` can match it.

### Mutation (`GPTreeOps.mutate`)

One node is picked uniformly at random, then:

- If it is a `PerturbableTerminal` and `rnd < ercPerturbRate`, its constant is nudged within
  the clamped range in the table above. This is ephemeral-random-constant (ERC) tuning: it lets
  evolution refine `EnemyInRange 0.06` into `EnemyInRange 0.08` without discarding the
  surrounding structure.
- Otherwise the node is replaced by a freshly grown subtree of the same type, given the depth
  budget still available at that position (`maxDepth - depthOfPick`).

`maxDepth` is the only hard structural limit, and it is enforced after both operators.

## Dead-branch analysis and `reduce()`

`GPTreeOps` can tell which parts of a tree are actually reachable, by tracking what the
conditions on the path to a node already force to be true. This powers two things:
`reachableActionNames` (used by selection) and `reduce()` (used to simplify the published
program).

The analysis is a three-valued (`TRUE` / `FALSE` / `UNKNOWN`) evaluation:

- `resolve` decides a condition against what the path already knows, handling `True`, `Not`,
  and short-circuit/agreement cases for `And` and `Or`.
- `assume` records what taking a branch tells us. Only forcing cases are recorded: a true `And`
  pins both sides, a false `Or` pins both sides, and nothing else decomposes.
- Atomic conditions are matched by their **exact printed form**. There is deliberately no
  reasoning that `GameTimeAtLeast 1200` implies `GameTimeAtLeast 200`. The threshold predicates
  here point in different directions (`HPBelow` versus `OwnWorkersAtLeast`) and some are
  distances rather than counts, so hand-declaring monotonicity per node risks pruning live
  branches. Missing some dead code is safe; deleting reachable code is not.

Soundness rests on conditions being stable within one evaluation: `IfThenElse` evaluates its
condition and *then* descends, so nothing executes and no state changes between the condition
tests along a single root-to-leaf path.

**Why reachability rather than a plain node count matters.** Counting every action terminal in
the tree makes dead capability strictly cheaper to acquire than live capability while earning
identical credit, so the capability gate ends up selecting for decorative action terminals.
Observed in run `gp-1786302912023`: a champion carried `BuildBarracks` and `BuildBase` inside
`(If (CanHarvest) ...)` nested in the **else** branch of `(If (CanHarvest) ...)` — provably
unreachable, yet counting for two of the eight actions that bought the tree its parsimony
immunity. Its real behaviour was a worker rush with no economy at all.

`reduce()` applies the same path knowledge plus constant rewrites (`And`/`Or` with a constant
operand, double `Not`, `If` with a constant condition, `If` with identical branches) and is
semantics-preserving: a branch is only removed when the conditions above it force the other
way. It runs once at the end of a phase, so `best.txt` holds the simplified program.

## Evaluating one individual

`GPMatch.evaluateCases` plays the individual against each requested (map, opponent) case, plus
every case in the hard-case archive. Each case is played **twice — once as player 0 and once as
player 1** — to cancel first-move and map-side asymmetries, using the same headless game loop as
`tests.Experimenter`.

A single game ends when someone wins, `maxCycles` is reached, or neither side has issued an
action for `maxInactiveCycles`. Each game yields:

| Field | Meaning |
|---|---|
| `score` | `1.0` win, `0.5` no winner, `0.0` loss. Unshaped. |
| `margin` | `(myMaterial - theirMaterial) / totalMaterial` at the final state, in `[-1, 1]`, where material is banked resources plus the cost of every unit still held. |
| `cycles` | Game length. |
| `endedByLimit` | True when the game hit `maxCycles` or the inactivity limit rather than finishing. |
| `crashed` | A bot threw mid-game. |

**Why margin exists at all.** Win rate alone made "stop harvesting entirely" fitness-neutral in
every matchup the opponent pool does not specifically punish: a rush that wins a game scores
1.0 and an economy that wins the same game scores 1.0, so nothing pushed the economy to survive
once mutation dropped it. Margin gives that a real, secondary reward.

### Outcome shaping

Selection uses a shaped score; strict win rate and champion checks use the unshaped one.

- **Timeout draws** become `clamp(0.5 + drawMarginWeight * margin, 0, 1)`. This rewards a
  defensive program that converts its position into material advantage and pressures it toward
  a decisive win.
- **Losses** become `cap * (1 + margin) / 2` where `cap = min(lossMarginWeight, max(0, 0.5 - drawMarginWeight))`.
  An unshaped loss is a flat `0.0` whatever happened in the game, which leaves selection with
  no gradient at all on cases the population cannot yet win — the matchup becomes a constant in
  the harmonic mean rather than something evolution can climb. The cap is the lowest score a
  draw can shape down to, so a defeat can at worst tie a draw and never outrank one, whatever
  `lossMarginWeight` is set to. **win > draw > loss survives shaping.**
- **Wins** are already maximal and pass through unshaped.
- **Crash losses are never shaped** (see below).

### Crash handling

If a bot throws mid-game, the game is abandoned and scored as a **loss for the GP candidate**,
deliberately. Several stock abstraction bots have edge cases on unusual maps, and a throw must
not take a multi-hour training run with it — but anything more generous than a loss would
reward evolution for *causing* the crash rather than for winning. The result is flagged
`crashed` so loss shaping is skipped, and the first occurrence of each distinct failure
signature (`AI vs AI | exception @ origin`) is printed with a full stack trace, after which
identical failures are counted silently. One broken matchup therefore cannot print millions of
traces.

### Determinism

- `randomSeed` seeds evolution's `Random` (initialisation, selection, crossover, mutation).
- `evaluationSeed` seeds opponents. Each matchup mixes it with the opponent name and map index
  (`mixSeed`), and the player-1 game XORs in a constant, so the two sides of a matchup give a
  stochastic opponent genuinely different games rather than a mirrored one.
- Full evaluations repeat each matchup `fullEvaluationRepeats` times, offsetting the seed per
  repeat, and average the results.
- Autopilot derives each phase's seed as `randomSeed + label.hashCode()`, so sibling phases do
  not retread identical populations.

Only some opponents are actually stochastic; `EconomyMilitaryRush` is the one that takes a seed
from `GPOpponents`.

### The result structure

`GPMatch.evaluateCases` returns one `MatchupResult` per matchup — not a single aggregate — so
the caller can decide how to combine them:

| Field | Meaning |
|---|---|
| `score` | Shaped score, both sides averaged. |
| `rawScore` | Unshaped win/draw/loss score, both sides averaged. |
| `margin` | Mean material margin. |
| `candidateAsPlayer0Score` / `candidateAsPlayer1Score` | Per-side unshaped scores. Read these separately; see [Known behaviour](#known-behaviour-worth-knowing-about). |
| `limitedGames` | How many of the two games ended on a cycle/inactivity limit. |
| `archiveCase` | Whether the matchup came from the hard-case archive rather than the configured grid. |
| `opponentKey()` | `opponent@mapN` — identifies a bot+map pair as a single "opponent" for Elo and archive dedup. |

## From matchup scores to one fitness number

`GPPopulation.computeCombatScore` turns the per-matchup breakdown into a single **combat
score**. Two mechanisms apply, always, with no plain-win-rate fallback mode.

### 1. Elo-style difficulty weighting

Every opponent — meaning a specific bot+map pair, keyed by `opponentKey()` — carries a rating in
`GPEloTable`, starting at `eloInitialRating` (1000). After each generation, every opponent's
rating is updated from how the **whole population** did against it that generation, using the
standard logistic expected-score formula against the fixed anchor `eloInitialRating`:

```
expectedOpponentWin = 1 / (1 + 10^((initialRating - rating) / eloScale))
rating += eloK * ((1 - populationMeanScore) - expectedOpponentWin)
```

An opponent the population now beats consistently drifts to a lower rating; one it still
struggles against drifts higher. `rewardMultiplier()` is the opponent's rating relative to the
anchor, clamped to `[eloMinMultiplier, eloMaxMultiplier]` (`[0.5, 3.0]`).

That multiplier is used as the matchup's **weight** in the harmonic mean below. It must *not*
multiply the raw score: doing that would make a solved opponent's deliberately-small multiplier
produce the smallest denominator input, paradoxically turning the easy case into the
bottleneck.

Ratings are read during a generation's parallel evaluation and written afterwards from a single
thread, so each individual's combat score that generation is judged against *last* generation's
difficulty picture. That ordering is intentional and is why no extra synchronisation is needed
beyond the concurrent map guarding lazy initialisation.

### 2. Difficulty-weighted harmonic mean

```
combatScore = Σ w_i / Σ (w_i / (score_i + harmonicMeanEpsilon))
w_i = (archiveCase ? hardCaseWeight : 1.0) * rewardMultiplier(opponentKey_i)
```

A harmonic mean is dominated by its smallest inputs, so one matchup scored near 0 drags the
whole thing down far more than a plain average would. An individual can no longer average out a
genuine weakness against one opponent by being strong everywhere else. This is why there is no
arithmetic-mean mode: the two cannot both be "the" fitness value, and the harmonic mean is
strictly the better fit for "don't let evolution hide a weakness".

**`harmonicMeanEpsilon` is load-bearing, and 0.1 is not arbitrary.** It is added to each score
before inversion, so a lost matchup contributes a firm-but-bounded `1/0.1 = 10` against a full
win's `1/1.1 ≈ 0.91` instead of `1/0 = ∞`. Setting it too small does not merely make selection
harsher — it breaks the mechanism. With `1e-3`, a single `0.0` matchup's term (~1000) is roughly
1000× any other term, so `combatScore` degenerates into "how many matchups were lost outright"
and every other signal (extra wins, Elo multipliers, partial credit for ties) is rounded away.
That collapsed the selection signal outright in early testing: best fitness sat pinned at
~0.0045 for 90+ generations with `winRate` frozen too, both stuck because there was no usable
gradient left.

### `winRate`, tracked separately

`GPIndividual.winRate` is a plain, unweighted mean of the **raw, non-archive** matchup scores —
the win rate the individual would have under no weighting scheme at all. It exists because Elo
weighting means `combatScore` no longer naturally maxes out at 1.0 once every opponent's rating
has settled below the anchor, so `targetFitness` and the curriculum/stopping checks are compared
against `winRate` instead.

## Ranking individuals

`GPPopulation.compareIndividuals` is used by both `getBest()` and tournament selection. Higher
is better. The order is:

1. **Target gate.** `winRate >= targetFitness` outranks not meeting it, regardless of fitness
   magnitude.
2. **If both meet the target:** smaller tree wins outright. This is what lets training keep
   running past "found a winner" and spend the remaining generations shrinking the program
   instead of stopping.
3. **Quantised combat band.** `round(combatScore / combatTieEpsilon)`. Differences smaller than
   `combatTieEpsilon` are treated as ties so the tie-breakers below can act.
4. **If neither meets the target — capability gate** (`capabilityParsimonyGate`): more distinct
   *reachable* action terminals wins, counted up to `capabilityGateCeiling` and flat above it.
5. **If neither meets the target — size:** smaller tree wins.
6. **Novelty** (`useNoveltyBonus`): more novel behaviour wins.
7. **Exact combat score.**
8. **Margin.**

### Why the capability gate exists

Size as the first tie-break punishes an individual for carrying machinery it cannot *yet* use.
Barracks plus military training wins no game until the whole chain is present, so every partial
step along the way is same-band-but-larger and loses. Run `gp-1786197862030-resume80` lost
`BuildBarracks` and every military action from its champion this way around generation 100, and
spent the next 70 generations unable to score at all on `BWDistantResources32x32`. The gate puts
action vocabulary ahead of size so those intermediate steps survive.

It is ranked by a **count**, capped by `capabilityGateCeiling`, and not by subset containment.
Containment is only a partial order, and mixing it with the size tie-break admits cycles
(A ⊃ B beats B, B is smaller than C, C is smaller than A) — which is enough for TimSort to
reject the comparator outright when `nextGeneration` sorts. Capping the count keeps size
meaningful again once a tree already has plenty of vocabulary.

Note that the size tie-break is **soft** parsimony pressure, not a limit. `maxDepth` remains the
only hard structural cap. Within a combat band it stops bloat drifting upward for free, but a
genuinely better (if larger) individual still wins on combat score.

## Novelty search

Controlled by `useNoveltyBonus` (on by default, and automatically disabled during the
curriculum phase).

Every game played during evaluation records a coarse behaviour trace (`GPBehaviorTrace`) for the
candidate: the cycle of its first attack action, the cycle it first held two or more
bases/stockpiles ("expansion"), its peak army size, its peak worker count, and its final
unit-type composition.

`GPBehaviorVector.build` averages an individual's traces across all its games that generation
into one fixed-length, roughly `[0,1]`-scaled vector, so behaviours can be compared with a plain
Euclidean distance regardless of how many games or unit types were involved:

| Index | Feature | Scaling |
|---|---|---|
| 0 | First attack cycle | `/ maxCycles`, or 1.0 if it never attacked |
| 1 | First expansion cycle | `/ maxCycles`, or 1.0 if it never expanded |
| 2 | Peak army size | `/ 30`, clamped to 1.0 |
| 3 | Peak worker count | `/ 20`, clamped to 1.0 |
| 4… | Final composition | Proportion of final units per unit type |

Each individual's `noveltyScore` is its mean Euclidean distance to its `noveltyNeighbors`
nearest neighbours, drawn from this generation's population **plus** a standing archive of past
generations' most-novel vectors (`noveltyArchiveSize`, oldest evicted first). The archive is
what stops a behaviour from *staying* rewarded: without it, a strategy that was novel two
generations ago would still look novel today simply because nothing else has changed.

Combat remains the primary objective — novelty only separates candidates inside the same
quantised combat band. Two individuals that both rush from turn one, never harvest, and end
with the same unit mix score near-identical novelty regardless of which wins more often. The
point is to keep genuinely different strategies (economy-first, turtle-then-punish, early
aggression) alive rather than letting one dominant strategy crowd out the rest.

## Hard-case archive

Controlled by `useHardCaseArchive` (on by default). `GPHardCaseArchive` is a bounded, growing
list of (map, opponent) matchups that have exposed a weakness.

**How cases get in.** After each generation, if the current best individual's combat score is at
least `hardCaseFitnessThreshold` (0.45) — i.e. evolution has actually found something decent —
every matchup it scored below `hardCaseWeaknessThreshold` (0.5) is added. The threshold matters:
without it, every matchup looks "weak" simply because nothing good exists yet. Full evaluations
add up to `fullEvaluationHardCasesToAdd` of their own weakest cases as well.

**What being in the archive costs.** Every individual, every generation, is played against every
archived matchup **in addition to** the sampled grid. This is extra evaluation cost, not a
reweighting of existing games. Those extra games count `hardCaseWeight` (2.0) times as much
toward the combat score's weighted harmonic mean.

**Bounds and pinning.** Cases are deduplicated by `opponent@mapN`. Once `hardCaseArchiveMax` is
exceeded, the oldest **unpinned** case is evicted. Cases used to solve the curriculum are pinned
permanently, so switching to the full pool cannot remove the original training objective from
selection pressure. A case ejected from the active pool for stagnation is removed from the
archive outright, pinned or not — leaving it in would keep it being played, at double weight,
against a population no longer being selected on it.

In practice the archive is what "HeavyRush on 16x16" or "RangedDefense on 8x8" looks like:
once a matchup beats the reigning best individual, it stops being an occasional entry in the
rotation and becomes something every future generation is specifically pressure-tested against.

## The training loop

`GPTrain.main` builds the full case grid from `maps` × `opponents`, builds the curriculum grid
from `curriculumMaps` × `curriculumOpponents`, and calls `runPhase` once. (Curriculum maps must
be a subset of `maps`; otherwise the sampler throws.) With the defaults that is 5 maps × 15
opponents = 75 cases.

Each generation, `runPhase`:

1. **Picks the active pool.** The curriculum pool while `generation < curriculumGenerations` and
   the curriculum has not been completed; the full pool afterwards.
2. **Samples `sampledMatchupsPerGeneration` cases** from that pool via `GPMatchupSampler.sample`.
   Sampling is deterministic in `(randomSeed, generation)` and stratified: after a deterministic
   shuffle, cases are picked greedily to favour an unseen opponent group (+100), an unseen map
   group (+50), and then rarer exact opponents and rarer groups. Opponent groups are
   `defense` / `economy` / `rush` / `other` (matched in that order, so `EconomyRush` is economy,
   not rush); map groups are `terrain` / `large` / `medium` / `small`, matched by path substring.
   Every sample therefore balances rush, economy and defense opponents as well as small, medium,
   large and terrain-focused maps. If `sampledMatchupsPerGeneration >= poolSize`, the whole pool
   is used.
3. **Evaluates the whole population in parallel** across `threads` workers, then updates Elo
   ratings, novelty scores and the hard-case archive.
4. **Runs a full evaluation** every `fullEvaluationInterval` generations (and every generation
   during the curriculum): the top `fullEvaluationEliteCount` individuals, plus the standing
   generalist champion, curriculum champion and seeded specialists, are scored on the **complete
   active pool**, with each matchup repeated `fullEvaluationRepeats` times and averaged. Champions
   are ranked by worst case, then win rate, then (at target) size, then margin. Repeating and
   averaging is what stops a noisy two-game result from displacing a more reliable generalist.
   Full evaluations exclude archive cases.
5. **Logs** a generation line to stdout, the weakest `weakestCasesToLog` full-pool cases, and one
   JSON record per generation to `metrics.jsonl`.
6. **Checks for the curriculum switch:** when the champion's worst curriculum case reaches
   `curriculumWorstCaseTarget`, the curriculum champion is stored, its cases are pinned in the
   archive, and training moves to the full pool. Global stagnation tracking restarts at that
   point, because `combatScore` is measured against a different, harder pool from there on.
7. **Tracks per-case progress and ejects stalled cases.** A case is ejectable only if it is both
   stalled (no improvement of `caseStagnationImprovementThreshold` for `caseStagnationPatience`
   generations) **and** still scoring below `hardCaseWeaknessThreshold` — a matchup plateaued at
   a winning score is solved, not stuck. Ejected cases leave the active pool and the archive, so
   the rest can keep improving; they are reported in `PhaseResult.ejectedCases` and written to
   `ejected.txt`. Because per-case scores only refresh on a full evaluation,
   `caseStagnationPatience` should be a comfortable multiple of `fullEvaluationInterval`. If
   *every* active case stalls at once, the phase stops and leaves `activeCases` intact, which is
   the caller's signal that this phase split into nothing. Dropping cases also restarts global
   stagnation tracking, since a harmonic mean over fewer cases is on a different scale.
8. **Checks stopping conditions.** Before the target is reached: stop if `combatScore` has not
   improved by `stagnationImprovementThreshold` in `stagnationPatience` generations. After the
   target is reached: stop if the champion's size has not shrunk in `sizeShrinkPatience`
   generations. `0` disables the corresponding patience check.
9. **Breeds the next generation** and checkpoints every `checkpointInterval` generations.

At the end of the phase, the top `finalEvaluationEliteCount` candidates are evaluated on the
surviving active cases, the winner is passed through `reduce()`, and the result is printed and
written out.

### Run outputs

Runs are isolated under `runs/<run-id>/`:

| File | Contents |
|---|---|
| `manifest.properties` | Seed, engine rules and effective training configuration for the phase. |
| `metrics.jsonl` | One JSON record per generation: combat score, sampled win rate, average fitness, best size, sampled case count, archive size, and (on full-evaluation generations) full win rate, worst case and weakest cases. |
| `checkpoint-N.properties` | Resumable population, RNG, Elo, archive and champion state. |
| `checkpoint-final.properties` | Same, written when the phase ends. |
| `best.txt` | The final reduced tree, as an s-expression. |
| `ejected.txt` | Cases ejected for per-case stagnation, if any. |

## Autopilot

`make autopilot` trains one program against a whole opponent/map grid without you picking which
matchups need specialising or hand-managing seed files between runs. You say what to train on;
it decides what is hard and splits it off itself.

```bash
make autopilot GP_ARGS="--run-id=2026-08-04-mayari-8x8 --seed=45 \
  --opponents=mayariBot \
  --maps=maps/8x8/basesWorkers8x8.xml,maps/8x8/TwoBasesWorkers8x8.xml,maps/8x8/basesWorkers8x8Obstacle.xml"
```

Each phase is one ordinary `GPTrain.runPhase` call. Matchups that stall are ejected from that
phase's pool, so the rest keep improving instead of being dragged down by a case nothing can
solve; the driver then trains the ejected ones on their own — warm-started from the phase that
gave up on them — and reseeds both results into a fresh attempt at the full set:

```
solve(cases, seeds):
  phase = train(cases, seeds)                     // ejects whatever stalls
  hard  = phase.ejected
  if hard is empty   -> phase                     (solved)
  if hard == cases   -> phase                     (nothing converged; stop)
  if at depth cap    -> phase
  hardBest = solve(hard,  [phase.best])           // specialise
  merged   = solve(cases, [phase.best, hardBest]) // reseed over the full set
  return best of {phase, hardBest, merged} scored on cases
```

The last phase to run is **not** automatically the best one — a merge can come out worse than
the specialist that seeded it — so every distinct candidate is re-scored on that level's case
set and the winner chosen explicitly.

Recursion is bounded twice: `autopilotMaxDepth` caps nesting, and
`autopilotMaxAttemptsPerCaseSet` stops the same exact case set (identified order-independently)
being retrained forever when a merge keeps re-ejecting what it was supposed to reconcile. In
practice the attempt cap, not the depth cap, is usually what ends recursion.

Per-phase config differs from the parent in four ways: its own seed (`randomSeed +
label.hashCode()`), no checkpoint resume (that belongs to whichever single run the user pointed
at), no publishing (the driver publishes once at the end from the overall winner), and no
curriculum.

**There is no curriculum phase in autopilot**, at the root or anywhere else, and the
`curriculum*` fields are ignored (it warns if you set them). The curriculum narrows training to
a hand-picked subset, which is exactly the decision autopilot exists to make for itself — and
keying it off `GPConfig` defaults would silently give a plain `make autopilot` 50 generations
against one opponent nobody asked for. The curriculum remains available for manual `make train`
runs.

Output lands under one tree, so nothing needs tracking by hand:

```
runs/<run-id>/best.txt          # overall winner
runs/<run-id>/summary.txt       # every phase, its score, and what it ejected
runs/<run-id>/root/             # a normal run directory per phase
runs/<run-id>/root-hard/        #   (best.txt, ejected.txt, metrics.jsonl, checkpoints, manifest)
runs/<run-id>/root-merge/
```

Phase win rates in the summary are measured on the cases that *survived* ejection and are
labelled with that denominator (`on 3 of 4 case(s)`); the `(selected)` line is scored on the
full set. **They are not comparable without reading the denominators.**

## Checkpoints and resuming

`GPCheckpoint` writes a plain `.properties` file (atomically, via a temp file and a rename) at
a generation boundary, before evaluation, so a resume continues exactly. It stores the
population as s-expressions, the serialised `Random`, Elo ratings, hard cases with their pinned
flags, novelty archive vectors, the curriculum-completed flag, and all three kinds of champion.

On load, the following must match the requested configuration or the load fails: `maps`,
`opponents`, unit type table version, conflict policy, `evaluationSeed`, `maxCycles`,
`maxInactiveCycles`, and the `novelty` / `hard-cases` toggles. `populationSize` is checked
separately by `runPhase`. `sampledMatchupsPerGeneration` is saved but deliberately *not*
enforced, so a run can be branched with wider per-generation coverage. Version-1 checkpoints
written before a given fingerprint field existed remain loadable (a missing field is not an
error).

When resuming a run whose curriculum was already completed but whose curriculum champion is
absent, the best curriculum specialist still present in the population is recovered
automatically.

## Configuration reference

Every field lives on `GPConfig`. Fields with a CLI flag can be set as `--flag=value`; the rest
are edited in `GPConfig.java`.

### Evolution

| Field | Default | CLI flag | Meaning |
|---|---|---|---|
| `populationSize` | 1000 | `--population` | Individuals per generation. |
| `generations` | 200 | `--generations` | Generation cap for the phase. |
| `targetFitness` | 1.0 | — | `winRate` at which selection switches to shrinking the tree. |
| `tournamentSize` | 3 | — | Individuals sampled per tournament-selection draw. |
| `eliteSize` | 5 | — | Slots reserved for champions, seeded specialists and top individuals. |
| `crossoverRate` | 0.6 | — | Chance a new individual is a crossover child. |
| `mutationRate` | 0.3 | — | Chance a new individual is a mutated copy. |
| *(implicit) reproduction* | 0.1 | — | `1 - crossoverRate - mutationRate`; a tournament winner copied through untouched. |
| `ercPerturbRate` | 0.5 | — | Chance mutation nudges a parameterised terminal's constant instead of regrowing the subtree. |
| `maxDuplicateRetries` | 5 | — | Retries to avoid producing an s-expression already in the next generation. |
| `minInitDepth` / `maxInitDepth` | 2 / 6 | — | Depth range for initial ramped half-and-half trees. |
| `harvestSeedFraction` | 0.5 | — | Share of the initial population wrapped in `(If (CanHarvest) (HarvestResources) …)`. |
| `maxDepth` | 10 | — | Hard depth cap enforced after crossover/mutation. |
| `terminalProbability` | 0.35 | — | In "grow" trees, chance to stop early at a terminal. |
| `randomSeed` | 42 | `--seed` | Seed for evolution's `Random`. |
| `threads` | CPU count | `--threads` | Evaluation pool size. |

### Games and scoring

| Field | Default | CLI flag | Meaning |
|---|---|---|---|
| `maxCycles` | 10000 | `--max-cycles` | Game length cap, in engine cycles. |
| `maxInactiveCycles` | 300 | `--max-inactive-cycles` | Game is cut short if neither side has acted for this long. |
| `drawMarginWeight` | 0.25 | `--draw-margin-weight` | Shapes timeout draws around 0.5 using final material advantage. |
| `lossMarginWeight` | 0.2 | `--loss-margin-weight` | Partial credit ceiling for a loss, capped so a loss can never outrank a draw. |
| `evaluationSeed` | 4242 | `--evaluation-seed` | Base seed for opponents. |
| `harmonicMeanEpsilon` | 0.1 | — | Added to each score before inversion in the harmonic mean. Do not shrink this; see [above](#2-difficulty-weighted-harmonic-mean). |
| `combatTieEpsilon` | 0.01 | — | Combat-score band width for tie-breaking. |
| `capabilityParsimonyGate` | true | `--capability-parsimony-gate` | Rank reachable action vocabulary ahead of tree size below target. |
| `capabilityGateCeiling` | 8 | `--capability-gate-ceiling` | Vocabulary count above which the gate flattens and size matters again. |
| `eloInitialRating` | 1000 | — | Starting rating and fixed anchor for every opponent. |
| `eloK` | 16 | — | Elo update step size. |
| `eloScale` | 400 | — | Logistic scale in the expected-score formula. |
| `eloMinMultiplier` / `eloMaxMultiplier` | 0.5 / 3.0 | — | Clamp on a matchup's harmonic-mean weight. |

### Opponents, maps and sampling

| Field | Default | CLI flag |
|---|---|---|
| `maps` | 5 maps (4x4, 8x8, 16x16, TwoBases 8x8, Obstacle 8x8) | `--maps` |
| `opponents` | 15 bots (WorkerRush, WorkerRushPlusPlus, LightRush, HeavyRush, RangedRush, SimpleEconomyRush, EconomyRush, EconomyRushBurster, EconomyMilitaryRush, EMRDeterministico, WorkerDefense, LightDefense, HeavyDefense, RangedDefense, mayariBot) | `--opponents` |
| `sampledMatchupsPerGeneration` | 20 | `--sampled-matchups` |
| `fullEvaluationInterval` | 5 | `--full-evaluation-interval` |
| `fullEvaluationEliteCount` | 10 | — |
| `fullEvaluationRepeats` | 5 | `--full-evaluation-repeats` |
| `finalEvaluationEliteCount` | 20 | — |
| `weakestCasesToLog` | 5 | — |

`sampledMatchupsPerGeneration` is the main cost driver alongside `populationSize` and the
hard-case archive.

### Curriculum

| Field | Default | CLI flag | Meaning |
|---|---|---|---|
| `curriculumMaps` | `maps/16x16/basesWorkers16x16.xml` | `--curriculum-maps` | Must be a subset of `maps`. |
| `curriculumOpponents` | `WorkerRush` | `--curriculum-opponents` | |
| `curriculumGenerations` | 50 | `--curriculum-generations` | `0` disables the curriculum phase. |
| `curriculumWorstCaseTarget` | 0.75 | — | Worst-case score that ends the curriculum early. |

### Stopping and stagnation

| Field | Default | CLI flag | Meaning |
|---|---|---|---|
| `stagnationPatience` | 100 | `--stagnation-patience` | Stop if `combatScore` has not improved enough in this many generations. Checked outside the curriculum, only until the target is reached. `0` disables. |
| `stagnationImprovementThreshold` | 0.005 | `--stagnation-improvement-threshold` | What counts as improvement above. |
| `caseStagnationPatience` | 80 | `--case-stagnation-patience` | Eject a still-weak case that has not improved in this many generations. Keep it a comfortable multiple of `fullEvaluationInterval`. `0` disables; a run with one active case never ejects. |
| `caseStagnationImprovementThreshold` | 0.01 | `--case-stagnation-improvement-threshold` | What counts as per-case improvement. |
| `sizeShrinkPatience` | 50 | — | After the target is reached, stop if the champion has not shrunk in this many generations. |

### Novelty and hard cases

| Field | Default | CLI flag | Meaning |
|---|---|---|---|
| `useNoveltyBonus` | true | `--novelty` | Behaviour-diversity tie-break. Always off during the curriculum. |
| `noveltyNeighbors` | 15 | — | k for the k-nearest-neighbour distance. |
| `noveltyArchiveSize` | 200 | — | Standing archive of past most-novel vectors; oldest evicted first. |
| `useHardCaseArchive` | true | `--hard-cases` | Replay matchups that exposed a weakness. |
| `hardCaseArchiveMax` | 12 | — | Archive bound; oldest unpinned case evicted on overflow. |
| `hardCaseWeight` | 2.0 | — | Weight multiplier for archive matchups in the harmonic mean. |
| `hardCaseFitnessThreshold` | 0.45 | — | Minimum best-individual score before weak matchups are archived at all. |
| `hardCaseWeaknessThreshold` | 0.5 | — | Score below which a matchup counts as a weakness (also gates case ejection). |
| `fullEvaluationHardCasesToAdd` | 1 | — | Weakest full-evaluation cases added to the archive per full evaluation. |

### Seeding known specialists

| Field | Default | CLI flag | Meaning |
|---|---|---|---|
| `seedBotFiles` | empty | `--seed-bots` | Existing GP programs to inject and protect. |
| `seedCopies` | 20 | `--seed-copies` | Population entries created per seed: one exact copy, the rest crossovers with the generalist champion and mutations. |
| `specialistCrossoverRate` | 0.1 | `--specialist-crossover-rate` | Chance crossover deliberately uses a protected specialist as donor. |

### Autopilot

| Field | Default | CLI flag |
|---|---|---|
| `autopilotMaxDepth` | 4 | `--autopilot-max-depth` |
| `autopilotMaxAttemptsPerCaseSet` | 2 | `--autopilot-max-attempts` |

### Run plumbing

| Field | Default | CLI flag | Meaning |
|---|---|---|---|
| `runId` | `gp-<millis>` | `--run-id` | Output directory name under `outputDirectory`. |
| `outputDirectory` | `runs` | `--output-dir` | |
| `publishBotFile` | empty | `--publish` | Copy the final `best.txt` here. Never use from concurrent runs. |
| `checkpointInterval` | 10 | `--checkpoint-interval` | `0` disables periodic checkpoints. |
| `resumeCheckpoint` | empty | `--resume` | Checkpoint file to continue from. |
| `unitTypeTableVersion` | 2 | — | Passed to `UnitTypeTable`. |
| `conflictPolicy` | 1 | — | Passed to `UnitTypeTable`. |

### Benchmarking (`GPPlay`)

| Field | Default | CLI flag |
|---|---|---|
| `playBotFile` | `./models/baseline.txt` | `--play-bot` |
| `playMap` | `maps/16x16/basesWorkers16x16.xml` | `--play-map` |
| `playOpponents` | 19 bots (the 15 training bots plus RandomAI, RandomBiasedAI, RandomBiasedSingleUnitAI, PassiveAI) | `--play-opponents` |
| `playIterations` | 10 | `--play-iterations` |
| `playHoldout` | false | `--holdout` |
| `holdoutMaps` | 7 maps not in `maps` | — |
| `holdoutOpponents` | RandomAI, RandomBiasedAI, RandomBiasedSingleUnitAI | — |
| `playVisualize` | false | `--visualize` |
| `playVisualDelayMillis` | 50 | `--visual-delay` |

## Running it

All command-line options use `--key=value`. An unknown key or a missing `=` is a hard error.

```bash
# One fastest run on an 8-core workstation:
make train GP_ARGS="--run-id=seed42 --seed=42 --threads=8"

# Two independent replicates in separate terminals:
make train GP_ARGS="--run-id=seed42 --seed=42 --threads=4"
make train GP_ARGS="--run-id=seed43 --seed=43 --threads=4"

# Exact continuation:
make train GP_ARGS="--run-id=seed42-resumed --seed=42 --threads=8 \
  --resume=runs/seed42/checkpoint-100.properties"

# Specialist run against mayariBot on 16x16 only:
make train GP_ARGS="--run-id=gp-mayari-16x16 \
  --maps=maps/16x16/basesWorkers16x16.xml --opponents=mayariBot \
  --curriculum-generations=0"

# Resume a generalist while injecting and protecting a known specialist:
make train GP_ARGS="--run-id=seeded-generalist \
  --resume=runs/generalist/checkpoint-100.properties \
  --seed-bots=runs/gp-mayari-final/best.txt --seed-copies=20"

# Let autopilot decide what is hard:
make autopilot GP_ARGS="--run-id=full-grid --seed=45"
```

Do not run multiple 8-thread trainers on the same 8 physical cores. Use unique run IDs.
`--publish=<file>` may be supplied to a successful, chosen run to update the canonical GUI bot,
but must not be used by concurrent runs.

## Evaluating a trained bot

`GPPlay` reports win/tie/loss, score rate, strict-win Wilson 95% confidence intervals,
per-side results, cycle/inactivity-limit counts, mean game length and the worst matchup score.
Its cycle limits and unit table match training. Each iteration plays two games (one per side).

```bash
make play PLAY_ARGS="--play-bot=runs/seed42/best.txt \
  --play-map=maps/16x16/basesWorkers16x16.xml \
  --play-opponents=WorkerRush,LightRush --play-iterations=50"

# Maps/opponents that are rejected outright if they overlap the training defaults:
make holdout PLAY_ARGS="--play-bot=runs/seed42/best.txt --play-iterations=50"
```

For a credible comparison, train at least seeds 42, 43 and 44, run the same holdout schedule
for each, and select by **worst matchup score first, then overall score**. Do not select on
training fitness.

Reload a saved bot as a normal `AI`:

```java
UnitTypeTable utt = new UnitTypeTable();
AI bot = new StructuredGPAI(utt, "models/best.txt");
```

It is a regular `AbstractionLayerAI` subclass, so it drops straight into any existing runner
(`tests.CompareAllAIsObservable`, tournaments, and so on). For runners that need a
zero-configuration bot, `StructuredGPAIFromFile` loads `./models/best.txt`; launch Java with
`-Dgp.botFile=runs/seed42/best.txt` to point it at a run artifact without publishing.

To see the tree rather than read the s-expression, `models/visualize_gp_tree.py` renders any
saved bot file as a colour-coded PNG (blue = `If`, orange = boolean logic, aqua = conditions,
red = actions). It needs `pycairo`:

```bash
python3 models/visualize_gp_tree.py models/best.txt models/best.png
```

With no arguments it reads `baseline.txt` from the working directory; with one argument it
writes `<input>.png`.

## Adding a new node

Random generation, tree walking, crossover, mutation, printing and parsing all work generically
off `GPNode`, so adding a node is three edits:

1. Write one small class under `nodes/functions`, `nodes/terminals/conditions` or
   `nodes/terminals/actions`. Extend `BoolNode` or `ActionNode`, declare
   `public static final String NAME`, and implement `getName`, `getParams`, `getChildren`,
   `setChild` and `copy`. If it carries a numeric parameter, also implement
   `PerturbableTerminal` so mutation can tune it, and clamp the perturbed value.
2. Add one `case` to `GPNodeRegistry.build`, so saved programs containing it can be parsed back.
3. Add it to the relevant `switch` in `GPNodeFactory`, so random generation can reach it, and
   bump the `rnd.nextInt(n)` bound.

Miss step 2 and old bot files still load but new ones fail to parse. Miss step 3 and the node
exists but evolution can never discover it. Any parameter value set you add belongs in
`GPNodeFactory` next to the existing ones.

## Known behaviour worth knowing about

**Per-side scores split even on symmetric maps.** All the default training maps are
180°-rotationally symmetric, but the game is not side-symmetric, so a matchup scoring 0.5
usually means "won one side, lost the other" rather than "drew twice". Read
`candidateAsPlayer0Score` and `candidateAsPlayer1Score` separately and do not treat a split as
noise or as a bug in the evolved tree. Causes, most significant first:

1. `AStarPathFinding` expands neighbours up → right → down → left, with equal-`f` ties resolved
   by insertion order. Those are absolute directions, so a 180° rotation selects a different
   equal-length path.
2. `AbstractionLayerAI.findBuildingPosition` scans sides in the same absolute order, so player 0
   (low `y`) expands away from the centre while player 1 (high `y`) expands toward the enemy.
3. `GameState.issueSafe` is called for player 0 before player 1 each cycle, so player 0 wins
   every contested tile and player 1 stalls for an action duration.
4. `GPMatch.playMatchup` deliberately reseeds the opponent per side, so a stochastic opponent
   plays genuinely different games on the two sides.

Empirically the direction-ordered tie-breaking dominates the issue-order effect. Averaging both
sides cancels the aggregate bias but cannot make one evolved program side-invariant. Fixing it
properly means ordering the build scan and A* expansion relative to the unit's own base rather
than by absolute compass direction — a change to shared microRTS code that would affect every
scripted bot.

**Fitness is not comparable across pools.** `combatScore` is a weighted harmonic mean over
whichever cases are active, so it changes scale when the curriculum ends or a case is ejected.
Both events deliberately reset stagnation tracking. When comparing runs, compare `winRate` on a
stated case set, never `combatScore`.

**Full evaluations exclude archive cases.** Champion selection and the reported "full win rate"
are measured on the configured pool only, so they stay comparable across generations as the
archive grows.
