"""
Analysis: rotten_fruit_v1
Novel-world aversion learning — 5 conditions × 5 trials × 5 creatures

Conditions:
  1_baseline               — no learning filters
  2_memory_only            — MEMORY filter, no consolidation
  3_memory_consolidation   — MEMORY filter + MemoryTraceConsolidator
  4_jepa_rpe_only          — WORLD_MODEL + JEPA RPE, no consolidation
  5_jepa_rpe_consolidation — WORLD_MODEL + JEPA RPE + adapter consolidation

World: 500 RED_APPLE, 500 GREEN_APPLE, 500 ROTTEN_APPLE (novel, caloricValue=-0.3),
       50 CACTUS, 100 ALOE. No GRAY_APPLE. maxRuntimeMinutes=120.

Primary question: do creatures learn to avoid ROTTEN_APPLE, and which condition
learns it fastest (earliest life decile where interaction rate drops)?

Data: ml/data_rotten_fruit_v1/

Usage:
  python3 analysis/exp_rotten_fruit_v1.py
"""

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

EXP      = "rotten_fruit_v1"
ROOT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT_DIR / "ml" / "data_rotten_fruit_v1"
FIG_DIR  = ROOT_DIR / "docs" / "reports" / "figures" / "rotten_fruit_v1"
REPORT_DIR = ROOT_DIR / "docs" / "reports"

FIG_DIR.mkdir(parents=True, exist_ok=True)

CONDITIONS = [
    ("1_baseline",               "Baseline"),
    ("2_memory_only",            "Memory"),
    ("3_memory_consolidation",   "Mem+Consol"),
    ("4_jepa_rpe_only",          "JEPA"),
    ("5_jepa_rpe_consolidation", "JEPA+Consol"),
]
COND_KEYS   = [c for c, _ in CONDITIONS]
COND_LABELS = [l for _, l in CONDITIONS]
TRIALS      = list(range(1, 6))

PALETTE = {
    "1_baseline":               "#9e9e9e",
    "2_memory_only":            "#5c85d6",
    "3_memory_consolidation":   "#2b5eb8",
    "4_jepa_rpe_only":          "#b05ec4",
    "5_jepa_rpe_consolidation": "#7b2d8b",
}

FOOD_TYPES  = ["RED_APPLE", "GREEN_APPLE", "ROTTEN_APPLE"]
FOOD_COLORS = {
    "RED_APPLE":    "#e74c3c",
    "GREEN_APPLE":  "#27ae60",
    "ROTTEN_APPLE": "#6d4c41",
}
DECILE_LABELS = [f"{i*10}–{i*10+10}%" for i in range(10)]

DRIVE_COLS = [
    "final_hunger", "final_sleep", "final_pain", "final_tedium",
    "final_apathy", "final_stress", "final_fear", "final_curiosity",
]

# ─── Loaders ─────────────────────────────────────────────────────────────────

def load_all(fname):
    frames = []
    for cond, _ in CONDITIONS:
        for trial in TRIALS:
            path = DATA_DIR / cond / f"trial_{trial}" / fname
            if path.exists():
                df = pd.read_parquet(path)
                df["condition"] = cond
                df["trial"] = trial
                frames.append(df)
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def num(s):
    return pd.to_numeric(s, errors="coerce")

# ─── Load ─────────────────────────────────────────────────────────────────────

print("Loading data …")
creatures   = load_all("creatures.parquet")
drives      = load_all("drives.parquet")
actions     = load_all("actions.parquet")
mouth       = load_all("mouth_interactions.parquet")
perceptions = load_all("perceptions.parquet")
neuro       = load_all("neuromodulators.parquet")
expectancy  = load_all("expectancy.parquet")
engrams     = load_all("engrams.parquet")
sleep_ep    = load_all("sleep_episodes.parquet")

creatures["lifetime_s"] = num(creatures["lifetime_s"])
creatures["born_time"]  = num(creatures["born_time"])
drives["time"]          = num(drives["time"])
for col in DRIVE_COLS:
    if col in drives.columns:
        drives[col] = num(drives[col])

born_lookup = creatures[["creature_key", "condition", "trial", "born_time"]].drop_duplicates()
drives = drives.merge(born_lookup, on=["creature_key", "condition", "trial"], how="left")
drives["elapsed_s"] = (num(drives["time"]) - num(drives["born_time"])) / 1000.0

actions["time"]         = num(actions["time"])
actions["inference_ms"] = num(actions["inference_ms"])
mouth["time"]           = num(mouth["time"])
perceptions["time"]     = num(perceptions["time"])
perceptions["distance"] = num(perceptions["distance"])
neuro["dopamine"]       = num(neuro["dopamine"])
neuro["serotonin"]      = num(neuro["serotonin"])
neuro["orexin"]         = num(neuro["orexin"])
neuro["seq"]            = num(neuro["seq"])

# Approximate wall-clock elapsed time for neuromodulator records.
# seq is a tick-based counter (not wall-clock time): JEPA creatures have fewer
# ticks per second than baseline, so raw seq is misleading as a time axis.
# We linearly interpolate: elapsed_s ≈ (seq / max_seq_per_creature) × lifetime_s.
_neuro_max_seq = (
    neuro.groupby(["creature_key", "condition", "trial"])["seq"]
    .max().reset_index(name="neuro_max_seq")
)
_neuro_lifetime = creatures[["creature_key", "condition", "trial", "lifetime_s"]].drop_duplicates()
_neuro_max_seq = _neuro_max_seq.merge(_neuro_lifetime, on=["creature_key", "condition", "trial"], how="left")
neuro = neuro.merge(_neuro_max_seq, on=["creature_key", "condition", "trial"], how="left")
neuro["elapsed_s"] = (neuro["seq"] / neuro["neuro_max_seq"].clip(lower=1)) * num(neuro["lifetime_s"])
expectancy["rpe"]       = num(expectancy["rpe"])
expectancy["expected"]  = num(expectancy["expected"])
expectancy["reward"]    = num(expectancy["reward"])
expectancy["cycle"]     = num(expectancy["cycle"])
engrams["eligibility"]  = num(engrams["eligibility"])
engrams["emotion_delta"] = num(engrams["emotion_delta"])

# Tick counts and inference correction
tick_counts = (
    actions.groupby(["creature_key", "condition", "trial"])
    .size().reset_index(name="tick_count")
)
creatures = creatures.merge(tick_counts, on=["creature_key", "condition", "trial"], how="left")
creatures["tick_count"] = creatures["tick_count"].fillna(0).astype(int)

wm_overhead = (
    actions[actions["selection_type"] == "WORLD_MODEL"]
    .groupby(["creature_key", "condition", "trial"])["inference_ms"]
    .sum().reset_index(name="total_inference_ms")
)
creatures = creatures.merge(wm_overhead, on=["creature_key", "condition", "trial"], how="left")
creatures["total_inference_ms"]   = creatures["total_inference_ms"].fillna(0.0)
creatures["lifetime_corrected_s"] = creatures["lifetime_s"] - creatures["total_inference_ms"] / 1000.0

print(f"  Creatures : {len(creatures)}")
print(f"  Actions   : {len(actions)}")
print(f"  Mouth     : {len(mouth)}")

# Shared tick-rank helper
born_lookup2 = creatures[["creature_key", "condition", "trial",
                           "born_time", "tick_count"]].drop_duplicates()
actions_ranked = actions[["creature_key", "condition", "trial", "time", "action_type"]].copy()
actions_ranked["tick_rank"] = actions_ranked.groupby(
    ["creature_key", "condition", "trial"]).cumcount()
actions_for_asof = actions_ranked.sort_values("time").reset_index(drop=True)

def attach_tick_rank(df):
    df = df.merge(born_lookup2, on=["creature_key", "condition", "trial"], how="left")
    df_s = df.sort_values("time").reset_index(drop=True)
    df_s = pd.merge_asof(
        df_s,
        actions_for_asof[["creature_key", "condition", "trial", "time", "tick_rank"]],
        on="time", by=["creature_key", "condition", "trial"], direction="nearest",
    )
    df_s["life_frac"]   = df_s["tick_rank"] / df_s["tick_count"].clip(lower=1)
    df_s["life_decile"] = (df_s["life_frac"] * 10).clip(upper=9).astype(int)
    return df_s

mouth_ranked = attach_tick_rank(mouth.copy())

# ─── Helper ───────────────────────────────────────────────────────────────────

def cond_stats(series_by_cond, label=""):
    print(f"\n  {label}")
    for ck, cl in CONDITIONS:
        vals = np.array(series_by_cond.get(ck, []))
        vals = vals[~np.isnan(vals)]
        if len(vals):
            print(f"    {cl:<22s}  {np.mean(vals):8.2f} ± {np.std(vals):6.2f}  (n={len(vals)})")


def kruskal_test(groups, labels):
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
# 1. SURVIVAL
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 1. SURVIVAL ===")

lifetimes = {ck: creatures[creatures["condition"] == ck]["lifetime_s"].dropna().values
             for ck, _ in CONDITIONS}
cond_stats(lifetimes, "Lifetime (s)")
kruskal_test([np.array(lifetimes[ck]) for ck, _ in CONDITIONS], COND_LABELS)

ticks_by_cond = {ck: creatures[creatures["condition"] == ck]["tick_count"].values
                 for ck, _ in CONDITIONS}

fig, axes = plt.subplots(1, 2, figsize=(13, 5))

ax = axes[0]
bp = ax.boxplot([np.array(lifetimes[ck]) / 60 for ck, _ in CONDITIONS],
                tick_labels=COND_LABELS, patch_artist=True)
for patch, (ck, _) in zip(bp["boxes"], CONDITIONS):
    patch.set_facecolor(PALETTE[ck]); patch.set_alpha(0.7)
ax.set_ylabel("Lifetime (min)")
ax.set_title("Wall-clock Lifetime")
ax.grid(axis="y", alpha=0.3)

ax = axes[1]
bp2 = ax.boxplot([np.array(ticks_by_cond[ck]) for ck, _ in CONDITIONS],
                 tick_labels=COND_LABELS, patch_artist=True)
for patch, (ck, _) in zip(bp2["boxes"], CONDITIONS):
    patch.set_facecolor(PALETTE[ck]); patch.set_alpha(0.7)
ax.set_ylabel("Decision cycles")
ax.set_title("Lifetime (decision ticks)")
ax.grid(axis="y", alpha=0.3)

plt.tight_layout()
fig.savefig(FIG_DIR / "01_survival.png", dpi=150)
plt.close(fig)
print("  → 01_survival.png")

# ══════════════════════════════════════════════════════════════════════════════
# 2. ROTTEN APPLE CONSUMPTION — primary learning metric
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 2. ROTTEN APPLE CONSUMPTION ===")

rotten_mouth = mouth_ranked[mouth_ranked["object_type"] == "ROTTEN_APPLE"].copy()
total_mouth  = mouth_ranked.copy()

print("  Total EAT events per condition:")
for ck, cl in CONDITIONS:
    total = len(total_mouth[total_mouth["condition"] == ck])
    rotten = len(rotten_mouth[rotten_mouth["condition"] == ck])
    pct = 100 * rotten / total if total > 0 else 0
    print(f"    {cl:<22s}  total={total:5d}  rotten={rotten:4d} ({pct:.1f}%)")

# --- Rotten apple % of EAT events by life decile (primary figure) ---
fig, axes = plt.subplots(1, 2, figsize=(14, 5))

ax = axes[0]
for ck, cl in CONDITIONS:
    rotten_by_decile = (
        rotten_mouth[rotten_mouth["condition"] == ck]
        .groupby("life_decile").size()
    )
    total_by_decile = (
        total_mouth[total_mouth["condition"] == ck]
        .groupby("life_decile").size()
    )
    # Reindex both to all 10 deciles before dividing to avoid NaN from missing keys.
    # Deciles with total=0 → NaN (genuinely no data); deciles with total>0, rotten=0 → 0%.
    r_filled = rotten_by_decile.reindex(range(10), fill_value=0)
    t_filled = total_by_decile.reindex(range(10), fill_value=0)
    pct = np.where(t_filled > 0, r_filled / t_filled.clip(lower=1) * 100, np.nan)
    ax.plot(range(10), pct, color=PALETTE[ck], lw=2.5, marker="o", ms=5, label=cl)

ax.set_xticks(range(10))
ax.set_xticklabels(DECILE_LABELS, rotation=30, ha="right", fontsize=8)
ax.set_xlabel("Life decile")
ax.set_ylabel("ROTTEN_APPLE % of EAT events")
ax.set_title("A — Rotten Apple Consumption Rate Over Lifetime\n(declining = aversion learning)")
ax.legend(fontsize=9)
ax.grid(alpha=0.3)

# --- Cumulative rotten apple count per creature over lifetime ---
ax = axes[1]
cum_records = []
for (cond, trial, ck_), grp in mouth_ranked.groupby(["condition", "trial", "creature_key"]):
    per_decile = grp[grp["object_type"] == "ROTTEN_APPLE"].groupby("life_decile").size()
    cumulative = 0
    for d in range(10):
        cumulative += per_decile.get(d, 0)
        cum_records.append({"condition": cond, "life_decile": d, "cum_count": cumulative})

cum_df = pd.DataFrame(cum_records)
avg_cum = cum_df.groupby(["condition", "life_decile"])["cum_count"].mean().reset_index()

for ck, cl in CONDITIONS:
    sub = avg_cum[avg_cum["condition"] == ck]
    vals = sub.set_index("life_decile")["cum_count"].reindex(range(10)).values
    ax.plot(range(10), vals, color=PALETTE[ck], lw=2.5, marker="o", ms=5, label=cl)

ax.set_xticks(range(10))
ax.set_xticklabels(DECILE_LABELS, rotation=30, ha="right", fontsize=8)
ax.set_xlabel("Life decile")
ax.set_ylabel("Cumulative ROTTEN_APPLE eaten (avg per creature)")
ax.set_title("B — Cumulative Rotten Apple Consumption\n(flattening slope = aversion kicking in)")
ax.legend(fontsize=9)
ax.grid(alpha=0.3)

fig.suptitle("Rotten Apple Aversion Learning", fontsize=13)
plt.tight_layout()
fig.savefig(FIG_DIR / "02_rotten_consumption.png", dpi=150)
plt.close(fig)
print("  → 02_rotten_consumption.png")

# ══════════════════════════════════════════════════════════════════════════════
# 3. ACTION TAKEN WHEN ROTTEN APPLE PERCEIVED
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 3. ACTION ON ROTTEN APPLE PERCEPTION ===")

rotten_perc = (
    perceptions[perceptions["object_type"] == "ROTTEN_APPLE"]
    .copy().sort_values("time").reset_index(drop=True)
)
rotten_perc_ranked = attach_tick_rank(rotten_perc)

rotten_w_action = pd.merge_asof(
    rotten_perc_ranked.sort_values("time").reset_index(drop=True),
    actions_for_asof[["creature_key", "condition", "trial", "time", "action_type"]],
    on="time", by=["creature_key", "condition", "trial"], direction="nearest",
)

APPROACH_ACTIONS = {"APPROACH", "EAT", "TOUCH"}
rotten_w_action["approached"] = rotten_w_action["action_type"].isin(APPROACH_ACTIONS).astype(int)

print("  Overall approach rate when ROTTEN_APPLE perceived:")
for ck, cl in CONDITIONS:
    sub = rotten_w_action[rotten_w_action["condition"] == ck]
    rate = sub["approached"].mean() * 100 if len(sub) else 0
    print(f"    {cl:<22s}  encounters={len(sub):6d}  approach={rate:.1f}%")

# Approach rate by life decile
fig, axes = plt.subplots(1, 2, figsize=(14, 5))

ax = axes[0]
for ck, cl in CONDITIONS:
    sub = rotten_w_action[rotten_w_action["condition"] == ck]
    rate = sub.groupby("life_decile")["approached"].mean() * 100
    ax.plot(range(10), rate.reindex(range(10), fill_value=np.nan).values,
            color=PALETTE[ck], lw=2.5, marker="o", ms=5, label=cl)

ax.set_xticks(range(10))
ax.set_xticklabels(DECILE_LABELS, rotation=30, ha="right", fontsize=8)
ax.set_xlabel("Life decile")
ax.set_ylabel("% ROTTEN_APPLE encounters → APPROACH/EAT/TOUCH")
ax.set_title("A — Approach Rate When Rotten Apple Perceived\n(declining = aversion learning)")
ax.legend(fontsize=9)
ax.grid(alpha=0.3)
ax.axhline(y=33, color="gray", ls="--", lw=1, alpha=0.5, label="chance (3 actions)")

# Action breakdown when perceiving ROTTEN_APPLE
ax = axes[1]
action_dist = (
    rotten_w_action.groupby(["condition", "action_type"])
    .size().unstack(fill_value=0)
)
action_pct = action_dist.div(action_dist.sum(axis=1), axis=0) * 100
action_pct_plot = action_pct.reindex([ck for ck, _ in CONDITIONS])
action_pct_plot.index = COND_LABELS
action_pct_plot.plot(kind="bar", stacked=True, ax=ax, colormap="tab10", alpha=0.85)
ax.set_ylabel("% of ROTTEN_APPLE perception events")
ax.set_title("B — Action Distribution When Rotten Apple Perceived")
ax.legend(loc="upper right", fontsize=7, ncol=2)
ax.set_xticklabels(COND_LABELS, rotation=15, ha="right")
ax.grid(axis="y", alpha=0.3)

fig.suptitle("Behavioural Response to Rotten Apple", fontsize=13)
plt.tight_layout()
fig.savefig(FIG_DIR / "03_rotten_perception_response.png", dpi=150)
plt.close(fig)
print("  → 03_rotten_perception_response.png")

# ══════════════════════════════════════════════════════════════════════════════
# 4. FOOD TYPE SELECTION OVER NORMALISED LIFETIME
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 4. FOOD TYPE SELECTION ===")

print("  Total EAT by food type:")
ot_counts = mouth_ranked.groupby(["condition", "object_type"]).size().unstack(fill_value=0)
print(ot_counts.reindex([ck for ck, _ in CONDITIONS]).to_string())

n_cond = len(CONDITIONS)
fig, axes = plt.subplots(1, n_cond, figsize=(5 * n_cond, 5), sharey=True)

for ax, (ck, cl) in zip(axes, CONDITIONS):
    for ft in FOOD_TYPES:
        cum_recs = []
        for (cond, trial, ck_), grp in mouth_ranked[mouth_ranked["condition"] == ck].groupby(
                ["condition", "trial", "creature_key"]):
            per_decile = grp[grp["object_type"] == ft].groupby("life_decile").size()
            cumulative = 0
            for d in range(10):
                cumulative += per_decile.get(d, 0)
                cum_recs.append({"life_decile": d, "cum_count": cumulative})
        cum = pd.DataFrame(cum_recs).groupby("life_decile")["cum_count"].mean()
        ax.plot(range(10), cum.reindex(range(10), fill_value=np.nan).values,
                color=FOOD_COLORS.get(ft, "gray"), lw=2.2, marker="o", ms=5,
                label=ft.replace("_", " ").title())
    ax.set_title(cl, fontsize=10)
    ax.set_xticks(range(10))
    ax.set_xticklabels(DECILE_LABELS, rotation=45, ha="right", fontsize=6)
    ax.set_xlabel("Life decile")
    ax.grid(alpha=0.3)

axes[0].set_ylabel("Cumulative EAT count (avg per creature)")
axes[0].legend(fontsize=9)
fig.suptitle(
    "Food-Type Preference Over Normalised Lifetime\n"
    "(flattening ROTTEN curve = learning to avoid; steepening GREEN = preferring quality food)",
    fontsize=12)
plt.tight_layout()
fig.savefig(FIG_DIR / "04_food_preference.png", dpi=150)
plt.close(fig)
print("  → 04_food_preference.png")

# ══════════════════════════════════════════════════════════════════════════════
# 5. DRIVE REGULATION
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 5. DRIVE REGULATION ===")

drives["arousal"] = drives[[c for c in DRIVE_COLS if c in drives.columns]].sum(axis=1)
BIN_S = 30
drives["time_bin"] = (drives["elapsed_s"] // BIN_S).clip(lower=0).astype(int)

mean_arousal = {ck: drives[drives["condition"] == ck]["arousal"].dropna().values
                for ck, _ in CONDITIONS}
cond_stats(mean_arousal, "Mean arousal")

fig, axes = plt.subplots(1, 2, figsize=(14, 5))

ax = axes[0]
for ck, cl in CONDITIONS:
    sub = drives[drives["condition"] == ck]
    gb  = sub.groupby("time_bin")["arousal"].mean()
    t   = gb.index * BIN_S / 60
    ax.plot(t, gb, color=PALETTE[ck], lw=2, label=cl)
ax.set_xlabel("Elapsed time (min)")
ax.set_ylabel("Mean Total Arousal")
ax.set_title("Arousal Over Time")
ax.legend(fontsize=10, loc="upper right", framealpha=0.9)
ax.grid(alpha=0.3)

ax = axes[1]
DRIVE_PLOT_COLS  = ["final_hunger", "final_sleep", "final_pain", "final_tedium"]
DRIVE_PLOT_NAMES = ["Hunger", "Sleep", "Pain", "Tedium"]
DRIVE_LS = {"final_hunger": "-", "final_sleep": "--", "final_pain": ":", "final_tedium": "-."}
for col, name in zip(DRIVE_PLOT_COLS, DRIVE_PLOT_NAMES):
    if col not in drives.columns:
        continue
    for ck, cl in CONDITIONS:
        sub = drives[drives["condition"] == ck]
        gb  = sub.groupby("time_bin")[col].mean()
        t   = gb.index * BIN_S / 60
        ax.plot(t, gb, color=PALETTE[ck], lw=1.5, linestyle=DRIVE_LS.get(col, "-"),
                label=f"{name} ({cl})" if ck == COND_KEYS[0] else f"_ {col} {ck}")
ax.set_xlabel("Elapsed time (min)")
ax.set_ylabel("Drive Level")
ax.set_title("Per-Drive Levels Over Time")
ax.legend(fontsize=7, ncol=2, loc="upper right", framealpha=0.9)
ax.grid(alpha=0.3)

plt.tight_layout()
fig.savefig(FIG_DIR / "05_drives.png", dpi=150)
plt.close(fig)
print("  → 05_drives.png")

# ══════════════════════════════════════════════════════════════════════════════
# 6. NEUROMODULATORS
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 6. NEUROMODULATORS ===")

BIN_S_NEURO = 5  # 5-second bins based on approximated wall-clock elapsed time
neuro["time_bin"] = (neuro["elapsed_s"] // BIN_S_NEURO).clip(lower=0).astype(int)

fig, axes = plt.subplots(1, 3, figsize=(16, 5))
for ax, nm, nm_name in zip(axes, ["dopamine", "serotonin", "orexin"],
                                   ["Dopamine", "Serotonin", "Orexin"]):
    for ck, cl in CONDITIONS:
        sub = neuro[neuro["condition"] == ck]
        gb  = sub.groupby("time_bin")[nm].mean()
        t   = gb.index * BIN_S_NEURO / 60
        ax.plot(t, gb, color=PALETTE[ck], lw=2, label=cl)
    ax.set_title(nm_name)
    ax.set_xlabel("Time (min)")
    ax.set_ylabel("Tonic level")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)

fig.suptitle("Neuromodulator Tonic Levels", fontsize=13)
plt.tight_layout()
fig.savefig(FIG_DIR / "06_neuromodulators.png", dpi=150)
plt.close(fig)
print("  → 06_neuromodulators.png")

# ══════════════════════════════════════════════════════════════════════════════
# 7. RPE AROUND ROTTEN APPLE EVENTS (all conditions with expectancy data)
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 7. RPE & EXPECTANCY ===")

exp_conds = [ck for ck, _ in CONDITIONS if ck in expectancy["condition"].unique()]
if not expectancy.empty and exp_conds:
    print("  Conditions with expectancy data:", exp_conds)
    for ck in exp_conds:
        sub = expectancy[expectancy["condition"] == ck]["rpe"].dropna()
        print(f"    {dict(CONDITIONS)[ck]:<22s}  |RPE| mean={np.abs(sub).mean():.4f}  std={sub.std():.4f}")

    BIN_EXP = 50
    expectancy["cycle_bin"] = (num(expectancy["cycle"]) // BIN_EXP).astype(int)

    n_panels = len(exp_conds)
    fig, axes = plt.subplots(1, n_panels, figsize=(4 * n_panels, 4), sharey=False)
    if n_panels == 1:
        axes = [axes]

    for ax, ck in zip(axes, exp_conds):
        sub = expectancy[expectancy["condition"] == ck]
        if sub.empty:
            ax.set_visible(False)
            continue
        gb = sub.groupby("cycle_bin")["rpe"].apply(lambda x: np.abs(x).mean())
        ax.plot(gb.index * BIN_EXP, gb, color=PALETTE[ck], lw=2)
        ax.fill_between(gb.index * BIN_EXP, gb, alpha=0.25, color=PALETTE[ck])
        ax.set_title(dict(CONDITIONS)[ck], fontsize=11, color=PALETTE[ck], fontweight="bold")
        ax.set_xlabel("Cognitive cycle")
        ax.grid(alpha=0.3)

    axes[0].set_ylabel("|RPE|")
    fig.suptitle("Mean |RPE| Over Time — one panel per condition", fontsize=12)
    plt.tight_layout()
    fig.savefig(FIG_DIR / "07_rpe.png", dpi=150)
    plt.close(fig)
    print("  → 07_rpe.png")
else:
    print("  No expectancy data found")

# ══════════════════════════════════════════════════════════════════════════════
# 8. ENGRAMS — salience of rotten-fruit-associated traces
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== 8. ENGRAMS ===")

engram_counts = engrams.groupby("condition").size()
engram_delta  = engrams.groupby("condition")["emotion_delta"].apply(lambda x: np.abs(x).mean())

for ck, cl in CONDITIONS:
    n     = engram_counts.get(ck, 0)
    delta = engram_delta.get(ck, np.nan)
    print(f"  {cl:<22s}  n={n:6d}  mean|delta|={delta:.4f}")

fig, axes = plt.subplots(1, 2, figsize=(13, 5))

ax = axes[0]
ax.bar(COND_LABELS, [engram_counts.get(ck, 0) for ck, _ in CONDITIONS],
       color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
ax.set_title("Engrams Formed (5 trials)")
ax.set_xlabel("Condition")
ax.set_ylabel("Count")
ax.set_xticklabels(COND_LABELS, rotation=15, ha="right")
ax.grid(axis="y", alpha=0.3)

ax = axes[1]
for ck, cl in CONDITIONS:
    sub = engrams[engrams["condition"] == ck]["emotion_delta"].dropna().abs()
    if sub.empty: continue
    ax.hist(sub.clip(0, 1), bins=40, alpha=0.5, color=PALETTE[ck], label=cl, density=True)
ax.set_title("|Emotion Delta| Distribution")
ax.set_xlabel("|emotion_delta| (clipped 0–1)")
ax.set_ylabel("Density")
ax.legend(fontsize=9)
ax.grid(alpha=0.3)

plt.tight_layout()
fig.savefig(FIG_DIR / "08_engrams.png", dpi=150)
plt.close(fig)
print("  → 08_engrams.png")

# ══════════════════════════════════════════════════════════════════════════════
# 9. SUMMARY TABLE
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== SUMMARY TABLE ===")

ot_counts_full = mouth_ranked.groupby(["condition", "object_type"]).size().unstack(fill_value=0)

print(f"{'Condition':<24}  {'Lifetime(s)':>11}  {'Ticks':>7}  "
      f"{'Rotten EAT':>10}  {'Rotten%':>7}  {'|RPE|':>7}  {'Engrams':>8}")
print("-" * 90)
for ck, cl in CONDITIONS:
    lt      = np.nanmean(lifetimes[ck]) if len(lifetimes[ck]) else np.nan
    tc      = np.nanmean(ticks_by_cond[ck]) if len(ticks_by_cond[ck]) else np.nan
    rotten  = ot_counts_full.loc[ck, "ROTTEN_APPLE"] if ck in ot_counts_full.index else 0
    total_e = ot_counts_full.loc[ck].sum() if ck in ot_counts_full.index else 0
    rpct    = 100 * rotten / total_e if total_e else 0
    rpe_m   = np.abs(expectancy[expectancy["condition"] == ck]["rpe"].dropna()).mean() if ck in exp_conds else np.nan
    eng     = engram_counts.get(ck, 0)
    print(f"  {cl:<22s}  {lt:>11.1f}  {tc:>7.0f}  "
          f"{rotten:>10.0f}  {rpct:>6.1f}%  {rpe_m:>7.4f}  {eng:>8d}")

print(f"\nFigures saved → {FIG_DIR}")
