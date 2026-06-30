"""
EXP-51 analysis: internal-aware Critic — SLEEP bias elimination.

Compares:
  EXP-48B (dual-encoder, blind Critic)   data/exp_48_b/
  EXP-51  (dual-encoder, aware Critic)   data/exp_51/

Metric of interest: Mode-2 SLEEP selection rate.
Baseline (blind Critic, same architecture): 95.7% — measured on current arch.
Target: significant reduction in SLEEP % with comparable or better lifetime.

NOTE: The original EXP-48B (288s, 94.4% SLEEP) used a DIFFERENT model architecture
(Critic input: z_next+action only, live_dims=[0,1]). The fair blind-Critic baseline
trained on p9 data with current arch (Critic input: z_next+z_internal+action, live_dims
=[0,1,4,5]) gives 210s and 95.7% SLEEP. All comparisons use this new baseline.

Usage:
    python3 analysis/exp_51_internal_critic.py
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
FIG_DIR = os.path.join(ROOT, "docs", "figures", "exp_51")
RPT_DIR = os.path.join(ROOT, "docs", "reports")
os.makedirs(FIG_DIR, exist_ok=True)
os.makedirs(RPT_DIR, exist_ok=True)


# ── helpers ──────────────────────────────────────────────────────────────────

def load_lifetimes(exp_dir):
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


def load_internal_states(exp_dir):
    """Load InternalDynamicStateExtractor output if present."""
    frames = []
    for p in glob.glob(os.path.join(exp_dir, "*:*", "trajectory_emotions.csv")):
        frames.append(pd.read_csv(p))
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def sleep_rate_in_mode2(df):
    if df.empty or "WORLD_MODEL" not in df.get("selection_type", pd.Series()).values:
        return None, 0
    wm = df[df["selection_type"] == "WORLD_MODEL"]
    rate = (wm["action_type"] == "SLEEP").sum() / len(wm) * 100
    return rate, len(wm)


def action_distribution_mode2(df):
    if df.empty or "WORLD_MODEL" not in df.get("selection_type", pd.Series()).values:
        return pd.Series(dtype=float)
    wm = df[df["selection_type"] == "WORLD_MODEL"]
    return wm["action_type"].value_counts(normalize=True) * 100


# ── load data ─────────────────────────────────────────────────────────────────

e48b_dir = os.path.join(ROOT, "data", "exp_48_b")
e51_dir  = os.path.join(ROOT, "data", "exp_51")

lt_48b = load_lifetimes(e48b_dir)
lt_51  = load_lifetimes(e51_dir)

act_48b = load_actions(e48b_dir)
act_51  = load_actions(e51_dir)

cons_48b = load_consolidation(e48b_dir)
cons_51  = load_consolidation(e51_dir)

emo_51 = load_internal_states(e51_dir)


# ── text output ───────────────────────────────────────────────────────────────

print("=" * 65)
print("EXP-51: Internal-Aware Critic — SLEEP bias analysis")
print("=" * 65)

# Lifetimes
print("\nLifetime summary (seconds)")
print("-" * 65)
for label, lt in [("EXP-48B (dual-enc, blind Critic)", lt_48b),
                  ("EXP-51  (dual-enc, aware Critic)",  lt_51)]:
    if len(lt) == 0:
        print(f"  {label}: NO DATA")
    else:
        print(f"  {label}: n={len(lt):3d}  "
              f"median={np.median(lt):.0f}s  mean={np.mean(lt):.0f}s  "
              f"min={np.min(lt):.0f}s  max={np.max(lt):.0f}s")

if len(lt_48b) > 0 and len(lt_51) > 0:
    u, p = stats.mannwhitneyu(lt_51, lt_48b, alternative="greater")
    print(f"\n  EXP-51 > EXP-48B: U={u:.0f}, p={p:.4f} {'*' if p < 0.05 else ''}")

# SLEEP rate
print("\nMode-2 SLEEP selection rate")
print("-" * 65)
rates = {}
for label, df, key in [("EXP-48B (blind Critic)", act_48b, "48b"),
                        ("EXP-51  (aware Critic)", act_51,  "51")]:
    rate, n = sleep_rate_in_mode2(df)
    if rate is None:
        print(f"  {label}: no Mode-2 data")
    else:
        flag = "✓" if rate < 20 else "✗"
        print(f"  {label}: {rate:.1f}%  (n={n} Mode-2 decisions) {flag}")
        rates[key] = (rate, n)

# Action distribution
print("\nMode-2 action distribution")
print("-" * 65)
for label, df in [("EXP-48B (blind Critic)", act_48b),
                  ("EXP-51  (aware Critic)", act_51)]:
    dist = action_distribution_mode2(df)
    if dist.empty:
        print(f"  {label}: no data")
        continue
    print(f"  {label}:")
    for action, pct in dist.items():
        print(f"    {action:15s}: {pct:5.1f}%")

# Internal state correlation (EXP-51 only)
if not emo_51.empty and not act_51.empty:
    print("\nSLEEP decision vs. hunger at decision time (EXP-51)")
    print("-" * 65)
    wm51 = act_51[act_51["selection_type"] == "WORLD_MODEL"].copy() if not act_51.empty else pd.DataFrame()
    if not wm51.empty and "hunger" in emo_51.columns:
        # Merge action decisions with the most recent emotion state
        wm51 = wm51.sort_values("action_time")
        emo_sorted = emo_51.sort_values("regulation_time")
        hunger_at_sleep = []
        hunger_at_other = []
        for _, row in wm51.iterrows():
            prior = emo_sorted[(emo_sorted["creatureKey"] == row["creatureKey"]) &
                               (emo_sorted["regulation_time"] <= row["action_time"])]
            if prior.empty:
                continue
            h = prior.iloc[-1]["hunger"]
            if row["action_type"] == "SLEEP":
                hunger_at_sleep.append(h)
            else:
                hunger_at_other.append(h)
        if hunger_at_sleep and hunger_at_other:
            print(f"  Mean hunger when SLEEP chosen: {np.mean(hunger_at_sleep):.3f}")
            print(f"  Mean hunger when other chosen: {np.mean(hunger_at_other):.3f}")
            u, p = stats.mannwhitneyu(hunger_at_other, hunger_at_sleep, alternative="greater")
            print(f"  Hunger(other) > Hunger(sleep) test: p={p:.4f} {'*' if p < 0.05 else ''}")
            print("  (negative result = Critic correctly lowers SLEEP value when hungry)")


# ── figures ───────────────────────────────────────────────────────────────────

experiments_lt = [
    ("EXP-48B\n(dual-enc\nblind Critic)", lt_48b, "#FF9800"),
    ("EXP-51\n(dual-enc\naware Critic)", lt_51,  "#4CAF50"),
]

# Fig 1 — lifetime comparison
fig, ax = plt.subplots(figsize=(7, 5))
for i, (lbl, lt, col) in enumerate(experiments_lt):
    if len(lt) == 0:
        ax.bar(i, 0, color=col, alpha=0.5, label=f"{lbl} (no data)")
        continue
    ax.bar(i, np.median(lt) / 60, color=col, alpha=0.75, label=lbl)
    ax.scatter([i]*len(lt), lt / 60, color="black", zorder=5, s=60)
ax.axhline(4000 / 60, color="gray", linestyle="--", linewidth=1, label="Target (4000 s)")
ax.set_xticks(range(len(experiments_lt)))
ax.set_xticklabels([e[0] for e in experiments_lt], fontsize=9)
ax.set_ylabel("Lifetime (minutes)")
ax.set_title("EXP-51: creature lifetime — blind vs aware Critic")
ax.legend(fontsize=8)
fig.tight_layout()
fig.savefig(os.path.join(FIG_DIR, "fig1_lifetime_comparison.png"), dpi=150)
plt.close(fig)
print("\nSaved fig1_lifetime_comparison.png")

# Fig 2 — Mode-2 SLEEP rate comparison with EXP-48 baselines
all_baselines = []
# Load EXP-43 for reference if available
e43_dir = os.path.join(ROOT, "data", "exp_43")
act_43 = load_actions(e43_dir)
for label, df, col in [("EXP-43\n(zero-init)", act_43, "#F44336"),
                        ("EXP-48B\n(blind Critic)", act_48b, "#FF9800"),
                        ("EXP-51\n(aware Critic)", act_51, "#4CAF50")]:
    rate, n = sleep_rate_in_mode2(df)
    if rate is not None:
        all_baselines.append((label, rate, col))

if all_baselines:
    fig, ax = plt.subplots(figsize=(8, 4))
    labels, sleep_rates, colors = zip(*all_baselines)
    ax.bar(labels, sleep_rates, color=colors, alpha=0.75)
    ax.axhline(20, color="gray", linestyle="--", linewidth=1.2, label="Target threshold (20%)")
    ax.set_ylabel("SLEEP rate in Mode-2 decisions (%)")
    ax.set_title("EXP-51: Mode-2 SLEEP selection rate (vs prior experiments)")
    ax.legend()
    fig.tight_layout()
    fig.savefig(os.path.join(FIG_DIR, "fig2_sleep_rate_comparison.png"), dpi=150)
    plt.close(fig)
    print("Saved fig2_sleep_rate_comparison.png")

# Fig 3 — Mode-2 action distribution side-by-side
action_data = [("EXP-48B\n(blind Critic)", act_48b), ("EXP-51\n(aware Critic)", act_51)]
action_data = [(l, d) for l, d in action_data if not d.empty and
               "WORLD_MODEL" in d.get("selection_type", pd.Series()).values]
if action_data:
    fig, axes = plt.subplots(1, len(action_data), figsize=(5*len(action_data), 5), sharey=False)
    if len(action_data) == 1:
        axes = [axes]
    cols = ["#FF9800", "#4CAF50"]
    for ax, (label, df), col in zip(axes, action_data, cols):
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

# Fig 4 — consolidation loss: EXP-48B vs EXP-51
fig, axes = plt.subplots(1, 2, figsize=(12, 5))
for ax, (label, df) in zip(axes, [("EXP-48B (blind Critic)", cons_48b),
                                   ("EXP-51 (aware Critic)",  cons_51)]):
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


# ── report skeleton ───────────────────────────────────────────────────────────

e51_rate, e51_n = sleep_rate_in_mode2(act_51)
e48b_rate, _ = sleep_rate_in_mode2(act_48b)
target_met = e51_rate is not None and e51_rate < 20

rpt = f"""# EXP-51 — Internal-Aware Critic: SLEEP Bias Elimination

## Purpose

Determine whether giving the Critic access to `z_internal` (the creature's homeostatic
state encoded by `InternalEncoder`) eliminates the persistent Mode-2 SLEEP bias.

EXP-48B established the baseline: 94.4% SLEEP rate with a dual-encoder Predictor but
a Critic still blind to internal state.

## Assumptions

1. The SLEEP bias is caused by the Critic's inability to distinguish high-hunger from
   low-hunger states (confirmed by EXP-48B null result).
2. Giving the Critic `concat(z_next[64], z_internal[16])` → 80-dim input is sufficient
   for it to learn hunger-conditional action values.
3. Training data with plant perceptions (CACTUS/ALOE now collidable after PR #50) enriches
   the experience space and improves generalisation.

## Hypothesis

`Critic(concat(z_next, z_internal), action)` will assign lower value to SLEEP when hunger
is high, causing Mode-2 SLEEP rate to drop below 20%.

## Results

| Experiment  | SLEEP rate (Mode-2) | n decisions |
|-------------|---------------------|-------------|
| EXP-43 (baseline, old model) | 94.6% | — |
| EXP-48A (single-encoder retrain) | 94.2% | — |
| EXP-48B (dual-encoder, blind Critic) | 94.4% | — |
| EXP-51 (dual-encoder, aware Critic) | {f"{e51_rate:.1f}%" if e51_rate is not None else "NO DATA"} | {e51_n} |

Target: < 20% SLEEP rate.
Outcome: {"**TARGET MET ✓**" if target_met else "**TARGET NOT MET ✗**"}

## Analysis

See figures in `docs/figures/exp_51/`:
- `fig1_lifetime_comparison.png` — creature lifespan EXP-48B vs EXP-51
- `fig2_sleep_rate_comparison.png` — SLEEP rate across all experiments
- `fig3_mode2_action_distribution.png` — full Mode-2 action breakdown
- `fig4_consolidation_loss.png` — per-creature consolidation loss curves
"""

rpt_path = os.path.join(RPT_DIR, "EXP_51_INTERNAL_CRITIC.md")
with open(rpt_path, "w") as f:
    f.write(rpt)
print(f"\nReport written to {rpt_path}")
print("\nDone.")
