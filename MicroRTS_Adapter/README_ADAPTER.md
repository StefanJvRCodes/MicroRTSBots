# Adapter-RL for the GP bot

Implementation of *Adapter-RL: Adaptation of Any Agent Using Reinforcement Learning*
(Jin, Slabaugh, Lucas, IEEE ToG 2026) with an evolved GP tree as the base agent.
Code: `src/ai/custom/adapter/`.

## Idea

The GP tree stays frozen. For every unit that is free to act, the tree's output
vector (7 cells: move to enemy, attack, move to resource, harvest, return,
produce worker, produce combat unit) becomes a distribution, and the adapter
only adds logits to those 7 cells (the additive policy adjuster, paper eq. 12):

```
base logits  = onehot: 1/T for the tree's choice, 0 for other applicable cells, -inf for cells that don't apply
final logits = base logits + adapter(unit observation)
cell         ~ softmax(final logits)        -> turned into a concrete action exactly as the GP bot does
```

The adapter is trained with PPO (paper Algorithm 1). Gradients only reach the
adapter: the base logits are constants.

## Run

The quickest way is the `adapt` command in `MicroRTS_ResearchSetup`. Use
`adapt.cmd` from cmd or PowerShell, or `./adapt` from Git Bash. It compiles
this project first:

```
adapt <bot.ser | stefan> <maps folder> <opponents folder> [--opponents a,b] [--builtin a,b]
      [--updates N] [--games N] [--temperature T] [--max-cycles N] [--threads N]
      [--run DIR] [--resume] [--config FILE]
```

- **Paths:** relative paths are resolved from the folder you run the command in.
- **Defaults:** everything not given comes from `adapter_parameters.txt`.
- **Run folder:** `adapter_runs/<bot>__<maps>__<opponents>`. To continue after Ctrl+C, run the same command with `--resume`.
- **Opponent JARs:** these bundle many helper bots and some don't work: Coac fails to load, and Droplet, Izanagi and Tiamat take 18–33 s per game. So `--opponents mayari,Rojo` picks specific classes.
- **No JARs:** `none` as the opponents folder trains only against `--builtin` bots.

Or run the trainer directly with the parameters file:

```
java -cp "bin;lib/*" ai.custom.adapter.AdapterTrainer adapter_parameters.txt
```

Settings are in `adapter_parameters.txt`. Each run folder (`run folder:`) gets:

- `training_log.csv`: one row per update. Row 0 is the baseline, the base tree playing alone.
- `adapter_latest.ser`: the model after the latest update.
- `checkpoint.ser`: lets you stop with Ctrl+C and continue with `resume: true`.
- `adapter_final.ser`: the trained model, written at the end.

A model file holds the base tree too, so it always plays with the base it was trained on.

To use a trained model as a normal bot (in tournaments, for example), use the
`ai.custom.adapter.AdapterBot(UnitTypeTable)` constructor with
`-Dadapter.model=path/to/adapter_final.ser`. The default path is
`adapter_runs/default/adapter_final.ser`.

## Final evaluation

After the last update, the trainer plays the trained adapter and the base tree
alone against `final evaluation opponents` (default Coac, Rojo, mayari) on every
map. Each plays `final evaluation games` games (default 20) per opponent per map.

For each opponent and map it prints a table and saves it to
`final_evaluation.txt` in the run folder. The table shows:

- **Win rate:** (wins + 0.5 × draws) / games.
- **Std:** the standard deviation of the per-game score (1 for a win, 0.5 for a draw, 0 for a loss).
- **W/D/L:** wins, draws and losses.

It also shows the average for each opponent over all maps, and the overall average.

To evaluate a saved model again, for example with more games, run this from `MicroRTS_Adapter`:

```
java -cp "bin;lib/*" ai.custom.adapter.AdapterEvaluator <model.ser> [maps folder] [games] [opponents]
```

It uses `adapter_parameters.txt` for anything not given and saves `evaluation_<timestamp>.txt` next to the
model. Besides the final evaluation's columns, it also reports mean game length and errored games.

GP training (`ai.custom.GP_bot.Main`) does the same for its final generation,
with these differences:

- **Who plays:** every individual plays `final evaluation games` games (default 1)
  per opponent per map.
- **What's reported:** the population average, the standard deviation across
  individuals, the best individual's win rate, and the win rate of the
  individual with the best overall result.
- **Where it's saved:** `final_evaluation.txt`, and `final_evaluation_population.csv`
  with every individual's numbers.

**Coac** (`ai.coac.CoacAI`) only works when `lib/Coac.jar` is on the classpath. It
adds a class to the project's own `ai.abstraction` package, which fails when the
JAR is loaded from `opponents/` by a separate class loader. Use it by the name
`Coac` in `builtin opponents:` or `final evaluation opponents:`.

## Components

| File | What it does |
|---|---|
| `AdapterBot` | The base tree plus the adapter. Combines their logits (APA), samples a cell and records decisions for PPO. |
| `AdapterModel` | The base tree, its mode and temperature, the policy network (adapter) and the value network. |
| `AdapterObservation` | Per-unit adapter input: the tree's 7 features, the base's choice, which cells apply, unit type/HP/cargo, position, time, resources, offsets to the nearest enemy and resource, and a 7x7 map patch with 8 channels. Value input: an 8x8 pooled map plus global counts. |
| `AdapterTrainer` | PPO: parallel games, shaped reward, GAE, a clipped joint-ratio objective, an entropy bonus, the value loss, gradient-norm clipping and Adam. |
| `Mlp` | A small hand-written network (ReLU MLP, backprop, Adam). Gradients were checked numerically (worst relative error 1.6e-7). |

Paper settings used: gamma 0.99, GAE lambda 0.95, clip 0.2, value coef 0.5,
Adam 2.5e-4, 500 updates, hidden width 256. The shaped reward uses the
gym-microrts weights the paper's code uses: win 10, harvest 1, worker 1,
building 0.2, attack 1, combat unit 4.

## Differences from the paper

- **Action space.** The paper adapts the full gym-microrts action (unit, action type,
  directions, attack target). Here the adapter adapts only the tree's 7 output
  cells, so the GP bot's cell-to-action mapping is unchanged.
- **Adapter input.** The paper uses a CNN over the whole map. Here it is an MLP over
  per-unit features plus a local patch, because decisions are made per unit.
- **Temperature: 0.25 instead of the paper's 1/100.** In the authors' code the
  base distribution is `onehot * 100`, the same as here. It works for them
  because in gym-microrts one unit acts per step, and whenever the base bot
  issues no action for that unit (or for an action component), the base
  distribution is all zeros and the adapter decides alone. Our tree scores every
  unit on every step. With 1/T = 100, the other cells get probability about
  e^-100: they are never sampled, the policy gradient is about 0, and nothing is
  learned (measured: entropy 0, policy loss 0). With T = 0.25 the base cell's
  logit is 4, which keeps the base's choice about 90% of the time while still
  exploring. The paper's sweep (1/2 to 1/1000) shows the method is robust to T.

## First result

Run `adapter_runs/test_t025`: the base is the StefanFirstBot tree, the task is basesWorkers8x8 vs WorkerRush,
with 40 updates of 16 games (about 1 minute):

- Base tree alone: 0% wins.
- Adapter, during training: 19–31% from update 20 on.
- Adapter, 100 fresh games with `AdapterBot`: 20% wins.
