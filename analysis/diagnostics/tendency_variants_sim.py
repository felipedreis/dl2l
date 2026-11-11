"""Offline check of three action-tendency variants for A2 (CAMPOS = dl2l's current filter, MAPA = Mapa 0.9.5's lists, NONE = no
filter), each with the operant table learning (valence sample) or frozen. Stand-in for the cluster run p107_tendency.

The arbiter and the operant table mirror the code (see operant_sample_sim.py). The world is a stand-in with movement:
  nothing seen  -> afforded SLEEP, WANDER              (an object comes into view with prob P_SEE[world])
  object seen   -> APPROACH (reach it w.p. 0.3), AVOID (leave), SLEEP / OBSERVE (stay), WANDER (leave w.p. 0.5)
  at contact    -> EAT (consumes it: food lowers hunger by its caloric value, gray does nothing; either way the object is gone),
                   AVOID (leave), SLEEP (stay), WANDER (stay w.p. 0.7: an uneaten object keeps the creature near it)
Hunger is the only drive (dominant always): +DRIFT per decision, death at 7. DRIFT is calibrated so that CAMPOS-frozen just
sustains itself in the pilot world. Only EAT produces an outcome that trains the table (D2). Sleep has no benefit here (the sleep
drive is not modelled), so SLEEP is pure lost time while hungry, which is the #57 concern.

    python3 analysis/diagnostics/tendency_variants_sim.py
"""
import math

import numpy as np

from operant_sample_sim import Table, decide

CAL = {"RED": 0.2, "GREEN": 0.5, "GRAY": 0.0}
WORLDS = {"pilot (100/100/300)": ({"RED": 100, "GREEN": 100, "GRAY": 300}, 0.45),
          "scarce (10/10/300)": ({"RED": 10, "GREEN": 10, "GRAY": 300}, 0.45)}
AFFORD = {"none": ["SLEEP", "WANDER"],
          "seen": ["APPROACH", "AVOID", "SLEEP", "OBSERVE", "WANDER"],
          "contact": ["EAT", "AVOID", "SLEEP", "WANDER"]}
TEND = {"CAMPOS": {"EAT", "APPROACH", "WANDER"},
        "MAPA": {"APPROACH", "AVOID", "EAT", "SLEEP", "WANDER", "TOUCH", "PLAY"},
        "NONE": None}


def candidates(state, obj, variant):
    acts = AFFORD[state]
    t = TEND[variant]
    kept = [a for a in acts if t is None or a in t] or acts   # soft filter: pass through when empty, as ActionTendencyFilter
    target = obj if state != "none" else "SELF"
    return [(target, a) for a in kept]


def life(world, p_see, variant, learn, seed, drift, max_steps=150000, track_hunger=True):
    rng = np.random.default_rng(seed)
    types, w = list(world), np.array(list(world.values()), float)
    w /= w.sum()
    t = Table()
    state, obj, h = "none", None, 0.3
    n = {"steps": 0, "food": 0, "gray": 0, "sleep": 0, "linger": 0, "decisive": 0, "multi": 0, "hsum": 0.0}
    for step in range(max_steps):
        if state == "none" and rng.random() < p_see:
            state, obj = "seen", types[rng.choice(len(types), p=w)]
        cands = candidates(state, obj, variant)
        i, conf = decide(t, cands, rng)
        if len(cands) > 1:
            n["multi"] += 1
            n["decisive"] += conf >= 0.8
        act = cands[i][1]
        if act == "SLEEP":
            n["sleep"] += 1
        if state == "contact" and act != "EAT" and obj == "GRAY":
            n["linger"] += 1
        if state == "seen":
            if act == "APPROACH" and rng.random() < 0.3:
                state = "contact"
            elif act == "AVOID" or (act == "WANDER" and rng.random() < 0.5):
                state, obj = "none", None
        elif state == "contact":
            if act == "EAT":
                if CAL[obj] > 0:
                    n["food"] += 1
                    h = max(0.0, h - CAL[obj])
                else:
                    n["gray"] += 1
                if learn:
                    t.observe((obj, "EAT"), 1.0 if CAL[obj] > 0 else -1.0)
                state, obj = "none", None
            elif act == "AVOID" or (act == "WANDER" and rng.random() < 0.3):
                state, obj = "none", None
        if track_hunger:
            h += drift
            n["hsum"] += h
            if h >= 7:
                n["steps"] = step + 1
                n["died"] = 1
                return n
    n["steps"] = max_steps
    n["died"] = 0
    return n


def calibrate():
    world, p_see = WORLDS["pilot (100/100/300)"]
    r = [life(world, p_see, "CAMPOS", False, s, 0.0, 60000, False) for s in range(5)]
    food_per_step = np.mean([x["food"] / 60000 for x in r])
    mean_cal = (CAL["RED"] + CAL["GREEN"]) / 2
    return 0.9 * food_per_step * mean_cal


if __name__ == "__main__":
    drift = calibrate()
    print(f"drift per decision (calibrated on CAMPOS-frozen, pilot world): {drift:.5f}; cap 150,000 decisions; 15 creatures per arm")
    for wname, (world, p_see) in WORLDS.items():
        print(f"\n== {wname}")
        print(f"  {'variant':8s} {'table':7s} {'died':>5s} {'life (k decisions)':>18s} {'mean hunger':>11s} {'food/1k':>8s} {'gray/1k':>8s} "
              f"{'gray share':>10s} {'sleep %':>8s} {'linger %':>9s} {'decisive %':>10s}")
        for variant in ["CAMPOS", "MAPA", "NONE"]:
            for learn in [False, True]:
                rs = [life(world, p_see, variant, learn, 1000 + s, drift) for s in range(15)]
                died = sum(x["died"] for x in rs)
                st = np.array([x["steps"] for x in rs], float)
                f = lambda k: np.mean([x[k] / x["steps"] for x in rs])
                gs = np.mean([x["gray"] / max(1, x["gray"] + x["food"]) for x in rs])
                print(f"  {variant:8s} {'learn' if learn else 'frozen':7s} {died:>3d}/15 {st.mean()/1000:>18.1f} {f('hsum'):>11.2f} "
                      f"{1000*f('food'):>8.2f} {1000*f('gray'):>8.2f} {gs:>10.3f} {100*f('sleep'):>8.1f} {100*f('linger'):>9.1f} "
                      f"{100*np.mean([x['decisive']/max(1,x['multi']) for x in rs]):>10.1f}")
