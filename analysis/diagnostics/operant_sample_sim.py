"""Offline check of the A2 operant table before a cluster run: frozen vs magnitude sample vs valence sample.

Mirrors the code exactly where it matters: OperantEstimator (Welford with fading 0.995, Q = (n*mean + kappa*Q0)/(n+kappa), kappa 1,
Q0 0, sigma blended with prior sigma0 1 by NU0 1 pseudo-observation, SE = sigma/sqrt(n+kappa)) and Arbiter.decide (decisiveness
Phi((Q1-Q2)/sqrt(SE1^2+SE2^2)) on the two highest Q, theta 0.8; epsilon floor 0.03; Thompson draw N(Q, (tau*SE)^2), tau 1).
The world is a stand-in, calibrated on the frozen scarce-world run: each decision is either at contact with one object (candidates
EAT and WANDER on it, after the hunger tendency filter) or not (APPROACH and WANDER on one seen object, or WANDER on self alone).
Only EAT produces an outcome (D2): red +0.2, green +0.5, gray 0 drive reduction. No hunger dynamics, no death: it tests the mechanism.

    python3 analysis/diagnostics/operant_sample_sim.py
"""
import math

import numpy as np

KAPPA, Q0, SIGMA0, NU0, DECAY, THETA, EPS, TAU = 1.0, 0.0, 1.0, 1.0, 0.995, 0.8, 0.03, 1.0
GAIN = {"RED": 0.2, "GREEN": 0.5, "GRAY": 0.0}


class Table:
    def __init__(self):
        self.c = {}

    def observe(self, key, v):
        n, mean, m2 = self.c.get(key, (0.0, 0.0, 0.0))
        n *= DECAY
        nn = n + 1
        d = v - mean
        mean += d / nn
        m2 = DECAY * m2 + d * (v - mean)
        self.c[key] = (nn, mean, m2)

    def est(self, key):
        if key not in self.c:
            return Q0, SIGMA0 / math.sqrt(KAPPA)
        n, mean, m2 = self.c[key]
        q = (n * mean + KAPPA * Q0) / (n + KAPPA)
        if n > 1:
            sd = math.sqrt(max(0.0, m2) / (n - 1))
            sigma = math.sqrt((NU0 * SIGMA0 ** 2 + (n - 1) * sd ** 2) / (NU0 + n - 1))
        else:
            sigma = SIGMA0
        return q, sigma / math.sqrt(n + KAPPA)


def phi(x):
    return 0.5 * math.erfc(-x / math.sqrt(2))


def decide(table, cands, rng):
    est = [table.est(k) for k in cands]
    if len(cands) == 1:
        return 0, 1.0
    order = sorted(range(len(cands)), key=lambda i: -est[i][0])
    (q1, s1), (q2, s2) = est[order[0]], est[order[1]]
    conf = 0.5 if q1 == q2 else (1.0 if s1 == s2 == 0 else phi((q1 - q2) / math.sqrt(s1 * s1 + s2 * s2)))
    if rng.random() < EPS:
        return int(rng.integers(len(cands))), conf
    draws = [q + TAU * s * rng.standard_normal() for q, s in est]
    return int(np.argmax(draws)), conf


def run(world, mode, seed, decisions=20000, p_contact=0.10, p_seen=0.35):
    rng = np.random.default_rng(seed)
    types, w = list(world), np.array(list(world.values()), float)
    w /= w.sum()
    t = Table()
    eats = {k: 0 for k in types}
    contacts = {k: 0 for k in types}
    multi = decisive = contact_multi = contact_decisive = 0
    for _ in range(decisions):
        u = rng.random()
        if u < p_contact:
            obj = types[rng.choice(len(types), p=w)]
            cands = [(obj, "EAT"), (obj, "WANDER")]
            contacts[obj] += 1
        elif u < p_contact + p_seen:
            obj = types[rng.choice(len(types), p=w)]
            cands = [(obj, "APPROACH"), (obj, "WANDER")]
        else:
            cands = [("SELF", "WANDER")]
        i, conf = decide(t, cands, rng)
        if len(cands) > 1:
            multi += 1
            decisive += conf >= THETA
            if cands[0][1] == "EAT":
                contact_multi += 1
                contact_decisive += conf >= THETA
        obj, act = cands[i]
        if act == "EAT":
            eats[obj] += 1
            if mode == "magnitude":
                t.observe((obj, act), GAIN[obj])
            elif mode == "valence":
                t.observe((obj, act), 1.0 if GAIN[obj] > 0 else -1.0)
    e = sum(eats.values())
    food_eats = eats.get("RED", 0) + eats.get("GREEN", 0)
    food_contacts = contacts.get("RED", 0) + contacts.get("GREEN", 0)
    return {"gray_share_of_eats": eats["GRAY"] / e if e else float("nan"),
            "P(eat | gray contact)": eats["GRAY"] / max(1, contacts["GRAY"]),
            "P(eat | food contact)": food_eats / max(1, food_contacts),
            "decisive (multi)": decisive / max(1, multi),
            "decisive (contact)": contact_decisive / max(1, contact_multi)}


if __name__ == "__main__":
    worlds = {"pilot world (100/100/300)": {"RED": 100, "GREEN": 100, "GRAY": 300},
              "scarce world (10/10/300)": {"RED": 10, "GREEN": 10, "GRAY": 300}}
    for wname, world in worlds.items():
        print(f"\n== {wname}: mean over 15 creatures x 20,000 decisions")
        for mode in ["frozen", "magnitude", "valence"]:
            rs = [run(world, mode, seed) for seed in range(15)]
            agg = {k: float(np.mean([r[k] for r in rs])) for k in rs[0]}
            print(f"  {mode:9s} " + "  ".join(f"{k} {v:.3f}" for k, v in agg.items()))
