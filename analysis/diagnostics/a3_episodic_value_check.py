"""What would the episodic controller have learned? Offline check of A3's mechanism on real data.

Reads the `episode_returns` of existing A2 runs (any arm that has them) and groups the closed steps by (action, object type): the mean of
`value` = V(G | H_t) is what an episodic estimator keyed on the object type alone would answer, the sd its sigma, the count its evidence.
The question (pilot finding, p107_exposure_diagnosis.py): does APPROACH toward food carry a higher return than APPROACH toward a gray
apple, which the habit table cannot see? Reported overall and by close type: a timed-out episode zeroes its APPROACH steps (paper 4.2), and
half of the pilot's closed steps come from timed-out episodes.

    /opt/homebrew/bin/python3.13 analysis/diagnostics/a3_episodic_value_check.py [data_dir ...]
"""
import glob
import sys

import numpy as np
import pandas as pd

DEFAULT = ["ml/data_p107_operant/a2n"]


def load(dirs):
    frames = []
    for d in dirs:
        for f in sorted(glob.glob(f"{d}/trial_*/episode_returns.parquet")):
            df = pd.read_parquet(f)
            df["trial"] = f.split("trial_")[1].split("/")[0]
            frames.append(df)
    return pd.concat(frames, ignore_index=True)


def table(df, by):
    g = df.groupby(by)["value"]
    out = pd.DataFrame({"n": g.size(), "mean V": g.mean(), "sd": g.std()})
    out["SE (sd/sqrt n)"] = out["sd"] / np.sqrt(out["n"])
    return out.round(4)


if __name__ == "__main__":
    df = load(sys.argv[1:] or DEFAULT)
    df["object"] = df["object_type"].fillna("self")
    df["food"] = np.where(df.object.isin(["RED_APPLE", "GREEN_APPLE"]), "food (red/green)",
                          np.where(df.object == "GRAY_APPLE", "gray", "other"))
    print(f"{len(df):,} closed steps, {df.creature_key.nunique()} creatures\n")
    print("== value V(G|H_t) by action and object type")
    print(table(df[df.action.isin(["APPROACH", "EAT"])], ["action", "object"]).to_string())
    print("\n== APPROACH, food vs gray, by close type")
    print(table(df[(df.action == "APPROACH") & df.food.isin(["food (red/green)", "gray"])], ["close_type", "food"]).to_string())
    ap = df[(df.action == "APPROACH") & df.food.isin(["food (red/green)", "gray"])]
    m = ap.groupby("food")["value"].mean()
    print(f"\nAPPROACH food - gray (mean V over all closes): {m['food (red/green)'] - m['gray']:+.4f}")
    # per-trial paired difference, to see whether the sign is stable
    per = ap.groupby(["trial", "food"])["value"].mean().unstack()
    print("per trial (food - gray):", (per["food (red/green)"] - per["gray"]).round(4).tolist())
    print("\nshare of APPROACH steps by close type:",
          (df[df.action == "APPROACH"].close_type.value_counts(normalize=True).round(3).to_dict()))
