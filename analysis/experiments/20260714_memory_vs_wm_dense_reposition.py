"""
Analysis: 20260714_memory_vs_wm_dense_reposition
Memory-based learning vs. JEPA world model, dense world + reposition — 5 conditions × 5 trials × 10 creatures

Conditions:
  1_baseline               — no extra learning
  2_memory_only            — memory filter in action selection
  3_memory_consolidation   — memory filter + sleep consolidation (MemoryTraceConsolidator)
  4_jepa_rpe_only          — JEPA world-model filter + JEPA RPE baseline, no consolidation
  5_jepa_rpe_consolidation — JEPA world-model filter + JEPA RPE baseline + consolidation

World: 500 RED_APPLE, 500 GREEN_APPLE, 500 GRAY_APPLE, 50 CACTUS, 100 ALOE,
1200x900 (2x the original 20260709_memory_vs_wm_v1's world), 10 creatures (2x),
reposition=true (food regenerates, no scarcity depletion), maxRuntimeMinutes=60.

This is the dl2l_analysis-based port of analysis/exp_20260709_memory_vs_wm_v1.py
(same conditions, same world-object types, same figures/stats) — adjusted for
this variant's larger world/population and re-run through the shared
loading/stats/figure scaffold in analysis/dl2l_analysis/ instead of duplicating
it. The primary question carried over from that experiment: does memory/JEPA
world-model filtering improve survival and world-interaction quality (food
selection, cactus avoidance) relative to baseline, and does consolidation add
anything on top of the filter alone? The dense/reposition variant additionally
asks whether those effects hold up under higher creature density (this was
also the setup used to validate this week's CCAD infra fixes — see
docs/plans/ccad-metrics-completion-detection.md and
docs/plans/ccad-singularity-experiments.md for that side of the story).

Data: ml/data_20260714_memory_vs_wm_dense_reposition/

Usage:
  python3 -m dl2l_analysis --experiment 20260714_memory_vs_wm_dense_reposition
  (or directly: python3 -m analysis.experiments.20260714_memory_vs_wm_dense_reposition)
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from analysis.dl2l_analysis.config import ExperimentAnalysis
from analysis.dl2l_analysis.figures import DECILE_LABELS, plt, save
from analysis.dl2l_analysis.loading import (
    attach_born_time_and_ticks,
    attach_elapsed_s,
    load_all,
    make_tick_rank_attacher,
    num,
)
from analysis.dl2l_analysis.stats import cond_stats, kruskal_test

FOOD_TYPES = ["RED_APPLE", "GREEN_APPLE", "GRAY_APPLE"]
FOOD_COLORS = {
    "RED_APPLE": "#e74c3c",
    "GREEN_APPLE": "#27ae60",
    "GRAY_APPLE": "#7f8c8d",
}

DRIVE_COLS = [
    "final_hunger", "final_sleep", "final_pain", "final_tedium",
    "final_apathy", "final_stress", "final_fear", "final_curiosity",
]
DRIVE_PLOT_COLS = ["final_hunger", "final_sleep", "final_pain", "final_tedium"]
DRIVE_PLOT_NAMES = ["Hunger", "Sleep", "Pain", "Tedium"]

EXP_NAME = "20260714_memory_vs_wm_dense_reposition"


def run(cfg: ExperimentAnalysis | None = None) -> None:
    cfg = cfg or ExperimentAnalysis.from_spec(EXP_NAME)
    CONDITIONS = [(c.key, c.label) for c in cfg.conditions]
    COND_KEYS = cfg.cond_keys
    COND_LABELS = cfg.cond_labels
    PALETTE = cfg.palette

    # ─── Load ───────────────────────────────────────────────────────────────
    print("Loading data …")
    creatures = load_all(cfg, "creatures.parquet")
    drives = load_all(cfg, "drives.parquet")
    actions = load_all(cfg, "actions.parquet")
    behav = load_all(cfg, "behavioural_efficiency.parquet")
    mouth = load_all(cfg, "mouth_interactions.parquet")
    perceptions = load_all(cfg, "perceptions.parquet")
    neuro = load_all(cfg, "neuromodulators.parquet")
    expectancy = load_all(cfg, "expectancy.parquet")
    engrams = load_all(cfg, "engrams.parquet")
    sleep_ep = load_all(cfg, "sleep_episodes.parquet")

    for col in DRIVE_COLS:
        if col in drives.columns:
            drives[col] = num(drives[col])
    drives = attach_elapsed_s(drives, creatures, "time")

    actions["time"] = num(actions["time"])
    actions["inference_ms"] = num(actions["inference_ms"])
    mouth["time"] = num(mouth["time"])
    perceptions["time"] = num(perceptions["time"])
    perceptions["distance"] = num(perceptions["distance"])
    neuro["dopamine"] = num(neuro["dopamine"])
    neuro["serotonin"] = num(neuro["serotonin"])
    neuro["orexin"] = num(neuro["orexin"])
    neuro["seq"] = num(neuro["seq"])
    expectancy["rpe"] = num(expectancy["rpe"])
    expectancy["expected"] = num(expectancy["expected"])
    expectancy["reward"] = num(expectancy["reward"])
    expectancy["cycle"] = num(expectancy["cycle"])
    engrams["eligibility"] = num(engrams["eligibility"])
    engrams["emotion_delta"] = num(engrams["emotion_delta"])
    sleep_ep["duration_ticks"] = num(sleep_ep["duration_ticks"])

    creatures = attach_born_time_and_ticks(creatures, actions)
    behav = attach_elapsed_s(behav, creatures, "time")
    behav["efficiency"] = num(behav["efficiency"])

    print(f"  Creatures : {len(creatures)}")
    print(f"  Drives    : {len(drives)}")
    print(f"  Actions   : {len(actions)}")

    attach_tick_rank, actions_for_asof = make_tick_rank_attacher(actions)
    mouth_ranked = attach_tick_rank(mouth.copy(), creatures)

    print("  Tick counts and inference correction attached:")
    for ck, cl in CONDITIONS:
        sub = creatures[creatures["condition"] == ck]
        if sub.empty:
            continue
        print(f"    {cl:<20s}  {sub['tick_count'].mean():.0f} ticks  "
              f"overhead={sub['total_inference_ms'].mean()/1000:.1f}s  "
              f"corrected={sub.get('lifetime_corrected_s', pd.Series(dtype=float)).mean():.1f}s  (n={len(sub)})")

    # ══════════════════════════════════════════════════════════════════════
    # 1. SURVIVAL
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 1. SURVIVAL ===")

    lifetimes = {ck: creatures[creatures["condition"] == ck]["lifetime_s"].dropna().values
                 for ck, _ in CONDITIONS}
    cond_stats(lifetimes, "Lifetime (s)", cfg=cfg)
    kruskal_test([np.array(lifetimes[ck]) for ck, _ in CONDITIONS], COND_LABELS)

    ticks_by_cond = {ck: creatures[creatures["condition"] == ck]["tick_count"].values
                      for ck, _ in CONDITIONS}
    corrected_by_cond = {ck: creatures[creatures["condition"] == ck].get(
        "lifetime_corrected_s", pd.Series(dtype=float)).values for ck, _ in CONDITIONS}

    fig, axes = plt.subplots(1, 3, figsize=(18, 5))
    for ax, data, ylabel, title in zip(
        axes,
        [lifetimes, corrected_by_cond, ticks_by_cond],
        ["Lifetime (s)", "Lifetime (s)", "Lifetime (decision cycles)"],
        ["Wall-clock seconds\n(raw)",
         "Inference-corrected seconds\n(− WORLD_MODEL overhead per creature)",
         "Decision ticks\n(inference-independent)"],
    ):
        bp = ax.boxplot([np.array(data[ck]) for ck, _ in CONDITIONS],
                         tick_labels=COND_LABELS, patch_artist=True)
        for patch, (ck, _) in zip(bp["boxes"], CONDITIONS):
            patch.set_facecolor(PALETTE[ck])
            patch.set_alpha(0.7)
        ax.set_ylabel(ylabel)
        ax.set_title(title)
        ax.grid(axis="y", alpha=0.3)
    plt.tight_layout()
    save(fig, "01_lifespan.png", cfg)

    print("\n  Lifetime (inference-corrected seconds):")
    cond_stats(corrected_by_cond, "Lifetime corrected (s)", cfg=cfg)
    kruskal_test([np.array(corrected_by_cond[ck]) for ck, _ in CONDITIONS], COND_LABELS)

    print("\n  Lifetime (decision ticks):")
    cond_stats(ticks_by_cond, "Lifetime (decision cycles)", cfg=cfg)
    kruskal_test([np.array(ticks_by_cond[ck]) for ck, _ in CONDITIONS], COND_LABELS)

    # ══════════════════════════════════════════════════════════════════════
    # 2. DRIVE REGULATION — mean arousal over time (binned)
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 2. DRIVE REGULATION ===")

    drives["arousal"] = drives[[c for c in DRIVE_COLS if c in drives.columns]].sum(axis=1)
    BIN_S = 30
    drives["time_bin"] = (drives["elapsed_s"] // BIN_S).clip(lower=0).astype(int)

    fig, axes = plt.subplots(2, 3, figsize=(15, 8))
    axes = axes.flatten()
    for ax_idx, (ck, cl) in enumerate(CONDITIONS):
        ax = axes[ax_idx]
        sub = drives[drives["condition"] == ck]
        gb = sub.groupby("time_bin")["arousal"]
        mean_a, std_a = gb.mean(), gb.std()
        t = mean_a.index * BIN_S / 60
        ax.plot(t, mean_a, color=PALETTE[ck], lw=2, label=cl)
        ax.fill_between(t, mean_a - std_a, mean_a + std_a, color=PALETTE[ck], alpha=0.2)
        ax.set_title(cl, fontsize=11)
        ax.set_xlabel("Elapsed time (min)")
        ax.set_ylabel("Total Arousal")
        ax.grid(alpha=0.3)

    ax = axes[5]
    for ck, cl in CONDITIONS:
        sub = drives[drives["condition"] == ck]
        gb = sub.groupby("time_bin")["arousal"].mean()
        ax.plot(gb.index * BIN_S / 60, gb, color=PALETTE[ck], lw=2, label=cl)
    ax.set_title("All conditions", fontsize=11)
    ax.set_xlabel("Elapsed time (min)")
    ax.set_ylabel("Mean Arousal")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)

    fig.suptitle("Total Arousal Over Simulation Time", fontsize=13)
    plt.tight_layout()
    save(fig, "02_arousal_time.png", cfg)

    mean_arousal = {ck: drives[drives["condition"] == ck]["arousal"].dropna().values
                     for ck, _ in CONDITIONS}
    cond_stats(mean_arousal, "Mean arousal", cfg=cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 3. PER-DRIVE TRAJECTORIES
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 3. PER-DRIVE TRAJECTORIES ===")

    fig, axes = plt.subplots(2, 2, figsize=(14, 9))
    for ax, col, name in zip(axes.flatten(), DRIVE_PLOT_COLS, DRIVE_PLOT_NAMES):
        for ck, cl in CONDITIONS:
            sub = drives[drives["condition"] == ck]
            gb = sub.groupby("time_bin")[col].mean()
            ax.plot(gb.index * BIN_S / 60, gb, color=PALETTE[ck], lw=2, label=cl)
        ax.set_title(name)
        ax.set_xlabel("Elapsed time (min)")
        ax.set_ylabel("Drive Level")
        ax.legend(fontsize=7)
        ax.grid(alpha=0.3)
    fig.suptitle("Per-Drive Levels Over Time", fontsize=13)
    plt.tight_layout()
    save(fig, "03_per_drive.png", cfg)

    for col, name in zip(DRIVE_PLOT_COLS, DRIVE_PLOT_NAMES):
        d = {ck: drives[drives["condition"] == ck][col].dropna().values for ck, _ in CONDITIONS}
        cond_stats(d, f"Mean {name}", cfg=cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 4. ACTION SELECTION — filter types
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 4. ACTION SELECTION ===")

    filter_counts = actions.groupby(["condition", "selection_type"]).size().unstack(fill_value=0)
    filter_pct = filter_counts.div(filter_counts.sum(axis=1), axis=0) * 100
    print(filter_pct.to_string())

    fig, axes = plt.subplots(1, 2, figsize=(14, 5))

    ax = axes[0]
    wm_pct = {cl: (filter_pct.loc[ck] if ck in filter_pct.index else pd.Series(dtype=float)).get("WORLD_MODEL", 0.0)
              for ck, cl in CONDITIONS}
    bars = ax.bar(list(wm_pct.keys()), list(wm_pct.values()),
                   color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
    ax.set_ylabel("% cycles with WORLD_MODEL selection")
    ax.set_title("World Model Filter Activation Rate")
    ax.set_ylim(0, max(wm_pct.values()) * 1.25 if max(wm_pct.values()) > 0 else 10)
    for bar, val in zip(bars, wm_pct.values()):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3,
                 f"{val:.1f}%", ha="center", va="bottom", fontsize=9)
    ax.grid(axis="y", alpha=0.3)

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
    save(fig, "04_action_filters.png", cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 5. BEHAVIOURAL EFFICIENCY
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 5. BEHAVIOURAL EFFICIENCY ===")

    mean_eff = {ck: behav[behav["condition"] == ck]["efficiency"].dropna().values for ck, _ in CONDITIONS}
    cond_stats(mean_eff, "Mean efficiency", cfg=cfg)

    fig, axes = plt.subplots(1, 2, figsize=(13, 5))

    ax = axes[0]
    BIN_E_S = 30
    behav["time_bin"] = (behav["elapsed_s"] // BIN_E_S).clip(lower=0).astype(int)
    for ck, cl in CONDITIONS:
        sub = behav[behav["condition"] == ck]
        gb = sub.groupby("time_bin")["efficiency"].mean()
        ax.plot(gb.index * BIN_E_S / 60, gb, color=PALETTE[ck], lw=2, label=cl)
    ax.set_xlabel("Elapsed time (min)")
    ax.set_ylabel("Behavioural Efficiency")
    ax.set_title("Efficiency Over Time")
    ax.legend(fontsize=9, loc="upper right", framealpha=0.9)
    ax.grid(alpha=0.3)

    ax = axes[1]
    bp = ax.boxplot([np.array(mean_eff[ck]) for ck, _ in CONDITIONS],
                     tick_labels=COND_LABELS, patch_artist=True)
    for patch, (ck, _) in zip(bp["boxes"], CONDITIONS):
        patch.set_facecolor(PALETTE[ck])
        patch.set_alpha(0.7)
    ax.set_ylabel("Behavioural Efficiency")
    ax.set_title("Efficiency Distribution")
    ax.set_xticklabels(COND_LABELS, rotation=20, ha="right", fontsize=9)
    ax.grid(axis="y", alpha=0.3)

    plt.tight_layout()
    save(fig, "05_efficiency.png", cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 6. EATING BEHAVIOUR & CACTUS AVOIDANCE
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 6. EATING BEHAVIOUR & CACTUS AVOIDANCE ===")

    drives_for_asof = (drives[["creature_key", "condition", "trial", "time", "init_hunger"]]
                        .sort_values("time").reset_index(drop=True))
    mouth_ranked = pd.merge_asof(
        mouth_ranked.sort_values("time").reset_index(drop=True),
        drives_for_asof, on="time", by=["creature_key", "condition", "trial"],
        direction="nearest", suffixes=("", "_drv"),
    )
    mouth_ranked["init_hunger"] = num(mouth_ranked["init_hunger"])

    cactus_perc = (perceptions[perceptions["object_type"] == "CACTUS"]
                   .copy().sort_values("time").reset_index(drop=True))
    cactus_ranked = attach_tick_rank(cactus_perc, creatures)
    cactus_w_action = pd.merge_asof(
        cactus_ranked.sort_values("time").reset_index(drop=True),
        actions_for_asof[["creature_key", "condition", "trial", "time", "action_type"]],
        on="time", by=["creature_key", "condition", "trial"], direction="nearest",
    )
    cactus_w_action["avoided"] = (cactus_w_action["action_type"] == "AVOID").astype(int)

    print("  Cactus encounters and avoidance rate:")
    for ck, cl in CONDITIONS:
        sub = cactus_w_action[cactus_w_action["condition"] == ck]
        rate = sub["avoided"].mean() * 100 if len(sub) else 0
        print(f"    {cl:<20s}  encounters={len(sub):5d}  avoidance={rate:.1f}%")

    ot_counts = mouth_ranked.groupby(["condition", "object_type"]).size().unstack(fill_value=0)
    print("\n  Food type totals:")
    print(ot_counts.reindex([ck for ck, _ in CONDITIONS]).to_string())

    print("\n  Hunger at time of eating (mean ± std):")
    for ck, cl in CONDITIONS:
        sub = mouth_ranked[mouth_ranked["condition"] == ck]["init_hunger"].dropna()
        print(f"    {cl:<20s}  {sub.mean():.3f} ± {sub.std():.3f}  (n={len(sub)})")

    fig, axes = plt.subplots(2, 3, figsize=(18, 10))

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

    ax = axes[0, 2]
    avoid_rates = [cactus_w_action[cactus_w_action["condition"] == ck]["avoided"].mean() * 100
                   if len(cactus_w_action[cactus_w_action["condition"] == ck]) else 0
                   for ck, _ in CONDITIONS]
    bars = ax.bar(COND_LABELS, avoid_rates, color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
    for bar, val in zip(bars, avoid_rates):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.5,
                 f"{val:.1f}%", ha="center", va="bottom", fontsize=9)
    ax.set_ylabel("% of CACTUS encounters → AVOID")
    ax.set_title("C — Cactus Avoidance Rate\n(higher = better danger recognition)")
    ax.set_xticklabels(COND_LABELS, rotation=20, ha="right")
    ax.set_ylim(0, max(avoid_rates) * 1.2 if max(avoid_rates) > 0 else 10)
    ax.grid(axis="y", alpha=0.3)

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
    save(fig, "06_eating_behaviour.png", cfg)

    # ── Food-type preference learning: cumulative eats per food type over life ─
    print("\n  Food-type cumulative accumulation by life decile:")
    cum_records = []
    for (cond, trial, ck_), grp in mouth_ranked.groupby(["condition", "trial", "creature_key"]):
        for ft in FOOD_TYPES:
            per_decile = grp[grp["object_type"] == ft].groupby("life_decile").size()
            cumulative = 0
            for d in range(10):
                cumulative += per_decile.get(d, 0)
                cum_records.append({"condition": cond, "food_type": ft,
                                     "life_decile": d, "cum_count": cumulative})
    cum_df = pd.DataFrame(cum_records)
    avg_cum = cum_df.groupby(["condition", "food_type", "life_decile"])["cum_count"].mean().reset_index()

    for ck, cl in CONDITIONS:
        sub = avg_cum[avg_cum["condition"] == ck]
        total_at_end = sub[sub["life_decile"] == 9].set_index("food_type")["cum_count"]
        parts = "  ".join(f"{ft.replace('_', ' ')}: {total_at_end.get(ft, 0):.1f}" for ft in FOOD_TYPES)
        print(f"    {cl:<20s}  {parts}")

    n_cond = len(CONDITIONS)
    fig, axes = plt.subplots(1, n_cond, figsize=(5 * n_cond, 5), sharey=True)
    if n_cond == 1:
        axes = [axes]
    for ax, (ck, cl) in zip(axes, CONDITIONS):
        sub = avg_cum[avg_cum["condition"] == ck]
        for ft in FOOD_TYPES:
            vals = (sub[sub["food_type"] == ft].set_index("life_decile")["cum_count"]
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
        "(steeper slope in later deciles = learning to prefer that food type)", fontsize=12)
    plt.tight_layout()
    save(fig, "07_food_learning.png", cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 7. NEUROMODULATORS
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 7. NEUROMODULATORS ===")

    BIN_N = 300
    neuro["time_bin"] = (neuro["seq"] // BIN_N).astype(int)

    fig, axes = plt.subplots(1, 3, figsize=(16, 5))
    for ax, nm, nm_name in zip(axes, ["dopamine", "serotonin", "orexin"],
                                ["Dopamine", "Serotonin", "Orexin"]):
        for ck, cl in CONDITIONS:
            sub = neuro[neuro["condition"] == ck]
            gb = sub.groupby("time_bin")[nm].mean()
            ax.plot(gb.index * BIN_N / 60, gb, color=PALETTE[ck], lw=2, label=cl)
        ax.set_title(nm_name)
        ax.set_xlabel("Time (min)")
        ax.set_ylabel("Tonic level")
        ax.legend(fontsize=7)
        ax.grid(alpha=0.3)
    fig.suptitle("Neuromodulator Tonic Levels Over Time", fontsize=13)
    plt.tight_layout()
    save(fig, "08_neuromodulators.png", cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 8. EXPECTANCY / RPE
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 8. EXPECTANCY / RPE ===")

    exp_conds = [ck for ck, _ in CONDITIONS if ck in expectancy["condition"].unique()]
    if expectancy.empty or not exp_conds:
        print("  No expectancy data found")
    else:
        print("  Conditions with expectancy data:", exp_conds)
        BIN_EXP = 50
        expectancy["cycle_bin"] = (expectancy["cycle"] // BIN_EXP).astype(int)

        fig, axes = plt.subplots(1, 2, figsize=(13, 5))
        ax = axes[0]
        for ck in exp_conds:
            sub = expectancy[expectancy["condition"] == ck]
            if sub.empty:
                continue
            gb = sub.groupby("cycle_bin")["rpe"].apply(lambda x: np.abs(x).mean())
            ax.plot(gb.index * BIN_EXP, gb, color=PALETTE[ck], lw=2, label=dict(CONDITIONS)[ck])
        ax.set_xlabel("Cognitive cycle")
        ax.set_ylabel("|RPE|")
        ax.set_title("Mean |RPE| Over Time")
        ax.legend(fontsize=8)
        ax.grid(alpha=0.3)

        ax = axes[1]
        for ck in exp_conds:
            sub = expectancy[expectancy["condition"] == ck]["rpe"].dropna()
            if sub.empty:
                continue
            ax.hist(sub, bins=40, alpha=0.5, color=PALETTE[ck], label=dict(CONDITIONS)[ck], density=True)
        ax.set_xlabel("RPE")
        ax.set_ylabel("Density")
        ax.set_title("RPE Distribution")
        ax.legend(fontsize=8)
        ax.grid(alpha=0.3)

        plt.tight_layout()
        save(fig, "09_expectancy_rpe.png", cfg)

        for ck in exp_conds:
            sub = expectancy[expectancy["condition"] == ck]["rpe"].dropna()
            print(f"    {dict(CONDITIONS)[ck]:<20s}  |RPE| mean={np.abs(sub).mean():.4f}  std={sub.std():.4f}")

    # ══════════════════════════════════════════════════════════════════════
    # 9. ENGRAMS
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 9. ENGRAMS ===")

    engram_counts = engrams.groupby("condition").size()
    engram_elig = engrams.groupby("condition")["eligibility"].mean()
    engram_delta = engrams.groupby("condition")["emotion_delta"].apply(lambda x: np.abs(x).mean())

    print("  Engrams formed:")
    for ck, cl in CONDITIONS:
        n = engram_counts.get(ck, 0)
        elig = engram_elig.get(ck, np.nan)
        delta = engram_delta.get(ck, np.nan)
        print(f"    {cl:<20s}  n={n:5d}  mean_elig={elig:.3f}  mean|delta|={delta:.4f}")

    fig, axes = plt.subplots(1, 3, figsize=(15, 5))

    ax = axes[0]
    vals = [engram_counts.get(ck, 0) for ck, _ in CONDITIONS]
    ax.bar(COND_LABELS, vals, color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
    ax.set_title("Engrams Formed")
    ax.set_ylabel("Count (5 trials)")
    ax.set_xticklabels(COND_LABELS, rotation=20, ha="right")
    ax.grid(axis="y", alpha=0.3)

    ax = axes[1]
    for ck, cl in CONDITIONS:
        sub = engrams[engrams["condition"] == ck]["eligibility"].dropna()
        if sub.empty:
            continue
        ax.hist(sub, bins=30, alpha=0.5, color=PALETTE[ck], label=cl, density=True)
    ax.set_title("Engram Eligibility Distribution")
    ax.set_xlabel("Eligibility")
    ax.set_ylabel("Density")
    ax.legend(fontsize=7)
    ax.grid(alpha=0.3)

    ax = axes[2]
    for ck, cl in CONDITIONS:
        sub = engrams[engrams["condition"] == ck]["emotion_delta"].dropna().abs()
        if sub.empty:
            continue
        ax.hist(sub, bins=30, alpha=0.5, color=PALETTE[ck], label=cl, density=True)
    ax.set_title("|Emotion Delta| Distribution")
    ax.set_xlabel("|emotion_delta|")
    ax.set_ylabel("Density")
    ax.legend(fontsize=7)
    ax.grid(alpha=0.3)

    fig.suptitle("Memory Engram Formation", fontsize=13)
    plt.tight_layout()
    save(fig, "10_engrams.png", cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 10. SLEEP EPISODES
    # ══════════════════════════════════════════════════════════════════════
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
    stds = [sleep_summary.loc[ck, "std_dur"] if ck in sleep_summary.index else 0 for ck, _ in CONDITIONS]
    ax.bar(COND_LABELS, means, yerr=stds, capsize=5,
           color=[PALETTE[ck] for ck, _ in CONDITIONS], alpha=0.8)
    ax.set_title("Mean Sleep Episode Duration (ticks)")
    ax.set_ylabel("Duration (ticks)")
    ax.set_xticklabels(COND_LABELS, rotation=20, ha="right")
    ax.grid(axis="y", alpha=0.3)

    plt.tight_layout()
    save(fig, "11_sleep_episodes.png", cfg)

    # ══════════════════════════════════════════════════════════════════════
    # 11. WORLD MODEL INFERENCE LATENCY
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== 11. INFERENCE LATENCY ===")

    wm_actions = actions[actions["selection_type"] == "WORLD_MODEL"].copy()
    if not wm_actions.empty:
        print(wm_actions.groupby("condition")["inference_ms"].describe().to_string())

    # ══════════════════════════════════════════════════════════════════════
    # Summary table
    # ══════════════════════════════════════════════════════════════════════
    print("\n=== SUMMARY TABLE ===")
    print(f"{'Condition':<22}  {'Raw (s)':>8}  {'Corrected (s)':>14}  {'Ticks':>8}  {'WM%':>6}  {'Engrams':>8}")
    print("-" * 85)
    for ck, cl in CONDITIONS:
        lt = np.nanmean(lifetimes[ck]) if len(lifetimes[ck]) else np.nan
        lc = np.nanmean(corrected_by_cond[ck]) if len(corrected_by_cond[ck]) else np.nan
        tc = np.nanmean(ticks_by_cond[ck]) if len(ticks_by_cond[ck]) else np.nan
        row = filter_pct.loc[ck] if ck in filter_pct.index else pd.Series(dtype=float)
        wm = row.get("WORLD_MODEL", 0.0)
        eng = engram_counts.get(ck, 0)
        print(f"  {cl:<20s}  {lt:>8.1f}  {lc:>14.1f}  {tc:>8.0f}  {wm:>5.1f}%  {eng:>8d}")

    print(f"\nFigures saved → {cfg.fig_dir}")


if __name__ == "__main__":
    run()
