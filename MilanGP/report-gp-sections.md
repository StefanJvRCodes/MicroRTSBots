MERGE INSTRUCTIONS (for the person or AI combining the report)

Author of this part: Milan Kruger (U04948123). Scope: a structure-based GP (SBGP) bot written in plain
Java directly on the microRTS engine (no GP library), its training runs and their evaluation.
Teammates built their own bots, which are described in their own sections. Nothing in this part
replaces or evaluates their work.
Every heading, table and figure in this part is numbered with the prefix M so that it can sit beside
the other members' parts without clashes. Figure and table images are in `models/figures/` (PNG and
PDF of each); the file name is given with every caption.
**[TODO: ...]** = content still missing. **[VERIFY: ...]** = a fact to check before submission.
Citations use the report's IEEE numbering; one new reference, [19], is listed in Section M.9.
Section M.5 uses its own map and opponent split, not the team evaluation protocol; see the notes for
the group at the end before merging.

---

# Part M: A Structure-Based GP Bot for microRTS (Milan Kruger)

## M.1 Overview of this part

This part describes a second, independent GP pipeline. It evolves a complete microRTS agent as a
single typed decision tree: every idle unit runs the same tree, which inspects that unit and the game
state through boolean conditions and ends in one high-level action. The system is written from scratch
in Java against the microRTS engine, and evolution uses the structure-based GP of Scheepers and
Pillay [18].

Twenty independent runs of 200 generations were made on two machines, and the best tree of ten of
them was evaluated on unseen opponents and unseen maps. The best trees won 69.3% of their training
games on average and 53.4% of games against unseen opponents on unseen maps. The best single bot is a
readable 51-node worker strategy. An ordinary-GP baseline has not yet been run in this pipeline, so
this part reports what SBGP produces, not what the structure-based component adds (Section M.8).

## M.2 Design of the evolved bot

### M.2.1 How the bot decides

Every game cycle, the bot runs its tree once for each of its own units that has no action in
progress. The tree's leaves are high-level actions such as `HarvestResources` or `AttackNearestEnemy`.
These are handed to microRTS's abstraction layer, which turns each one into the next concrete step
(move, gather, return, attack or produce) using A* pathfinding. When that step finishes, the unit is
free again and the tree is run again, so a unit can change its mind every few cycles (Figure M.1).

*Figure M.1. How the tree decides for one unit: conditions are tested from the root down until an
action leaf is reached. (`decision.png`)*

We chose high-level actions over raw unit actions for two reasons. First, the tree never has to learn
pathfinding or the harvest-return cycle, which random search would rarely assemble from single moves.
Second, every leaf is something a human can read, so an evolved tree can be explained (Section M.7.3).
The cost is that the bot can only do what the action set offers; it cannot, for example, place a
building at a chosen spot.

Every action falls back to microRTS's `Idle` when the unit cannot carry it out (for example `Train`
without enough resources). `Idle` is not quite idle: a unit that can attack still strikes any enemy
within its attack range. Part of every evolved bot's fighting therefore comes from this engine
behaviour rather than from evolution.

### M.2.2 Representation

Each individual is one strongly typed tree with two node types that are never mixed. A boolean node
evaluates to true or false; an action node issues one action. The root is always an action node.
The function set is `If(condition, action, action)`, `And`, `Or` and `Not`. Crossover and mutation only
ever replace a subtree with one of the same type, so every tree produced during evolution is a valid
program and no repair step is needed.

All units share one tree. The tree therefore has to tell units apart itself, which it does with
conditions such as `CanHarvest`, `IsMilitary`, `IsUnitType(t)` and the two worker ranks described
below. We kept a single tree, rather than one tree per unit type, so that evolution decides how
finely to split behaviour between unit types, and so that a whole bot reads as one program.

Trees are stored as S-expressions, for example `(If (EnemyInRange 0.2) (AttackNearestEnemy) (Idle))`.
The same text form is used for saved bots, checkpoints and the evaluation cache.

### M.2.3 Primitive set

The terminal set holds 23 conditions and 13 actions (Table M.1). Terminals with a parameter carry an
ephemeral random constant, which mutation can nudge as well as replace.

**Table M.1. Primitive set.** *(`table_terminals.png`)*

| Kind | Nodes |
|---|---|
| Unit state | `CanHarvest`, `CanAttack`, `IsMilitary`, `IsUnitType(t)`, `IsCarryingResources`, `HPBelow(f)`, `StrongerThanNearestEnemy` |
| Distances | `EnemyInAttackRange`, `EnemyInSightRange`, `EnemyInRange(f)`, `EnemyBaseInRange(f)`, `NearOwnBase(f)`, `ResourceInRange(f)` |
| Counts and economy | `ResourcesAtLeast(f)`, `OwnMilitaryAtLeast(f)`, `EnemyMilitaryAtLeast(f)`, `EnemyWorkersAtLeast(f)`, `OwnWorkersAtLeast(n)`, `OwnHasBarracks`, `EnemyHasBarracks` |
| Time and roles | `GameTimeAtLeast(n)`, `WorkerAttackRankAtMost(n)`, `WorkerHarvestRankAtMost(n)` |
| Actions | `Idle`, `HarvestResources`, `AttackNearestEnemy`, `AttackWeakestEnemy`, `AttackEnemyBase`, `Train(t)`, `TrainOrSave(t)`, `BuildBase`, `BuildBarracks`, `MoveToEnemyBase`, `MoveToOwnBase`, `MoveToNearestEnemy`, `MoveToNearestResource` |
| Functions | `If(condition, action, action)`, `And`, `Or`, `Not` |

The set went through several revisions. The design decisions that shaped it, and the reason for
each, are:

- **Map-relative constants.** Distance and count thresholds are stored as a fraction between 0 and 1
  and scaled by the map size at run time. A threshold evolved on an 8×8 map therefore keeps its
  meaning on a 24×24 map. Worker counts, worker ranks and game time are kept absolute, because "two
  workers" or "cycle 100" mean the same on every map.
- **Worker ranks.** `WorkerAttackRankAtMost(n)` and `WorkerHarvestRankAtMost(n)` number a player's
  workers by distance to their nearest enemy or nearest resource. A rule such as "the two workers
  closest to the minerals harvest, the rest fight" then takes two nodes instead of a combination
  evolution would have to stumble on. The best bot uses both ranks (Section M.7.3).
- **A matchup question instead of hit points.** `HPBelow`, the unit's hit points as a fraction of its
  maximum, is the wrong question for whether to fight: a Heavy
  on 3 of 8 hit points still kills a Worker in one hit, and since Workers and Ranged units have only
  one hit point, `HPBelow` can never fire for them. `StrongerThanNearestEnemy` asks the matchup question
  directly. It compares how many attacks each side needs to kill the other and is true when the unit
  needs no more than its nearest enemy does, so an even trade counts as winning. `HPBelow` was kept,
  but it only affects Light, Heavy and building branches.
- **One parameterised type test.** `IsMilitary` is true for Light, Heavy and Ranged alike.
  `IsUnitType(t)` carries the unit type as its parameter, so one terminal separates all six types and
  mutation can switch between them.
- **Two kinds of training.** `Train(Cheapest)` trains the cheapest military unit the player can
  afford now. `TrainOrSave(t)` trains only the requested type and otherwise waits, which lets a tree
  save up for a Heavy instead of spending on a Light.
- **No constant `True`.** `True` was removed from the generated set, because `(If (True) a b)` is just
  `a` and only added dead structure. Older saved bots that contain it still load.

Two of these terminals, `StrongerThanNearestEnemy` and the worker ranks, encode human knowledge about
microRTS. They were added because evolution rarely assembled the equivalent logic from smaller
conditions, but it means part of an evolved bot's skill rests on hand design (Section M.8).

Two smaller mechanics sit inside the actions. When several workers are told to build in the same
cycle, build positions are reserved so that two workers never try to build on the same cell. And a
melee unit that stands diagonally next to a moving enemy, which it cannot hit, waits instead of
attacking when its unit ID is higher than the enemy's, so that the two units do not keep stepping
around each other. **[VERIFY: confirm this is the intended reason for the diagonal-standoff rule in
`Actions.attack`.]**

### M.2.4 Initial population

The initial population uses ramped half-and-half initialisation: tree depths are drawn between 2
and 6, and alternate individuals are grown "full" or "grow", with a terminal probability of 0.35 in grow
mode. Half of the initial trees are additionally wrapped in
`(If (CanHarvest) (HarvestResources) ...)`, so that half the population starts with a working economy
instead of waiting for evolution to discover harvesting. Duplicate trees are redrawn up to five times.

### M.2.5 Fitness function

Every individual plays every training case, a (map, opponent) pair, once from each side. A game ends
when one side is destroyed, at 2 000 cycles, or when neither side has issued an action for 300 cycles.
The last rule ends stalemates early and saves evaluation time. Each game is turned into a shaped score
between 0 and 1:

- a win scores 1;
- a draw scores 0.5 + 0.25 · *m*, so at least 0.25;
- a loss scores 0.2 · ((1 + *m*)/2 + *g*)/2, so at most 0.2;
- a crash scores 0,

where *m* ∈ [−1, 1] is the **exchange margin** and *g* is the **gathered share**. The exchange margin
compares the resource cost of enemy units destroyed with the cost of own units lost over the whole
game: +1 when only the enemy lost units, −1 when only the candidate did, 0 for an even trade. The
gathered share is the candidate's share of all resources delivered to a base (0.5 when neither side
mined). Because a loss never exceeds 0.2 and a draw is never below 0.25, no loss can outscore a draw.

Both shaping terms were added to fix flat fitness landscapes we observed:

- **Losses were indistinguishable.** An early version scored losses by the material left at the end
  of the game. A player loses when it has no units left, so this only measured unspent resources and
  every loss looked alike. Tracking units destroyed throughout the game fixed this: a loss that traded
  evenly now scores above a wipe-out.
- **Economy was invisible in a loss.** With the exchange margin alone, an all-in rush that lost to a
  scripted economy scored the same whatever it did. The gathered share separates two losses that
  traded alike, rewarding the one that kept its economy going.

The **combat score** of an individual is the harmonic mean of its per-case shaped scores, with
ε = 0.1 added to each term. A harmonic mean is dominated by its smallest terms, so a bot cannot hide a
weakness against one opponent behind strong results elsewhere. Individuals are ranked by combat
score, then by smaller tree size (a light parsimony pressure that only breaks ties), then by exchange
margin. The win rate reported throughout this part is the plain, unshaped fraction of training games
won; shaping is used only for selection.

A bot that throws an exception scores 0 for that game, and the failure is logged once per kind, so a
crashing tree is selected against without stopping the run.

### M.2.6 Selection and variation

The five best distinct trees are copied unchanged into the next generation. Every other slot is
filled by exactly one operator: subtree crossover (probability 0.6), subtree mutation (0.3) or a plain
copy (0.1). When mutation picks a terminal with a constant, it nudges the constant with probability
0.5 instead of replacing the node (Figures M.2 to M.4). The only hard structural limit is a maximum
depth of 15; an offspring that would exceed it is rejected and another crossover point is tried.

*Figure M.2. Crossover swaps a subtree for one of the same type from a second parent.
(`crossover.png`)*
*Figure M.3. Subtree mutation regrows a random subtree. (`mutation_subtree.png`)*
*Figure M.4. Constant mutation nudges one number. (`mutation_number.png`)*

Parents are chosen by **epsilon-lexicase selection** [19] on the per-case shaped scores rather than
by combat score. For each selection, the cases are put in a random order; at each case, only the
candidates within ε of the best score on that case survive, until one remains. ε is the median
absolute deviation of that case's scores over the population, so it needs no tuning. We chose
lexicase because the training cases differ greatly in difficulty: a tree that alone beats Mayari on
one map can still become a parent even if its average is poor, whereas under tournament selection on
the combat score it would usually be lost.

Offspring that duplicate a tree already in the new generation are redrawn, up to five times, to
keep the population diverse.

### M.2.7 Evolutionary parameters

**Table M.2. Parameters used in all twenty runs.** *(`table_params.png`)*

| Parameter | Value |
|---|---|
| Implementation | Plain Java (no GP library), microRTS engine from this repository, 8 evaluation threads |
| Unit type table | Version 2 (original, fine-tuned), move conflicts cancel both units |
| Population size | 100 (code default 1200; reduced so that ten runs fit in a 16-hour budget) |
| Generations | 200, stopping early if the best combat score improves by less than 0.005 in 100 generations |
| Initialisation | Ramped half-and-half, depths 2–6, terminal probability 0.35, half the trees harvest-seeded |
| Maximum tree depth | 15 |
| Elitism | 5 distinct trees |
| Selection | Epsilon-lexicase on per-case shaped scores |
| Operators | Subtree crossover 0.6, subtree mutation 0.3, copy 0.1; constant nudge 0.5 within mutation |
| Duplicate retries | 5 |
| Game length | 2 000 cycles; game ends after 300 cycles without any action |
| Fitness | Harmonic mean (ε = 0.1) of shaped scores; draw weight 0.25, loss weight 0.2 |
| Games per individual | 8 maps × 8 opponents × 2 sides = 128 |
| SBGP | *D*g = 4, *S*g = 10, *W*g = 10, *T*g = 6 (Section M.3) |

### M.2.8 Saving and simplifying the best tree

The best tree of the whole run, not just of the last generation, is kept. This matters for SBGP,
because retiring an area can drop the previous best from the population (Section M.3.3). Before it is
saved, the tree is simplified: branches that the conditions on the path already rule out are removed,
constant boolean structure is folded, and `If` nodes whose two branches are identical are replaced
by the branch. The simplifier only matches conditions by their exact text, so it can miss some
simplifications but never removes a branch that can run.

## M.3 Structure-based genetic programming

### M.3.1 The method

Ordinary GP tends to converge because the best trees come to share a root and the same function nodes
in their first few levels; after that, variation only reshuffles the lower levels. Structure-based GP
[18] names that shared top the **global area** and alternates between two phases, so that a run moves
on from an area once it stops paying off (Table M.3, Figure M.5).

**Table M.3. Phases of structure-based GP.** *(`table_phases.png`)*

| Phase | Lasts | Operators may change | Offspring rejected when |
|---|---|---|---|
| Explore | at least *S*g = 10 generations, and until the best tree is deeper than *D*g = 4 | levels 0 to *D*g | it shares *T*g = 6 or more nodes with an area already explored |
| Exploit | until the best combat score has not improved for *W*g = 10 generations | levels below *D*g | it does not contain the current area |

*Figure M.5. The explore and exploit phases on a small example tree. (`structure_phases.png`)*
*Figure M.6. The phase sequence over a run. (`structure_timeline.png`)*

At the end of an explore phase, levels 0 to *D*g of the best tree become the new global area. In
the following exploit phase, the area is fixed and only the levels below it are varied. When exploit
stalls, the area is stored in memory and the next explore phase may only keep offspring whose top is
different enough from every stored area. Similarity is counted top-down from the root: a node counts
only if it and all its ancestors match, by position, down to depth *D*g.

### M.3.2 Implementation

SBGP is a small class that the ordinary GP loop consults in three places, so both variants share all
other code: representation, fitness, selection, operators and evaluation. A single setting,
`structureBased`, switches it on.

- **Where operators may act.** Before crossover or mutation picks a point, the candidate points are
  filtered to the levels the current phase allows (levels 0 to *D*g in explore, below *D*g in
  exploit).
- **Which offspring are kept.** Each offspring is checked against the current phase's rule in
  Table M.3. A rejected offspring is redrawn, sharing the five retries used for duplicates. Elites
  must pass the same check.
- **When phases change.** After every generation, the phase machine looks at the best individual and
  decides whether to switch.

The phase and the number of areas explored are written to the per-generation log, which is what
Figure M.6 and Section M.3.4 are drawn from.

### M.3.3 Interpretations of the method

Applying the method to this setting required decisions that the paper does not settle for our case.
Each is a parameter or a line of code; Table M.4 lists them with the reason. *D*g, *S*g, *W*g and *T*g
take the tuned values from [18].

**Table M.4. Interpretations made when applying SBGP.** *(`table_sbgp_choices.png`)*

| Decision | Our choice | Reason |
|---|---|---|
| Area depth *D*g | 4, the paper's value | Best trees in the final generation held 48 to 1 610 nodes, and the explore rule below makes sure the best tree reaches below the area, so exploit always has levels to vary. |
| Ending an explore phase | After *S*g generations, but only once the best tree is deeper than *D*g | Exploit can only change levels below the area. A best tree no deeper than the area would be copied into the whole population with nothing left to vary. |
| Entering exploit | One seeding generation: only the best tree is kept as an elite and every offspring is bred from it, with operators already restricted below *D*g | Every tree then contains the area by construction, without grafting the area onto other individuals. |
| What "structure" means | Node symbols only; constants are content. `EnemyInRange 0.2` and `EnemyInRange 0.6` match | Otherwise a nudged constant would count as a new structure and the filter would be trivially passed. |
| Terminals in the similarity count | Counted | Our trees often have terminals within the top four levels. |
| Threshold *T*g | 6, not capped | With *D*g = 4, areas hold many more than six nodes, so the threshold can always be reached. |
| Exploit stagnation | Any increase in the best combat score resets the *W*g counter | The evaluation cache makes repeated scores identical, so "no change" is meaningful. Most training opponents are deterministic; see Section M.8 for the exception. |
| When no offspring passes the filter | After five failed attempts the last candidate is admitted anyway | Keeps the population size fixed. The filter is therefore soft: under heavy rejection, a few non-conforming trees can enter. |
| Best of run | The best tree of any generation | Retiring an area can drop the previous best from the population. |
| Run-level stopping | Separate from *W*g: stop after 100 generations without a 0.005 gain in combat score | Ends runs that have stalled across several areas. Used once, in batch 2, seed 3. |
| Resuming a run | Phase and area memory are not saved in checkpoints | Kept the checkpoint format simple. A resumed run starts a fresh explore phase with an empty memory (this happened once; Section M.5.3). |

### M.3.4 Observed behaviour

Across all twenty runs, the phase machine behaved as intended. Explore phases lasted a median of 10
generations, the minimum *S*g, and were extended beyond it only occasionally (longest 39 generations).
Exploit phases lasted a median of 16 to 17 generations, ranging from 3 to 77. Each run explored 3 to 8
areas (mean 5.4) and spent about a third of its generations (32–33%) in explore phases.

The filter left visible traces. Normally 98 of 100 trees in a generation were distinct, but in 208 of
the roughly 3 900 generations fewer than 90 were, and 179 of those were explore generations. In
explore, operators may only change the top four levels and must avoid every stored area, so retries
run out more often and duplicates or near-duplicates are admitted (Table M.4, soft filter). The
evaluation cache answered 24% to 25% of each generation's individuals without playing a game.

The best combat score improved in both phases: in about 200 of some 630 explore generations per batch
and in about 250 to 280 of some 1 300 exploit generations. Improvements during explore are expected,
because the run is searching new tops. These counts do not show whether SBGP finds better areas than
ordinary GP would; that needs the baseline (Section M.8).

## M.4 Tooling and reproducibility

Several tools make runs repeatable and the results checkable:

- **One settings object.** Every setting is a public field with a default, set from the command line
  or a properties file under the same name. Each run writes its complete settings to
  `manifest.properties`, so any run can be repeated from its own folder.
- **Per-generation log.** `metrics.jsonl` records, for every generation, the best combat score and
  win rate, the population's mean and spread, the best tree's size, the number of distinct and cached
  trees, the time taken, and the SBGP phase and area count. Every training figure in this part is drawn
  from these logs.
- **Checkpoints.** Every ten generations the population, the generation number, the settings and the
  random-number generator's exact state are saved. A resumed run continues the same random sequence;
  resuming refuses to start if the maps, opponents or unit table differ from the checkpoint.
- **Evaluation cache.** Each tree is stored in canonical text form with its per-case results. A tree
  that reappears (an elite, a copy, or an offspring identical to an earlier tree) is scored by lookup
  instead of 128 games.
- **Seeded evaluation.** Opponents that accept a seed (here only EconomyMilitaryRush) get one derived
  from a single evaluation seed, the case and the side, and the other scripted opponents are
  deterministic, so a tree's evaluation is the same in every generation. **[VERIFY: whether the competition bots loaded from jars (Mayari, Rojo, CoacAI)
  are deterministic; RandomBiasedAI is not seeded (Section M.8).]**
- **Batch runs and statistics.** `make train-batch RUNS=10 BATCH=<name>` runs seeds 1 to 10 into
  one folder, skipping runs already finished, and `make stats` (`GPStats`) collects every run's
  summary into one table with means and spreads.
- **Benchmarking a saved bot.** `make play` and `make holdout` (`GPPlay`) play a saved tree against a
  list of opponents and maps, from both sides, and report wins, draws and losses with 95% confidence
  intervals per matchup.
- **Figures.** `models/results_charts.py`, `models/report_figures.py` and `models/gp_figures.py`
  regenerate every table and chart in this part from the run folders and benchmark output;
  `models/visualize_gp_tree.py` draws any saved tree.

## M.5 Experimental setup

### M.5.1 Training and held-out sets

Opponents and maps were split into disjoint training and held-out sets (Table M.5). Evolution only
ever saw the training sets. `EightBasesWorkers16x16` was first a training map but made each evaluation
about four times slower, because Rojo and Mayari control many units on it, so it was moved to the
held-out set.

**Table M.5. Training and held-out sets.** *(`table_sets.png`)*

| | Training | Held out |
|---|---|---|
| Opponents | RandomBiasedAI, WorkerRush, LightRush, HeavyRush, RangedRush, Mayari, Rojo, EconomyLightRush | CoacAI, WorkerRushPlusPlus, EconomyRushBurster, EconomyMilitaryRush |
| Maps | basesWorkersBarracks8x8, basesWorkers8x8A, basesWorkers8x8Obstacle, basesWorkers12x12, SixBasesWorkers12x12, basesWorkers12x12F, melee16x16Mixed12, TwoBasesBarracks16x16 | basesWorkers10x10, basesWorkers12x12A, basesWorkers24x24, EightBasesWorkers16x16 |

### M.5.2 Evaluation

The best tree of each run plays 20 games per (map, opponent) pair, 10 from each side. Four test sets
are reported: training opponents on training maps, held-out opponents on training maps, training
opponents on held-out maps, and held-out opponents on held-out maps. Games use the same 2 000-cycle
cap as training. The main measure is the win rate; draws count as non-wins.

### M.5.3 Runs

Twenty runs were made with identical settings: seeds 1 to 10 on each of two machines. Batch 1 ran on
an 8-core desktop CPU; batch 2 on **[TODO: second machine's CPU]**. No run was selected or discarded.
One run, batch 1 seed 7, was interrupted at generation 165 and resumed from its generation-160
checkpoint. Its population and random state were restored exactly, but its SBGP memory restarted
empty (Table M.4); this run produced the best bot (Section M.7.3).

Held-out evaluation was run for the ten batch-1 bots; batch 2 has so far only been evaluated on
its training cases.

## M.6 Training results

### M.6.1 Training performance across seeds

Table M.6 summarises the twenty runs. The best tree of a run won on average 69.3% of its training games
(SD 4.7%), ranging from 62.1% to 76.2%. No run reached a non-zero worst case: every best tree lost
every game of at least one training matchup.

**Table M.6. Training results per batch (best tree of each run).** *(`table_batches.png`)*

| | Batch 1 | Batch 2 | All 20 runs |
|---|---|---|---|
| Training win rate, mean (SD) | 71.1% (5.2%) | 67.4% (3.6%) | 69.3% (4.7%) |
| Training win rate, range | 62.1–76.2% | 62.9–74.2% | 62.1–76.2% |
| Population mean win rate, final generation | 55.0% (13.9%) | 47.3% (11.8%) | — |
| Generations | 200 in all runs | 193.6 (one run stopped at 136) | — |
| Training time per run, mean (SD) | 68.9 min (22.7) | 152.6 min (120.2) | — |
| Total training time | 11.5 h | 25.4 h | 36.9 h |
| Global areas explored per run, mean | 5.1 | 5.7 | 5.4 |

Training times are summed from the per-generation log, because the summary file of the resumed run
counts only the time after the resume.

The two batches use the same code and settings, so a difference between them would reflect only seed
and machine. An exact two-sided Mann-Whitney U test on the per-run training win rates gives
U = 74.5, p = 0.065, which is not significant at α = 0.05; the twenty runs are therefore treated as one
sample. Batch 1 still had the higher median (71.7% against 66.8%), so the result is borderline rather
than clear-cut, and more seeds would be needed to rule out a machine effect such as timing
differences under the per-cycle budget.

In batch 1, most runs were still improving near the end: the best win rate was first reached on
average at generation 168, and five of ten runs set their best after generation 190. This
suggests the 200-generation budget, not the search, was the limit. One batch-2 run (seed 3) stalled at
generation 35 and was stopped by the stagnation rule at generation 136.

*Figure M.7. Training win rate of all 20 runs, by seed and batch. (`results_runs_both.png`)*
*Figure M.8. Win rate of the best tree over training, batch 1: each run in light blue, the average
in dark blue. (`results_winrate_time.png`)*

## M.7 Generalisation and the best bot

### M.7.1 Generalisation to held-out opponents and maps

The best tree from each of the ten batch-1 runs was evaluated on the three held-out test sets
(Table M.7, Figure M.9).

**Table M.7. Held-out win rate, batch 1 (mean and SD over 10 runs).** *(`table_heldout.png`)*

| Test set | Win rate |
|---|---|
| Training opponents, training maps | 71.1% (5.2%) |
| Held-out opponents, training maps | 60.2% (8.0%) |
| Training opponents, held-out maps | 50.2% (7.9%) |
| Held-out opponents, held-out maps | 53.4% (5.0%) |

*Figure M.9. Win rate per test set, mean ± SD over the 10 batch-1 runs. (`results_heldout_runs.png`)*

Compared with training, win rate falls by 10.9 percentage points against unseen opponents and by
20.9 points on unseen maps. New maps therefore cost about twice as much as new opponents, which
suggests that most of the over-fitting is to the maps, even though distance and count thresholds
are scaled by map size. The two effects do not simply add up: with both unseen opponents and unseen
maps the bots won 53.4%, slightly more than the 50.2% against training opponents on unseen maps.
Given the spread across runs (SD 5–8 points), this difference is within the noise.

### M.7.2 By opponent and map

On the fully held-out set, the bots beat the two economy scripts in most games
(EconomyRushBurster 82.5%, EconomyMilitaryRush 73.8%) but struggled against CoacAI (35.0%) and
WorkerRushPlusPlus (22.5%). By map, they won about two-thirds of their games on the three small and
medium maps (basesWorkers10x10 67.5%, basesWorkers12x12A 66.3%, EightBasesWorkers16x16 68.8%) but
only 11.3% on basesWorkers24x24, the only map larger than any seen in training. For the best bot
(Section M.7.3), all 20 of its 24×24 games against WorkerRushPlusPlus were draws at the 2 000-cycle
cap, which suggests the evolved strategy does not adjust its attack timing to longer travel
distances.

### M.7.3 The best evolved bot

The best training run (batch 1, seed 7, 76.2% training win rate) reduced to a tree of 51 nodes:

```
(If (GameTimeAtLeast 10)
    (If (And C (CanAttack))
        (If (GameTimeAtLeast 100) (AttackNearestEnemy) (HarvestResources))
        (If (CanAttack) (HarvestResources) (Train Worker)))
    (Train Heavy))
```

where *C* is a nested condition over worker ranks, resources, own military count and enemy distance.
The structure is readable as a worker strategy: buildings train workers; workers that satisfy *C*
harvest until cycle 100 and then attack the nearest enemy, while the rest keep harvesting. The bot
never builds barracks, so the `Train Heavy` branch can never succeed and is in effect an idle for the
first 10 cycles. A pure worker strategy fits where the bot does well and badly: it is strong on
small and medium maps and weak on the largest ones (TwoBasesBarracks16x16, 24×24), where opponents
have more time to build an economy and army before the workers arrive.

*Figure M.10. The best bot's tree, top four levels with condition C collapsed
(`best_bot_tree_small.png`); the full tree is `best_bot_tree.png`.*

**Table M.8. Best bot (seed 7), all four test sets.** *(`table_best_bot.png`)*

| Test set | Wins | Draws | Losses | Win rate |
|---|---|---|---|---|
| Training opponents, training maps | 882 | 71 | 327 | 68.9% |
| Held-out opponents, training maps | 390 | 70 | 180 | 60.9% |
| Training opponents, held-out maps | 348 | 60 | 232 | 54.4% |
| Held-out opponents, held-out maps | 170 | 30 | 120 | 53.1% |

Results per matchup are mostly all-or-nothing (20, 10 or 0 wins out of 20). Because the bot and most
opponents are deterministic, a matchup is often decided by map and starting side alone; 10 wins
usually means every game won from one side and lost from the other. The bot beat RandomBiasedAI in
every game on all twelve maps and beat HeavyRush and the economy rushes on most small and medium
maps. Its results against the strongest opponents depend heavily on the map: it won all 20 games
against Mayari on basesWorkers8x8A and basesWorkers12x12F but none on five other training maps, and
it won all 20 against CoacAI on basesWorkers12x12F and basesWorkers10x10 but none on eight of the
twelve maps. Surprisingly, it often lost to the plain WorkerRush (no wins on three training maps and
two held-out maps), which suggests that a mirror worker rush is decided by small timing details the
tree does not control. On the 24×24 map it won only against RandomBiasedAI and Rojo; its games
against WorkerRush and WorkerRushPlusPlus there all ended as draws at the cycle cap.

*Figure M.11. The best bot's win rate for every map and opponent (`results_matchups.png`).
`results_sets.png`, `results_opponents.png` and `results_maps.png` show the same bot's results
grouped by test set, opponent and map.*

### M.7.4 Computational cost

A full 200-generation run took a little over an hour on a single 8-core desktop CPU (batch 1 mean
68.9 min), and ten seeds took 11.5 hours, with no GPU involved. This compares with the roughly seventy
GPU-days reported for RAISocketAI [13]. The bots are not yet at the level of the strongest rule-based
bots, so the comparison is about the cost of reaching a competent bot, not an equal one. Inference is a
single tree walk per idle unit, far inside the 100 ms budget. **[TODO: measure the worst per-cycle
decision time of the best bot, as the other parts do.]**

## M.8 Limitations and outstanding work

- **No ordinary-GP baseline yet.** All twenty runs use structure-based GP. The ablation in Section 4
  needs the same experiment with `structureBased=false` (`make train-cgp`) before any claim about the
  structural component can be made.
- **Batch 2 not evaluated on held-out sets.** Only training win rates exist for it.
- **Worst case is zero in every run.** Each bot has at least one matchup it never wins, Mayari
  in particular.
- **RandomBiasedAI is not seeded.** The evaluation cache assumes a tree's games are repeatable. That
  holds for the scripted opponents and the seeded ones, but RandomBiasedAI draws from its own unseeded
  random-number generator, so a cached tree keeps whatever result it first got against it. Since the
  bots beat RandomBiasedAI in nearly every game, the effect on fitness is likely small.
- **Small population.** The population of 100 is a twelfth of the code default and of the team
  protocol, chosen to fit the compute budget.
- **Soft structure filter.** After five failed attempts a rejected offspring is admitted, so the
  explore and exploit rules hold for most but not all of the population (Section M.3.4).
- **One resumed run.** The best bot's run lost its SBGP area memory on resume.
- **Engine behaviour in the bots.** The `Idle` fallback attacks enemies in range, and the abstraction
  layer handles pathfinding and harvesting, so part of each bot's behaviour is not evolved.
- **Hand-designed terminals.** `StrongerThanNearestEnemy` and the worker ranks encode human
  knowledge, so part of the bots' behaviour rests on them rather than on evolution.
- **Panel differs from Section 4.2.** Mayari was used as a training opponent, not a held-out one,
  and TMA and the RL agent were not evaluated against the GP bots.

## M.9 References used in this part

Merge into the shared reference list. All other citations in this part already use the report's
numbering ([13] Goodfriend, [18] Scheepers and Pillay).

[19] W. La Cava, L. Spector, and K. Danai, "Epsilon-Lexicase Selection for Regression," in *Proc.
Genet. Evol. Comput. Conf. (GECCO '16)*, Denver, CO, USA, Jul. 2016, pp. 741–748, doi:
10.1145/2908812.2908898.

---

## Notes for the group (not part of the report)

1. **This part does not follow the team evaluation protocol in Part H.** Part H evaluates on nine team
   maps (basesWorkers8x8A, basesWorkersBarracks8x8, basesWorkers8x8Obstacle, basesWorkers12x12,
   basesWorkers12x12F, SixBasesWorkers12x12, melee16x16Mixed12, TwoBasesBarracks16x16,
   EightBasesWorkers16x16). Eight of those are this part's **training** maps, and Mayari and Rojo are
   this part's training opponents. Evaluating these bots on the team protocol would therefore mostly
   measure training performance. To compare with Part H, the bots need to be retrained on maps outside
   the team set, or the difference stated plainly wherever results sit side by side.
2. **Population size.** Part H lists a population of 1 200 as team protocol; this part used 100.
3. **4.2 Experimental Design** lists the held-out panel as CoacAI, Mayari, TMA and the RL agent. These
   runs held out CoacAI, WorkerRushPlusPlus, EconomyRushBurster and EconomyMilitaryRush.
4. **4.6 Technical Specifications** says "an established GP library". Part H uses ECJ; this part uses
   no library. The text should say which part uses which.
5. **4.6** says "NVIDIA GTX 5070"; it should be RTX 5070.
6. **4.3** says starting positions introduce variation between matches. The default maps have fixed
   starts; the variation comes from swapping sides and from the opponents' own randomness.
7. **3.5** still has "(Pillay, [year])"; this should cite [18].
8. **Bibliography**: the Harvard-style "REFERENCED / READ BUT NOT USED" lists sit above the IEEE list
   and should be removed before submission.
9. **Shared finding.** Part H also found that its evolved bots become worker rushes that never build a
   barracks. Worth one sentence in a shared discussion.
