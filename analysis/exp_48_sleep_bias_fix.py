"""
EXP-48 analysis: species Critic SLEEP bias — dual-encoder vs retrain-only vs baseline.

Compares:
  EXP-43  — zero-init adapter, old model (2-apple world, 2026-06-22)    data/exp_43/
  EXP-48A — retrained single-encoder on full-world data                  data/exp_48_a/
  EXP-48B — retrained dual-encoder (WorldEncoder + InternalEncoder)      data/exp_48_b/

Usage:
    python3 analysis/exp_48_sleep_bias_fix.py
"""

import os
import glob
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy import stats

ROOT    = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIG_DIR = os.path.join(ROOT, "docs", "figures", "exp_48")
os.makedirs(FIG_DIR, exist_ok=True)


# ── helpers ──────────────────────────────────────────────────────────────────

def load_lifetimes(exp_dir):
    """Return np.array of lifetimes (seconds) from lifetimes.csv or creature_state.csv."""
    lt_path = os.path.join(exp_dir, "lifetimes.csv")
    cs_path = os.path.join(exp_dir, "creature_state.csv")
    if os.path.exists(lt_path):
        df = pd.read_csv(lt_path)
        return df["lifetime"].values
    if os.path.exists(cs_path):
        df = pd.read_csv(cs_path)
        df["lifetime_s"] = (df["deadtime"] - df["borntime"]) / 1000.0
        return df["lifetime_s"].values
    return np.array([])


def load_actions(exp_dir):
    frames = []
    for p in glob.glob(os.path.join(exp_dir, "*:*", "trajectory_actions.csv")):
        frames.append(pd.read_csv(p))
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def load_consolidation(exp_dir):
    frames = []
    for p in glob.glob(os.path.join(exp_dir, "*:*", "consolidation_batches.csv")):
        frames.append(pd.read_csv(p))
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def print_lifetime_summary(label, lt):
    if len(lt) == 0:
        print(f"  {label:35s}: NO DATA")
        return
    print(f"  {label:35s}: n={len(lt)}  median={np.median(lt):.1f}s  "
          f"mean={np.mean(lt):.1f}s  min={np.min(lt):.1f}s  max={np.max(lt):.1f}s")


def print_mode2_distribution(label, df):
    if df.empty:
        print(f"\n  {label}: no action data")
        return
    total = len(df)
    sel = df["selection_type"].value_counts()
    print(f"\n  {label}  (total decisions: {total})")
    for s, cnt in sel.items():
        print(f"    {s:15s}: {cnt:6d} ({cnt/total*100:.1f}%)")
    if "WORLD_MODEL" in df["selection_type"].values:
        wm = df[df["selection_type"] == "WORLD_MODEL"]
        act = wm["action_type"].value_counts()
        print(f"  → Mode-2 fires (n={len(wm)}):")
        for a, cnt in act.items():
            print(f"      {a:15s}: {cnt:6d} ({cnt/len(wm)*100:.1f}%)")


# ── load data ─────────────────────────────────────────────────────────────────

e43_dir = os.path.join(ROOT, "data", "exp_43")
e48a_dir = os.path.join(ROOT, "data", "exp_48_a")
e48b_dir = os.path.join(ROOT, "data", "exp_48_b")

lt_43  = load_lifetimes(e43_dir)
lt_48a = load_lifetimes(e48a_dir)
lt_48b = load_lifetimes(e48b_dir)

act_43  = load_actions(e43_dir)
act_48a = load_actions(e48a_dir)
act_48b = load_actions(e48b_dir)

cons_48a = load_consolidation(e48a_dir)
cons_48b = load_consolidation(e48b_dir)

# ── lifetime summary ──────────────────────────────────────────────────────────

print("=" * 65)
print("Lifetime summary (seconds)")
print("=" * 65)
print_lifetime_summary("EXP-43 (zero-init, old model)", lt_43)
print_lifetime_summary("EXP-48A (retrain, single-encoder)", lt_48a)
print_lifetime_summary("EXP-48B (retrain, dual-encoder)", lt_48b)

print("\nStatistical tests (Mann-Whitney U):")
for (la, a), (lb, b) in [
    (("EXP-48A", lt_48a), ("EXP-43", lt_43)),
    (("EXP-48B", lt_48b), ("EXP-43", lt_43)),
    (("EXP-48B", lt_48b), ("EXP-48A", lt_48a)),
]:
    if len(a) == 0 or len(b) == 0:
        print(f"  {la} > {lb}: NO DATA")
        continue
    u, p = stats.mannwhitneyu(a, b, alternative="greater")
    print(f"  {la} > {lb}: U={u:.0f}, p={p:.4f} {'*' if p < 0.05 else ''}")

# ── action distribution ───────────────────────────────────────────────────────

print("\n" + "=" * 65)
print("Mode-2 action distribution")
print("=" * 65)
print_mode2_distribution("EXP-43 (old model)", act_43)
print_mode2_distribution("EXP-48A (single-encoder retrain)", act_48a)
print_mode2_distribution("EXP-48B (dual-encoder)", act_48b)

# SLEEP selection rate per experiment
print("\nSLEEP rate in Mode-2 (target: < 20%):")
for label, df in [("EXP-43", act_43), ("EXP-48A", act_48a), ("EXP-48B", act_48b)]:
    if df.empty or "WORLD_MODEL" not in df.get("selection_type", pd.Series()).values:
        print(f"  {label}: no Mode-2 data")
        continue
    wm = df[df["selection_type"] == "WORLD_MODEL"]
    sleep_pct = (wm["action_type"] == "SLEEP").sum() / len(wm) * 100
    flag = "✓" if sleep_pct < 20 else "✗"
    print(f"  {label}: {sleep_pct:.1f}% {flag}")

# ── consolidation loss ────────────────────────────────────────────────────────

print("\n" + "=" * 65)
print("Consolidation loss (first 10 vs last 10 batches)")
print("=" * 65)
for label, df in [("EXP-48A", cons_48a), ("EXP-48B", cons_48b)]:
    if df.empty:
        print(f"  {label}: no consolidation data")
        continue
    first10 = df.groupby("creature_key").apply(lambda g: g.head(10)["loss"].mean())
    last10  = df.groupby("creature_key").apply(lambda g: g.tail(10)["loss"].mean())
    print(f"\n  {label}:")
    for cid in sorted(first10.index):
        print(f"    creature {cid}: first10={first10[cid]:.3f}  last10={last10[cid]:.3f}  "
              f"reduction={100*(1-last10[cid]/first10[cid]):.1f}%")

# ── figures ───────────────────────────────────────────────────────────────────

experiments = [
    ("EXP-43\n(zero-init\nold model)", lt_43,  "#F44336"),
    ("EXP-48A\n(retrain\nsingle-enc)", lt_48a, "#FF9800"),
    ("EXP-48B\n(retrain\ndual-enc)",   lt_48b, "#4CAF50"),
]

# Fig 1 — lifetime comparison
fig, ax = plt.subplots(figsize=(9, 5))
for i, (lbl, lt, col) in enumerate(experiments):
    if len(lt) == 0:
        continue
    ax.bar(i, np.median(lt) / 60, color=col, alpha=0.75, label=lbl)
    ax.scatter([i]*len(lt), lt / 60, color="black", zorder=5, s=60)
ax.axhline(4000 / 60, color="gray", linestyle="--", linewidth=1, label="Target (4000 s)")
ax.set_xticks(range(len(experiments)))
ax.set_xticklabels([e[0] for e in experiments], fontsize=9)
ax.set_ylabel("Lifetime (minutes)")
ax.set_title("EXP-48: creature lifetime — retrain vs dual-encoder")
ax.legend(fontsize=8)
fig.tight_layout()
fig.savefig(os.path.join(FIG_DIR, "fig1_lifetime_comparison.png"), dpi=150)
plt.close(fig)
print("\nSaved fig1_lifetime_comparison.png")

# Fig 2 — Mode-2 SLEEP rate comparison
sleep_rates = []
exp_labels  = []
for label, df in [("EXP-43", act_43), ("EXP-48A", act_48a), ("EXP-48B", act_48b)]:
    if df.empty or "WORLD_MODEL" not in df.get("selection_type", pd.Series()).values:
        continue
    wm = df[df["selection_type"] == "WORLD_MODEL"]
    sleep_rates.append((wm["action_type"] == "SLEEP").sum() / len(wm) * 100)
    exp_labels.append(label)

if sleep_rates:
    fig, ax = plt.subplots(figsize=(7, 4))
    colors = ["#F44336", "#FF9800", "#4CAF50"][:len(sleep_rates)]
    ax.bar(exp_labels, sleep_rates, color=colors, alpha=0.75)
    ax.axhline(20, color="gray", linestyle="--", linewidth=1.2, label="Target threshold (20%)")
    ax.set_ylabel("SLEEP rate in Mode-2 decisions (%)")
    ax.set_title("EXP-48: Mode-2 SLEEP selection rate")
    ax.legend()
    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, "fig2_sleep_rate.png"), dpi=150)
    plt.close(fig)
    print("Saved fig2_sleep_rate.png")

# Fig 3 — Mode-2 action distribution side-by-side
action_data = [("EXP-43", act_43), ("EXP-48A", act_48a), ("EXP-48B", act_48b)]
action_data = [(l, d) for l, d in action_data
               if not d.empty and "WORLD_MODEL" in d.get("selection_type", pd.Series()).values]

if action_data:
    fig, axes = plt.subplots(1, len(action_data), figsize=(5 * len(action_data), 5), sharey=False)
    if len(action_data) == 1:
        axes = [axes]
    colors_map = ["#F44336", "#FF9800", "#4CAF50"]
    for ax, (label, df), col in zip(axes, action_data, colors_map):
        wm = df[df["selection_type"] == "WORLD_MODEL"]
        act_counts = wm["action_type"].value_counts()
        ax.bar(act_counts.index, act_counts.values / len(wm) * 100, color=col, alpha=0.75)
        ax.set_title(f"Mode-2 action %\n{label}")
        ax.set_ylabel("% of Mode-2 decisions")
        ax.tick_params(axis="x", rotation=35)
    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, "fig3_mode2_action_distribution.png"), dpi=150)
    plt.close(fig)
    print("Saved fig3_mode2_action_distribution.png")

# Fig 4 — consolidation loss for 48A and 48B
fig, axes = plt.subplots(1, 2, figsize=(12, 5))
for ax, (label, df) in zip(axes, [("EXP-48A (single-enc)", cons_48a),
                                   ("EXP-48B (dual-enc)", cons_48b)]):
    if df.empty:
        ax.text(0.5, 0.5, "no data", ha="center", va="center", transform=ax.transAxes)
        ax.set_title(label)
        continue
    for cid, grp in df.groupby("creature_key"):
        ax.plot(grp.reset_index(drop=True)["loss"].values, alpha=0.8, label=f"Creature {cid}")
    ax.set_xlabel("Batch index (cumulative)")
    ax.set_ylabel("MSE loss")
    ax.set_title(f"Consolidation loss\n{label}")
    ax.legend(fontsize=8)
fig.tight_layout()
fig.savefig(os.path.join(FIG_DIR, "fig4_consolidation_loss.png"), dpi=150)
plt.close(fig)
print("Saved fig4_consolidation_loss.png")

print("\nDone.")
