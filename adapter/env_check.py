"""Drive rl.AdapterEnv from Python: base codes on a few games, then random masked codes,
timing decision steps/s including the JPype -> numpy conversion."""
import glob, os, time
import numpy as np
import jpype

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
cp = [os.path.join(ROOT, "out")]
cp += glob.glob(os.path.join(ROOT, "lib", "*.jar"))
cp += glob.glob(os.path.join(ROOT, "lib", "bots", "*.jar"))
jpype.startJVM(classpath=cp)

AdapterEnv = jpype.JClass("rl.AdapterEnv")
JIntArray = jpype.JArray(jpype.JInt)
SPEC = "results/best-seed5242-20261012-160727-pruned.ind"
N = 86

def observe(env):
    h, w = env.height(), env.width()
    obs = np.asarray(env.obs(), dtype=np.float32).reshape(env.channels(), h, w)
    mask = np.asarray(env.mask(), dtype=np.int8).reshape(h * w, N).astype(bool)
    base = np.asarray(env.baseCode(), dtype=np.int32)
    return obs, mask, base

env = AdapterEnv(SPEC, 4000)

print("base codes (should match Bench/Panel results for this bot):")
for m in ["maps/8x8/basesWorkers8x8.xml", "maps/16x16/basesWorkers16x16B.xml"]:
    for side in (0, 1):
        env.reset(m, "WorkerRush", side)
        steps = 0
        while not env.done():
            obs, mask, base = observe(env)
            env.step(JIntArray(base))
            steps += 1
        print(f"  {os.path.basename(m)} side {side}: winner {env.winner()} at cycle {env.time()}, {steps} decision steps")

rng = np.random.default_rng(20261014)
t0, steps, games = time.perf_counter(), 0, 0
for m in ["maps/8x8/basesWorkers8x8.xml", "maps/16x16/basesWorkers16x16B.xml"]:
    for opp in ["WorkerRush", "RandomBiasedAI"]:
        for side in (0, 1):
            env.reset(m, opp, side)
            while not env.done():
                obs, mask, base = observe(env)
                codes = np.full(base.shape, -1, dtype=np.int32)
                for c in np.flatnonzero(base >= 0):
                    codes[c] = rng.choice(np.flatnonzero(mask[c]))
                env.step(JIntArray(codes))
                steps += 1
            games += 1
dt = time.perf_counter() - t0
print(f"random codes: {games} games, {steps} decision steps in {dt:.2f} s -> {steps/dt:.0f} steps/s from Python")
print("diagnostics:", env.diagnostics())