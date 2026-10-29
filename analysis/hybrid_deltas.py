import csv, glob, os, re, statistics as st
from collections import defaultdict
from scipy.stats import wilcoxon

ROOT = r"results-chpc\results"
OPPS = ["RandomBiasedAI","WorkerRush","LightRush","HeavyRush","RangedRush",
        "EconomyLightRush","mayari","Rojo","Droplet","Coac"]

def rates(rows):
    t = defaultdict(lambda: [0, 0])
    for r in rows:
        t[r["opponent"]][0] += r["result"].strip().upper().startswith("W"); t[r["opponent"]][1] += 1
    return {o: 100 * w / g for o, (w, g) in t.items()}

base_rows = defaultdict(list)
with open(ROOT + r"\teameval\all-team-sbgp.csv", newline="") as f:
    for r in csv.DictReader(f):
        if not (r.get("error") or "").strip(): base_rows[r["seed"]].append(r)
base = {s: rates(rows) for s, rows in base_rows.items()}

hyb = {}
for d in glob.glob(ROOT + r"\adapter-team\sbgp-seed*"):
    seed = re.search(r"seed(\d+)", d).group(1)
    files = sorted(glob.glob(d + r"\eval-adapter-final-0300-team-*.csv"))
    if not files: print(f"  seed {seed}: NO eval csv"); continue
    if len(files) > 1: print(f"  seed {seed}: {len(files)} eval csvs, using latest")
    with open(files[-1], newline="") as f: hyb[seed] = rates(list(csv.DictReader(f)))

seeds = sorted(s for s in hyb if s in base)
missing = sorted(set(hyb) - set(base))
if missing: print("  hybrid seeds without a base:", missing)
print(f"paired seeds: n={len(seeds)}")

def holm(ps):
    order = sorted(range(len(ps)), key=lambda i: ps[i]); adj, run = [0]*len(ps), 0
    for k, i in enumerate(order):
        run = max(run, min(1, (len(ps) - k) * ps[i])); adj[i] = run
    return adj

def wp(x, y):
    try: return wilcoxon(y, x).pvalue
    except ValueError: return 1.0   # all differences zero

mb = [st.mean(base[s][o] for o in OPPS) for s in seeds]
mh = [st.mean(hyb[s][o] for o in OPPS) for s in seeds]
dm = [h - b for b, h in zip(mb, mh)]
print(f"MACRO base {st.mean(mb):.1f}  hybrid {st.mean(mh):.1f}  delta {st.mean(dm):+.1f} "
      f"(median {st.median(dm):+.1f})  better/equal/worse "
      f"{sum(d>0.05 for d in dm)}/{sum(abs(d)<=0.05 for d in dm)}/{sum(d<-0.05 for d in dm)}  "
      f"Wilcoxon p={wp(mb, mh):.4f}")

rows, ps = [], []
for o in OPPS:
    b = [base[s][o] for s in seeds]; h = [hyb[s][o] for s in seeds]; d = [y - x for x, y in zip(b, h)]
    p = wp(b, h); ps.append(p)
    rows.append([o, st.mean(b), st.mean(h), st.mean(d), sum(x>0 for x in d), sum(x==0 for x in d), sum(x<0 for x in d), p])
for r, a in zip(rows, holm(ps)): r.append(a)

print(f"\n{'opponent':<18}{'base':>7}{'hybrid':>8}{'delta':>8}   +/=/-     p      p_holm")
for o, b, h, d, up, eq, dn, p, a in rows:
    print(f"{o:<18}{b:7.1f}{h:8.1f}{d:+8.1f}   {up:>2}/{eq:>2}/{dn:<2}  {p:7.4f}  {a:7.4f}{'  *' if a < 0.05 else ''}")

os.makedirs("analysis", exist_ok=True)
with open(r"analysis\hybrid_deltas.csv", "w", newline="") as f:
    w = csv.writer(f); w.writerow(["opponent","base_mean","hybrid_mean","delta_mean","n_up","n_equal","n_down","p_wilcoxon","p_holm"])
    for r in rows: w.writerow([r[0]] + [f"{x:.4f}" if isinstance(x, float) else x for x in r[1:]])
    w.writerow(["MACRO", f"{st.mean(mb):.4f}", f"{st.mean(mh):.4f}", f"{st.mean(dm):.4f}", "", "", "", f"{wp(mb, mh):.4f}", ""])
print("\nwrote analysis\\hybrid_deltas.csv")