#!/usr/bin/env python3
"""
Issue #57 — neuromodulatory expectancy loop + emotion→action coupling analysis.

Arms (ml/data_p57/{baseline,discrete,continuous}/):
  - chosen_actions.csv          (all arms): action,actionselectiontype
  - expectancy_state.csv        (discrete, continuous): creature_key,cycle,mode,drive,drive_level,
                                target,action,expected,reward,rpe
  - neuromodulator_state_log.csv(discrete, continuous): creature_key,seq,dopamine,serotonin

Outputs figures + printed summary to ml/data_p57/figures/.
Run: python3 analysis/exp_p57_expectancy.py
"""
import os
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

try:
    from scipy import stats as scipy_stats
except Exception:
    scipy_stats = None

wd = os.path.join(os.path.dirname(__file__), "..", "ml", "data_p57")
figdir = os.path.join(wd, "figures")
os.makedirs(figdir, exist_ok=True)

ARMS = ["baseline", "discrete", "continuous"]
QUIETING = {"SLEEP", "OBSERVE", "WANDER"}
FORAGING = {"APPROACH", "EAT"}


def load(arm, name):
    return pd.read_csv(os.path.join(wd, arm, name))


# ---------------------------------------------------------------------------
# 1. Prediction accuracy + statistical test (comment: need a test to reject H1)
# ---------------------------------------------------------------------------
def squared_errors(df, warmup_frac=0.5):
    df = df.sort_values("cycle")
    tail = df.iloc[int(len(df) * warmup_frac):]
    return ((tail.reward - tail.expected) ** 2).values


def main():
    discrete = load("discrete", "expectancy_state.csv")
    continuous = load("continuous", "expectancy_state.csv")

    print("=" * 72)
    print("1. PREDICTION MSE + statistical test (H1: CONTINUOUS < DISCRETE)")
    print("=" * 72)
    se_d = squared_errors(discrete)
    se_c = squared_errors(continuous)
    mse_d, mse_c = float(np.mean(se_d)), float(np.mean(se_c))
    print(f"DISCRETE   post-warmup MSE = {mse_d:.6e}  (n={len(se_d)})")
    print(f"CONTINUOUS post-warmup MSE = {mse_c:.6e}  (n={len(se_c)})")
    if scipy_stats is not None:
        u, p = scipy_stats.mannwhitneyu(se_c, se_d, alternative="less")
        print(f"Mann-Whitney U (CONTINUOUS < DISCRETE): U={u:.1f}  p={p:.4f}")
        print("  => " + ("reject" if p < 0.05 else "CANNOT reject") +
              " H0 at alpha=0.05; the difference is "
              + ("significant" if p < 0.05 else "not significant"))
    else:
        print("scipy unavailable — skipping significance test")

    # Reward vs drive_level dependence (why the predictors tie).
    print("\n" + "=" * 72)
    print("2. REWARD vs DRIVE_LEVEL (reward_std≈0 => level-independent reward)")
    print("=" * 72)
    rows = []
    for (drive, action), g in continuous.groupby(["drive", "action"]):
        rows.append({"drive": drive, "action": action, "events": len(g),
                     "levels": g.drive_level.round(1).nunique(),
                     "reward_std": float(g.reward.std(ddof=0))})
    print(pd.DataFrame(rows).sort_values("events", ascending=False).to_string(index=False))

    # -- Figure 1: RPE convergence --
    plt.figure(figsize=(8, 4.3))
    for name, df, c in [("DISCRETE", discrete, "tab:blue"), ("CONTINUOUS", continuous, "tab:orange")]:
        roll = df.sort_values("cycle").reset_index(drop=True).rpe.abs().rolling(200, min_periods=20).mean()
        plt.plot(roll.values, label=name, color=c, lw=1.5)
    plt.xlabel("evaluation event #"); plt.ylabel("rolling mean |RPE| (win=200)")
    plt.title("Reward-prediction error converges toward 0")
    plt.legend(); plt.tight_layout()
    plt.savefig(os.path.join(figdir, "rpe_convergence.png"), dpi=130); plt.close()

    # -- Figure 2: MSE bars --
    plt.figure(figsize=(5, 4.3))
    plt.bar(["DISCRETE", "CONTINUOUS"], [mse_d, mse_c], color=["tab:blue", "tab:orange"])
    for i, v in enumerate([mse_d, mse_c]):
        plt.text(i, v, f"{v:.2e}", ha="center", va="bottom")
    plt.ylabel("post-warmup prediction MSE"); plt.title("DISCRETE vs CONTINUOUS")
    plt.tight_layout(); plt.savefig(os.path.join(figdir, "mse_comparison.png"), dpi=130); plt.close()

    # ---------------------------------------------------------------------------
    # 3. Behaviour: action distribution per arm (the ActionTendency effect)
    # ---------------------------------------------------------------------------
    print("\n" + "=" * 72)
    print("3. ACTION DISTRIBUTION per arm (ActionTendency fixes over-sleeping)")
    print("=" * 72)
    dist = {}
    for arm in ARMS:
        a = load(arm, "chosen_actions.csv")
        d = a.action.value_counts(normalize=True).mul(100).round(1)
        dist[arm] = d
        quiet = d.reindex(list(QUIETING)).fillna(0).sum()
        forage = d.reindex(list(FORAGING)).fillna(0).sum()
        sleep = d.get("SLEEP", 0.0)
        print(f"[{arm:10s}] SLEEP={sleep:5.1f}%  quieting={quiet:5.1f}%  foraging(APPROACH+EAT)={forage:5.1f}%")

    all_actions = sorted(set().union(*[set(d.index) for d in dist.values()]))
    x = np.arange(len(all_actions)); w = 0.25
    plt.figure(figsize=(9, 4.3))
    for i, arm in enumerate(ARMS):
        plt.bar(x + (i - 1) * w, [dist[arm].get(a, 0.0) for a in all_actions], w, label=arm)
    plt.xticks(x, all_actions, rotation=30, ha="right"); plt.ylabel("% of chosen actions")
    plt.title("Action distribution: baseline (82% SLEEP) vs ActionTendency arms (foraging)")
    plt.legend(); plt.tight_layout()
    plt.savefig(os.path.join(figdir, "action_distribution.png"), dpi=130); plt.close()

    # ---------------------------------------------------------------------------
    # 4. Tonic dopamine / serotonin over time (requested graph)
    # ---------------------------------------------------------------------------
    print("\n" + "=" * 72)
    print("4. TONIC NEUROMODULATORS over time (mean across creatures)")
    print("=" * 72)
    plt.figure(figsize=(8, 4.3))
    for arm, c in [("discrete", "tab:blue"), ("continuous", "tab:orange")]:
        nm = load(arm, "neuromodulator_state_log.csv")
        # Average across creatures at each seq (binned) to get a population tonic trace.
        nm["bin"] = (nm["seq"] // 50) * 50
        g = nm.groupby("bin")[["dopamine", "serotonin"]].mean()
        plt.plot(g.index, g.dopamine, color=c, lw=1.3, label=f"{arm} dopamine")
        plt.plot(g.index, g.serotonin, color=c, lw=1.3, ls="--", label=f"{arm} serotonin")
        print(f"[{arm:10s}] mean dopamine={nm.dopamine.mean():.3f}  mean serotonin={nm.serotonin.mean():.3f}")
    plt.xlabel("publish seq (per creature)"); plt.ylabel("tonic concentration")
    plt.title("Tonic dopamine (solid) & serotonin (dashed) over a run")
    plt.legend(fontsize=8); plt.tight_layout()
    plt.savefig(os.path.join(figdir, "neuromodulators_over_time.png"), dpi=130); plt.close()

    print("\nFigures written to", figdir)


if __name__ == "__main__":
    main()
