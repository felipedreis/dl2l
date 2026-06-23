#!/usr/bin/env python3
"""
EXP-P5-1: Sleep consolidation analysis — Phase 5 DJL integration.

Reads from data/exp_p5_1/ (CSV exports from PostgreSQL) and writes
figures to docs/reports/figures/exp_p5_1/.
"""

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
from pathlib import Path

REPO = Path(__file__).parent.parent
DATA = REPO / "data" / "exp_p5_1"
FIGS = REPO / "docs" / "reports" / "figures" / "exp_p5_1"
FIGS.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------------------
# Load
# ---------------------------------------------------------------------------
eps   = pd.read_csv(DATA / "consolidation_episode_stat.csv")
batch = pd.read_csv(DATA / "consolidation_batch_stat.csv")
sleep = pd.read_csv(DATA / "sleep_episode_state.csv")
crea  = pd.read_csv(DATA / "creature_state.csv")

eps["aborted"] = eps["aborted"].map({"t": True, "f": False})

creatures = sorted(eps["creature_key"].unique())
PALETTE   = {c: col for c, col in zip(creatures, ["#2196F3", "#FF5722", "#4CAF50"])}
LABELS    = {c: f"Creature {c}" for c in creatures}

# ---------------------------------------------------------------------------
# Derived
# ---------------------------------------------------------------------------
eps = eps.sort_values(["creature_key", "onset_cycle"])
eps["episode_seq"] = eps.groupby("creature_key").cumcount()

batch = batch.sort_values(["creature_key", "onset_cycle", "batch_index"])
batch["batch_seq"] = batch.groupby("creature_key").cumcount()

# Rolling mean loss, NaN-aware (20-batch window)
batch["loss_valid"] = batch["loss"]  # NaN stays NaN, handled by rolling
batch["loss_roll20"] = (
    batch.groupby("creature_key")["loss_valid"]
    .transform(lambda s: s.rolling(20, min_periods=1).mean())
)

sleep["duration_ticks"] = sleep["duration_ticks"].astype(float)

# ---------------------------------------------------------------------------
# Print header
# ---------------------------------------------------------------------------
valid_counts = batch.groupby("creature_key")["loss"].apply(lambda s: s.notna().sum())
nan_counts   = batch.groupby("creature_key")["loss"].apply(lambda s: s.isna().sum())
print(f"Creatures: {creatures}")
print(f"Episodes:  {len(eps)} (aborted: {eps['aborted'].sum()})")
print(f"Batches (all): {len(batch)}")
for c in creatures:
    print(f"  Creature {c}: {valid_counts[c]} valid, {nan_counts[c]} NaN loss batches")
print(f"Sleep eps: {len(sleep)}")

# ---------------------------------------------------------------------------
# Fig 1 — Loss curve per creature (rolling mean on valid batches)
# ---------------------------------------------------------------------------
fig, ax = plt.subplots(figsize=(12, 5))
for c in creatures:
    b = batch[batch["creature_key"] == c]
    valid = b[b["loss"].notna()]
    ax.scatter(valid["batch_seq"], valid["loss"], s=1.5, alpha=0.12, color=PALETTE[c])
    ax.plot(b["batch_seq"], b["loss_roll20"], lw=1.8, color=PALETTE[c], label=LABELS[c])
ax.set_xlabel("Cumulative batch (across all sleep episodes)")
ax.set_ylabel("MSE loss (prediction-error)")
ax.set_title("Per-creature adapter training loss — full prediction-error chain\n"
             "encoder(perc) → adapter → predictor(z, action) → critic → MSE(pred_delta, actual_delta)")
ax.legend()
ax.set_yscale("log")
ax.set_ylim(bottom=0.05)
fig.tight_layout()
fig.savefig(FIGS / "fig1_loss_curve.png", dpi=150)
plt.close(fig)
print("Saved fig1_loss_curve.png")

# ---------------------------------------------------------------------------
# Fig 2 — Batches completed per episode
# ---------------------------------------------------------------------------
fig, axes = plt.subplots(1, 3, figsize=(14, 4), sharey=True)
for ax, c in zip(axes, creatures):
    e = eps[eps["creature_key"] == c]
    ax.bar(e["episode_seq"], e["batches_completed"], color=PALETTE[c], alpha=0.8, width=1.0)
    ax.set_title(LABELS[c])
    ax.set_xlabel("Sleep episode index")
axes[0].set_ylabel("Batches completed")
fig.suptitle("Training depth per sleep episode", fontsize=12)
fig.tight_layout()
fig.savefig(FIGS / "fig2_batches_per_episode.png", dpi=150)
plt.close(fig)
print("Saved fig2_batches_per_episode.png")

# ---------------------------------------------------------------------------
# Fig 3 — Engram count and mean eligibility
# ---------------------------------------------------------------------------
fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 7), sharex=False)
for c in creatures:
    e = eps[eps["creature_key"] == c]
    ax1.plot(e["episode_seq"], e["engram_count"],    lw=1.2, color=PALETTE[c], label=LABELS[c])
    ax2.plot(e["episode_seq"], e["mean_eligibility"], lw=1.2, color=PALETTE[c])
ax1.set_ylabel("Engrams in window")
ax1.set_title("Engram count per sleep episode")
ax1.legend()
ax2.set_ylabel("Mean eligibility weight")
ax2.set_xlabel("Sleep episode index")
ax2.set_title("Mean eligibility per episode (decay of recency weighting)")
ax2.set_ylim(0, 1)
fig.tight_layout()
fig.savefig(FIGS / "fig3_engrams_eligibility.png", dpi=150)
plt.close(fig)
print("Saved fig3_engrams_eligibility.png")

# ---------------------------------------------------------------------------
# Fig 4 — Sleep duration distribution
# ---------------------------------------------------------------------------
fig, ax = plt.subplots(figsize=(9, 4))
for c in creatures:
    s = sleep[sleep["creature_key"] == c]["duration_ticks"]
    ax.hist(s, bins=50, alpha=0.6, color=PALETTE[c], label=LABELS[c], density=True)
ax.set_xlabel("Sleep duration (cognitive cycles)")
ax.set_ylabel("Density")
ax.set_title("Distribution of sleep episode durations")
ax.legend()
fig.tight_layout()
fig.savefig(FIGS / "fig4_sleep_duration_dist.png", dpi=150)
plt.close(fig)
print("Saved fig4_sleep_duration_dist.png")

# ---------------------------------------------------------------------------
# Fig 5 — Within-episode convergence: first vs last batch loss
# ---------------------------------------------------------------------------
fig, axes = plt.subplots(1, 3, figsize=(14, 4))
for ax, c in zip(axes, creatures):
    b = batch[batch["creature_key"] == c][batch["loss"].notna()]
    grp = b.groupby("onset_cycle")
    def safe_at(g, fn):
        try: return g.loc[fn(g.index), "loss"]
        except: return np.nan
    first = grp.apply(lambda g: g.loc[g["batch_index"].idxmin(), "loss"] if len(g) > 0 else np.nan).dropna()
    last  = grp.apply(lambda g: g.loc[g["batch_index"].idxmax(), "loss"] if len(g) > 0 else np.nan).dropna()
    common = first.index.intersection(last.index)
    first, last = first.loc[common], last.loc[common]
    ep_idx = np.arange(len(first))
    ax.plot(ep_idx, first.values, lw=1.0, color=PALETTE[c], alpha=0.8, label="First batch")
    ax.plot(ep_idx, last.values,  lw=1.0, color=PALETTE[c], alpha=0.8, ls="--", label="Last batch")
    ax.fill_between(ep_idx,
                    np.minimum(first.values, last.values),
                    np.maximum(first.values, last.values),
                    alpha=0.10, color=PALETTE[c])
    ax.set_title(LABELS[c])
    ax.set_xlabel("Episode index (valid only)")
    ax.set_yscale("log")
axes[0].set_ylabel("Loss (log scale)")
axes[0].legend(fontsize=8)
fig.suptitle("Within-episode convergence: first vs last batch loss", fontsize=12)
fig.tight_layout()
fig.savefig(FIGS / "fig5_within_episode_convergence.png", dpi=150)
plt.close(fig)
print("Saved fig5_within_episode_convergence.png")

# ---------------------------------------------------------------------------
# Fig 6 — Sleep frequency over lifetime
# ---------------------------------------------------------------------------
fig, ax = plt.subplots(figsize=(11, 4))
for c in creatures:
    s = sleep[sleep["creature_key"] == c].sort_values("onset_cycle")
    max_cycle = int(s["wake_cycle"].max())
    bins = np.arange(0, max_cycle + 100, 100)
    counts, _ = np.histogram(s["onset_cycle"], bins=bins)
    ax.plot(bins[:-1], counts, lw=1.2, color=PALETTE[c], label=LABELS[c])
ax.set_xlabel("Cognitive cycle (onset time)")
ax.set_ylabel("Sleep onsets per 100-cycle window")
ax.set_title("Sleep frequency over lifetime")
ax.legend()
fig.tight_layout()
fig.savefig(FIGS / "fig6_sleep_frequency.png", dpi=150)
plt.close(fig)
print("Saved fig6_sleep_frequency.png")

# ---------------------------------------------------------------------------
# Fig 7 — Cumulative valid batch count (shows where NaN starts for creature 180)
# ---------------------------------------------------------------------------
fig, ax = plt.subplots(figsize=(10, 4))
for c in creatures:
    b = batch[batch["creature_key"] == c].sort_values("batch_seq")
    cum_valid = b["loss"].notna().cumsum()
    ax.plot(b["batch_seq"], cum_valid, lw=1.5, color=PALETTE[c], label=LABELS[c])
ax.set_xlabel("Cumulative batch index")
ax.set_ylabel("Cumulative valid (non-NaN) batches")
ax.set_title("Valid training batch accumulation per creature\n"
             "(plateau for creature 180 indicates gradient explosion onset)")
ax.legend()
fig.tight_layout()
fig.savefig(FIGS / "fig7_valid_batch_accumulation.png", dpi=150)
plt.close(fig)
print("Saved fig7_valid_batch_accumulation.png")

# ---------------------------------------------------------------------------
# Summary statistics
# ---------------------------------------------------------------------------
print("\n=== SUMMARY STATISTICS ===")
for c in creatures:
    e  = eps[eps["creature_key"] == c]
    b  = batch[batch["creature_key"] == c]
    bv = b[b["loss"].notna()].sort_values(["onset_cycle", "batch_index"])
    s  = sleep[sleep["creature_key"] == c]
    cr = crea[crea["creature_key"] == c].iloc[0]
    lifetime_min = (cr["deadtime"] - cr["borntime"]) / 60000.0

    n_complete = (e["aborted"] == False).sum()
    n_aborted  = (e["aborted"] == True).sum()
    total_batches_all   = len(b)
    total_batches_valid = len(bv)
    mean_batches_per_ep = e[e["batches_completed"] > 0]["batches_completed"].mean()
    nan_pct = (1 - total_batches_valid / total_batches_all) * 100 if total_batches_all > 0 else 0

    loss_first10 = bv.iloc[:10]["loss"].mean() if len(bv) >= 10 else float("nan")
    loss_last10  = bv.iloc[-10:]["loss"].mean() if len(bv) >= 10 else float("nan")
    loss_reduction = (1 - loss_last10 / loss_first10) * 100 if (not np.isnan(loss_first10) and loss_first10 > 0) else float("nan")

    print(f"\nCreature {c}:")
    print(f"  Lifetime:                 {lifetime_min:.1f} min")
    print(f"  Sleep episodes:           {len(e)}  (complete={n_complete}, aborted={n_aborted})")
    print(f"  Total training batches:   {total_batches_all}  (valid={total_batches_valid}, NaN={nan_pct:.1f}%)")
    print(f"  Mean batches/episode:     {mean_batches_per_ep:.1f}")
    print(f"  Mean engrams/episode:     {e['engram_count'].mean():.1f}")
    print(f"  Mean eligibility:         {e['mean_eligibility'].mean():.3f} ± {e['std_eligibility'].mean():.3f}")
    print(f"  Sleep dur:                {s['duration_ticks'].mean():.1f} ± {s['duration_ticks'].std():.1f} cycles")
    print(f"  Loss first 10 batches:    {loss_first10:.4f}")
    print(f"  Loss last 10 valid batches: {loss_last10:.4f}")
    print(f"  Lifetime loss reduction:  {loss_reduction:.1f}%")

print(f"\nFigures written to {FIGS}")
