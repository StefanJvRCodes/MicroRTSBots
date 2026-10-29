import csv, sys, random, statistics as st
from collections import Counter, defaultdict

DIR = r"results-chpc\results\teameval"
FILES = {"GP": DIR + r"\all-team.csv", "SBGP": DIR + r"\all-team-sbgp.csv"}

def macro_by_seed(path):
    vals, errs = Counter(), 0
    tally = defaultdict(lambda: defaultdict(lambda: [0, 0]))   # seed -> opp -> [wins, games]
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            if (r.get("error") or "").strip():
                errs += 1; continue
            res = r["result"].strip().upper(); vals[res] += 1
            t = tally[r["seed"]][r["opponent"]]
            t[0] += res.startswith("W"); t[1] += 1
    print(f"  {path}: result values {dict(vals)}, error rows skipped {errs}")
    return {s: 100 * st.mean(w / g for w, g in opps.values()) for s, opps in tally.items()}

groups = {}
for name, path in FILES.items():
    m = macro_by_seed(path); groups[name] = list(m.values())
    v = groups[name]
    print(f"{name}: n={len(v)} mean={st.mean(v):.1f} sd={st.stdev(v):.1f} "
          f"median={st.median(v):.1f} min={min(v):.1f} max={max(v):.1f}")

def bf_F(gs):
    z = [[abs(x - st.median(g)) for x in g] for g in gs]
    allz = [x for g in z for x in g]; gm = st.mean(allz); k, N = len(z), len(allz)
    ssb = sum(len(g) * (st.mean(g) - gm) ** 2 for g in z)
    ssw = sum((x - st.mean(g)) ** 2 for g in z for x in g)
    return (ssb / (k - 1)) / (ssw / (N - k)), k - 1, N - k

a, b = groups["GP"], groups["SBGP"]
F, d1, d2 = bf_F([a, b])
print(f"\nBrown-Forsythe (Levene, median-centred): F({d1},{d2}) = {F:.3f}")
try:
    from scipy.stats import levene
    print(f"  scipy p = {levene(a, b, center='median').pvalue:.4f}")
except ImportError:
    rng, pooled, hits, N = random.Random(1), a + b, 0, 20000
    for _ in range(N):
        rng.shuffle(pooled)
        hits += bf_F([pooled[:len(a)], pooled[len(a):]])[0] >= F
    print(f"  permutation p = {hits / N:.4f} ({N} permutations; scipy not installed)")
print(f"  variance ratio GP/SBGP = {st.variance(a) / st.variance(b):.2f}")