#!/usr/bin/env python3
"""
Issue #57 — neuromodulatory expectancy loop analysis.

Compares the two symbolic expectancy predictors (DISCRETE vs CONTINUOUS) and the baseline arm.

Inputs (set `wd`): ml/data_p57/{baseline,discrete,continuous}/
  - expectancy_state.csv  (discrete, continuous): creature_key,cycle,mode,drive,drive_level,
                          target,action,expected,reward,rpe
  - chosen_actions.csv    (all arms): action,actionselectiontype

Outputs: figures + printed summary to ml/data_p57/figures/.
Run: python3 analysis/exp_p57_expectancy.py
"""
import os
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

wd = os.path.join(os.path.dirname(__file__), "..", "ml", "data_p57")
figdir = os.path.join(wd, "figures")
os.makedirs(figdir, exist_ok=True)


def load_expectancy(arm):
    return pd.read_csv(os.path.join(wd, arm, "expectancy_state.csv"))


def load_actions(arm):
    return pd.read_csv(os.path.join(wd, arm, "chosen_actions.csv"))


# ---------------------------------------------------------------------------
# 1. Prediction accuracy: MSE(reward, expected) per arm, overall and post-warmup.
# ---------------------------------------------------------------------------
def prediction_mse(df, warmup_frac=0.5):
    df = df.sort_values("cycle")
    n = len(df)
    tail = df.iloc[int(n * warmup_frac):]
    return {
        "n": n,
        "mse_all": float(np.mean((df.reward - df.expected) ** 2)),
        "mse_post_warmup": float(np.mean((tail.reward - tail.expected) ** 2)),
        "mean_abs_rpe_post": float(np.mean(np.abs(tail.rpe))),
    }


# ---------------------------------------------------------------------------
# 2. The key diagnostic: does reward depend on drive_level?
#    If regulation applies fixed decrements, reward is constant per (drive,action),
#    and the CONTINUOUS predictor has no level signal to exploit.
# ---------------------------------------------------------------------------
def reward_level_dependence(df):
    rows = []
    for (drive, action), g in df.groupby(["drive", "action"]):
        rows.append({
            "drive": drive, "action": action, "events": len(g),
            "distinct_levels": g.drive_level.round(1).nunique(),
            "reward_std": float(g.reward.std(ddof=0)),
            "reward_range": float(g.reward.max() - g.reward.min()),
        })
    return pd.DataFrame(rows).sort_values("events", ascending=False)


def main():
    discrete = load_expectancy("discrete")
    continuous = load_expectancy("continuous")

    print("=" * 70)
    print("1. PREDICTION MSE (lower = better prediction)")
    print("=" * 70)
    for name, df in [("DISCRETE", discrete), ("CONTINUOUS", continuous)]:
        m = prediction_mse(df)
        print(f"{name:11s}  n={m['n']:6d}  MSE(all)={m['mse_all']:.5f}  "
              f"MSE(post-warmup)={m['mse_post_warmup']:.5f}  "
              f"mean|RPE|post={m['mean_abs_rpe_post']:.5f}")

    print("\n" + "=" * 70)
    print("2. REWARD vs DRIVE_LEVEL dependence (per drive,action)")
    print("   reward_std≈0 => reward is level-independent (fixed decrement)")
    print("=" * 70)
    print(reward_level_dependence(continuous).to_string(index=False))

    # -- Figure 1: RPE convergence (rolling mean of |RPE| over event index) --
    plt.figure(figsize=(8, 4.5))
    for name, df, color in [("DISCRETE", discrete, "tab:blue"),
                            ("CONTINUOUS", continuous, "tab:orange")]:
        s = df.sort_values("cycle").reset_index(drop=True)
        roll = s.rpe.abs().rolling(200, min_periods=20).mean()
        plt.plot(roll.values, label=name, color=color, lw=1.5)
    plt.xlabel("evaluation event #")
    plt.ylabel("rolling mean |RPE|  (window=200)")
    plt.title("Reward-prediction error converges toward 0 as expectations learn")
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(figdir, "rpe_convergence.png"), dpi=130)
    plt.close()

    # -- Figure 2: post-warmup MSE bar chart (discrete vs continuous) --
    mses = [prediction_mse(discrete)["mse_post_warmup"],
            prediction_mse(continuous)["mse_post_warmup"]]
    plt.figure(figsize=(5, 4.5))
    plt.bar(["DISCRETE", "CONTINUOUS"], mses, color=["tab:blue", "tab:orange"])
    for i, v in enumerate(mses):
        plt.text(i, v, f"{v:.5f}", ha="center", va="bottom")
    plt.ylabel("post-warmup prediction MSE")
    plt.title("DISCRETE vs CONTINUOUS prediction accuracy")
    plt.tight_layout()
    plt.savefig(os.path.join(figdir, "mse_comparison.png"), dpi=130)
    plt.close()

    # -- Figure 3: action-selection-type distribution per arm (vs Campos Fig 4) --
    print("\n" + "=" * 70)
    print("3. ACTION DISTRIBUTION per arm (baseline vs treatment => regression check)")
    print("=" * 70)
    arms = ["baseline", "discrete", "continuous"]
    act_counts = {}
    for arm in arms:
        a = load_actions(arm)
        dist = a.action.value_counts(normalize=True).mul(100).round(1)
        act_counts[arm] = dist
        print(f"\n[{arm}] action %:")
        print(dist.to_string())

    all_actions = sorted(set().union(*[set(d.index) for d in act_counts.values()]))
    x = np.arange(len(all_actions))
    w = 0.25
    plt.figure(figsize=(9, 4.5))
    for i, arm in enumerate(arms):
        vals = [act_counts[arm].get(act, 0.0) for act in all_actions]
        plt.bar(x + (i - 1) * w, vals, w, label=arm)
    plt.xticks(x, all_actions, rotation=30, ha="right")
    plt.ylabel("% of chosen actions")
    plt.title("Action distribution per arm (baseline ≈ treatment ⇒ no behavioural regression)")
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(figdir, "action_distribution.png"), dpi=130)
    plt.close()

    print("\nFigures written to", figdir)


if __name__ == "__main__":
    main()
