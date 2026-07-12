"""
Analysis: 20260709_memory_vs_wm_v1
Memory-based learning vs. JEPA world model — 5 conditions × 5 trials × 5 creatures

Conditions:
  1_baseline               — no extra learning
  2_memory_only            — memory filter in action selection
  3_memory_consolidation   — memory filter + sleep consolidation (MemoryTraceConsolidator)
  4_jepa_only              — JEPA world-model filter (no consolidation)
  5_jepa_consolidation     — JEPA world-model filter + MemoryConsolidator adapter fine-tuning

Data directories:
  Conditions 1-3: ml/data_20260709_memory_vs_wm_v2/   (5 creatures — v2 rerun)
  Conditions 4-5: ml/data_20260709_memory_vs_wm_v1/   (5 creatures — original run)

Usage:
  python3 analysis/exp_20260709_memory_vs_wm_v1.py
"""

import os
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
from scipy import stats

warnings.filterwarnings("ignore")

# ─── Configuration ────────────────────────────────────────────────────────────

EXP        = "20260709_memory_vs_wm_v1"
ROOT_DIR   = Path(__file__).resolve().parent.parent
# Conditions 1-3 were rerun with 5 creatures in v2; conditions 4-5 from the original v1 run.
DIR_V1     = ROOT_DIR / "ml" / "data_20260709_memory_vs_wm_v1"
DIR_V2     = ROOT_DIR / "ml" / "data_20260709_memory_vs_wm_v2"
COND_DIR   = {
    "1_baseline":             DIR_V2,
    "2_memory_only":          DIR_V2,
    "3_memory_consolidation": DIR_V2,
    "4_jepa_only":            DIR_V1,
    "5_jepa_consolidation":   DIR_V1,
}
FIG_DIR    = ROOT_DIR / "docs" / "reports" / "figures" / f"p{EXP[:8].replace('-','')[:8]}"
REPORT_DIR = ROOT_DIR / "docs" / "reports"

FIG_DIR.mkdir(parents=True, exist_ok=True)

CONDITIONS = [
    ("1_baseline",             "Baseline"),
    ("2_memory_only",          "Memory"),
    ("3_memory_consolidation", "Mem+Consol"),
    ("4_jepa_only",            "JEPA"),
    ("5_jepa_consolidation",   "JEPA+Consol"),
]
COND_KEYS  = [c for c, _ in CONDITIONS]
COND_LABELS= [l for _, l in CONDITIONS]
TRIALS     = list(range(1, 6))

PALETTE = {
    "1_baseline":             "#9e9e9e",
    "2_memory_only":          "#5c85d6",
    "3_memory_consolidation": "#2b5eb8",
    "4_jepa_only":            "#e07b39",
    "5_jepa_consolidation":   "#c0392b",
}

DRIVE_COLS = [
    "final_hunger", "final_sleep", "final_pain", "final_tedium",
    "final_apathy", "final_stress", "final_fear", "final_curiosity",
]
DRIVE_NAMES = ["Hunger", "Sleep", "Pain", "Tedium", "Apathy", "Stress", "Fear", "Curiosity"]
# Subset shown in the per-drive subplots (the four homeostatic ones)
DRIVE_PLOT_COLS  = ["final_hunger", "final_sleep", "final_pain", "final_tedium"]
DRIVE_PLOT_NAMES = ["Hunger", "Sleep", "Pain", "Tedium"]

# ─── Loaders ─────────────────────────────────────────────────────────────────

def load_all(fname):
    """Load a parquet file across all conditions × trials, return combined DataFrame."""
    frames = []
    for cond, _ in CONDITIONS:
        base = COND_DIR[cond]
        for trial in TRIALS:
            path = base / cond / f"trial_{trial}" / fname
            if path.exists():
                df = pd.read_parquet(path)
                df["condition"] = cond
                df["trial"] = trial
                frames.append(df)
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def num(s):
    return pd.to_numeric(s, errors="coerce")

# ─── Load datasets ────────────────────────────────────────────────────────────

print("Loading data …")
creatures   = load_all("creatures.parquet")
drives      = load_all("drives.parquet")
actions     = load_all("actions.parquet")
behav       = load_all("behavioural_efficiency.parquet")
mouth       = load_all("mouth_interactions.parquet")
neuro       = load_all("neuromodulators.parquet")
expectancy  = load_all("expectancy.parquet")
engrams     = load_all("engrams.parquet")
sleep_ep    = load_all("sleep_episodes.parquet")

# Convert numeric columns
creatures["lifetime_s"] = num(creatures["lifetime_s"])
creatures["born_time"]  = num(creatures["born_time"])
drives["time"]           = num(drives["time"])
for col in DRIVE_COLS + [c.replace("final_", "init_") for c in DRIVE_COLS if c.replace("final_", "init_") in drives.columns]:
    if col in drives.columns:
        drives[col] = num(drives[col])

# Compute elapsed seconds from each creature's birth so time bins are comparable.
# drives["time"] is Unix epoch ms; creatures["born_time"] is also epoch ms.
born_lookup = creatures[["creature_key", "condition", "trial", "born_time"]].drop_duplicates()
drives = drives.merge(born_lookup, on=["creature_key", "condition", "trial"], how="left")
drives["elapsed_s"] = (drives["time"] - drives["born_time"]) / 1000.0
behav["efficiency"] = num(behav["efficiency"])
behav["time"]       = num(behav["time"])
neuro["dopamine"]   = num(neuro["dopamine"])
neuro["serotonin"]  = num(neuro["serotonin"])
neuro["orexin"]     = num(neuro["orexin"])
neuro["seq"]        = num(neuro["seq"])
expectancy["rpe"]      = num(expectancy["rpe"])
expectancy["expected"] = num(expectancy["expected"])
expectancy["reward"]   = num(expectancy["reward"])
expectancy["cycle"]    = num(expectancy["cycle"])
engrams["eligibility"]    = num(engrams["eligibility"])
engrams["emotion_delta"]  = num(engrams["emotion_delta"])
sleep_ep["duration_ticks"] = num(sleep_ep["duration_ticks"])
actions["time"]         = num(actions["time"])
actions["inference_ms"] = num(actions["inference_ms"])
mouth["time"]           = num(mouth["time"])

print(f"  Creatures : {len(creatures)} rows")
print(f"  Drives    : {len(drives)} rows")
print(f"  Actions   : {len(actions)} rows")

# Attach tick count (= number of decision cycles) to each creature.
# This is a discrete measure independent of wall-clock inference time.
tick_counts = (
    actions.groupby(["creature_key", "condition", "trial"])
    .size()
    .reset_index(name="tick_count")
)
creatures = creatures.merge(tick_counts, on=["creature_key", "condition", "trial"], how="left")
creatures["tick_count"] = creatures["tick_count"].fillna(0).astype(int)

# Compute per-creature total inference overhead (sum of inference_ms for all WORLD_MODEL
# actions). Subtracting this from wall-clock lifetime gives the inference-corrected lifetime:
# the time the creature would have lived if JEPA inference were instantaneous.
# Non-JEPA creatures have inference_ms == 0 everywhere, so their corrected lifetime == raw.
wm_overhead = (
    actions[actions["selection_type"] == "WORLD_MODEL"]
    .groupby(["creature_key", "condition", "trial"])["inference_ms"]
    .sum()
    .reset_index(name="total_inference_ms")
)
creatures = creatures.merge(wm_overhead, on=["creature_key", "condition", "trial"], how="left")
creatures["total_inference_ms"] = creatures["total_inference_ms"].fillna(0.0)
creatures["lifetime_corrected_s"] = creatures["lifetime_s"] - creatures["total_inference_ms"] / 1000.0

print(f"  Tick counts and inference correction attached:")
for ck, cl in CONDITIONS:
    sub = creatures[creatures["condition"] == ck]
    print(f"    {cl:<20s}  {sub['tick_count'].mean():.0f} ticks  "
          f"overhead={sub['total_inference_ms'].mean()/1000:.1f}s  "
          f"corrected={sub['lifetime_corrected_s'].mean():.1f}s  (n={len(sub)})")

# ─── Helper: per-condition stats ─────────────────────────────────────────────

def cond_stats(series_by_cond, label=""):
    """Print mean ± std per condition for a dict {cond_key: values_array}."""
    print(f"\n  {label}")
    for ck, cl in CONDITIONS:
        vals = series_by_cond.get(ck, np.array([]))
        vals = np.array(vals)
        vals = vals[~np.isnan(vals)]
        if len(vals):
            print(f"    {cl:<20s}  {np.mean(vals):8.2f} ± {np.std(vals):6.2f}  (n={len(vals)})")


def kruskal_test(groups, labels):
    """Run Kruskal-Wallis + pairwise Mann-Whitney with Bonferroni correction."""
    clean = [(g[~np.isnan(g)], l) for g, l in zip(groups, labels) if len(g[~np.isnan(g)]) > 0]
    if len(clean) < 2:
        return
    stat, p = stats.kruskal(*[g for g, _ in clean])
    print(f"    Kruskal-Wallis: H={stat:.3f}, p={p:.4f}")
    pairs = [(i, j) for i in range(len(clean)) for j in range(i + 1, len(clean))]
    alpha = 0.05 / len(pairs)
    for i, j in pairs:
        _, pw = stats.mannwhitneyu(clean[i][0], clean[j][0], alternative="two-sided")
        sig = "***" if pw < alpha else ("*" if pw < 0.05 else "ns")
        print(f"      {clean[i][1]} vs {clean[j][1]}: p={pw:.4f} {sig}")

# ══════════════════════════════════════════════════════════════════════════════
# 1. SURVIVAL — lifetime per creature
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 1. SURVIVAL ===")

lifetimes = {ck: [] for ck, _ in CONDITIONS}
for ck, _ in CONDITIONS:
    sub = creatures[creatures["condition"] == ck]["lifetime_s"].dropna()
    lifetimes[ck] = sub.values

cond_stats(lifetimes, "Lifetime (s)")
groups = [np.array(lifetimes[ck]) for ck, _ in CONDITIONS]
kruskal_test(groups, COND_LABELS)

ticks_by_cond     = {ck: creatures[creatures["condition"] == ck]["tick_count"].values
                     for ck, _ in CONDITIONS}
corrected_by_cond = {ck: creatures[creatures["condition"] == ck]["lifetime_corrected_s"].values
                     for ck, _ in CONDITIONS}

fig, axes = plt.subplots(1, 3, figsize=(18, 5))

# Panel 1: raw wall-clock seconds
ax = axes[0]
data_bp = [np.array(lifetimes[ck]) for ck, _ in CONDITIONS]
bp = ax.boxplot(data_bp, tick_labels=COND_LABELS, patch_artist=True)
for patch, (ck, _) in zip(bp["boxes"], CONDITIONS):
    patch.set_facecolor(PALETTE[ck])
    patch.set_alpha(0.7)
ax.set_ylabel("Lifetime (s)")
ax.set_title("Wall-clock seconds\n(raw)")
ax.yaxis.set_minor_locator(ticker.AutoMinorLocator())
ax.grid(axis="y", alpha=0.3)

# Panel 2: inference-corrected seconds
ax = axes[1]
data_corr = [np.array(corrected_by_cond[ck]) for ck, _ in CONDITIONS]
bp2 = ax.boxplot(data_corr, tick_labels=COND_LABELS, patch_artist=True)
for patch, (ck, _) in zip(bp2["boxes"], CONDITIONS):
    patch.set_facecolor(PALETTE[ck])
    patch.set_alpha(0.7)
ax.set_ylabel("Lifetime (s)")
ax.set_title("Inference-corrected seconds\n(− WORLD_MODEL overhead per creature)")
ax.yaxis.set_minor_locator(ticker.AutoMinorLocator())
ax.grid(axis="y", alpha=0.3)

# Panel 3: decision ticks
ax = axes[2]
data_ticks = [np.array(ticks_by_cond[ck]) for ck, _ in CONDITIONS]
bp3 = ax.boxplot(data_ticks, tick_labels=COND_LABELS, patch_artist=True)
for patch, (ck, _) in zip(bp3["boxes"], CONDITIONS):
    patch.set_facecolor(PALETTE[ck])
    patch.set_alpha(0.7)
ax.set_ylabel("Lifetime (decision cycles)")
ax.set_title("Decision ticks\n(inference-independent)")
ax.yaxis.set_minor_locator(ticker.AutoMinorLocator())
ax.grid(axis="y", alpha=0.3)

plt.tight_layout()
fig.savefig(FIG_DIR / "01_lifespan.png", dpi=150)
plt.close(fig)
print(f"  → 01_lifespan.png")

# Stats: corrected seconds
print("\n  Lifetime (inference-corrected seconds):")
cond_stats(corrected_by_cond, "Lifetime corrected (s)")
kruskal_test([np.array(corrected_by_cond[ck]) for ck, _ in CONDITIONS], COND_LABELS)

# Stats: ticks
print("\n  Lifetime (decision ticks):")
cond_stats(ticks_by_cond, "Lifetime (decision cycles)")
kruskal_test([np.array(ticks_by_cond[ck]) for ck, _ in CONDITIONS], COND_LABELS)

# ══════════════════════════════════════════════════════════════════════════════
# 2. DRIVE REGULATION — mean arousal over time (binned)
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 2. DRIVE REGULATION ===")

drives["arousal"] = drives[[c for c in DRIVE_COLS if c in drives.columns]].sum(axis=1)

# Bin elapsed time into 30-second windows
BIN_S = 30
drives["time_bin"] = (drives["elapsed_s"] // BIN_S).astype(int)
drives["time_bin"] = drives["time_bin"].clip(lower=0)

fig, axes = plt.subplots(2, 3, figsize=(15, 8))
axes = axes.flatten()

for ax_idx, (ck, cl) in enumerate(CONDITIONS):
    ax = axes[ax_idx]
    sub = drives[drives["condition"] == ck]
    gb = sub.groupby("time_bin")["arousal"]
    mean_a = gb.mean()
    std_a  = gb.std()
    t = mean_a.index * BIN_S / 60  # minutes from birth
    ax.plot(t, mean_a, color=PALETTE[ck], lw=2, label=cl)
    ax.fill_between(t, mean_a - std_a, mean_a + std_a, color=PALETTE[ck], alpha=0.2)
    ax.set_title(cl, fontsize=11)
    ax.set_xlabel("Elapsed time (min)")
    ax.set_ylabel("Total Arousal")
    ax.grid(alpha=0.3)

# Combined on 6th panel
ax = axes[5]
for ck, cl in CONDITIONS:
    sub = drives[drives["condition"] == ck]
    gb = sub.groupby("time_bin")["arousal"].mean()
    t = gb.index * BIN_S / 60
    ax.plot(t, gb, color=PALETTE[ck], lw=2, label=cl)
ax.set_title("All conditions", fontsize=11)
ax.set_xlabel("Elapsed time (min)")
ax.set_ylabel("Mean Arousal")
ax.legend(fontsize=8)
ax.grid(alpha=0.3)

fig.suptitle("Total Arousal Over Simulation Time", fontsize=13)
plt.tight_layout()
fig.savefig(FIG_DIR / "02_arousal_time.png", dpi=150)
plt.close(fig)
print("  → 02_arousal_time.png")

# Mean arousal per condition (summary)
mean_arousal = {}
for ck, _ in CONDITIONS:
    sub = drives[drives["condition"] == ck]["arousal"].dropna()
    mean_arousal[ck] = sub.values
cond_stats(mean_arousal, "Mean arousal")

# ══════════════════════════════════════════════════════════════════════════════
# 3. PER-DRIVE TRAJECTORIES
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 3. PER-DRIVE TRAJECTORIES ===")

fig, axes = plt.subplots(2, 2, figsize=(14, 9))
for ax, col, name in zip(axes.flatten(), DRIVE_PLOT_COLS, DRIVE_PLOT_NAMES):
    for ck, cl in CONDITIONS:
        sub = drives[drives["condition"] == ck]
        gb = sub.groupby("time_bin")[col].mean()
        t = gb.index * BIN_S / 60
        ax.plot(t, gb, color=PALETTE[ck], lw=2, label=cl)
    ax.set_title(name)
    ax.set_xlabel("Elapsed time (min)")
    ax.set_ylabel("Drive Level")
    ax.legend(fontsize=7)
    ax.grid(alpha=0.3)

fig.suptitle("Per-Drive Levels Over Time", fontsize=13)
plt.tight_layout()
fig.savefig(FIG_DIR / "03_per_drive.png", dpi=150)
plt.close(fig)
print("  → 03_per_drive.png")

# Summary stats for the four main homeostatic drives
for col, name in zip(DRIVE_PLOT_COLS, DRIVE_PLOT_NAMES):
    d = {ck: drives[drives["condition"] == ck][col].dropna().values for ck, _ in CONDITIONS}
    cond_stats(d, f"Mean {name}")

# ══════════════════════════════════════════════════════════════════════════════
# 4. ACTION SELECTION — filter types
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 4. ACTION SELECTION ===")

filter_counts = actions.groupby(["condition", "selection_type"]).size().unstack(fill_value=0)
filter_pct    = filter_counts.div(filter_counts.sum(axis=1), axis=0) * 100
print(filter_pct.to_string())

fig, axes = plt.subplots(1, 2, figsize=(14, 5))

# Left: WORLD_MODEL usage %
ax = axes[0]
wm_pct = {}
for ck, cl in CONDITIONS:
    row = filter_pct.loc[ck] if ck in filter_pct.index else pd.Series(dtype=float)
    wm_pct[cl] = row.get("WORLD_MODEL", 0.0)

bars = ax.bar(list(wm_pct.keys()), list(wm_pct.values()),
              color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
ax.set_ylabel("% cycles with WORLD_MODEL selection")
ax.set_title("World Model Filter Activation Rate")
ax.set_ylim(0, max(wm_pct.values()) * 1.25 if max(wm_pct.values()) > 0 else 10)
for bar, val in zip(bars, wm_pct.values()):
    ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3,
            f"{val:.1f}%", ha="center", va="bottom", fontsize=9)
ax.grid(axis="y", alpha=0.3)

# Right: stacked bar of all filter types
ax = axes[1]
stacked = filter_pct.reindex([ck for ck, _ in CONDITIONS])
stacked.index = COND_LABELS
stacked.plot(kind="bar", stacked=True, ax=ax, colormap="tab10", alpha=0.85)
ax.set_ylabel("% of action cycles")
ax.set_title("Action Selection Filter Distribution")
ax.legend(loc="upper right", fontsize=8)
ax.set_xticklabels(COND_LABELS, rotation=25, ha="right")
ax.grid(axis="y", alpha=0.3)

plt.tight_layout()
fig.savefig(FIG_DIR / "04_action_filters.png", dpi=150)
plt.close(fig)
print("  → 04_action_filters.png")

# ══════════════════════════════════════════════════════════════════════════════
# 5. BEHAVIOURAL EFFICIENCY
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 5. BEHAVIOURAL EFFICIENCY ===")

mean_eff = {}
for ck, _ in CONDITIONS:
    sub = behav[behav["condition"] == ck]["efficiency"].dropna()
    mean_eff[ck] = sub.values
cond_stats(mean_eff, "Mean efficiency")

fig, axes = plt.subplots(1, 2, figsize=(13, 5))

# Time series
ax = axes[0]
BIN_E = 300
behav["time_bin"] = (behav["time"] // BIN_E).astype(int)
for ck, cl in CONDITIONS:
    sub = behav[behav["condition"] == ck]
    gb = sub.groupby("time_bin")["efficiency"].mean()
    t = gb.index * BIN_E / 60
    ax.plot(t, gb, color=PALETTE[ck], lw=2, label=cl)
ax.set_xlabel("Time (min)")
ax.set_ylabel("Behavioural Efficiency")
ax.set_title("Efficiency Over Time")
ax.legend(fontsize=8)
ax.grid(alpha=0.3)

# Boxplot
ax = axes[1]
data_eff = [np.array(mean_eff[ck]) for ck, _ in CONDITIONS]
bp = ax.boxplot(data_eff, tick_labels=COND_LABELS, patch_artist=True)
for patch, (ck, _) in zip(bp["boxes"], CONDITIONS):
    patch.set_facecolor(PALETTE[ck])
    patch.set_alpha(0.7)
ax.set_ylabel("Behavioural Efficiency")
ax.set_title("Efficiency Distribution")
ax.grid(axis="y", alpha=0.3)

plt.tight_layout()
fig.savefig(FIG_DIR / "05_efficiency.png", dpi=150)
plt.close(fig)
print("  → 05_efficiency.png")

# ══════════════════════════════════════════════════════════════════════════════
# 6. EATING BEHAVIOUR & CACTUS AVOIDANCE
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 6. EATING BEHAVIOUR & CACTUS AVOIDANCE ===")

# Load perceptions for cactus encounter analysis
perceptions = load_all("perceptions.parquet")
perceptions["time"]     = num(perceptions["time"])
perceptions["distance"] = num(perceptions["distance"])

# --- Shared prep: tick rank and born_time on actions ---
born_lookup2 = creatures[["creature_key", "condition", "trial",
                           "born_time", "tick_count"]].drop_duplicates()

actions_ranked = actions[["creature_key", "condition", "trial", "time", "action_type"]].copy()
actions_ranked["tick_rank"] = actions_ranked.groupby(
    ["creature_key", "condition", "trial"]).cumcount()
# merge_asof requires global sort on the join key
actions_for_asof = actions_ranked.sort_values("time").reset_index(drop=True)

def attach_tick_rank(df):
    """Nearest-time join to assign tick_rank and life_decile to a timed event table."""
    df = df.merge(born_lookup2, on=["creature_key", "condition", "trial"], how="left")
    df_s = df.sort_values("time").reset_index(drop=True)
    df_s = pd.merge_asof(
        df_s,
        actions_for_asof[["creature_key", "condition", "trial", "time", "tick_rank"]],
        on="time",
        by=["creature_key", "condition", "trial"],
        direction="nearest",
    )
    df_s["life_frac"]   = df_s["tick_rank"] / df_s["tick_count"].clip(lower=1)
    df_s["life_decile"] = (df_s["life_frac"] * 10).clip(upper=9).astype(int)
    return df_s

# --- 6a. EAT events with tick rank + hunger level ---
mouth["time"] = num(mouth["time"])
mouth_ranked  = attach_tick_rank(mouth.copy())

# Attach hunger at time of eating via nearest-time join with drives
drives_for_asof = (drives[["creature_key", "condition", "trial", "time", "init_hunger"]]
                   .sort_values("time").reset_index(drop=True))
mouth_ranked = pd.merge_asof(
    mouth_ranked.sort_values("time").reset_index(drop=True),
    drives_for_asof,
    on="time",
    by=["creature_key", "condition", "trial"],
    direction="nearest",
    suffixes=("", "_drv"),
)
mouth_ranked["init_hunger"] = num(mouth_ranked["init_hunger"])

# --- 6b. Cactus encounters and avoidance ---
# A "cactus encounter" = a tick where CACTUS is in perception.
# An "avoidance" = that same tick selects action_type == AVOID.
cactus_perc = (perceptions[perceptions["object_type"] == "CACTUS"]
               .copy()
               .sort_values("time")
               .reset_index(drop=True))
cactus_ranked = attach_tick_rank(cactus_perc)

# Join with the action chosen at that tick
cactus_w_action = pd.merge_asof(
    cactus_ranked.sort_values("time").reset_index(drop=True),
    actions_for_asof[["creature_key", "condition", "trial", "time", "action_type"]],
    on="time",
    by=["creature_key", "condition", "trial"],
    direction="nearest",
)
cactus_w_action["avoided"] = (cactus_w_action["action_type"] == "AVOID").astype(int)

print("  Cactus encounters and avoidance rate:")
for ck, cl in CONDITIONS:
    sub = cactus_w_action[cactus_w_action["condition"] == ck]
    rate = sub["avoided"].mean() * 100 if len(sub) else 0
    print(f"    {cl:<20s}  encounters={len(sub):5d}  avoidance={rate:.1f}%")

print("\n  Food type totals:")
ot_counts = mouth_ranked.groupby(["condition", "object_type"]).size().unstack(fill_value=0)
print(ot_counts.reindex([ck for ck, _ in CONDITIONS]).to_string())

print("\n  Hunger at time of eating (mean ± std):")
for ck, cl in CONDITIONS:
    sub = mouth_ranked[mouth_ranked["condition"] == ck]["init_hunger"].dropna()
    print(f"    {cl:<20s}  {sub.mean():.3f} ± {sub.std():.3f}  (n={len(sub)})")

# --- Stats ---
print("  Food type totals:")
ot_counts = mouth_ranked.groupby(["condition", "object_type"]).size().unstack(fill_value=0)
print(ot_counts.reindex([ck for ck, _ in CONDITIONS]).to_string())

print("\n  Hunger at time of eating (mean ± std):")
for ck, cl in CONDITIONS:
    sub = mouth_ranked[mouth_ranked["condition"] == ck]["init_hunger"].dropna()
    print(f"    {cl:<20s}  {sub.mean():.3f} ± {sub.std():.3f}  (n={len(sub)})")

# --- Figure ---
FOOD_COLORS   = {"RED_APPLE": "#e74c3c", "GREEN_APPLE": "#27ae60", "GRAY_APPLE": "#7f8c8d"}
DECILE_LABELS = [f"{i*10}–{i*10+10}%" for i in range(10)]

fig, axes = plt.subplots(2, 3, figsize=(18, 10))

# Panel A: food-type breakdown per condition (stacked bar, normalised to %)
ax = axes[0, 0]
ot_pct = ot_counts.div(ot_counts.sum(axis=1), axis=0) * 100
ot_pct_plot = ot_pct.reindex([ck for ck, _ in CONDITIONS])
ot_pct_plot.index = COND_LABELS
bottom = np.zeros(len(COND_LABELS))
for col, color in FOOD_COLORS.items():
    if col in ot_pct_plot.columns:
        vals = ot_pct_plot[col].fillna(0).values
        ax.bar(COND_LABELS, vals, bottom=bottom, color=color, alpha=0.85,
               label=col.replace("_", " ").title())
        bottom += vals
ax.set_ylabel("% of EAT events")
ax.set_title("A — Food Type Selection")
ax.set_xticklabels(COND_LABELS, rotation=20, ha="right")
ax.legend(fontsize=8)
ax.set_ylim(0, 100)
ax.grid(axis="y", alpha=0.3)

# Panel B: hunger level at time of eating (boxplot per condition)
ax = axes[0, 1]
hunger_data = [mouth_ranked[mouth_ranked["condition"] == ck]["init_hunger"].dropna().values
               for ck, _ in CONDITIONS]
bp = ax.boxplot(hunger_data, tick_labels=COND_LABELS, patch_artist=True)
for patch, (ck, _) in zip(bp["boxes"], CONDITIONS):
    patch.set_facecolor(PALETTE[ck])
    patch.set_alpha(0.7)
ax.set_ylabel("Hunger level at EAT time")
ax.set_title("B — Hunger Level When Eating\n(higher = eating when appropriately hungry)")
ax.grid(axis="y", alpha=0.3)
ax.set_xticklabels(COND_LABELS, rotation=20, ha="right")

# Panel C: cactus avoidance rate per condition
ax = axes[0, 2]
avoid_rates = []
for ck, cl in CONDITIONS:
    sub = cactus_w_action[cactus_w_action["condition"] == ck]
    avoid_rates.append(sub["avoided"].mean() * 100 if len(sub) else 0)
bars = ax.bar(COND_LABELS, avoid_rates,
              color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
for bar, val in zip(bars, avoid_rates):
    ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.5,
            f"{val:.1f}%", ha="center", va="bottom", fontsize=9)
ax.set_ylabel("% of CACTUS encounters → AVOID")
ax.set_title("C — Cactus Avoidance Rate\n(higher = better danger recognition)")
ax.set_xticklabels(COND_LABELS, rotation=20, ha="right")
ax.set_ylim(0, max(avoid_rates) * 1.2 if max(avoid_rates) > 0 else 10)
ax.grid(axis="y", alpha=0.3)

# Panel D: eating rate over normalised lifetime
ax = axes[1, 0]
for ck, cl in CONDITIONS:
    sub = mouth_ranked[mouth_ranked["condition"] == ck]
    n_cr = creatures[creatures["condition"] == ck]["creature_key"].nunique()
    rate = sub.groupby("life_decile").size() / max(n_cr, 1)
    ax.plot(range(10), rate.reindex(range(10), fill_value=0).values,
            color=PALETTE[ck], lw=2, marker="o", ms=4, label=cl)
ax.set_xticks(range(10))
ax.set_xticklabels(DECILE_LABELS, rotation=30, ha="right", fontsize=7)
ax.set_ylabel("EAT events per creature")
ax.set_title("D — Eating Rate Over Normalised Lifetime")
ax.legend(fontsize=8)
ax.grid(alpha=0.3)

# Panel E: mean hunger at eating over normalised lifetime
ax = axes[1, 1]
for ck, cl in CONDITIONS:
    sub = mouth_ranked[mouth_ranked["condition"] == ck]
    rate = sub.groupby("life_decile")["init_hunger"].mean()
    ax.plot(range(10), rate.reindex(range(10)).values,
            color=PALETTE[ck], lw=2, marker="o", ms=4, label=cl)
ax.set_xticks(range(10))
ax.set_xticklabels(DECILE_LABELS, rotation=30, ha="right", fontsize=7)
ax.set_ylabel("Mean hunger level")
ax.set_title("E — Hunger at Eating Over Normalised Lifetime\n(rising = deferring eating until hungrier)")
ax.legend(fontsize=8)
ax.grid(alpha=0.3)

# Panel F: cactus avoidance rate over normalised lifetime
ax = axes[1, 2]
for ck, cl in CONDITIONS:
    sub = cactus_w_action[cactus_w_action["condition"] == ck]
    rate = sub.groupby("life_decile")["avoided"].mean() * 100
    ax.plot(range(10), rate.reindex(range(10), fill_value=np.nan).values,
            color=PALETTE[ck], lw=2, marker="o", ms=4, label=cl)
ax.set_xticks(range(10))
ax.set_xticklabels(DECILE_LABELS, rotation=30, ha="right", fontsize=7)
ax.set_ylabel("% CACTUS encounters → AVOID")
ax.set_title("F — Cactus Avoidance Over Normalised Lifetime\n(rising = learning to avoid cacti)")
ax.legend(fontsize=8)
ax.grid(alpha=0.3)

fig.suptitle("World Interaction Quality: Food Selection, Hunger Targeting, Cactus Avoidance", fontsize=13)
plt.tight_layout()
fig.savefig(FIG_DIR / "06_eating_behaviour.png", dpi=150)
plt.close(fig)
print("  → 06_eating_behaviour.png")

# ── Food-type preference learning: cumulative eats per food type over life ────
print("\n  Food-type cumulative accumulation by life decile:")
FOOD_TYPES = ["RED_APPLE", "GREEN_APPLE", "GRAY_APPLE"]

# Build per-creature cumulative eat count at each decile, then average across creatures
cum_records = []
for (cond, trial, ck), grp in mouth_ranked.groupby(["condition", "trial", "creature_key"]):
    for ft in FOOD_TYPES:
        per_decile = grp[grp["object_type"] == ft].groupby("life_decile").size()
        cumulative = 0
        for d in range(10):
            cumulative += per_decile.get(d, 0)
            cum_records.append({"condition": cond, "food_type": ft,
                                 "life_decile": d, "cum_count": cumulative})

cum_df = pd.DataFrame(cum_records)
avg_cum = (cum_df.groupby(["condition", "food_type", "life_decile"])["cum_count"]
           .mean().reset_index())

for ck, cl in CONDITIONS:
    sub = avg_cum[avg_cum["condition"] == ck]
    total_at_end = sub[sub["life_decile"] == 9].set_index("food_type")["cum_count"]
    parts = "  ".join(f"{ft.replace('_', ' ')}: {total_at_end.get(ft, 0):.1f}" for ft in FOOD_TYPES)
    print(f"    {cl:<20s}  {parts}")

n_cond = len(CONDITIONS)
fig, axes = plt.subplots(1, n_cond, figsize=(5 * n_cond, 5), sharey=True)
for ax, (ck, cl) in zip(axes, CONDITIONS):
    sub = avg_cum[avg_cum["condition"] == ck]
    for ft in FOOD_TYPES:
        vals = (sub[sub["food_type"] == ft]
                .set_index("life_decile")["cum_count"]
                .reindex(range(10), fill_value=np.nan).values)
        ax.plot(range(10), vals, color=FOOD_COLORS.get(ft, "gray"),
                lw=2.2, marker="o", ms=5, label=ft.replace("_", " ").title())
    ax.set_title(cl, fontsize=10)
    ax.set_xticks(range(10))
    ax.set_xticklabels(DECILE_LABELS, rotation=45, ha="right", fontsize=6)
    ax.set_xlabel("Life decile")
    ax.grid(alpha=0.3)

axes[0].set_ylabel("Cumulative EAT count (avg per creature)")
axes[0].legend(fontsize=9)
fig.suptitle(
    "Food-Type Preference: Cumulative Average Eats per Creature Over Normalised Lifetime\n"
    "(steeper slope in later deciles = learning to prefer that food type)",
    fontsize=12)
plt.tight_layout()
fig.savefig(FIG_DIR / "07_food_learning.png", dpi=150)
plt.close(fig)
print("  → 07_food_learning.png")

# ══════════════════════════════════════════════════════════════════════════════
# 7. NEUROMODULATORS — dopamine, serotonin, orexin
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 7. NEUROMODULATORS ===")

BIN_N = 300
neuro["time_bin"] = (neuro["seq"] // BIN_N).astype(int)

fig, axes = plt.subplots(1, 3, figsize=(16, 5))
for ax, nm, nm_name in zip(axes, ["dopamine", "serotonin", "orexin"],
                                   ["Dopamine", "Serotonin", "Orexin"]):
    for ck, cl in CONDITIONS:
        sub = neuro[neuro["condition"] == ck]
        gb = sub.groupby("time_bin")[nm].mean()
        t = gb.index * BIN_N / 60
        ax.plot(t, gb, color=PALETTE[ck], lw=2, label=cl)
    ax.set_title(nm_name)
    ax.set_xlabel("Time (min)")
    ax.set_ylabel("Tonic level")
    ax.legend(fontsize=7)
    ax.grid(alpha=0.3)

fig.suptitle("Neuromodulator Tonic Levels Over Time", fontsize=13)
plt.tight_layout()
fig.savefig(FIG_DIR / "07_neuromodulators.png", dpi=150)
plt.close(fig)
print("  → 07_neuromodulators.png")

# ══════════════════════════════════════════════════════════════════════════════
# 8. EXPECTANCY / RPE  (conditions 2, 3, 5 where expectancyEnabled=true)
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 8. EXPECTANCY / RPE ===")

exp_conds = [ck for ck, _ in CONDITIONS if ck in expectancy["condition"].unique()]
if expectancy.empty or len(exp_conds) == 0:
    print("  No expectancy data found")
else:
    print("  Conditions with expectancy data:", exp_conds)

    # |RPE| over time
    fig, axes = plt.subplots(1, 2, figsize=(13, 5))

    ax = axes[0]
    BIN_EXP = 50
    expectancy["cycle_bin"] = (expectancy["cycle"] // BIN_EXP).astype(int)
    for ck in exp_conds:
        sub = expectancy[expectancy["condition"] == ck]
        if sub.empty: continue
        gb = sub.groupby("cycle_bin")["rpe"].apply(lambda x: np.abs(x).mean())
        ax.plot(gb.index * BIN_EXP, gb, color=PALETTE[ck], lw=2,
                label=dict(CONDITIONS)[ck])
    ax.set_xlabel("Cognitive cycle")
    ax.set_ylabel("|RPE|")
    ax.set_title("Mean |RPE| Over Time")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)

    # RPE distribution
    ax = axes[1]
    for ck in exp_conds:
        sub = expectancy[expectancy["condition"] == ck]["rpe"].dropna()
        if sub.empty: continue
        ax.hist(sub, bins=40, alpha=0.5, color=PALETTE[ck],
                label=dict(CONDITIONS)[ck], density=True)
    ax.set_xlabel("RPE")
    ax.set_ylabel("Density")
    ax.set_title("RPE Distribution")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)

    plt.tight_layout()
    fig.savefig(FIG_DIR / "08_expectancy_rpe.png", dpi=150)
    plt.close(fig)
    print("  → 08_expectancy_rpe.png")

    for ck in exp_conds:
        sub = expectancy[expectancy["condition"] == ck]["rpe"].dropna()
        print(f"    {dict(CONDITIONS)[ck]:<20s}  |RPE| mean={np.abs(sub).mean():.4f}  std={sub.std():.4f}")

# ══════════════════════════════════════════════════════════════════════════════
# 9. ENGRAMS — memory trace formation
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 9. ENGRAMS ===")

engram_counts = engrams.groupby("condition").size()
engram_elig   = engrams.groupby("condition")["eligibility"].mean()
engram_delta  = engrams.groupby("condition")["emotion_delta"].apply(lambda x: np.abs(x).mean())

print("  Engrams formed:")
for ck, cl in CONDITIONS:
    n     = engram_counts.get(ck, 0)
    elig  = engram_elig.get(ck, np.nan)
    delta = engram_delta.get(ck, np.nan)
    print(f"    {cl:<20s}  n={n:5d}  mean_elig={elig:.3f}  mean|delta|={delta:.4f}")

fig, axes = plt.subplots(1, 3, figsize=(15, 5))

# Count
ax = axes[0]
vals = [engram_counts.get(ck, 0) for ck, _ in CONDITIONS]
ax.bar(COND_LABELS, vals, color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
ax.set_title("Engrams Formed")
ax.set_ylabel("Count (5 trials)")
ax.set_xticklabels(COND_LABELS, rotation=20, ha="right")
ax.grid(axis="y", alpha=0.3)

# Eligibility distribution
ax = axes[1]
for ck, cl in CONDITIONS:
    sub = engrams[engrams["condition"] == ck]["eligibility"].dropna()
    if sub.empty: continue
    ax.hist(sub, bins=30, alpha=0.5, color=PALETTE[ck], label=cl, density=True)
ax.set_title("Engram Eligibility Distribution")
ax.set_xlabel("Eligibility")
ax.set_ylabel("Density")
ax.legend(fontsize=7)
ax.grid(alpha=0.3)

# |Emotion delta|
ax = axes[2]
for ck, cl in CONDITIONS:
    sub = engrams[engrams["condition"] == ck]["emotion_delta"].dropna().abs()
    if sub.empty: continue
    ax.hist(sub, bins=30, alpha=0.5, color=PALETTE[ck], label=cl, density=True)
ax.set_title("|Emotion Delta| Distribution")
ax.set_xlabel("|emotion_delta|")
ax.set_ylabel("Density")
ax.legend(fontsize=7)
ax.grid(alpha=0.3)

fig.suptitle("Memory Engram Formation", fontsize=13)
plt.tight_layout()
fig.savefig(FIG_DIR / "09_engrams.png", dpi=150)
plt.close(fig)
print("  → 09_engrams.png")

# ══════════════════════════════════════════════════════════════════════════════
# 10. SLEEP EPISODES
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 10. SLEEP EPISODES ===")

sleep_summary = sleep_ep.groupby("condition").agg(
    count=("duration_ticks", "count"),
    mean_dur=("duration_ticks", "mean"),
    std_dur=("duration_ticks", "std"),
).reindex([ck for ck, _ in CONDITIONS])
print(sleep_summary.to_string())

fig, axes = plt.subplots(1, 2, figsize=(12, 5))

ax = axes[0]
ax.bar(COND_LABELS,
       [sleep_summary.loc[ck, "count"] if ck in sleep_summary.index else 0 for ck, _ in CONDITIONS],
       color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
ax.set_title("Sleep Episodes (total, 5 trials)")
ax.set_ylabel("Count")
ax.set_xticklabels(COND_LABELS, rotation=20, ha="right")
ax.grid(axis="y", alpha=0.3)

ax = axes[1]
means = [sleep_summary.loc[ck, "mean_dur"] if ck in sleep_summary.index else 0 for ck, _ in CONDITIONS]
stds  = [sleep_summary.loc[ck, "std_dur"] if ck in sleep_summary.index else 0 for ck, _ in CONDITIONS]
ax.bar(COND_LABELS, means, yerr=stds, capsize=5,
       color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
ax.set_title("Mean Sleep Episode Duration (ticks)")
ax.set_ylabel("Duration (ticks)")
ax.set_xticklabels(COND_LABELS, rotation=20, ha="right")
ax.grid(axis="y", alpha=0.3)

plt.tight_layout()
fig.savefig(FIG_DIR / "10_sleep_episodes.png", dpi=150)
plt.close(fig)
print("  → 10_sleep_episodes.png")

# ══════════════════════════════════════════════════════════════════════════════
# 11. WORLD MODEL INFERENCE LATENCY (conds 4 & 5)
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 11. INFERENCE LATENCY ===")

wm_actions = actions[actions["selection_type"] == "WORLD_MODEL"].copy()
wm_actions["inference_ms"] = num(wm_actions["inference_ms"])
if not wm_actions.empty:
    latency = wm_actions.groupby("condition")["inference_ms"].describe()
    print(latency.to_string())

# ══════════════════════════════════════════════════════════════════════════════
# Print summary table
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== SUMMARY TABLE ===")
print(f"{'Condition':<22}  {'Raw (s)':>8}  {'Corrected (s)':>14}  {'Ticks':>8}  {'WM%':>6}  {'Engrams':>8}")
print("-" * 85)
for ck, cl in CONDITIONS:
    lt   = np.nanmean(lifetimes[ck]) if lifetimes[ck].size else np.nan
    lc   = np.nanmean(corrected_by_cond[ck]) if len(corrected_by_cond[ck]) else np.nan
    tc   = np.nanmean(ticks_by_cond[ck]) if len(ticks_by_cond[ck]) else np.nan
    row  = filter_pct.loc[ck] if ck in filter_pct.index else pd.Series(dtype=float)
    wm   = row.get("WORLD_MODEL", 0.0)
    eng  = engram_counts.get(ck, 0)
    print(f"  {cl:<20s}  {lt:>8.1f}  {lc:>14.1f}  {tc:>8.0f}  {wm:>5.1f}%  {eng:>8d}")

print(f"\nFigures saved → {FIG_DIR}")
