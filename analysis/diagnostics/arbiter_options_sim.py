"""Offline simulation of two changes to the arbiter's habit, and the episodic APPROACH credit, before any cluster time.

Options (docs/reports/p117_factorial_mechanism.md, discussion of 2026-11-13):
  * option 2 ("abstain"): the habit gives an opinion only on candidates it has evidence for. Untrained candidates enter its decisiveness and its draw
    as ONE pseudo-candidate "something else" (Q = 0, SE = the best trained candidate's SE); if that wins the draw, an untrained candidate is
    picked uniformly. Without any trained candidate the habit is not decisive (0.5).
  * option 3 ("chain"): the habit values every action: when an object is eaten, every action the creature took on that object since it came into
    view gets the same valence sample (+1 food, -1 otherwise) as the EAT. Decisiveness and draw unchanged.
  * mc_fix: timed-out episodes no longer zero their APPROACH steps (every step keeps its Monte Carlo return).

Stand-in world, calibrated on p117_factorial's tendency-off / lock-off cells (both arms; see the CAL block): objects enter and leave view
(about 1.9 in view on average, 13% of decisions with none), every (object, action) pair is a candidate (5 per object at a distance, 4 at contact,
SLEEP/WANDER on the creature itself when nothing is in view), hunger is the only drive (+0.0015 per decision, 30 decisions per second, death at 7),
food relieves hunger, gray/cactus/aloe do not. Controllers mirror the Java code: OperantEstimator (decayed Welford, kappa 1, prior sigma 1),
EpisodicEstimator (exact-key neighbours instead of kNN, prior sigma 0.1), Arbiter (cheapest first, theta 0.8, eps 0.03, tau 1, novelty
0.1/sqrt(1+n_eff), budget e >= 0.3 with e as PartialAppraisal computes it from hunger and the number of objects in view, fixed order with n_eff >= 3).

Validation before reading any option: the stand-in must reproduce the real direction A5 > A3 (fewer deaths, more food per decision) without
being tuned on A5.

    /opt/homebrew/bin/python3.13 analysis/diagnostics/arbiter_options_sim.py [creatures_per_arm] [decisions_cap]
"""
import json
import math
import os
import sys
from collections import defaultdict, deque
from multiprocessing import Pool

import numpy as np

# ---- calibration (p117_factorial, a3/a5_L0T0) ----
CAL = dict(
    drift=2.70 / 60 / 30,          # hunger per decision: 2.70 per creature-minute at about 30 decisions per second
    relief={"RED_APPLE": 0.15, "GREEN_APPLE": 0.30},  # hunger removed per EAT (engrams: mean relief of EAT food 0.24)
    mix={"RED_APPLE": 100, "GREEN_APPLE": 100, "GRAY_APPLE": 300, "CACTUS": 140, "ALOE": 140},
    arrive=0.040,                  # P(a new object comes into view) per decision
    leave=0.021,                   # P(a far object drifts out of view) per decision
    reach=0.045,                   # P(an APPROACH reaches contact)
)
CAL.update(json.loads(os.environ.get("SIMCAL", "{}")))  # calibration overrides, e.g. SIMCAL='{"reach": 0.3}'
H_STAR, LETHAL = 0.18, 7.0
THETA, EPS, TAU, NOVELTY, MIN_NEFF = 0.8, 0.03, 1.0, 0.1, 3.0
KAPPA, Q0, SIGMA0_OP, NU0, DECAY = 1.0, 0.0, 1.0, 1.0, 0.995
SIGMA0_EP, M_NEIGH, CAP_PER_KEY = 0.1, 10, 2000
GAMMA, TMAX = 2 ** (-1 / 5), 200
EP_ON, EP_OFF = 1.0, 0.6          # stand-in focus thresholds (hunger level), chosen so that most episodes time out, as in the data
TRACE = bool(os.environ.get("THETA_TRACE"))
TYPES = list(CAL["mix"])
W = np.array([CAL["mix"][t] for t in TYPES], float); W /= W.sum()
FOOD = ("RED_APPLE", "GREEN_APPLE")


def phi(x):
    return 0.5 * math.erfc(-x / math.sqrt(2))


class Operant:
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
            return Q0, SIGMA0_OP, False
        n, mean, m2 = self.c[key]
        q = (n * mean + KAPPA * Q0) / (n + KAPPA)
        sd = math.sqrt(max(0.0, m2) / (n - 1)) if n > 1 else 0.0
        sigma = math.sqrt((NU0 * SIGMA0_OP ** 2 + (n - 1) * sd ** 2) / (NU0 + n - 1)) if n > 1 else SIGMA0_OP
        return q, sigma / math.sqrt(n + KAPPA), True


class Episodic:
    def __init__(self):
        self.store = defaultdict(lambda: deque(maxlen=CAP_PER_KEY))

    def add(self, key, g):
        self.store[key].append(g)

    def est(self, key, h):
        s = self.store.get(key)
        if not s:
            return 0.0, SIGMA0_EP, 0.0
        n = min(M_NEIGH, len(s))
        gs = [s[-i] for i in range(1, n + 1)]   # the most recent M_NEIGH, without copying the whole deque
        d0 = max(0.0, h - H_STAR)
        v = [d0 - max(0.0, h + g - H_STAR) for g in gs]
        q = sum(v) / n
        s2 = sum((x - q) ** 2 for x in v) / (n - 1) if n > 1 else 0.0
        sig2 = (SIGMA0_EP ** 2 + (n - 1) * s2) / n
        return q, math.sqrt(sig2 / n), float(n)


def decisiveness(est):
    if len(est) < 2:
        return 1.0
    order = sorted(range(len(est)), key=lambda i: -est[i][0])
    (q1, s1), (q2, s2) = est[order[0]][:2], est[order[1]][:2]
    if q1 == q2:
        return 0.5
    v = s1 * s1 + s2 * s2
    return 1.0 if v == 0 else phi((q1 - q2) / math.sqrt(v))


_GH_X, _GH_W = np.polynomial.hermite_e.hermegauss(64)
_GH_W = _GH_W / math.sqrt(2 * math.pi)


def whole_draw_conf(est):
    """Planned WHOLE_DRAW decisiveness (docs/plans/operant-kalman-and-stop-rule.md, section 2): the probability that the candidate with the
    highest mean wins the actual Thompson draw, q + tau * se * N(0,1) per candidate, by Gauss-Hermite quadrature (no random numbers). A candidate
    with se 0 is a point."""
    if len(est) < 2:
        return 1.0
    mu = np.array([e[0] for e in est], float)
    sd = np.array([TAU * e[1] for e in est], float)
    b = int(np.argmax(mu))
    x = mu[b] + sd[b] * _GH_X                                  # the best candidate's draw at each node
    prod = np.ones_like(x)
    for j in range(len(est)):
        if j == b:
            continue
        prod *= (x >= mu[j]).astype(float) if sd[j] == 0 else 0.5 * np.vectorize(math.erfc)(-(x - mu[j]) / sd[j] / math.sqrt(2))
    return float(np.sum(_GH_W * prod))


def thompson(est, rng, novelty=False):
    best, arg = -1e9, 0
    for i, e in enumerate(est):
        b = NOVELTY / math.sqrt(1 + e[2]) if novelty else 0.0
        d = e[0] + b + TAU * e[1] * rng.standard_normal()
        if d > best:
            best, arg = d, i
    return arg


def life(args):
    arm, seed, cap = args
    mode, opt, mc_fix = arm  # mode: ARB|FIX; opt: none|abstain|chain
    rng = np.random.default_rng(seed)
    op, ep = Operant(), Episodic()
    objs, oid = [], 0                  # each: [id, type, contact, actions_taken]
    h = 0.5
    ep_steps, since_fall = None, 0
    st = defaultdict(int)
    for step in range(cap):
        # world: arrivals and drift out of view
        if rng.random() < CAL["arrive"] and len(objs) < 6:
            objs.append([oid, TYPES[rng.choice(len(TYPES), p=W)], False, []]); oid += 1
        objs = [o for o in objs if o[2] or rng.random() >= CAL["leave"]]
        # candidates: (action, object index or -1 for self)
        cands = []
        if not objs:
            cands = [("SLEEP", -1), ("WANDER", -1)]
        for i, o in enumerate(objs):
            if o[2]:
                cands += [("EAT", i), ("AVOID", i), ("ESCAPE" if o[1] == "CACTUS" else "SLEEP", i), ("WANDER", i)]
            else:
                cands += [("APPROACH", i), ("AVOID", i), ("SLEEP", i), ("OBSERVE", i), ("WANDER", i)]
        n = len(cands)
        keys = [(a, objs[i][1] if i >= 0 else "SELF", objs[i][2] if i >= 0 else False) for a, i in cands]
        food_at_contact = any(a == "EAT" and objs[i][1] in FOOD for a, i in cands)
        # PartialAppraisal.normalizedBehaviouralEfficiency: monotone under COMPLEX_TASK = 2 percepts, Campos's inverted U from 2 on
        e_budget = (1 - math.exp(-0.4 * h)) if len(objs) < 2 else h * (280 / 49 - (40 / 49) * h) / 10
        affordable = e_budget >= 0.3

        # operant estimates (and option 2's opinion set)
        op_est = [op.est((k[0], k[1])) for k in keys]
        if opt == "abstain":
            trained = [j for j, e in enumerate(op_est) if e[2]]
            if trained:
                best_se = max(op_est[j][1] for j in trained)
                op_set = [(op_est[j][0], op_est[j][1], float("nan")) for j in trained] + [(0.0, best_se, float("nan"))]
                op_conf = decisiveness(op_set)
            else:
                op_set, op_conf = None, 0.5
        else:
            op_set = [(q, s, float("nan")) for q, s, _ in op_est]
            op_conf = decisiveness(op_set)

        if TRACE and step % 5 == 0:
            all_set = [(q, s_, float("nan")) for q, s_, _ in op_est]
            tr = [j for j, e in enumerate(op_est) if e[2]]
            ab_set = ([(op_est[j][0], op_est[j][1], float("nan")) for j in tr] + [(0.0, max(op_est[j][1] for j in tr), float("nan"))]
                      if tr else None)
            st.setdefault("trace", []).append((n, len(tr), affordable, decisiveness(all_set), whole_draw_conf(all_set),
                                                   whole_draw_conf(ab_set) if ab_set else float("nan"),
                                                   decisiveness(ab_set) if ab_set else float("nan")))

        def op_choose():
            if opt == "abstain":
                if op_set is None:
                    return int(rng.integers(n))
                j = thompson(op_set, rng)
                if j < len(trained):
                    return trained[j]
                untrained = [x for x in range(n) if x not in trained]
                return int(rng.choice(untrained)) if untrained else trained[0]
            return thompson(op_set, rng)

        decider = "OP"
        ep_est = None
        if mode == "ARB":
            if op_conf < THETA and affordable:
                ep_est = [ep.est(k, h) for k in keys]
                if decisiveness(ep_est) > op_conf:
                    decider = "EP"
        else:  # FIX: episodic when affordable and every candidate has n_eff >= MIN_NEFF
            if affordable:
                ep_est = [ep.est(k, h) for k in keys]
                if all(e[2] >= MIN_NEFF for e in ep_est):
                    decider = "EP"
        if n == 1:
            ci = 0
        elif rng.random() < EPS:
            ci = int(rng.integers(n))
        elif decider == "EP":
            ci = thompson(ep_est, rng, novelty=True)
        else:
            ci = op_choose()
        act, oi = cands[ci]
        st["decisions"] += 1
        st["by_" + decider] += 1
        st["food_at_contact"] += food_at_contact
        st["any_contact"] += any(o[2] for o in objs)
        if food_at_contact and act == "EAT" and objs[oi][1] in FOOD:
            st["ate_food_at_contact"] += 1
        if oi >= 0:
            objs[oi][3].append((act, objs[oi][1]))

        # effects
        k_now = 0.0
        if act == "APPROACH" and rng.random() < CAL["reach"]:
            objs[oi][2] = True
        elif act in ("AVOID", "ESCAPE"):
            objs.pop(oi)
        elif act == "WANDER":
            objs = [o for o in objs if rng.random() >= (0.3 if o[2] else 0.5)]
        elif act == "EAT":
            o = objs.pop(oi)
            if o[1] in FOOD:
                relief = min(CAL["relief"][o[1]], max(0.0, h - 0.0))
                k_now = -relief
                h -= relief
                st["food"] += 1
            else:
                st["nonfood_eat"] += 1
            valence = 1.0 if k_now < 0 else -1.0
            op.observe(("EAT", o[1]), valence)
            if opt == "chain":
                for a, t in o[3][:-1]:
                    op.observe((a, t), valence)

        # episodes (hunger focus) and engrams
        if ep_steps is None and h >= EP_ON:
            ep_steps, since_fall = [], 0
        if ep_steps is not None:
            ep_steps.append([keys[ci], k_now])
            since_fall = 0 if k_now < 0 else since_fall + 1
            close = "REG" if h <= EP_OFF else ("TIMEOUT" if since_fall >= TMAX else None)
            if close:
                g = 0.0
                for key, k in reversed(ep_steps):
                    g = k + GAMMA * g
                    ep.add(key, 0.0 if (close == "TIMEOUT" and key[0] == "APPROACH" and not mc_fix) else g)
                st["ep_" + close] += 1
                ep_steps = None
        h += CAL["drift"]
        if h >= LETHAL:
            st["died"] = 1
            st["life"] = step + 1
            return arm, dict(st)
    st["died"] = 0
    st["life"] = cap
    return arm, dict(st)


ARMS = [("ARB", "none", False), ("FIX", "none", False), ("ARB", "abstain", False), ("ARB", "chain", False),
        ("ARB", "none", True), ("FIX", "none", True), ("ARB", "abstain", True), ("ARB", "chain", True)]
NAMES = {("ARB", "none"): "A3 now", ("FIX", "none"): "A5 now", ("ARB", "abstain"): "A3 + option 2 (habit abstains)",
         ("ARB", "chain"): "A3 + option 3 (habit values all actions)"}

def theta_report(res, arm):
    """THETA_TRACE=1: how often each decisiveness reaches theta, for the operant's estimates of one arm (docs/plans/operant-kalman-and-stop-rule.md)."""
    rows = [t for a, r in res if a == arm for t in r.get("trace", [])]
    n_c, n_tr, aff, top2, whole_all, whole_ab, top2_ab = (np.array(c, float) for c in zip(*rows))
    f = lambda v, m=None: float(np.mean((v if m is None else v[m]) >= THETA))
    print(f"arm {arm}: {len(rows):,} sampled decisions; candidates mean {n_c.mean():.1f}, trained (with an opinion) mean {n_tr.mean():.2f}")
    for name, v in (("TOP_TWO, all candidates (today)", top2), ("WHOLE_DRAW, all candidates", whole_all),
                    ("TOP_TWO, trained + pseudo-candidate", top2_ab), ("WHOLE_DRAW, trained + pseudo-candidate", whole_ab)):
        ok = np.isfinite(v)
        print(f"  {name:42s} P(conf >= {THETA}) = {np.mean(v[ok] >= THETA):.3f}   quartiles {np.percentile(v[ok], [25, 50, 75]).round(3)}   (n={ok.sum():,})")
    for lo, hi in ((1, 5), (6, 10), (11, 20), (21, 99)):
        m = (n_c >= lo) & (n_c <= hi)
        if m.sum():
            ok = m & np.isfinite(whole_ab)
            print(f"    {lo:>2d}-{hi:<2d} candidates ({m.sum():>6,}): WHOLE_DRAW all P>=theta {f(whole_all, m):.3f}; "
                  f"trained+pseudo P>=theta {np.mean(whole_ab[ok] >= THETA) if ok.sum() else float('nan'):.3f}")


if __name__ == "__main__":
    if TRACE:
        creatures = int(sys.argv[1]) if len(sys.argv) > 1 else 8
        cap = int(sys.argv[2]) if len(sys.argv) > 2 else 30_000
        arms = [("ARB", "none", False), ("ARB", "abstain", False)]
        with Pool() as pool:
            res = pool.map(life, [(arm, 7000 + s, cap) for arm in arms for s in range(creatures)])
        for arm in arms:
            theta_report(res, arm)
        sys.exit(0)
    creatures = int(sys.argv[1]) if len(sys.argv) > 1 else 24
    cap = int(sys.argv[2]) if len(sys.argv) > 2 else 108_000
    jobs = [(arm, 7000 + s, cap) for arm in ARMS for s in range(creatures)]
    with Pool() as pool:
        res = pool.map(life, jobs)
    print(f"{creatures} creatures per arm, cap {cap:,} decisions ({cap / 30 / 60:.0f} min at 30 decisions/s); the same seeds in every arm\n")
    print(f"{'arm':44s} {'MC fix':6s} {'died':>7s} {'mean life (min)':>15s} {'food/1k':>8s} {'P(eat|food contact)':>19s} "
          f"{'episodic %':>10s} {'timeout %':>9s}")
    for arm in ARMS:
        rs = [r for a, r in res if a == arm]
        tot = lambda k: sum(r.get(k, 0) for r in rs)
        dec = tot("decisions")
        eps = tot("ep_REG") + tot("ep_TIMEOUT")
        print(f"{NAMES[arm[:2]]:44s} {'yes' if arm[2] else 'no':6s} {sum(r['died'] for r in rs):>3d}/{len(rs):<3d} "
              f"{np.mean([r['life'] for r in rs]) / 30 / 60:>15.1f} {1000 * tot('food') / dec:>8.2f} "
              f"{tot('ate_food_at_contact') / max(1, tot('food_at_contact')):>19.3f} {100 * tot('by_EP') / dec:>10.1f} "
              f"{100 * tot('ep_TIMEOUT') / max(1, eps):>9.1f}")
