"""Offline check of A3's mechanism before any cluster time: does episodic credit to APPROACH fix what the habit table cannot?

The pilot finding (p107_exposure_diagnosis.py): the per-outcome operant table has no value for APPROACH (approaching has no immediate
outcome), so A2 approaches a gray apple as readily as food, refuses it at contact, and moves on to the next gray apple. A3 adds an episodic
controller: engrams written from closed episodes carry the discounted return of what *followed* each step, so APPROACH toward food (an EAT
of food 1-2 decisions later) should be worth more than APPROACH toward gray (nothing follows).

Stand-in, deliberately coarse (same world as tendency_variants_sim.py, CAMPOS tendency lists, hunger the only drive):
  * Controllers: the operant table (Table, as operant_sample_sim.py) and an episodic estimator whose kNN is replaced by an exact-key match
    on (action, object, state seen/contact/none) over the 10 most recent engrams (the real key adds distance, bearing, contact and the
    drives; its object one-hot dominates them for this question). Q = mean V(G_j | h_now), sigma blended with the prior (sigma0 = 0.1, nu0 = 1),
    n_eff = number of neighbours, SE = sqrt(sigma^2 / n_eff) (beta = 0: identical keys), novelty 0.1 / sqrt(1 + n_eff).
  * Arbiter.decide: cheapest first, stop at conf >= 0.8, else the most decisive (ties to the cheaper); epsilon 0.03; Thompson tau 1; A5 =
    episodic when every candidate has n_eff >= 3, else operant. The budget is ignored (e = 1: every controller affordable), so this is an upper
    bound on how often the episodic controller is consulted.
  * Episodes (focus thresholds 0.4 / 0.15 as urgency over [0.18, 7]): open when hunger is above 2.9, close regulated at or below 1.2, or
    timed out after 200 decisions without a fall (APPROACH steps then get G = 0, as EpisodeBuffer). Discount gamma = 2^(-1/5) per decision.
    V(G | h) = d(h) - d(h + G) with d = max(0, h - 0.18).

    /opt/homebrew/bin/python3.13 analysis/diagnostics/a3_episodic_sim.py
"""
import math
from collections import defaultdict

import numpy as np

from operant_sample_sim import Table, phi, THETA, EPS, TAU
from tendency_variants_sim import CAL, WORLDS, candidates, calibrate

GAMMA = 2 ** (-1 / 5)
H_ON, H_OFF, TMAX, HSTAR = 2.9, 1.2, 200, 0.18
M, NOVELTY, MIN_NEFF, SIGMA0 = 10, 0.1, 3.0, 0.1


def d(h):
    return max(0.0, h - HSTAR)


class Episodic:
    def __init__(self):
        self.store = defaultdict(list)          # key -> list of G (hunger component), oldest first

    def est(self, key, h):
        gs = self.store.get(key, [])[-M:]
        n = len(gs)
        if n == 0:
            return 0.0, SIGMA0, 0.0
        v = np.array([d(h) - d(h + g) for g in gs])
        q = float(v.mean())
        s2 = float(v.var(ddof=1)) if n > 1 else 0.0
        sigma2 = (SIGMA0 ** 2 + (n - 1) * s2) / n
        return q, math.sqrt(sigma2 / n), float(n)


def conf_of(est):
    order = sorted(range(len(est)), key=lambda i: -est[i][0])
    (q1, s1), (q2, s2) = est[order[0]][:2], est[order[1]][:2]
    if q1 == q2:
        return 0.5
    return 1.0 if s1 == s2 == 0 else phi((q1 - q2) / math.sqrt(s1 * s1 + s2 * s2))


def decide(op, epi, cands, h, rng, arm):
    keyed = [(a, o, s) for (o, a), s in cands]
    est_op = [(*op.est((o, a)), float("nan")) for (o, a), _ in cands]
    consulted = [("op", est_op)]
    if arm != "A2":
        est_ep = [epi.est(k, h) for k in keyed]
        consulted.append(("ep", est_ep))
    if len(cands) == 1:
        return 0, "op", 0.0
    deciding, best = None, 0.0
    if arm == "A5" and len(consulted) == 2:
        ep_ok = all(e[2] >= MIN_NEFF for e in consulted[1][1])
        deciding = consulted[1] if ep_ok else consulted[0]
    else:
        for name, est in consulted:
            c = conf_of(est)
            if c > best:
                best, deciding = c, (name, est)
            if c >= THETA:
                break
    name, est = deciding
    if rng.random() < EPS:
        return int(rng.integers(len(cands))), name, 0.0
    draws, bonus = [], []
    for q, se, ne in est:
        b = NOVELTY / math.sqrt(1 + ne) if not math.isnan(ne) else 0.0
        draws.append(q + b + TAU * se * rng.standard_normal())
    return int(np.argmax(draws)), name, 0.0


def life(world, p_see, arm, seed, drift, max_steps=150000):
    rng = np.random.default_rng(seed)
    types, w = list(world), np.array(list(world.values()), float)
    w /= w.sum()
    op, epi = Table(), Episodic()
    state, obj, h = "none", None, 0.3
    ep_steps, ep_k, since_fall = None, None, 0
    n = defaultdict(float)
    for step in range(max_steps):
        if state == "none" and rng.random() < p_see:
            state, obj = "seen", types[rng.choice(len(types), p=w)]
        cands = [(c, state) for c in candidates(state, obj, "CAMPOS")]
        i, who, _ = decide(op, epi, cands, h, rng, arm)
        (target, act), st = cands[i]
        n["decisions"] += 1
        n["by_" + who] += 1
        if len(cands) > 1:
            n["multi"] += 1
        if state == "seen" and act == "APPROACH":
            n["approach_" + ("gray" if obj == "GRAY" else "food")] += 1
        if state == "seen":
            n["seen_" + ("gray" if obj == "GRAY" else "food")] += 1
        if state == "contact" and act != "EAT" and obj == "GRAY":
            n["linger"] += 1
        if ep_steps is None and h >= H_ON:
            ep_steps, ep_k, since_fall = [], [], 0
        if ep_steps is not None:
            ep_steps.append(((act, target, st)))
            ep_k.append(0.0)
        k_now = 0.0
        if state == "seen":
            if act == "APPROACH" and rng.random() < 0.3:
                state = "contact"
            elif act == "AVOID" or (act == "WANDER" and rng.random() < 0.5):
                state, obj = "none", None
        elif state == "contact":
            if act == "EAT":
                if CAL[obj] > 0:
                    n["food"] += 1
                    h_before = h
                    h = max(0.0, h - CAL[obj])
                    k_now = h - h_before
                else:
                    n["gray"] += 1
                op.observe((obj, "EAT"), 1.0 if CAL[obj] > 0 else -1.0)
                state, obj = "none", None
            elif act == "AVOID" or (act == "WANDER" and rng.random() < 0.3):
                state, obj = "none", None
        if ep_steps is not None:
            ep_k[-1] = k_now
            since_fall = 0 if k_now < 0 else since_fall + 1
            closed = None
            if h <= H_OFF:
                closed = "reg"
            elif since_fall >= TMAX:
                closed = "timeout"
            if closed:
                G = 0.0
                for t in range(len(ep_steps) - 1, -1, -1):
                    G = ep_k[t] + GAMMA * G
                    key = ep_steps[t]
                    g = 0.0 if (closed == "timeout" and key[0] == "APPROACH") else G
                    epi.store[(key[0], key[1], key[2])].append(g)
                n["episodes_" + closed] += 1
                ep_steps = None
        h += drift
        n["hsum"] += h
        if h >= 7:
            n["steps"], n["died"] = step + 1, 1
            return n
    n["steps"], n["died"] = max_steps, 0
    return n


if __name__ == "__main__":
    drift = calibrate()
    print(f"drift per decision (calibrated as in tendency_variants_sim): {drift:.5f}; cap 150,000 decisions; 15 creatures per arm")
    for wname, (world, p_see) in WORLDS.items():
        print(f"\n== {wname}")
        print(f"  {'arm':4s} {'died':>5s} {'life(k)':>8s} {'food/1k':>8s} {'gray/1k':>8s} {'P(appr|gray)':>13s} {'P(appr|food)':>13s} "
              f"{'linger %':>9s} {'episodic %':>10s} {'ep timeout/reg':>15s}")
        for arm in ["A2", "A3", "A5"]:
            rs = [life(world, p_see, arm, 2000 + s, drift) for s in range(15)]
            f = lambda k: np.mean([x[k] / x["steps"] for x in rs])
            st = np.array([x["steps"] for x in rs], float)
            pg = np.mean([x["approach_gray"] / max(1, x["seen_gray"]) for x in rs])
            pf = np.mean([x["approach_food"] / max(1, x["seen_food"]) for x in rs])
            epi_share = np.mean([x["by_ep"] / max(1, x["decisions"]) for x in rs])
            print(f"  {arm:4s} {sum(x['died'] for x in rs):>3d}/15 {st.mean() / 1000:>8.1f} {1000 * f('food'):>8.2f} "
                  f"{1000 * f('gray'):>8.2f} {pg:>13.3f} {pf:>13.3f} {100 * f('linger'):>9.1f} {100 * epi_share:>10.1f} "
                  f"{np.mean([x['episodes_timeout'] for x in rs]):>7.0f}/{np.mean([x['episodes_reg'] for x in rs]):<7.0f}")
