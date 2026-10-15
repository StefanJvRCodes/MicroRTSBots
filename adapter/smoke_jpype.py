"""JPype smoke test: start the JVM on our classpath and step microRTS cycle by cycle
from Python. Uses only engine classes (no project code), so it isolates the bridge."""
import glob, os, time
import jpype

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)  # maps load by relative path
cp = [os.path.join(ROOT, "out")]
cp += glob.glob(os.path.join(ROOT, "lib", "*.jar"))
cp += glob.glob(os.path.join(ROOT, "lib", "bots", "*.jar"))
jpype.startJVM(classpath=cp)
print("JVM:", jpype.getDefaultJVMPath())

UnitTypeTable     = jpype.JClass("rts.units.UnitTypeTable")
PhysicalGameState = jpype.JClass("rts.PhysicalGameState")
GameState         = jpype.JClass("rts.GameState")
WorkerRush        = jpype.JClass("ai.abstraction.WorkerRush")
BFSPathFinding    = jpype.JClass("ai.abstraction.pathfinding.BFSPathFinding")
RandomBiasedAI    = jpype.JClass("ai.RandomBiasedAI")

MAP, GAMES, MAXCYCLES = "maps/8x8/basesWorkers8x8.xml", 5, 3000

total, t0 = 0, time.perf_counter()
for g in range(GAMES):
    utt = UnitTypeTable()
    gs = GameState(PhysicalGameState.load(MAP, utt), utt)
    ai0, ai1 = WorkerRush(utt, BFSPathFinding()), RandomBiasedAI()
    over = False
    while not over and gs.getTime() < MAXCYCLES:
        a0 = ai0.getAction(0, gs)
        a1 = ai1.getAction(1, gs)
        gs.issueSafe(a0); gs.issueSafe(a1)
        over = bool(gs.cycle())
    total += gs.getTime()
    print(f"game {g}: winner {gs.winner()} at cycle {gs.getTime()}")
dt = time.perf_counter() - t0
print(f"{GAMES} games, {total} cycles in {dt:.2f} s -> {total/dt:.0f} cycles/s driven from Python")