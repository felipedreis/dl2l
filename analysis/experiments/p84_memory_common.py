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

Every "was memory used?" quantity here comes from memory_decisions, never from
chosen_action_state.actionselectiontype. Memory narrows the candidates to one object and
hands the surviving actions to the operant table instead of returning a single action, so it
seldom ends the filter chain and seldom receives the selection credit — AFFORDANCE usually
does. Reading memory's influence off selection_type would report it as near zero.

Figures:
  M1  formation vs use          — cumulative engrams laid against cumulative
                                  memory-influenced decisions, on one cycle axis
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

def _mem_arms(cfg) -> list:
    """Arms that have the MEMORY filter — the only ones that can emit memory_decisions.

    Keyed off the "nomem" marker rather than a "_mem" substring: every p84 arm is named
    <stack>_(no)mem[_extra], and testing for absence is the reading that does not quietly
    break if an arm gains a suffix.
    """
    return [c for c in cfg.conditions if "nomem" not in c.key]


def _decile_mean(df: pd.DataFrame, col: str = "frac"):
    """(x, y) of the per-life-decile mean of `col`, ready to hand to ax.plot."""
    series = df.groupby("life_decile")[col].mean()
    return series.index, series.values


ENGRAM_COLUMNS = ["creature_key", "reinforced_cycle", "eligibility", "emotion_delta", "cycle_gap"]


def load_engrams_sampled(cfg, max_rows_per_trial: int = 50_000,
                         batch_size: int = 500_000) -> pd.DataFrame:
    """Loads engrams.parquet with a per-trial row cap, true-streaming one file at a time.

    Legacy-minimal trials in this campaign accumulate 70-125 MILLION engram rows each
    (vs ~170K for current-stack trials — a 700x difference that is itself a finding, see
    the report's D2 discussion: the current stack's neuromodulation/expectancy loop
    evidently gates memory formation far more tightly than the legacy-minimal one).
    Concatenating that across 48 trials via the ordinary load_all exceeds available
    memory — confirmed live: the campaign's automated analysis step was OOM-killed
    (rc=-9) trying to load the full table. A first fix (read each file fully, THEN
    downsample) still peaked at ~19GB RSS on one 125M-row file alone, since the whole
    file is materialized before the downsample step ever runs.

    Every downstream use of this table (attach_engram_life_decile's life-decile means,
    formation_vs_use's cumulative-count curves) is an aggregate statistic, never a
    row-level join against another table, so a large systematic sample per trial is
    statistically equivalent for these purposes. pyarrow's iter_batches streams the file
    in bounded chunks regardless of its on-disk row-group layout, so peak memory per
    file is ~batch_size rows, not the file's full row count — every large file is kept
    to roughly the same footprint as a small one.
    """
    import pyarrow.parquet as pq

    frames = []
    for cond in cfg.cond_keys:
        base = cfg.data_dir_for(cond)
        for trial in cfg.trial_range:
            path = base / cond / f"trial_{trial}" / "engrams.parquet"
            if not path.exists():
                continue
            pf = pq.ParquetFile(path)
            total_rows = pf.metadata.num_rows
            # Every Kth batch, not every batch, so a systematic sample spans the whole
            # file (i.e. the whole simulated lifetime) rather than only its first chunk.
            n_batches = max(1, -(-total_rows // batch_size))  # ceil
            keep_every = max(1, n_batches // max(1, (max_rows_per_trial // batch_size) or 1))
            trial_frames = []
            kept_rows = 0
            for i, batch in enumerate(pf.iter_batches(batch_size=batch_size, columns=ENGRAM_COLUMNS)):
                if i % keep_every != 0:
                    continue
                bdf = batch.to_pandas()
                if kept_rows + len(bdf) > max_rows_per_trial:
                    bdf = bdf.iloc[:max(0, max_rows_per_trial - kept_rows)]
                if not bdf.empty:
                    trial_frames.append(bdf)
                    kept_rows += len(bdf)
                if kept_rows >= max_rows_per_trial:
                    break
            if trial_frames:
                tdf = pd.concat(trial_frames, ignore_index=True)
                tdf["condition"] = cond
                tdf["trial"] = trial
                frames.append(tdf)
    cols = ENGRAM_COLUMNS + ["condition", "trial"]
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=cols)


def attach_engram_life_decile(engrams: pd.DataFrame, creatures: pd.DataFrame) -> pd.DataFrame:
    """Bucket engrams into life deciles by cycle rather than by wall-clock.

    The other timed tables get their life_decile from make_tick_rank_attacher, which
    nearest-joins on `time`. engrams has no wall-clock column at all — only lay_cycle and
    reinforced_cycle — so feeding it through that path would compare a cycle counter
    against epoch milliseconds and silently drop every engram into decile 0. Dividing
    reinforced_cycle by the creature's total decision count is the equivalent measure:
    both count cognitive cycles.
    """
    if engrams.empty:
        return engrams
    df = engrams.copy()
    df["reinforced_cycle"] = num(df["reinforced_cycle"])
    counts = creatures[["creature_key", "condition", "trial", "tick_count"]].drop_duplicates()
    df = df.merge(counts, on=["creature_key", "condition", "trial"], how="left")
    df["life_frac"] = df["reinforced_cycle"] / df["tick_count"].clip(lower=1)
    df["life_decile"] = (df["life_frac"].fillna(0) * 10).clip(0, 9).astype(int)
    return df


# ---------------------------------------------------------------------------
# M1 — formation vs use
# ---------------------------------------------------------------------------

def formation_vs_use(engrams: pd.DataFrame, decisions: pd.DataFrame, cfg, bins: int = 60) -> None:
    """Cumulative engrams formed and cumulative memory-influenced decisions, per arm.

    Campos observed that memories begin forming immediately but are not used to select an
    action until roughly interaction 150, after which random choice stops being needed.
    That is a statement about the *gap* between these two curves, which is why they belong
    on one axis rather than in separate figures.

    Use is counted from memory_decisions, NOT from selection_type == MEMORY. Memory now
    narrows to an object and hands the surviving actions to the operant table rather than
    returning one, so it seldom ends the filter chain and seldom takes the selection credit —
    AFFORDANCE usually does. `decided` is the honest influence signal; selection_type would
    read near zero and be mistaken for memory not being used at all.
    """
    if engrams.empty and decisions.empty:
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
    influenced = decisions[decisions["decided"] == True] if not decisions.empty else decisions  # noqa: E712
    for c in cfg.conditions:
        d = influenced[influenced["condition"] == c.key] if not influenced.empty else influenced
        if d.empty:
            continue
        per_trial = []
        grid = None
        for _, g in d.groupby("trial"):
            gr = np.sort(num(g["cycle"]).dropna().values)
            if len(gr) == 0:
                continue
            if grid is None:
                grid = np.linspace(0, gr.max(), bins)
            per_trial.append(np.searchsorted(gr, grid, side="right"))
        if per_trial and grid is not None:
            ax.plot(grid, np.mean(per_trial, axis=0), color=c.color, label=c.label)
    ax.set_xlabel("decision cycle")
    ax.set_ylabel("cumulative memory-influenced decisions")
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

    The three panels use the three denominators the filter records, which are deliberately
    not interchangeable: `decided` is per consultation, `scored` is per candidate OBJECT
    (memory scores objects, not actions), and `returned` is per candidate ACTION. Dividing
    `scored` by `candidates` would be objects-over-actions and is meaningless.
    """
    if decisions.empty:
        return

    arms = _mem_arms(cfg)
    if not arms:
        return

    fig, axes = plt.subplots(1, 3, figsize=(17, 4.8))

    for c in arms:
        d = decisions[decisions["condition"] == c.key]
        if d.empty:
            continue
        rate = d.groupby("life_decile")["decided"].mean()
        axes[0].plot(rate.index, rate.values, marker="o", color=c.color, label=c.label)

        known = d.assign(frac=num(d["scored"]) / num(d["objects"]).clip(lower=1))
        axes[1].plot(*_decile_mean(known), marker="o", color=c.color, label=c.label)

        # How far memory narrowed the action set. 1.0 means it passed everything through.
        narrowed = d.assign(frac=num(d["returned"]) / num(d["candidates"]).clip(lower=1))
        axes[2].plot(*_decile_mean(narrowed), marker="o", color=c.color, label=c.label)

    for ax, ylabel, title in (
        (axes[0], "P(memory decides | consulted)", "Does memory have an opinion?"),
        (axes[1], "scored / objects", "How much of the choice set is remembered?"),
        (axes[2], "returned / candidates", "How far does memory narrow the choice?"),
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

    Only the current_mem_consol arm can produce these rows; the figure is skipped entirely
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

def _trend(ax, x, y, bins: int = 6) -> None:
    """Draw the association the scatter is claiming: a binned-median trend and an OLS line.

    Both, because they answer different questions and disagreeing is informative. The reported
    statistic is Spearman — a RANK correlation, chosen because lifetime is heavily right-skewed
    — so an OLS line alone would quietly assert a linearity the statistic never tested, and a
    couple of long-lived outliers can swing it. The binned medians show the monotone trend
    Spearman actually measures and are robust to those outliers.
    """
    x = np.asarray(x, dtype=float)
    y = np.asarray(y, dtype=float)
    ok = ~(np.isnan(x) | np.isnan(y))
    x, y = x[ok], y[ok]
    if len(x) < 3 or np.ptp(x) == 0:
        return

    # OLS, dashed — descriptive only.
    slope, intercept = np.polyfit(x, y, 1)
    xs = np.linspace(x.min(), x.max(), 50)
    ax.plot(xs, slope * xs + intercept, ls="--", lw=1.4, color="#555555",
            label=f"OLS (slope {slope:+.3g})", zorder=3)

    # Binned medians, solid — the monotone trend Spearman tests, robust to skew.
    edges = np.quantile(x, np.linspace(0, 1, bins + 1))
    edges = np.unique(edges)
    if len(edges) >= 3:
        centres, meds = [], []
        for lo, hi in zip(edges[:-1], edges[1:]):
            m = (x >= lo) & (x <= hi if hi == edges[-1] else x < hi)
            if m.sum() >= 2:
                centres.append(np.median(x[m]))
                meds.append(np.median(y[m]))
        if len(centres) >= 2:
            ax.plot(centres, meds, "-o", lw=1.8, ms=4, color="#111111",
                    label="binned median", zorder=4)


def memory_use_vs_survival(decisions: pd.DataFrame, creatures: pd.DataFrame, cfg) -> dict:
    """Lifetime against how much the creature actually used memory, pooled across arms.

    The between-arm contrast (P4) answers "does having memory help?". This answers the
    finer question "does *using* it help?", using the spread within and across arms as
    natural variation. Reported with Spearman, since neither axis is expected to be normal
    and lifetime is heavily right-skewed.

    Counted from memory_decisions rather than selection_type, for the reason given in
    formation_vs_use: memory rarely takes the chain credit any more. The denominator is
    consultations, so mem_frac reads "of the cycles where memory got a say, how often did it
    use it" — a cleaner quantity than the old share of all decisions.
    """
    if decisions.empty or creatures.empty:
        return {}

    per_trial = (
        decisions.groupby(["condition", "trial", "creature_key"])
        .agg(total=("decided", "size"), mem=("decided", "sum"))
        .reset_index()
    )
    per_trial["mem"] = num(per_trial["mem"])
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
    for ax, col, xlabel in ((axes[0], "mem", "memory-influenced decisions"),
                            (axes[1], "mem_frac", "influenced share of consultations")):
        sub = df[df[col] > 0]
        if len(sub) >= 3:
            rho, p = scipy_stats.spearmanr(sub[col], sub["lifetime_s"])
            stats[col] = (rho, p, len(sub))
            ax.set_title(f"Spearman rho={rho:.3f}, p={p:.4f} (n={len(sub)}, memory-using creatures)")
            _trend(ax, sub[col].values, sub["lifetime_s"].values)
        ax.set_xlabel(xlabel)
        ax.set_ylabel("lifetime (s)")
        ax.legend(fontsize=7)
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

def run_all(cfg, *, engrams, decisions, creatures,
            episodes=None, traces=None) -> dict:
    """Draws every memory figure that the supplied frames can support.

    `actions` is no longer a parameter: every memory-use quantity now comes from
    memory_decisions, because memory stopped ending the filter chain and so stopped being
    visible in chosen_action_state.actionselectiontype.
    """
    print("\n  Memory mechanism figures")
    formation_vs_use(engrams, decisions, cfg)
    consultation_outcome(decisions, cfg)
    decision_confidence(decisions, cfg)
    engram_quality(engrams, cfg)
    consolidation(episodes, traces, creatures, cfg)
    return memory_use_vs_survival(decisions, creatures, cfg)
