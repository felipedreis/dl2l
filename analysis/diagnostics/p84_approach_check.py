"""Why do p84 legacy creatures stop eating mid-life? (2026-10-17)

Checks, per arm: whether chosen EATs happen, how approach runs end, how feeding is spread
over life, and how EAT share and distance-to-target depend on speed.

    python3 analysis/diagnostics/p84_approach_check.py ml/data_p84_behaviour_parity
"""
import glob
import sys

import numpy as np
import pandas as pd

ROOT = sys.argv[1]
WINDOW_MS = 1000  # an EAT within 1 s after a run's last APPROACH counts as its outcome
SPEED_BINS = [0, 3.5, 5, 7, 9, 11]


def load(arm, table):
    files = sorted(glob.glob(f"{ROOT}/{arm}/trial_*/{table}.parquet"))
    return pd.concat(pd.read_parquet(f) for f in files).reset_index(drop=True)


def life_decile(df, born, time_col="time"):
    df = df.join(born, on=["trial", "creature_key"])
    frac = (df[time_col] - df.born_time) / 1000 / df.lifetime_s
    return df.assign(dec=(frac.clip(0, 0.999) * 10).astype(int))


for arm in ["legacy_nomem", "legacy_mem"]:
    a = load(arm, "actions").sort_values(["trial", "creature_key", "time"]).reset_index(drop=True)
    m = load(arm, "mouth_interactions")
    p = load(arm, "perceptions").sort_values("time")
    bs = load(arm, "body_states").sort_values("time")
    born = (load(arm, "creatures").drop_duplicates(["trial", "creature_key"])
            .set_index(["trial", "creature_key"])[["born_time", "lifetime_s"]])
    print(f"\n=== {arm}: {len(born)} creatures, median lifetime {born.lifetime_s.median():.0f} s")
    print(f"EAT chosen: {(a.action_type == 'EAT').sum()}   EAT happened (mouth): {len(m)}")

    # Approach runs: consecutive APPROACH on the same target by the same creature
    grp = a.groupby(["trial", "creature_key"])
    a["next_action"] = grp.action_type.shift(-1)
    new_run = ((a.action_type != "APPROACH") | (grp.action_type.shift() != "APPROACH")
               | (a.target_key != grp.target_key.shift()))
    a["run"] = new_run.cumsum()
    ap = a[a.action_type == "APPROACH"]
    runs = ap.groupby("run").agg(trial=("trial", "first"), creature_key=("creature_key", "first"),
                                 target=("target_key", "first"), t0=("time", "min"),
                                 t1=("time", "max"), next_action=("next_action", "last"))
    mk = m.rename(columns={"object_key": "target"})[["trial", "creature_key", "target", "time"]]
    j = runs.reset_index().merge(mk, on=["trial", "creature_key", "target"], how="left")
    j["hit"] = (j.time >= j.t0) & (j.time <= j.t1 + WINDOW_MS)
    runs["eaten"] = j.groupby("run").hit.any()
    print(f"approach runs: {len(runs)}; ending in EAT of the target: {runs.eaten.mean():.1%}")
    print("what follows a run:", runs.next_action.value_counts(normalize=True).round(3).to_dict())

    # Feeding over life
    print("EATs by life decile:", np.bincount(life_decile(m, born).dec, minlength=10).tolist())
    dec = life_decile(a, born)
    print("action mix by life decile:")
    print(pd.crosstab(dec.dec, dec.action_type, normalize="index").round(3).T.to_string())

    # Speed: EAT share, and distance from APPROACH to its target
    j = pd.merge_asof(a.sort_values("time"), bs[["trial", "creature_key", "time", "speed"]],
                      on="time", by=["trial", "creature_key"], direction="backward")
    j["speed_bin"] = pd.cut(j.speed, SPEED_BINS)
    appr = (j[j.action_type == "APPROACH"][["trial", "creature_key", "time", "target_key", "speed_bin"]]
            .rename(columns={"target_key": "object_key"}))
    k = pd.merge_asof(appr, p[["trial", "creature_key", "object_key", "time", "distance"]],
                      on="time", by=["trial", "creature_key", "object_key"],
                      direction="backward", tolerance=200)
    out = pd.DataFrame({
        "decisions": j.groupby("speed_bin", observed=True).size(),
        "eat_share": j.groupby("speed_bin", observed=True).action_type.apply(lambda s: (s == "EAT").mean()),
        "appr_dist_median": k.groupby("speed_bin", observed=True).distance.median(),
        "appr_within_mouth": k.groupby("speed_bin", observed=True).distance.apply(lambda d: (d < 10).mean()),
        "appr_within_1": k.groupby("speed_bin", observed=True).distance.apply(lambda d: (d < 1).mean()),
    })
    print("by speed:")
    print(out.round(4).to_string())
