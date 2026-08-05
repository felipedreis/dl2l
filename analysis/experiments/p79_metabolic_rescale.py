"""
Analysis: p79_metabolic_rescale
Issue #79 Phase A validation — single condition (1_rescaled), 3 trials x 3 creatures.

Question: does rescaling Constants.java's metabolic/circadian/sleep rates by
1/S (S=4.286, the measured post-#76/#78 cognitive-cycle throughput jump) restore
realistic wall-clock lifespan (~150s, pre-#76 baseline) and give creatures enough
cognitive cycles per lifetime for learned behaviour to emerge?

Data: ml/data_p79_metabolic_rescale/

Usage:
  python3 -m dl2l_analysis --experiment p79_metabolic_rescale
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from analysis.dl2l_analysis.config import ExperimentAnalysis
from analysis.dl2l_analysis.figures import DECILE_LABELS, plt, save
from analysis.dl2l_analysis.loading import attach_born_time_and_ticks, load_all, make_tick_rank_attacher, num

# Pre-#76 baseline (docs/reports/20260709_p59_batching_fix_report.md, empirical from
# ml/data_p59_calibration): 3 creatures, same world/food sizing as this experiment's sim.
PRE_76_LIFETIMES_S = [151.285, 148.447, 153.822]
PRE_76_CYCLES = [23541, 22890, 24021]

# Current-broken baseline (docs/reports/p79_metabolic_clock_report.md, this issue's
# diagnostic, unscaled Constants.java): 3 trials x 3 creatures.
BROKEN_POOLED_RATE_HZ = 665.75
DRIVE_COLS = [
    "final_hunger", "final_sleep", "final_pain", "final_tedium",
    "final_apathy", "final_stress", "final_fear", "final_curiosity",
]


def default_config() -> ExperimentAnalysis:
    from analysis.dl2l_analysis.config import from_spec
    return from_spec("p79_metabolic_rescale")


def run(cfg: ExperimentAnalysis | None = None) -> None:
    cfg = cfg or default_config()
    COND_KEY = cfg.cond_keys[0]
    COLOR = cfg.palette[COND_KEY]

    print("Loading data …")
    creatures = load_all(cfg, "creatures.parquet")
    drives = load_all(cfg, "drives.parquet")
    actions = load_all(cfg, "actions.parquet")
    sleep_ep = load_all(cfg, "sleep_episodes.parquet")

    creatures["lifetime_s"] = num(creatures["lifetime_s"])
    actions["time"] = num(actions["time"])
    for col in DRIVE_COLS:
        if col in drives.columns:
            drives[col] = num(drives[col])

    creatures = attach_born_time_and_ticks(creatures, actions)
    attach_tick_rank, _ = make_tick_rank_attacher(actions)
    actions_ranked = attach_tick_rank(actions.copy(), creatures)

    print(f"  Creatures : {len(creatures)}")
    print(f"  Actions   : {len(actions)}")

    # ══════════════════════════════════════════════════════════════════════
    # 1. LIFESPAN — primary Phase-A success criterion
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 1. LIFESPAN ===")
    lifetimes = creatures["lifetime_s"].dropna().values
    print(f"  n={len(lifetimes)}  mean={np.mean(lifetimes):.1f}s  std={np.std(lifetimes):.1f}s  "
          f"min={np.min(lifetimes):.1f}s  max={np.max(lifetimes):.1f}s")
    print(f"  Pre-#76 baseline mean: {np.mean(PRE_76_LIFETIMES_S):.1f}s")

    fig, ax = plt.subplots(figsize=(6, 5))
    bp = ax.boxplot([lifetimes, PRE_76_LIFETIMES_S], tick_labels=["Phase A rescale", "Pre-#76 baseline"],
                     patch_artist=True)
    bp["boxes"][0].set_facecolor(COLOR)
    bp["boxes"][1].set_facecolor("#9e9e9e")
    for b in bp["boxes"]:
        b.set_alpha(0.7)
    ax.set_ylabel("Lifetime (s)")
    ax.set_title("Lifespan: Phase A rescale vs. pre-#76 baseline")
    ax.grid(axis="y", alpha=0.3)
    plt.tight_layout()
    save(fig, "01_lifespan.png", cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 2. CAUSE OF DEATH — should be hunger, not sleep (MAX_AROUSAL_LEVEL=7.0)
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 2. CAUSE OF DEATH ===")
    last_drive = (
        drives.sort_values("time")
        .groupby(["creature_key", "condition", "trial"]).tail(1)
    )
    causes = last_drive[DRIVE_COLS].idxmax(axis=1).str.replace("final_", "")
    cause_counts = causes.value_counts()
    print(cause_counts.to_string())
    max_sleep = last_drive["final_sleep"].max() if "final_sleep" in last_drive.columns else float("nan")
    print(f"  Max sleep arousal at any death: {max_sleep:.3f}  (lethal ceiling=7.0)")

    # ══════════════════════════════════════════════════════════════════════
    # 3. COGNITIVE CYCLES PER LIFETIME — enough cycles for behaviour to emerge?
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 3. COGNITIVE CYCLES PER LIFETIME ===")
    cycles = creatures["tick_count"].values
    print(f"  n={len(cycles)}  mean={np.mean(cycles):.0f}  std={np.std(cycles):.0f}  "
          f"min={np.min(cycles):.0f}  max={np.max(cycles):.0f}")
    print(f"  Pre-#76 baseline mean: {np.mean(PRE_76_CYCLES):.0f}")
    pct_above = 100 * np.mean(np.array(cycles) >= np.mean(PRE_76_CYCLES))
    print(f"  % of creatures with cycles >= pre-#76 mean: {pct_above:.0f}%")

    rate = creatures["tick_count"].sum() / creatures["lifetime_s"].sum()
    print(f"  Pooled cycle rate this run: {rate:.1f} Hz  (current-broken baseline: {BROKEN_POOLED_RATE_HZ:.1f} Hz)")

    fig, ax = plt.subplots(figsize=(6, 5))
    bp = ax.boxplot([cycles, PRE_76_CYCLES], tick_labels=["Phase A rescale", "Pre-#76 baseline"],
                     patch_artist=True)
    bp["boxes"][0].set_facecolor(COLOR)
    bp["boxes"][1].set_facecolor("#9e9e9e")
    for b in bp["boxes"]:
        b.set_alpha(0.7)
    ax.set_ylabel("Cognitive cycles per lifetime")
    ax.set_title("Cycles-to-death: Phase A rescale vs. pre-#76 baseline")
    ax.grid(axis="y", alpha=0.3)
    plt.tight_layout()
    save(fig, "02_cycles_per_lifetime.png", cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 4. SLEEP EPISODES — bounded, not runaway (matches p59-style expectation)
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 4. SLEEP EPISODES ===")
    if not sleep_ep.empty:
        sleep_ep["duration_ticks"] = num(sleep_ep["duration_ticks"])
        print(f"  Total episodes: {len(sleep_ep)}  ({len(sleep_ep)/len(creatures):.1f} per creature)")
        print(f"  Mean duration: {sleep_ep['duration_ticks'].mean():.1f} ticks  "
              f"(median: {sleep_ep['duration_ticks'].median():.0f})")
    else:
        print("  No sleep episodes recorded")

    # ══════════════════════════════════════════════════════════════════════
    # 5. BEHAVIOUR EMERGENCE — action distribution over normalised lifetime
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 5. BEHAVIOUR OVER LIFETIME (action distribution) ===")
    action_counts = actions["action_type"].value_counts()
    print(action_counts.to_string())

    fig, axes = plt.subplots(1, 2, figsize=(13, 5))

    ax = axes[0]
    ax.bar(action_counts.index, action_counts.values, color=COLOR, alpha=0.8)
    ax.set_ylabel("Count (all creatures, all trials)")
    ax.set_title("Overall Action Distribution")
    ax.tick_params(axis="x", rotation=30)
    ax.grid(axis="y", alpha=0.3)

    ax = axes[1]
    forage = actions_ranked[actions_ranked["action_type"].isin(["EAT", "APPROACH"])]
    by_decile = forage.groupby("life_decile").size()
    total_by_decile = actions_ranked.groupby("life_decile").size()
    pct = (by_decile.reindex(range(10), fill_value=0) / total_by_decile.reindex(range(10), fill_value=1) * 100)
    ax.plot(range(10), pct.values, color=COLOR, lw=2.5, marker="o", ms=5)
    ax.set_xticks(range(10))
    ax.set_xticklabels(DECILE_LABELS, rotation=30, ha="right", fontsize=8)
    ax.set_xlabel("Life decile")
    ax.set_ylabel("EAT+APPROACH % of actions")
    ax.set_title("Foraging Share Over Normalised Lifetime")
    ax.grid(alpha=0.3)

    plt.tight_layout()
    save(fig, "03_behaviour.png", cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 6. DRIVE TRAJECTORY — hunger should rise steadily to MAX_AROUSAL (7.0)
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 6. DRIVE TRAJECTORY ===")
    from analysis.dl2l_analysis.loading import attach_elapsed_s
    drives_e = attach_elapsed_s(drives, creatures, "time")
    BIN_S = 5
    drives_e["time_bin"] = (drives_e["elapsed_s"] // BIN_S).clip(lower=0).astype(int)

    fig, ax = plt.subplots(figsize=(8, 5))
    for col, name, ls in [("final_hunger", "Hunger", "-"), ("final_sleep", "Sleep", "--"),
                           ("final_pain", "Pain", ":"), ("final_tedium", "Tedium", "-.")]:
        if col not in drives_e.columns:
            continue
        gb = drives_e.groupby("time_bin")[col].mean()
        t = gb.index * BIN_S
        ax.plot(t, gb, lw=2, linestyle=ls, label=name, color=COLOR if col == "final_hunger" else None)
    ax.axhline(y=7.0, color="red", ls="--", lw=1, alpha=0.5, label="MAX_AROUSAL (death)")
    ax.set_xlabel("Elapsed time (s)")
    ax.set_ylabel("Drive level")
    ax.set_title("Drive Trajectories Over Time")
    ax.legend(fontsize=9)
    ax.grid(alpha=0.3)
    plt.tight_layout()
    save(fig, "04_drives_over_time.png", cfg)

    print(f"\nFigures saved → {cfg.fig_dir}")


if __name__ == "__main__":
    run()
