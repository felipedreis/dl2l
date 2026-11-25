"""Replay of the planned Kalman operant cell (docs/plans/operant-kalman-and-stop-rule.md, section 4) over real outcome streams.

Each creature's EAT outcomes in p117_factorial (operant_updates, sample +-1 valence) are joined to drive_outcomes (creature, object type, action, time
within 100 ms) to rebuild the drive-reduction sample, the one the Kalman cell takes (-arousalVariation = -(sum of the drive changes)); an outcome with no
drive_outcomes row changed no drive, so its sample is 0 (eating a gray apple). The cell mirrors KalmanOperantEstimator.java and is scored one step ahead:
z = (v - Q_before) / sqrt(SE_before^2 + sigma^2), by the basis of the estimate before the update (pooled prior, observed). A first outcome of an
abstaining cell has no prediction and no z. Pass (the plan): mean z within +-0.1 and var z in [0.8, 1.25].

    python3 analysis/diagnostics/kalman_operant_replay.py [data_dir] [condition] [tau2 ...]
"""
import glob
import math
import sys
from collections import defaultdict

import duckdb
import numpy as np

S2_0, S2_MIN, DECAY, NU0 = 0.01, 0.0025, 0.995, 1.0
POOL_FLOOR = 16.0  # multiple of S2_0 under the pooled variance; 16 is the value chosen on this replay (KalmanOperantEstimator.DEFAULT_POOL_FLOOR)


class Kalman:
    def __init__(self, tau2):
        self.tau2 = tau2
        self.cells = {}

    def spread(self, c):
        n, m2 = c["n"], c["m2"]
        s2 = max(0.0, m2) / (n - 1) if n > 1 else 0.0
        blended = (NU0 * S2_0 + (n - 1) * s2) / (NU0 + n - 1) if n > 1 else S2_0
        return max(blended, S2_MIN)

    def pool(self, action, key):
        ms = [c["m"] for k, c in self.cells.items() if k[1] == action and k != key]
        if not ms:
            return None
        mean = sum(ms) / len(ms)
        between = sum((x - mean) ** 2 for x in ms) / (len(ms) - 1) if len(ms) > 1 else 0.0
        return mean, max(between, POOL_FLOOR * S2_0) + self.tau2

    def predict(self, key):
        c = self.cells.get(key)
        if c:
            return "observed", c["m"], math.sqrt(c["p"]), math.sqrt(self.spread(c))
        pl = self.pool(key[1], key)
        if pl is None:
            return "abstain", None, None, None
        return "pooled", pl[0], math.sqrt(pl[1]), math.sqrt(S2_0)

    def record(self, key, v):
        pred = self.predict(key)
        for k, c in self.cells.items():
            if k != key and k[0] == key[0]:
                c["p"] += self.tau2
        c = self.cells.get(key)
        if c is None:
            c = {"m": 0.0, "p": 0.0, "n": 0.0, "mean": 0.0, "m2": 0.0}
            pl = self.pool(key[1], key)
            self.cells[key] = c
            if pl is None:
                c["m"], c["p"] = v, max(S2_0, S2_MIN)
                self._spread(c, v)
                return pred
            c["m"], c["p"] = pl
        else:
            c["p"] += self.tau2
        k = c["p"] / (c["p"] + self.spread(c))
        c["m"] += k * (v - c["m"])
        c["p"] *= 1 - k
        self._spread(c, v)
        return pred

    @staticmethod
    def _spread(c, v):
        c["n"] = c["n"] * DECAY + 1
        d = v - c["mean"]
        c["mean"] += d / c["n"]
        c["m2"] = DECAY * c["m2"] + d * (v - c["mean"])


def streams(trial_dir):
    con = duckdb.connect()
    q = f"""
    SELECT o.creature_key, o.seq, o.object_type, o.action, o.sample AS valence,
           COALESCE(-(d.dh + d.ds + d.dp + d.dt), 0.0) AS v, d.seq IS NOT NULL AS matched
    FROM '{trial_dir}/operant_updates.parquet' o
    LEFT JOIN (SELECT creature_key, object_type, action, time, min(seq) AS seq,
                      sum(d_hunger) dh, sum(d_sleep) ds, sum(d_pain) dp, sum(d_tedium) dt
               FROM '{trial_dir}/drive_outcomes.parquet' WHERE action = 'EAT' GROUP BY ALL) d
      ON o.creature_key = d.creature_key AND o.object_type = d.object_type AND o.action = d.action
     AND abs(o.time_ms - d.time) <= 100
    ORDER BY o.creature_key, o.seq
    """
    return con.sql(q).fetchall()


def replay(trials, tau2):
    z = defaultdict(list)
    cell_vals = defaultdict(set)
    rows = []
    for t in trials:
        k = {}
        for cr, seq, obj, act, val, v, matched in streams(t):
            if cr not in k:
                k[cr] = Kalman(tau2)
            basis, q, se, sg = k[cr].record((obj, act), v)
            cell_vals[(t, cr, obj, act)].add(round(v, 9))
            if basis != "abstain":
                rows.append((basis, (v - q) / math.sqrt(se ** 2 + sg ** 2), (obj, act), v))
    # the ladder analysis leaves out cells whose outcomes are all equal (eating a gray apple: always 0); so does this
    varying = {oa for oa in {r[2] for r in rows} if len({r[3] for r in rows if r[2] == oa}) > 1}
    for basis, zz, oa, v in rows:
        z[basis].append(zz)
        if oa in varying:
            z[basis + "_varying"].append(zz)
    return z


if __name__ == "__main__":
    data = sys.argv[1] if len(sys.argv) > 1 else "ml/data_p117_factorial"
    cond = sys.argv[2] if len(sys.argv) > 2 else "a3_L0T0"
    import os
    POOL_FLOOR = float(os.environ.get("POOL_FLOOR", "16"))
    taus = [float(x) for x in sys.argv[3:]] or [8e-4]
    trials = sorted(g for g in glob.glob(f"{data}/{cond}/trial_*") if "failed" not in g)
    print(f"{cond}: {len(trials)} trials")
    for tau2 in taus:
        z = replay(trials, tau2)
        for basis in ("observed", "observed_varying", "pooled", "pooled_varying"):
            a = np.array(z[basis])
            if len(a):
                ok = abs(a.mean()) <= 0.1 and 0.8 <= a.var() <= 1.25
                print(f"  tau2={tau2:g} {basis:9s} n={len(a):6d}  mean z={a.mean():+.3f}  var z={a.var():.3f}  {'PASS' if ok else 'out of band'}")
