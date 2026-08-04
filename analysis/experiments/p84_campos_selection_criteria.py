"""
Analysis: p84_campos_selection_criteria — Campos et al. (2015) replication (issue #84).

Arms (see simulations/p84_campos_*.conf):
  L_nomem / L_mem       legacy-minimal stack, without / with the MEMORY filter
  C_nomem / C_mem       current subsystem stack, without / with the MEMORY filter
  C_mem_consol          C_mem plus sleep consolidation — an extension arm, outside every
                        parity claim, since neither source architecture had consolidation

World: his verbatim setup — 20 RED_APPLE, 20 GREEN_APPLE, 60 GRAY_APPLE in 860x720, each
consumed fruit replaced at a random position, one creature, run to death.

His four selection criteria map onto ours one-for-one:
    Nearest -> TARGET_DISTANCE,  Affordances -> AFFORDANCE,  Memory -> MEMORY,  Random

Claims tested here:
  P2  (Fig. 5/6)  with memory, the RANDOM curve flattens once MEMORY starts winning
                  decisions; without memory the criteria show no trend over time
  P3              TARGET_DISTANCE and AFFORDANCE are used similarly with and without
                  memory, despite the large difference in total selections
  P4              memory arm's mean lifetime greatly exceeds no-memory's. Compared as a
                  RATIO against his published 6.7x (1.4e4 s vs 2.1e3 s) — absolute seconds
                  are not portable across machines (see the p59/p79 tick-rate work).

Usage:
  PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p84_campos_selection_criteria
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from analysis.dl2l_analysis.figures import boxplot_by_condition, plt, save, setup
from analysis.dl2l_analysis.loading import (
    attach_born_time_and_ticks,
    load_all,
    make_tick_rank_attacher,
    num,
)
from analysis.dl2l_analysis.stats import cond_stats, kruskal_test
from analysis.experiments import p84_memory_common

CRITERIA = ["TARGET_DISTANCE", "AFFORDANCE", "MEMORY", "RANDOM"]
CRITERION_LABEL = {
    "TARGET_DISTANCE": "Nearest",
    "AFFORDANCE": "Affordances",
    "MEMORY": "Memory",
    "RANDOM": "Random",
}
CRITERION_COLOR = {
    "TARGET_DISTANCE": "#4c9f70",
    "AFFORDANCE": "#e08a3c",
    "MEMORY": "#2b5eb8",
    "RANDOM": "#b04a4a",
}
ZOOM_N = 1000                    # Campos Fig. 6's window
CAMPOS_LIFETIME_RATIO = 6.7      # 1.4e4 s with memory / 2.1e3 s without
ARM_PAIRS = [("L_nomem", "L_mem"), ("C_nomem", "C_mem")]


# ---------------------------------------------------------------------------
# P2 — cumulative selections per criterion
# ---------------------------------------------------------------------------

def _representative_trial(actions: pd.DataFrame, cond: str) -> pd.DataFrame:
    """Campos plots typical single realizations, not averages — pick the median-length one."""
    d = actions[actions["condition"] == cond]
    if d.empty:
        return d
    lengths = d.groupby("trial").size()
    target = lengths.sort_values().index[len(lengths) // 2]
    return d[d["trial"] == target].sort_values("time")


def cumulative_figure(actions: pd.DataFrame, cfg, limit: int | None, fname: str,
                      title: str) -> None:
    conds = [c for c in cfg.conditions if not actions[actions["condition"] == c.key].empty]
    if not conds:
        return
    ncol = len(conds)
    fig, axes = plt.subplots(1, ncol, figsize=(4.6 * ncol, 4.6), squeeze=False, sharey=False)

    for ax, c in zip(axes[0], conds):
        d = _representative_trial(actions, c.key)
        if limit is not None:
            d = d.head(limit)
        if d.empty:
            continue
        idx = np.arange(1, len(d) + 1)
        for crit in CRITERIA:
            cum = (d["selection_type"] == crit).cumsum().values
            if cum[-1] == 0:
                continue
            ax.plot(idx, cum, color=CRITERION_COLOR[crit], label=CRITERION_LABEL[crit])
        ax.set_title(c.label, fontsize=10)
        ax.set_xlabel("selected actions")
        ax.set_ylabel("cumulative number of selections")
        ax.grid(alpha=0.3)

    # Figure-level legend covering all four criteria. A per-axes legend would be built from
    # the leftmost panel, which is a no-memory arm and therefore never draws a Memory line.
    handles = [plt.Line2D([], [], color=CRITERION_COLOR[c], label=CRITERION_LABEL[c])
               for c in CRITERIA]
    fig.legend(handles=handles, loc="lower center", ncol=len(CRITERIA), fontsize=8,
               frameon=False)
    fig.suptitle(title)
    fig.tight_layout(rect=[0, 0.06, 1, 0.94])
    save(fig, fname, cfg)


def p2_verdict(actions: pd.DataFrame, cfg) -> None:
    """P2: does RANDOM flatten once MEMORY engages?

    Quantified as the RANDOM curve's slope over the last third of a run divided by its
    slope over the first third. Campos's claim implies this ratio collapses well below 1
    with memory and sits near 1 without it — the no-memory arms are his stated control
    where "the selection criteria do not exhibit any trend over time".
    """
    print("\n  P2 — RANDOM slope ratio (last third / first third)")
    print("       ~1.0 = no trend (Campos's control); <<1.0 = random choice being displaced")

    ratios = {}
    for c in cfg.conditions:
        vals = []
        for _, g in actions[actions["condition"] == c.key].groupby("trial"):
            g = g.sort_values("time")
            n = len(g)
            if n < 30:
                continue
            third = n // 3
            is_random = (g["selection_type"] == "RANDOM").values
            early = is_random[:third].mean()
            late = is_random[-third:].mean()
            if early > 0:
                vals.append(late / early)
        if vals:
            ratios[c.key] = np.array(vals)

    cond_stats(ratios, "RANDOM slope ratio", cfg)
    for nomem, mem in ARM_PAIRS:
        if nomem in ratios and mem in ratios:
            kruskal_test([ratios[nomem], ratios[mem]], [nomem, mem])

    # First cycle at which MEMORY overtakes RANDOM — Campos put this near interaction 150.
    print("\n  P2 — first decision index where cumulative MEMORY exceeds cumulative RANDOM")
    for c in cfg.conditions:
        crossings = []
        for _, g in actions[actions["condition"] == c.key].groupby("trial"):
            g = g.sort_values("time")
            mem_cum = (g["selection_type"] == "MEMORY").cumsum().values
            rnd_cum = (g["selection_type"] == "RANDOM").cumsum().values
            hits = np.flatnonzero(mem_cum > rnd_cum)
            if len(hits):
                crossings.append(hits[0] + 1)
        if crossings:
            print(f"    {c.label:<22s} median {int(np.median(crossings)):>6d}  "
                  f"({len(crossings)} of the trials ever cross)")
        else:
            print(f"    {c.label:<22s} never crosses")


# ---------------------------------------------------------------------------
# P3 — Nearest / Affordances stability
# ---------------------------------------------------------------------------

def p3_verdict(actions: pd.DataFrame, cfg) -> None:
    """P3: are Nearest and Affordances used similarly with and without memory?

    Compared as a share of all selections, not a raw count: Campos's own arms differ by
    ~2e4 total selections, so raw counts cannot be compared directly and his claim is
    explicitly about the manner of use.
    """
    shares = (
        actions.assign(one=1)
        .groupby(["condition", "trial", "selection_type"])["one"].sum()
        .groupby(level=[0, 1]).transform(lambda s: s / s.sum())
        .reset_index(name="share")
    )

    fig, ax = plt.subplots(figsize=(10, 5.5))
    width = 0.8 / max(1, len(cfg.conditions))
    x = np.arange(len(CRITERIA))
    for i, c in enumerate(cfg.conditions):
        means = [shares[(shares["condition"] == c.key) &
                        (shares["selection_type"] == crit)]["share"].mean()
                 for crit in CRITERIA]
        means = [0 if pd.isna(m) else m for m in means]
        ax.bar(x + i * width, means, width, color=c.color, label=c.label, alpha=0.85)
    ax.set_xticks(x + width * (len(cfg.conditions) - 1) / 2)
    ax.set_xticklabels([CRITERION_LABEL[c] for c in CRITERIA])
    ax.set_ylabel("share of all selections")
    ax.set_title("P3 — how the selection criteria are used, with and without memory")
    ax.legend(fontsize=8)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    save(fig, "p3_criterion_shares.png", cfg)

    print("\n  P3 — Nearest / Affordances share, memory vs no-memory")
    for crit in ("TARGET_DISTANCE", "AFFORDANCE"):
        print(f"    {CRITERION_LABEL[crit]}:")
        for nomem, mem in ARM_PAIRS:
            a = shares[(shares["condition"] == nomem) & (shares["selection_type"] == crit)]["share"]
            b = shares[(shares["condition"] == mem) & (shares["selection_type"] == crit)]["share"]
            if len(a) and len(b):
                print(f"      {nomem} {a.mean():.3f}  vs  {mem} {b.mean():.3f}  "
                      f"(absolute difference {abs(a.mean() - b.mean()):.3f})")


# ---------------------------------------------------------------------------
# P4 — survival
# ---------------------------------------------------------------------------

def p4_verdict(creatures: pd.DataFrame, cfg) -> None:
    """P4: does memory extend life, and by a factor comparable to Campos's 6.7x?"""
    lifetimes = {}
    for c in cfg.conditions:
        v = num(creatures[creatures["condition"] == c.key]["lifetime_s"]).dropna()
        if len(v):
            lifetimes[c.key] = v.values

    if not lifetimes:
        print("  (skipping P4 — no lifetime data; creatures may still have been alive at cap)")
        return

    fig, ax = plt.subplots(figsize=(9, 5.5))
    boxplot_by_condition(ax, lifetimes, cfg, ylabel="lifetime (s)",
                         title="P4 — survival, with and without memory")
    fig.tight_layout()
    save(fig, "p4_lifetime.png", cfg)

    cond_stats(lifetimes, "Lifetime (s)", cfg)
    print(f"\n  P4 — memory/no-memory lifetime ratio (Campos reports {CAMPOS_LIFETIME_RATIO:.1f}x)")
    for nomem, mem in ARM_PAIRS:
        if nomem in lifetimes and mem in lifetimes:
            base = np.mean(lifetimes[nomem])
            ratio = np.mean(lifetimes[mem]) / base if base > 0 else np.nan
            print(f"    {nomem} -> {mem}: {ratio:.2f}x")
            kruskal_test([lifetimes[nomem], lifetimes[mem]], [nomem, mem])


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------

def run(cfg) -> None:
    setup(cfg)
    print(f"Loading {cfg.name} from {cfg.data_dir}")

    creatures = load_all(cfg, "creatures.parquet")
    actions = load_all(cfg, "actions.parquet")
    engrams = load_all(cfg, "engrams.parquet")
    decisions = load_all(cfg, "memory_decisions.parquet")
    episodes = load_all(cfg, "consolidation_episodes.parquet")
    traces = load_all(cfg, "memory_traces.parquet")

    if creatures.empty or actions.empty:
        print("No creatures/actions data found — nothing to analyse.")
        return

    actions["time"] = num(actions["time"])
    creatures = attach_born_time_and_ticks(creatures, actions)
    attach_tick_rank, _ = make_tick_rank_attacher(actions)

    actions = attach_tick_rank(actions, creatures)
    if not decisions.empty:
        decisions = decisions.rename(columns={"time_ms": "time"})
        decisions = attach_tick_rank(decisions, creatures)
    if not engrams.empty:
        engrams["time"] = num(engrams["reinforced_cycle"])
        engrams = attach_tick_rank(engrams, creatures)

    cumulative_figure(actions, cfg, None,
                      "p2_cumulative_lifetime.png",
                      "P2 — cumulative selections per criterion, whole lifetime (Campos Fig. 5)")
    cumulative_figure(actions, cfg, ZOOM_N,
                      "p2_cumulative_first1000.png",
                      f"P2 — first {ZOOM_N} interactions (Campos Fig. 6)")
    p2_verdict(actions, cfg)
    p3_verdict(actions, cfg)
    p4_verdict(creatures, cfg)

    p84_memory_common.run_all(
        cfg, engrams=engrams, actions=actions, decisions=decisions, creatures=creatures,
        episodes=episodes, traces=traces)

    print(f"\nFigures saved → {cfg.fig_dir}")
