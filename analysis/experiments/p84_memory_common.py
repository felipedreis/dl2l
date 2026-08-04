"""
Memory-mechanism figures shared by both p84 replications (issue #84).

The parity criteria P1-P5 ask whether our curves look like Mapa's and Campos's. These
figures ask the separate question underneath them: is the memory mechanism actually being
used, and does using it help?

They lean on one structural fact about the architecture. MemorySystemActor is created
unconditionally (CreatureActor:132) — the MEMORY filter gates only the *use* of engrams,
never their formation. So the *_nomem arms form engrams at the same rate as the *_mem arms
and simply never consult them, which makes formation a matched control and isolates
evocation as the single difference between an arm pair.

Figures:
  M1  formation vs use          — cumulative engrams laid against cumulative MEMORY-won
                                  decisions, on one cycle axis
  M2  consultation outcome      — how often memory has an opinion, over life deciles
  M3  decision confidence       — winning score and margin over the runner-up
  M4  engram quality            — eligibility / emotion_delta / cycle_gap over life deciles
  M5  consolidation             — episodes and traces, for the one arm that has them
  M6  is memory helpful?        — lifetime against memory use, pooled across arms
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats as scipy_stats

from analysis.dl2l_analysis.figures import DECILE_LABELS, plt, save
from analysis.dl2l_analysis.loading import num

MEM_ARMS_HINT = ("_mem",)


def _mem_arms(cfg) -> list:
    """Arms that actually have the MEMORY filter — the only ones with memory_decisions."""
    return [c for c in cfg.conditions if "_mem" in c.key]


# ---------------------------------------------------------------------------
# M1 — formation vs use
# ---------------------------------------------------------------------------

def formation_vs_use(engrams: pd.DataFrame, actions: pd.DataFrame, cfg, bins: int = 60) -> None:
    """Cumulative engrams formed and cumulative MEMORY-won decisions, per arm.

    Campos observed that memories begin forming immediately but are not used to select an
    action until roughly interaction 150, after which random choice stops being needed.
    That is a statement about the *gap* between these two curves, which is why they belong
    on one axis rather than in separate figures.
    """
    if engrams.empty and actions.empty:
        return

    fig, axes = plt.subplots(1, 2, figsize=(13, 5))

    ax = axes[0]
    for c in cfg.conditions:
        e = engrams[engrams["condition"] == c.key]
        if e.empty:
            continue
        cyc = num(e["reinforced_cycle"]).dropna()
        if cyc.empty:
            continue
        # Mean cumulative count across trials, on a shared cycle grid.
        grid = np.linspace(0, cyc.max(), bins)
        per_trial = []
        for _, g in e.groupby("trial"):
            gc = np.sort(num(g["reinforced_cycle"]).dropna().values)
            per_trial.append(np.searchsorted(gc, grid, side="right"))
        if per_trial:
            ax.plot(grid, np.mean(per_trial, axis=0), color=c.color, label=c.label)
    ax.set_xlabel("decision cycle")
    ax.set_ylabel("cumulative engrams formed")
    ax.set_title("Engram formation\n(identical by design in the no-memory arms)")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)

    ax = axes[1]
    for c in cfg.conditions:
        a = actions[(actions["condition"] == c.key) & (actions["selection_type"] == "MEMORY")]
        if a.empty:
            continue
        per_trial = []
        grid = None
        for _, g in a.groupby("trial"):
            gr = np.sort(num(g["tick_rank"]).dropna().values) if "tick_rank" in g else None
            if gr is None or len(gr) == 0:
                continue
            if grid is None:
                grid = np.linspace(0, gr.max(), bins)
            per_trial.append(np.searchsorted(gr, grid, side="right"))
        if per_trial and grid is not None:
            ax.plot(grid, np.mean(per_trial, axis=0), color=c.color, label=c.label)
    ax.set_xlabel("decision index")
    ax.set_ylabel("cumulative decisions won by MEMORY")
    ax.set_title("Memory use\n(zero by construction without the filter)")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)

    fig.tight_layout()
    save(fig, "m1_formation_vs_use.png", cfg)


# ---------------------------------------------------------------------------
# M2 — consultation outcome
# ---------------------------------------------------------------------------

def consultation_outcome(decisions: pd.DataFrame, cfg) -> None:
    """How often a consultation ends in a decision rather than a pass-through.

    A row in memory_decisions means the filter was actually reached (ActionSelection stops
    early once a filter narrows to one candidate), so `decided` is a rate over genuine
    opportunities, not over all cycles. Rising with experience is the signature of a
    mechanism that is working rather than merely wired up.
    """
    if decisions.empty:
        return

    arms = _mem_arms(cfg)
    if not arms:
        return

    fig, axes = plt.subplots(1, 2, figsize=(13, 5))

    for c in arms:
        d = decisions[decisions["condition"] == c.key]
        if d.empty:
            continue
        rate = d.groupby("life_decile")["decided"].mean()
        axes[0].plot(rate.index, rate.values, marker="o", color=c.color, label=c.label)

        share = d.assign(frac=num(d["scored"]) / num(d["candidates"]).clip(lower=1))
        frac = share.groupby("life_decile")["frac"].mean()
        axes[1].plot(frac.index, frac.values, marker="o", color=c.color, label=c.label)

    for ax, ylabel, title in (
        (axes[0], "P(memory decides | consulted)", "Does memory have an opinion?"),
        (axes[1], "scored / candidates", "How much of the choice set is remembered?"),
    ):
        ax.set_xlabel("life decile")
        ax.set_ylabel(ylabel)
        ax.set_title(title)
        ax.set_xticks(range(10))
        ax.set_xticklabels(DECILE_LABELS, rotation=45, ha="right", fontsize=7)
        ax.set_ylim(0, 1)
        ax.legend(fontsize=8)
        ax.grid(alpha=0.3)

    fig.tight_layout()
    save(fig, "m2_consultation_outcome.png", cfg)


# ---------------------------------------------------------------------------
# M3 — decision confidence
# ---------------------------------------------------------------------------

def decision_confidence(decisions: pd.DataFrame, cfg) -> None:
    """Winning score, and its margin over the runner-up, for decisions memory won.

    Separates "fired confidently on a broad base of engrams" from "fired on one weak
    engram" — two situations that chosen_action_state cannot tell apart.
    """
    if decisions.empty:
        return
    won = decisions[decisions["decided"] == True]  # noqa: E712 — parquet bool column
    if won.empty:
        return

    arms = _mem_arms(cfg)
    fig, axes = plt.subplots(1, 2, figsize=(13, 5))

    for c in arms:
        d = won[won["condition"] == c.key]
        if d.empty:
            continue
        score = d.groupby("life_decile")["winning_score"].mean()
        axes[0].plot(score.index, score.values, marker="o", color=c.color, label=c.label)

        # NaN runner-up means memory recognised exactly one candidate: an uncontested win,
        # which is a different thing from a narrow one and must not be averaged in as 0.
        contested = d[num(d["runnerup_score"]).notna()].copy()
        if not contested.empty:
            contested["margin"] = num(contested["winning_score"]) - num(contested["runnerup_score"])
            margin = contested.groupby("life_decile")["margin"].mean()
            axes[1].plot(margin.index, margin.values, marker="o", color=c.color, label=c.label)

    for ax, ylabel, title in (
        (axes[0], "mean winning score", "Strength of the winning evidence"),
        (axes[1], "mean (winner - runner-up)", "Margin, contested decisions only"),
    ):
        ax.set_xlabel("life decile")
        ax.set_ylabel(ylabel)
        ax.set_title(title)
        ax.set_xticks(range(10))
        ax.set_xticklabels(DECILE_LABELS, rotation=45, ha="right", fontsize=7)
        ax.legend(fontsize=8)
        ax.grid(alpha=0.3)

    fig.tight_layout()
    save(fig, "m3_decision_confidence.png", cfg)


# ---------------------------------------------------------------------------
# M4 — engram quality
# ---------------------------------------------------------------------------

def engram_quality(engrams: pd.DataFrame, cfg) -> None:
    """Eligibility, emotional delta and lay->reinforce gap of the engrams being laid."""
    if engrams.empty or "life_decile" not in engrams.columns:
        return

    panels = [
        ("eligibility", "mean eligibility", "Trace eligibility at reinforcement"),
        ("emotion_delta", "mean emotion delta", "Outcome valence (negative = good)"),
        ("cycle_gap", "mean cycles", "Lag from laying to reinforcement"),
    ]
    fig, axes = plt.subplots(1, 3, figsize=(16, 4.5))

    for ax, (col, ylabel, title) in zip(axes, panels):
        if col not in engrams.columns:
            continue
        for c in cfg.conditions:
            e = engrams[engrams["condition"] == c.key]
            if e.empty:
                continue
            series = e.assign(v=num(e[col])).groupby("life_decile")["v"].mean()
            ax.plot(series.index, series.values, marker="o", color=c.color, label=c.label)
        ax.set_xlabel("life decile")
        ax.set_ylabel(ylabel)
        ax.set_title(title)
        ax.set_xticks(range(10))
        ax.set_xticklabels(DECILE_LABELS, rotation=45, ha="right", fontsize=7)
        ax.grid(alpha=0.3)
    axes[0].legend(fontsize=8)

    fig.tight_layout()
    save(fig, "m4_engram_quality.png", cfg)


# ---------------------------------------------------------------------------
# M5 — consolidation
# ---------------------------------------------------------------------------

def consolidation(episodes: pd.DataFrame, traces: pd.DataFrame, creatures: pd.DataFrame,
                  cfg) -> None:
    """Consolidation activity, for whichever arm has consolidationEnabled.

    Only the C_mem_consol arm can produce these rows; the figure is skipped entirely
    otherwise rather than drawn empty.
    """
    have = [df for df in (episodes, traces) if df is not None and not df.empty]
    if not have:
        print("  (skipping M5 — no consolidation data; expected unless a *_consol arm ran)")
        return

    fig, axes = plt.subplots(1, 2, figsize=(13, 5))

    src = traces if (traces is not None and not traces.empty) else episodes
    per_trial = src.groupby(["condition", "trial"]).size().reset_index(name="n_episodes")
    for c in cfg.conditions:
        vals = per_trial[per_trial["condition"] == c.key]["n_episodes"]
        if len(vals):
            axes[0].bar(c.label, vals.mean(), color=c.color, alpha=0.8)
    axes[0].set_ylabel("consolidation episodes per trial")
    axes[0].set_title("Consolidation activity")
    axes[0].tick_params(axis="x", rotation=30)
    axes[0].grid(axis="y", alpha=0.3)

    life = creatures.groupby(["condition", "trial"])["lifetime_s"].max().reset_index()
    merged = per_trial.merge(life, on=["condition", "trial"], how="inner")
    for c in cfg.conditions:
        m = merged[merged["condition"] == c.key]
        if not m.empty:
            axes[1].scatter(m["n_episodes"], m["lifetime_s"], color=c.color,
                            label=c.label, alpha=0.75)
    axes[1].set_xlabel("consolidation episodes")
    axes[1].set_ylabel("lifetime (s)")
    axes[1].set_title("Consolidation against survival")
    axes[1].legend(fontsize=8)
    axes[1].grid(alpha=0.3)

    fig.tight_layout()
    save(fig, "m5_consolidation.png", cfg)


# ---------------------------------------------------------------------------
# M6 — is memory helpful?
# ---------------------------------------------------------------------------

def memory_use_vs_survival(actions: pd.DataFrame, creatures: pd.DataFrame, cfg) -> dict:
    """Lifetime against how much the creature actually used memory, pooled across arms.

    The between-arm contrast (P4) answers "does having memory help?". This answers the
    finer question "does *using* it help?", using the spread within and across arms as
    natural variation. Reported with Spearman, since neither axis is expected to be normal
    and lifetime is heavily right-skewed.
    """
    if actions.empty or creatures.empty:
        return {}

    per_trial = (
        actions.groupby(["condition", "trial", "creature_key"])
        .agg(total=("selection_type", "size"),
             mem=("selection_type", lambda s: (s == "MEMORY").sum()))
        .reset_index()
    )
    per_trial["mem_frac"] = per_trial["mem"] / per_trial["total"].clip(lower=1)

    life = creatures[["condition", "trial", "creature_key", "lifetime_s"]].drop_duplicates()
    df = per_trial.merge(life, on=["condition", "trial", "creature_key"], how="inner")
    df["lifetime_s"] = num(df["lifetime_s"])
    df = df[df["lifetime_s"].notna()]
    if df.empty:
        return {}

    fig, axes = plt.subplots(1, 2, figsize=(13, 5))
    for c in cfg.conditions:
        d = df[df["condition"] == c.key]
        if d.empty:
            continue
        axes[0].scatter(d["mem"], d["lifetime_s"], color=c.color, label=c.label, alpha=0.75)
        axes[1].scatter(d["mem_frac"], d["lifetime_s"], color=c.color, label=c.label, alpha=0.75)

    stats = {}
    for ax, col, xlabel in ((axes[0], "mem", "decisions won by MEMORY"),
                            (axes[1], "mem_frac", "MEMORY share of all decisions")):
        sub = df[df[col] > 0]
        if len(sub) >= 3:
            rho, p = scipy_stats.spearmanr(sub[col], sub["lifetime_s"])
            stats[col] = (rho, p, len(sub))
            ax.set_title(f"Spearman rho={rho:.3f}, p={p:.4f} (n={len(sub)}, memory-using trials)")
        ax.set_xlabel(xlabel)
        ax.set_ylabel("lifetime (s)")
        ax.legend(fontsize=8)
        ax.grid(alpha=0.3)

    fig.suptitle("M6 — does using memory track survival?")
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    save(fig, "m6_memory_use_vs_survival.png", cfg)

    print("\n  M6 — memory use vs lifetime (memory-using trials only)")
    for col, (rho, p, n) in stats.items():
        print(f"    {col:<10s} Spearman rho={rho:+.3f}  p={p:.4f}  n={n}")
    return stats


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------

def run_all(cfg, *, engrams, actions, decisions, creatures,
            episodes=None, traces=None) -> dict:
    """Draws every memory figure that the supplied frames can support."""
    print("\n  Memory mechanism figures")
    formation_vs_use(engrams, actions, cfg)
    consultation_outcome(decisions, cfg)
    decision_confidence(decisions, cfg)
    engram_quality(engrams, cfg)
    consolidation(episodes, traces, creatures, cfg)
    return memory_use_vs_survival(actions, creatures, cfg)
