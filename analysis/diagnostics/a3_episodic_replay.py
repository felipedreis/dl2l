"""Replays the episodic estimator (#113) on real closed episodes: would it be decisive, and for the right object?

Takes the `episode_returns` of one creature of an existing A2 run, in write order. The first half fills the store (per action FIFO of 2000,
as EpisodicEngramStore); the second half supplies the queries: a decision at a perceived object, with its drive levels. For each query the
estimator answers for the two candidates the CAMPOS tendency leaves at a distance, APPROACH and WANDER, as EpisodicEstimator does: the key is
the encoder's (one-hot object type, distance / 150, contact, drive levels; the bearing is not in `episode_returns` and is held fixed),
m = 10 nearest, omega = exp(-D^2 / 2 b^2), b the median distance, Q = weighted mean of V(G | h_now), n_eff, SE = sqrt(sigma^2 / n_eff + beta^2),
beta = 0.5 * Dbar. Variants differ in the prior spread sigma0 of the blend and in beta:

    paper   sigma = weighted sd (no prior)              beta on
    s1.0    prior sigma0 = 1.0 (the first implementation) beta on
    s0.1    prior sigma0 = 0.1 (one meal's worth of value) beta on
    s0.1-nb prior sigma0 = 0.1                            beta off  (what the SE would be if distance were in value units)

Reported per object class (food / gray): decisiveness of the best candidate over the other, share of queries with conf >= 0.8, and how often
the best candidate is APPROACH. Also the typical neighbour distance Dbar and the SE, against the value scale.

    /opt/homebrew/bin/python3.13 analysis/diagnostics/a3_episodic_replay.py [trial_dir]
"""
import math
import sys
from collections import deque

import numpy as np
import pandas as pd

HSTAR, VISION, M, L, CAP = 0.18, 150.0, 10, 0.5, 2000
TYPES = ["RED_APPLE", "GREEN_APPLE", "GRAY_APPLE", "ROTTEN_APPLE", "CACTUS", "ALOE", "self"]
RADIUS = {"RED_APPLE": 10, "GREEN_APPLE": 15, "GRAY_APPLE": 10, "ROTTEN_APPLE": 10, "CACTUS": 12, "ALOE": 10}
LAMBDA = 0.5


def key(obj, dist, h):
    n_p, n_h = 11, 4
    one = np.array([1.0 if t == obj else 0.0 for t in TYPES])
    one = (one - 1 / 7) / math.sqrt(1 / 7 * 6 / 7)
    ratio = (min(1.0, dist / VISION) - 0.5) / math.sqrt(1 / 12)
    contact = 1.0 if obj != "self" and dist <= 10 + RADIUS.get(obj, 0) else 0.0
    p = np.concatenate([one, [ratio, 0.0, 1.0 / math.sqrt(0.5), (contact - 0.1) / math.sqrt(0.09)]]) / math.sqrt(n_p)
    hh = (np.asarray(h) - 3.5) / (7 / math.sqrt(12)) * LAMBDA / math.sqrt(n_h)
    return np.concatenate([p, hh])


def vfun(g, h):
    d0 = np.maximum(0, np.asarray(h) - HSTAR)
    d1 = np.maximum(0, np.asarray(h) + g - HSTAR)
    return np.sqrt((d0 ** 2).sum(-1)) - np.sqrt((d1 ** 2).sum(-1))


class Store:
    def __init__(self):
        self.k, self.g, self.b = {}, {}, {}

    def add(self, a, k, g, b):
        self.k.setdefault(a, deque(maxlen=CAP)).append(k)
        self.g.setdefault(a, deque(maxlen=CAP)).append(g)
        self.b.setdefault(a, deque(maxlen=CAP)).append(b)

    def est(self, a, q, h, variant):
        if a not in self.k:
            return 0.0, 1.0, 0.0, 0.0
        K, G = np.array(self.k[a]), np.array(self.g[a])
        D = np.linalg.norm(K - q, axis=1)
        idx = np.argsort(D)[:M]
        Dn, v = D[idx], vfun(G[idx], np.tile(h, (len(idx), 1)))
        b = np.median(Dn)
        w = np.exp(-Dn ** 2 / (2 * b * b)) if b > 1e-12 else np.ones_like(Dn)
        sw, Q = w.sum(), (w * v).sum() / w.sum()
        neff = sw ** 2 / (w ** 2).sum()
        s2 = ((w * (v - Q) ** 2).sum() / sw) * (neff / (neff - 1)) if neff > 1 else 0.0
        sigma0 = {"s1.0": 1.0, "s0.1": 0.1, "s0.1-nb": 0.1}.get(variant)
        sigma2 = s2 if sigma0 is None else (sigma0 ** 2 + (neff - 1) * s2) / (1 + neff - 1)
        beta = 0.0 if variant.endswith("-nb") else L * (w * Dn).sum() / sw
        return Q, math.sqrt(sigma2 / neff + beta ** 2), neff, (w * Dn).sum() / sw


def phi(x):
    return 0.5 * math.erfc(-x / math.sqrt(2))


def main(path):
    df = pd.read_parquet(f"{path}/episode_returns.parquet")
    cr = df.creature_key.iloc[0]
    df = df[df.creature_key == cr].sort_values("seq")
    df["object"] = df.object_type.fillna("self")
    half = len(df) // 2
    train, test = df.iloc[:half], df.iloc[half:]
    store = Store()
    for r in train.itertuples():
        h = [r.h_hunger, r.h_sleep, r.h_pain, r.h_tedium]
        g = np.array([r.g_hunger, r.g_sleep, r.g_pain, r.g_tedium])
        bootstrap = 0.0 if pd.isna(r.bootstrap) else r.bootstrap * 0   # bootstrap term omitted in the replay (small)
        store.add(r.action, key(r.object, r.distance if not pd.isna(r.distance) else 0.0, h), g, bootstrap)
    # queries: decisions at a perceived food or gray apple, not at contact
    q = test[test.object.isin(["RED_APPLE", "GREEN_APPLE", "GRAY_APPLE"]) & (test.distance > 25) & test.action.isin(["APPROACH", "WANDER"])]
    q = q.sample(min(3000, len(q)), random_state=1)
    print(f"creature {cr}: store {half:,} steps ({', '.join(f'{a} {len(v)}' for a, v in store.k.items())}), {len(q)} queries\n")
    print(f"{'variant':8s} {'object':6s} {'conf mean':>9s} {'conf>=.8':>9s} {'best=APPR':>9s} {'dQ':>8s} {'SE(best)':>9s} {'Dbar':>6s} {'n_eff':>6s}")
    for variant in ["paper", "s1.0", "s0.1", "s0.1-nb"]:
        for cls, objs in [("food", ["RED_APPLE", "GREEN_APPLE"]), ("gray", ["GRAY_APPLE"])]:
            rows = []
            for r in q[q.object.isin(objs)].itertuples():
                h = [r.h_hunger, r.h_sleep, r.h_pain, r.h_tedium]
                k = key(r.object, r.distance, h)
                e = {a: store.est(a, k, h, variant) for a in ("APPROACH", "WANDER")}
                (qa, sa, na, da), (qw, sw_, nw, dw) = e["APPROACH"], e["WANDER"]
                best = "APPROACH" if qa >= qw else "WANDER"
                gap = abs(qa - qw)
                conf = 0.5 if gap == 0 else phi(gap / math.sqrt(sa * sa + sw_ * sw_))
                rows.append((conf, best == "APPROACH", qa - qw, (sa if best == "APPROACH" else sw_), da, na))
            a = np.array(rows, float)
            print(f"{variant:8s} {cls:6s} {a[:, 0].mean():9.3f} {(a[:, 0] >= .8).mean():9.3f} {a[:, 1].mean():9.3f} {a[:, 2].mean():8.4f} "
                  f"{a[:, 3].mean():9.4f} {a[:, 4].mean():6.3f} {a[:, 5].mean():6.2f}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "ml/data_p107_operant/a2n/trial_1")
