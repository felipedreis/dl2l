"""
Analysis: p84_mapa_interaction_interval — Mapa (2009) §6.4/§6.5 replication (issue #84).

Arms (see simulations/p84_mapa_*.conf):
  L_nomem / L_mem   legacy-minimal stack, without / with the MEMORY filter
  C_nomem / C_mem   current subsystem stack, without / with the MEMORY filter

World: 12 RED_APPLE, 18 GREEN_APPLE, 25 GRAY_APPLE in 638x534, no replenishment, one
creature. GRAY_APPLE stands in for her 25 balls; the world size is density-matched to
Campos's published 860x720 / 100 objects, since Mapa states neither.

Claims tested here:
  P1  (Fig. 47)  memory arm's mean interaction interval <= no-memory arm's, gap widening
                 with the interaction index k
  P5  (Fig. 50)  lifetime rises with interaction count, memory arm above no-memory.
                 Shape only — her §6.5 survival world had stones, bees and toys we cannot
                 reproduce.
  D1             the learned conditioning trajectory against her low/medium/high initial
                 levels (0.25 / 0.40 / 0.70 APPROACH). Descriptive, no pass/fail: we do
                 not replicate her initial-conditioning sweep, and our mechanism grades
                 reinforcement by prediction error rather than a fixed step.

Both P1 panels are drawn twice — in ms, mirroring her axis, and indexed by decision cycle.
Only the second is comparable across machines: `time` is System.currentTimeMillis() and our
ms-per-cycle moves with host load and dispatcher sizing (see the p59/p79 tick-rate work).

Usage:
  PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p84_mapa_interaction_interval
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats as scipy_stats

from analysis.dl2l_analysis.figures import plt, save, setup
from analysis.dl2l_analysis.loading import (
    attach_born_time_and_ticks,
    interaction_intervals,
    load_all,
    make_tick_rank_attacher,
    num,
)
from analysis.dl2l_analysis.stats import cond_stats, kruskal_test
from analysis.experiments import p84_memory_common

MAX_K = 10                       # Mapa measured the first 10 interactions
MAPA_LEVELS = {                  # her Tabela 6 initial APPROACH probabilities
    "low": 0.25,
    "medium": 0.40,
    "high": 0.70,
}
ARM_PAIRS = [("L_nomem", "L_mem"), ("C_nomem", "C_mem")]


# ---------------------------------------------------------------------------
# P1 — interaction interval
# ---------------------------------------------------------------------------

def _mean_by_k(intervals: pd.DataFrame, cfg, value: str):
    """Mean and standard error of `value` at each k, per condition, across trials."""
    out = {}
    for c in cfg.conditions:
        d = intervals[intervals["condition"] == c.key]
        if d.empty:
            continue
        # Average within a trial first, so a trial with many creatures cannot outvote one
        # with few. (Every p84 arm is single-creature, but this keeps the reduction honest.)
        per_trial = d.groupby(["trial", "k"])[value].mean().reset_index()
        g = per_trial.groupby("k")[value]
        out[c.key] = (g.mean(), g.sem(), g.size())
    return out


def interval_figure(intervals: pd.DataFrame, cfg, value: str, ylabel: str,
                    title: str, fname: str) -> None:
    stats = _mean_by_k(intervals, cfg, value)
    if not stats:
        return
    fig, ax = plt.subplots(figsize=(9, 5.5))
    for c in cfg.conditions:
        if c.key not in stats:
            continue
        mean, sem, _ = stats[c.key]
        ax.errorbar(mean.index, mean.values, yerr=sem.values, marker="o", capsize=3,
                    color=c.color, label=c.label)
    ax.set_xlabel("number of interactions")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.set_xticks(range(1, MAX_K + 1))
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)
    fig.tight_layout()
    save(fig, fname, cfg)


def p1_verdict(intervals: pd.DataFrame, cfg) -> None:
    """P1: is the memory arm faster, and does its advantage grow with k?"""
    print("\n  P1 — interaction interval, memory vs no-memory")
    stats = _mean_by_k(intervals, cfg, "interval_s")

    for nomem, mem in ARM_PAIRS:
        if nomem not in stats or mem not in stats:
            print(f"    {nomem} vs {mem}: missing data — inconclusive")
            continue
        a, b = stats[nomem][0], stats[mem][0]
        ks = sorted(set(a.index) & set(b.index))
        if not ks:
            print(f"    {nomem} vs {mem}: no overlapping k — inconclusive")
            continue
        gap = np.array([a[k] - b[k] for k in ks])       # >0 means memory is faster
        faster = int((gap > 0).sum())
        rho, p_rho = (scipy_stats.spearmanr(ks, gap) if len(ks) >= 3 else (np.nan, np.nan))
        print(f"    {nomem} vs {mem}: memory faster at {faster}/{len(ks)} values of k; "
              f"gap-vs-k Spearman rho={rho:+.3f} (p={p_rho:.4f})")

        per_trial = (intervals[intervals["condition"].isin([nomem, mem])]
                     .groupby(["condition", "trial"])["interval_s"].mean().reset_index())
        groups = [per_trial[per_trial["condition"] == k]["interval_s"].values
                  for k in (nomem, mem)]
        kruskal_test(groups, [nomem, mem])


# ---------------------------------------------------------------------------
# P5 — survival shape
# ---------------------------------------------------------------------------

def survival_figure(intervals: pd.DataFrame, cfg) -> None:
    """Mapa Fig. 50's axes: time alive at the k-th interaction."""
    stats = _mean_by_k(intervals, cfg, "cumulative_s")
    if not stats:
        return
    fig, ax = plt.subplots(figsize=(9, 5.5))
    for c in cfg.conditions:
        if c.key not in stats:
            continue
        mean, sem, _ = stats[c.key]
        ax.errorbar(mean.index, mean.values, yerr=sem.values, marker="o", capsize=3,
                    color=c.color, label=c.label)
    ax.set_xlabel("number of interactions")
    ax.set_ylabel("time alive (s)")
    ax.set_title("P5 — time alive at the k-th interaction (Mapa Fig. 50 axes)\n"
                 "shape-only: her survival world had stones, bees and toys we lack")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)
    fig.tight_layout()
    save(fig, "p5_survival_shape.png", cfg)


# ---------------------------------------------------------------------------
# D1 — conditioning trajectory
# ---------------------------------------------------------------------------

def _normalise(conditioning: pd.DataFrame) -> pd.DataFrame:
    """Raw probabilities -> the shares ActionProbabilityFilter actually samples.

    The stored column is not a share: varyProbability clamps at 0 while the compensating
    -delta/(n-1) is applied to the others unconditionally, so a target's raw sum drifts away
    from 100 once any entry bottoms out. Normalising per (trial, creature, target, seq) is
    exactly what the filter does at selection time.
    """
    df = conditioning.copy()
    df["probability"] = num(df["probability"])
    keys = ["condition", "trial", "creature_key", "target", "seq"]
    total = df.groupby(keys)["probability"].transform("sum")
    df["share"] = df["probability"] / total.where(total > 0, np.nan)
    return df


def conditioning_figures(conditioning: pd.DataFrame, cfg) -> None:
    if conditioning.empty:
        print("  (skipping D1 — no conditioning data)")
        return
    df = _normalise(conditioning)

    # Headline: APPROACH share against Mapa's three initial levels.
    fig, ax = plt.subplots(figsize=(9, 5.5))
    approach = df[df["action"] == "APPROACH"]
    for c in cfg.conditions:
        d = approach[approach["condition"] == c.key]
        if d.empty:
            continue
        d = d.assign(bucket=pd.qcut(num(d["seq"]), 20, labels=False, duplicates="drop"))
        series = d.groupby("bucket")["share"].mean()
        ax.plot(series.index, series.values, marker="o", ms=3, color=c.color, label=c.label)
    for name, level in MAPA_LEVELS.items():
        ax.axhline(level, ls="--", lw=1, color="#888")
        ax.text(0.02, level, f" Mapa {name} ({level:.2f})", va="bottom", fontsize=7,
                color="#666", transform=ax.get_yaxis_transform())
    ax.set_xlabel("reinforcement events (20 equal-count buckets)")
    ax.set_ylabel("normalised APPROACH share")
    ax.set_title("D1 — learned conditioning against Mapa's initial levels\n"
                 "descriptive: we do not replicate her initial-conditioning sweep")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)
    fig.tight_layout()
    save(fig, "d1_approach_share.png", cfg)

    # Per-target small multiples, all actions.
    targets = sorted(df["target"].dropna().unique())
    if not targets:
        return
    ncol = min(3, len(targets))
    nrow = int(np.ceil(len(targets) / ncol))
    fig, axes = plt.subplots(nrow, ncol, figsize=(5.2 * ncol, 3.8 * nrow), squeeze=False)
    mem_key = next((c.key for c in cfg.conditions if c.key.endswith("_mem")), cfg.cond_keys[0])
    for i, target in enumerate(targets):
        ax = axes[i // ncol][i % ncol]
        d = df[(df["target"] == target) & (df["condition"] == mem_key)]
        for action, g in d.groupby("action"):
            g = g.assign(bucket=pd.qcut(num(g["seq"]), 20, labels=False, duplicates="drop"))
            series = g.groupby("bucket")["share"].mean()
            ax.plot(series.index, series.values, marker="o", ms=2, label=action)
        ax.set_title(f"{target} ({mem_key})", fontsize=9)
        ax.set_xlabel("reinforcement bucket", fontsize=8)
        ax.set_ylabel("normalised share", fontsize=8)
        ax.grid(alpha=0.3)
    axes[0][0].legend(fontsize=6, ncol=2)
    for j in range(len(targets), nrow * ncol):
        axes[j // ncol][j % ncol].axis("off")
    fig.tight_layout()
    save(fig, "d1_conditioning_by_target.png", cfg)


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------

def run(cfg) -> None:
    setup(cfg)
    print(f"Loading {cfg.name} from {cfg.data_dir}")

    creatures = load_all(cfg, "creatures.parquet")
    actions = load_all(cfg, "actions.parquet")
    mouth = load_all(cfg, "mouth_interactions.parquet")
    engrams = load_all(cfg, "engrams.parquet")
    decisions = load_all(cfg, "memory_decisions.parquet")
    conditioning = load_all(cfg, "conditioning.parquet")

    if creatures.empty or actions.empty:
        print("No creatures/actions data found — nothing to analyse.")
        return

    actions["time"] = num(actions["time"])
    creatures = attach_born_time_and_ticks(creatures, actions)
    attach_tick_rank, _ = make_tick_rank_attacher(actions)

    # tick_rank lets the memory figures share a decision-index axis with `actions`.
    actions = attach_tick_rank(actions, creatures)
    if not decisions.empty:
        decisions = decisions.rename(columns={"time_ms": "time"})
        decisions = attach_tick_rank(decisions, creatures)
    if not engrams.empty:
        engrams["time"] = num(engrams["reinforced_cycle"])
        engrams = attach_tick_rank(engrams, creatures)

    intervals = interaction_intervals(mouth, creatures, max_k=MAX_K)
    if intervals.empty:
        print("No EAT interactions recorded — P1/P5 cannot be evaluated.")
    else:
        interval_figure(intervals, cfg, "interval_s", "interval (s)",
                        "P1 — mean interval to find and interact (Mapa Fig. 47)\n"
                        "wall-clock: comparable in shape only",
                        "p1_interval_seconds.png")
        cond_stats({c.key: intervals[intervals["condition"] == c.key]["interval_s"].values
                    for c in cfg.conditions},
                   "Mean interaction interval (s), all k", cfg)
        p1_verdict(intervals, cfg)
        survival_figure(intervals, cfg)

    conditioning_figures(conditioning, cfg)

    p84_memory_common.run_all(
        cfg, engrams=engrams, actions=actions, decisions=decisions, creatures=creatures)

    print(f"\nFigures saved → {cfg.fig_dir}")
