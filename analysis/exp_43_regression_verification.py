"""
EXP-43 regression verification: compare creature lifetimes and Mode-2
action distribution before and after zero-init adapter fix.

Baselines:
  EXP-P5-1  — no Mode-2, random adapter (sleep only)     data/exp_p5_1/
  EXP-P6-1  — Mode-2, RANDOM-init adapter (regression)   data/exp_p6_1/
  EXP-43    — Mode-2, ZERO-init  adapter (fix)            data/exp_43/
"""

import os
import glob
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy import stats

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIG_DIR = os.path.join(ROOT, "docs", "figures", "exp_43")
os.makedirs(FIG_DIR, exist_ok=True)


# ── helpers ──────────────────────────────────────────────────────────────────

def load_creature_state(exp_dir):
    """Return DataFrame with lifetime_s column."""
    path = os.path.join(exp_dir, "creature_state.csv")
    df = pd.read_csv(path)
    df["lifetime_s"] = (df["deadtime"] - df["borntime"]) / 1000.0
    return df


def load_lifetimes_csv(exp_dir):
    """Return Series of lifetimes (seconds) from lifetimes.csv if present."""
    path = os.path.join(exp_dir, "lifetimes.csv")
    if not os.path.exists(path):
        return None
    df = pd.read_csv(path)
    return df["lifetime"].values


def load_per_creature_actions(data_dir):
    """Collect trajectory_actions.csv from all creature sub-dirs."""
    frames = []
    for p in glob.glob(os.path.join(data_dir, "*:*", "trajectory_actions.csv")):
        frames.append(pd.read_csv(p))
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def load_per_creature_consolidation(data_dir):
    """Collect consolidation_batches.csv from all creature sub-dirs."""
    frames = []
    for p in glob.glob(os.path.join(data_dir, "*:*", "consolidation_batches.csv")):
        frames.append(pd.read_csv(p))
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


# ── data loading ─────────────────────────────────────────────────────────────

p5_dir  = os.path.join(ROOT, "data", "exp_p5_1")
p6_dir  = os.path.join(ROOT, "data", "exp_p6_1")
e43_dir = os.path.join(ROOT, "data", "exp_43")

# P5-1 and P6-1 have creature_state.csv; EXP-43 has lifetimes.csv
p5_lt   = load_creature_state(p5_dir)["lifetime_s"].values
p6_lt   = load_creature_state(p6_dir)["lifetime_s"].values
e43_lt  = load_lifetimes_csv(e43_dir)

print("=== Lifetime summary (seconds) ===")
for label, lt in [("P5-1 (no Mode-2)", p5_lt),
                  ("P6-1 (random init)", p6_lt),
                  ("EXP-43 (zero-init)", e43_lt)]:
    print(f"  {label:25s}: n={len(lt)}  median={np.median(lt):.1f}s  "
          f"mean={np.mean(lt):.1f}s  min={np.min(lt):.1f}s  max={np.max(lt):.1f}s")

# Mann-Whitney U: EXP-43 vs P6-1
stat_43_p6, p_43_p6 = stats.mannwhitneyu(e43_lt, p6_lt, alternative="greater")
print(f"\nMann-Whitney U (EXP-43 > P6-1): U={stat_43_p6:.0f}, p={p_43_p6:.4f}")

stat_43_p5, p_43_p5 = stats.mannwhitneyu(e43_lt, p5_lt, alternative="two-sided")
print(f"Mann-Whitney U (EXP-43 vs P5-1): U={stat_43_p5:.0f}, p={p_43_p5:.4f}")

# ── action selection distribution ────────────────────────────────────────────

actions_p6  = load_per_creature_actions(p6_dir)
actions_e43 = load_per_creature_actions(e43_dir)

print("\n=== Mode-2 (WORLD_MODEL) selection type distribution ===")
for label, df in [("P6-1 (random-init)", actions_p6),
                  ("EXP-43 (zero-init)", actions_e43)]:
    if df.empty:
        print(f"  {label}: no data")
        continue
    sel_counts = df["selection_type"].value_counts()
    total = len(df)
    wm_pct = sel_counts.get("WORLD_MODEL", 0) / total * 100
    print(f"\n  {label}  (total decisions: {total})")
    for sel, cnt in sel_counts.items():
        print(f"    {sel:15s}: {cnt:6d} ({cnt/total*100:.1f}%)")

    if "WORLD_MODEL" in df["selection_type"].values:
        wm = df[df["selection_type"] == "WORLD_MODEL"]
        act_counts = wm["action_type"].value_counts()
        print(f"  → When Mode-2 fires (n={len(wm)}):")
        for act, cnt in act_counts.items():
            print(f"      {act:15s}: {cnt:6d} ({cnt/len(wm)*100:.1f}%)")

# ── consolidation loss comparison ─────────────────────────────────────────────

cons_p6  = load_per_creature_consolidation(p6_dir)
cons_e43 = load_per_creature_consolidation(e43_dir)

print("\n=== Consolidation loss (first 10 batches vs last 10 batches) ===")
for label, df in [("P6-1", cons_p6), ("EXP-43", cons_e43)]:
    if df.empty:
        print(f"  {label}: no data")
        continue
    first10 = df.groupby("creature_key").apply(lambda g: g.head(10)["loss"].mean())
    last10  = df.groupby("creature_key").apply(lambda g: g.tail(10)["loss"].mean())
    print(f"\n  {label}:")
    for cid in sorted(first10.index):
        print(f"    creature {cid}: first10={first10[cid]:.3f}  last10={last10[cid]:.3f}  "
              f"reduction={100*(1-last10[cid]/first10[cid]):.1f}%")

# ── figures ──────────────────────────────────────────────────────────────────

# Fig 1 — lifetime comparison (bar chart with individual points)
fig, ax = plt.subplots(figsize=(8, 5))
data_sets = [p5_lt / 60, p6_lt / 60, e43_lt / 60]
labels    = ["P5-1\n(no Mode-2)", "P6-1\n(random init)", "EXP-43\n(zero-init fix)"]
colors    = ["#4CAF50", "#F44336", "#2196F3"]
for i, (d, lbl, col) in enumerate(zip(data_sets, labels, colors)):
    ax.bar(i, np.median(d), color=col, alpha=0.7, label=lbl)
    ax.scatter([i]*len(d), d, color="black", zorder=5, s=50)
ax.set_xticks(range(len(labels)))
ax.set_xticklabels(labels)
ax.set_ylabel("Lifetime (minutes)")
ax.set_title("Creature lifetime comparison across experiments")
ax.set_ylim(0, max(np.max(p5_lt)/60 * 1.15, 1))
fig.tight_layout()
fig.savefig(os.path.join(FIG_DIR, "fig1_lifetime_comparison.png"), dpi=150)
plt.close(fig)
print(f"\nSaved fig1_lifetime_comparison.png")

# Fig 2 — Mode-2 action distribution comparison (P6-1 vs EXP-43)
fig, axes = plt.subplots(1, 2, figsize=(12, 5))
for ax, (label, df) in zip(axes, [("P6-1 (random-init)", actions_p6),
                                   ("EXP-43 (zero-init)", actions_e43)]):
    if df.empty or "WORLD_MODEL" not in df["selection_type"].values:
        ax.text(0.5, 0.5, "no Mode-2 data", ha="center", va="center")
        ax.set_title(label)
        continue
    wm = df[df["selection_type"] == "WORLD_MODEL"]
    act_counts = wm["action_type"].value_counts()
    ax.bar(act_counts.index, act_counts.values, color="#2196F3")
    ax.set_title(f"Mode-2 action choices\n{label}")
    ax.set_ylabel("Count")
    ax.tick_params(axis="x", rotation=30)
fig.tight_layout()
fig.savefig(os.path.join(FIG_DIR, "fig2_mode2_action_distribution.png"), dpi=150)
plt.close(fig)
print("Saved fig2_mode2_action_distribution.png")

# Fig 3 — Consolidation loss trajectory EXP-43
if not cons_e43.empty:
    fig, ax = plt.subplots(figsize=(9, 5))
    for cid, grp in cons_e43.groupby("creature_key"):
        ax.plot(grp.reset_index(drop=True)["loss"].values, alpha=0.8, label=f"Creature {cid}")
    ax.set_xlabel("Batch index (cumulative)")
    ax.set_ylabel("MSE loss")
    ax.set_title("EXP-43: consolidation loss trajectory (all creatures)")
    ax.legend()
    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, "fig3_consolidation_loss_exp43.png"), dpi=150)
    plt.close(fig)
    print("Saved fig3_consolidation_loss_exp43.png")

print("\nDone.")
