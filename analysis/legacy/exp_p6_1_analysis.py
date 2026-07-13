#!/usr/bin/env python3
"""
EXP-P6-1: Mode-2 deliberative action selection — Phase 6 analysis.

Reads CSVs from data/exp_p6_1/ and writes figures to docs/reports/figures/exp_p6_1/.

Required CSV files (export via extractor or pg COPY):
  chosen_action_state.csv
  internal_dynamic_state_emotions.csv   (join of internal_dynamic_state + emotional_state)
  consolidation_episode_stat.csv
  consolidation_batch_stat.csv
  sleep_episode_state.csv
  creature_state.csv
  change_stimulus_state.csv
"""

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
from pathlib import Path

REPO = Path(__file__).parent.parent
DATA = REPO / "data" / "exp_p6_1"
FIGS = REPO / "docs" / "reports" / "figures" / "exp_p6_1"
FIGS.mkdir(parents=True, exist_ok=True)

HIGH_AROUSAL_THRESHOLD = 4.5

# ---------------------------------------------------------------------------
# Load
# ---------------------------------------------------------------------------
cas   = pd.read_csv(DATA / "chosen_action_state.csv")
emo   = pd.read_csv(DATA / "internal_dynamic_state_emotions.csv")
eps   = pd.read_csv(DATA / "consolidation_episode_stat.csv")
batch = pd.read_csv(DATA / "consolidation_batch_stat.csv")
sleep = pd.read_csv(DATA / "sleep_episode_state.csv")
crea  = pd.read_csv(DATA / "creature_state.csv")
css   = pd.read_csv(DATA / "change_stimulus_state.csv")

# Map creature key from change_stimulus_state join
cas = cas.merge(css[["id", "key", "time"]].rename(columns={"id": "css_id", "key": "creature_key"}),
                left_on="changestimulusstate_id", right_on="css_id", how="left")

emo = emo.merge(css[["id", "key", "time"]].rename(columns={"id": "css_id", "key": "creature_key"}),
                left_on="changestimulusstate_id", right_on="css_id", how="left")

emo["max_arousal"] = emo[["hunger_arausal", "sleep_arausal"]].max(axis=1)

eps["aborted"] = eps["aborted"].map({"t": True, "f": False})

creatures = sorted(cas["creature_key"].dropna().astype(int).unique())
PALETTE   = {c: col for c, col in zip(creatures, ["#2196F3", "#FF5722", "#4CAF50"])}
LABELS    = {c: f"Creature {c}" for c in creatures}

# ---------------------------------------------------------------------------
# Sort
# ---------------------------------------------------------------------------
cas   = cas.sort_values(["creature_key", "time"])
emo   = emo.sort_values(["creature_key", "time"])
eps   = eps.sort_values(["creature_key", "onset_cycle"])
batch = batch.sort_values(["creature_key", "onset_cycle", "batch_index"])

eps["episode_seq"]  = eps.groupby("creature_key").cumcount()
batch["batch_seq"]  = batch.groupby("creature_key").cumcount()
batch["loss_roll20"] = (
    batch.groupby("creature_key")["loss"]
    .transform(lambda s: s.rolling(20, min_periods=1).mean())
)
emo["reg_seq"] = emo.groupby("creature_key").cumcount()

# ---------------------------------------------------------------------------
# Print header
# ---------------------------------------------------------------------------
print(f"Creatures: {creatures}")
total_decisions = len(cas)
wm_decisions    = (cas["actionselectiontype"] == "WORLD_MODEL").sum()
print(f"Total action decisions:  {total_decisions}")
print(f"WORLD_MODEL decisions:   {wm_decisions}  ({100*wm_decisions/total_decisions:.1f}%)")
print()
print(cas.groupby(["creature_key", "actionselectiontype"]).size().unstack(fill_value=0))
print()

# ---------------------------------------------------------------------------
# Fig 1 — Action selection type distribution (stacked bar per creature)
# ---------------------------------------------------------------------------
types  = ["RANDOM", "AFFORDANCE", "WORLD_MODEL", "TARGET_DISTANCE", "MEMORY", "SHORT_TERM_MEMORY"]
colors = {"RANDOM": "#9E9E9E", "AFFORDANCE": "#2196F3", "WORLD_MODEL": "#FF5722",
          "TARGET_DISTANCE": "#4CAF50", "MEMORY": "#9C27B0", "SHORT_TERM_MEMORY": "#FF9800"}

fig, ax = plt.subplots(figsize=(10, 5))
x = np.arange(len(creatures))
bottoms = np.zeros(len(creatures))
for t in types:
    counts = []
    for c in creatures:
        c_cas = cas[cas["creature_key"] == c]
        counts.append((c_cas["actionselectiontype"] == t).sum())
    counts = np.array(counts, dtype=float)
    pcts   = counts / np.array([len(cas[cas["creature_key"] == c]) for c in creatures]) * 100
    bars   = ax.bar(x, pcts, bottom=bottoms, color=colors.get(t, "#607D8B"), label=t, width=0.6)
    bottoms += pcts

ax.set_xticks(x)
ax.set_xticklabels([LABELS[c] for c in creatures])
ax.set_ylabel("% of action decisions")
ax.set_title("Action selection type distribution per creature\n"
             f"(WORLD_MODEL = Mode-2, fires when arousal > {HIGH_AROUSAL_THRESHOLD})")
ax.legend(loc="upper right", fontsize=8)
ax.set_ylim(0, 105)
fig.tight_layout()
fig.savefig(FIGS / "fig1_selection_type_distribution.png", dpi=150)
plt.close(fig)
print("Saved fig1_selection_type_distribution.png")

# ---------------------------------------------------------------------------
# Fig 2 — Mode-2 firing rate vs arousal level (scatter + threshold line)
# ---------------------------------------------------------------------------
# Join chosen_action_state with regulation events via time proximity
fig, axes = plt.subplots(1, len(creatures), figsize=(5 * len(creatures), 5), sharey=True)
for ax, c in zip(axes, creatures):
    c_emo = emo[emo["creature_key"] == c].copy()
    c_emo["is_wm"] = False
    c_cas_wm = cas[(cas["creature_key"] == c) & (cas["actionselectiontype"] == "WORLD_MODEL")]
    # Bin arousal into buckets and compute WM rate
    c_emo["arousal_bin"] = pd.cut(c_emo["max_arousal"], bins=20)
    bin_counts = c_emo.groupby("arousal_bin").size()
    ax.bar([b.mid for b in bin_counts.index], bin_counts.values,
           width=[b.length * 0.8 for b in bin_counts.index],
           color=PALETTE[c], alpha=0.7, label="Regulation events")
    ax.axvline(HIGH_AROUSAL_THRESHOLD, color="red", lw=1.5, ls="--",
               label=f"Mode-2 threshold ({HIGH_AROUSAL_THRESHOLD})")
    ax.set_xlabel("Max arousal level (max(hunger, sleep))")
    ax.set_title(LABELS[c])
axes[0].set_ylabel("Event count")
handles, labels = axes[0].get_legend_handles_labels()
fig.legend(handles, labels, loc="upper right", fontsize=9)
fig.suptitle("Arousal distribution vs Mode-2 activation threshold", fontsize=12)
fig.tight_layout()
fig.savefig(FIGS / "fig2_arousal_vs_threshold.png", dpi=150)
plt.close(fig)
print("Saved fig2_arousal_vs_threshold.png")

# ---------------------------------------------------------------------------
# Fig 3 — Arousal trajectory over lifetime
# ---------------------------------------------------------------------------
fig, axes = plt.subplots(len(creatures), 1, figsize=(14, 4 * len(creatures)), sharex=False)
if len(creatures) == 1:
    axes = [axes]
for ax, c in zip(axes, creatures):
    c_emo = emo[emo["creature_key"] == c]
    ax.plot(c_emo["reg_seq"], c_emo["hunger_arausal"], lw=0.8, color="#FF9800", alpha=0.7, label="Hunger")
    ax.plot(c_emo["reg_seq"], c_emo["sleep_arausal"],  lw=0.8, color="#2196F3", alpha=0.7, label="Sleep")
    ax.plot(c_emo["reg_seq"], c_emo["max_arousal"],    lw=1.2, color="#212121", alpha=0.9, label="Max arousal")
    ax.axhline(HIGH_AROUSAL_THRESHOLD, color="red", lw=1.0, ls="--",
               label=f"Mode-2 threshold ({HIGH_AROUSAL_THRESHOLD})")
    ax.set_ylabel("Arousal level")
    ax.set_title(LABELS[c])
    ax.legend(fontsize=8, loc="upper right")
axes[-1].set_xlabel("Regulation event sequence")
fig.suptitle("Emotional arousal trajectory over creature lifetime", fontsize=12)
fig.tight_layout()
fig.savefig(FIGS / "fig3_arousal_trajectory.png", dpi=150)
plt.close(fig)
print("Saved fig3_arousal_trajectory.png")

# ---------------------------------------------------------------------------
# Fig 4 — Action type breakdown by selection mechanism
# ---------------------------------------------------------------------------
action_types = ["EAT", "APPROACH", "AVOID", "WANDER", "SLEEP", "ESCAPE"]
selection_types = cas["actionselectiontype"].dropna().unique()
fig, axes = plt.subplots(1, len(selection_types), figsize=(5 * len(selection_types), 5), sharey=True)
if len(selection_types) == 1:
    axes = [axes]
for ax, st in zip(axes, sorted(selection_types)):
    sub = cas[cas["actionselectiontype"] == st]
    cnts = sub["action"].value_counts()
    ax.bar(range(len(cnts)), cnts.values, color=colors.get(st, "#607D8B"))
    ax.set_xticks(range(len(cnts)))
    ax.set_xticklabels(cnts.index, rotation=45, ha="right")
    ax.set_title(st)
    ax.set_xlabel("Action type")
axes[0].set_ylabel("Count")
fig.suptitle("Action type distribution by deciding filter", fontsize=12)
fig.tight_layout()
fig.savefig(FIGS / "fig4_action_by_filter.png", dpi=150)
plt.close(fig)
print("Saved fig4_action_by_filter.png")

# ---------------------------------------------------------------------------
# Fig 5 — Mode-2 rate over lifetime (rolling window)
# ---------------------------------------------------------------------------
WINDOW = 200  # cognitive events
fig, ax = plt.subplots(figsize=(13, 5))
for c in creatures:
    c_cas = cas[cas["creature_key"] == c].sort_values("time").copy()
    c_cas["is_wm"] = (c_cas["actionselectiontype"] == "WORLD_MODEL").astype(float)
    c_cas["wm_rate"] = c_cas["is_wm"].rolling(WINDOW, min_periods=10).mean() * 100
    c_cas["seq"] = np.arange(len(c_cas))
    ax.plot(c_cas["seq"], c_cas["wm_rate"], lw=1.5, color=PALETTE[c], label=LABELS[c])
ax.set_xlabel(f"Decision sequence (rolling window={WINDOW})")
ax.set_ylabel("WORLD_MODEL rate (%)")
ax.set_title("Mode-2 activation rate over creature lifetime\n"
             "(rolling 200-decision window)")
ax.legend()
fig.tight_layout()
fig.savefig(FIGS / "fig5_mode2_rate_over_lifetime.png", dpi=150)
plt.close(fig)
print("Saved fig5_mode2_rate_over_lifetime.png")

# ---------------------------------------------------------------------------
# Fig 6 — Sleep consolidation: loss curve (same as P5 reference)
# ---------------------------------------------------------------------------
fig, ax = plt.subplots(figsize=(12, 5))
for c in creatures:
    b = batch[batch["creature_key"] == c]
    valid = b[b["loss"].notna()]
    ax.scatter(valid["batch_seq"], valid["loss"], s=1.5, alpha=0.12, color=PALETTE[c])
    ax.plot(b["batch_seq"], b["loss_roll20"], lw=1.8, color=PALETTE[c], label=LABELS[c])
ax.set_xlabel("Cumulative batch (across all sleep episodes)")
ax.set_ylabel("MSE loss (prediction-error)")
ax.set_title("Per-creature adapter training loss (Phase 6)\n"
             "encoder → adapter → predictor → critic  (MSE on emotional delta)")
ax.legend()
ax.set_yscale("log")
ax.set_ylim(bottom=0.05)
fig.tight_layout()
fig.savefig(FIGS / "fig6_consolidation_loss.png", dpi=150)
plt.close(fig)
print("Saved fig6_consolidation_loss.png")

# ---------------------------------------------------------------------------
# Fig 7 — Lifetime duration histogram
# ---------------------------------------------------------------------------
crea_alive = crea[crea["deadtime"].notna() & crea["deadtime"] > 0].copy()
crea_alive["lifetime_min"] = (crea_alive["deadtime"] - crea_alive["borntime"]) / 60000.0
fig, ax = plt.subplots(figsize=(7, 4))
ax.bar(range(len(crea_alive)), crea_alive["lifetime_min"].values,
       color=[PALETTE.get(c, "#607D8B") for c in crea_alive["creature_key"]],
       alpha=0.8)
ax.set_xticks(range(len(crea_alive)))
ax.set_xticklabels([LABELS.get(c, str(c)) for c in crea_alive["creature_key"]])
ax.set_ylabel("Lifetime (minutes)")
ax.set_title("Creature lifetime (Phase 6 — Mode-2 active)")
fig.tight_layout()
fig.savefig(FIGS / "fig7_creature_lifetime.png", dpi=150)
plt.close(fig)
print("Saved fig7_creature_lifetime.png")

# ---------------------------------------------------------------------------
# Summary statistics
# ---------------------------------------------------------------------------
print("\n=== SUMMARY STATISTICS ===")
total = len(cas)
print(f"\nOverall decisions: {total}")
for st, grp in cas.groupby("actionselectiontype"):
    print(f"  {st:20s}: {len(grp):6d}  ({100*len(grp)/total:.1f}%)")

print(f"\nMode-2 activation summary:")
wm = cas[cas["actionselectiontype"] == "WORLD_MODEL"]
print(f"  Total WORLD_MODEL decisions: {len(wm)}")
if len(wm) > 0:
    print(f"  Action breakdown when Mode-2 decides:")
    print(wm["action"].value_counts().to_string())

print(f"\nArousal statistics:")
for c in creatures:
    c_emo = emo[emo["creature_key"] == c]
    ar = c_emo["max_arousal"]
    n_above = (ar >= HIGH_AROUSAL_THRESHOLD).sum()
    print(f"  Creature {c}: mean={ar.mean():.3f} std={ar.std():.3f} "
          f"p75={ar.quantile(0.75):.3f} p90={ar.quantile(0.90):.3f} "
          f"peak={ar.max():.3f}  n_above_threshold={n_above} ({100*n_above/len(ar):.1f}%)")

print(f"\nConsolidation (per creature):")
for c in creatures:
    e  = eps[eps["creature_key"] == c]
    b  = batch[batch["creature_key"] == c]
    bv = b[b["loss"].notna()]
    print(f"  Creature {c}: {len(e)} episodes, {len(b)} batches ({len(bv)} valid), "
          f"mean_elig={e['mean_eligibility'].mean():.3f}")
    if len(bv) >= 10:
        first10 = bv.iloc[:10]["loss"].mean()
        last10  = bv.iloc[-10:]["loss"].mean()
        reduction = (1 - last10 / first10) * 100 if first10 > 0 else float("nan")
        print(f"    Loss: first10={first10:.4f}  last10={last10:.4f}  reduction={reduction:.1f}%")

print(f"\nFigures written to {FIGS}")
