"""
Analysis: p84_behaviour_parity — Mapa (2009) and Campos (2015) parity (issue #84).

One experiment, both papers. Their headline figures are different *measurements* of the
same runs, so they are produced side by side here:

  F1  interval to find and interact, k = 1..10      Mapa Fig. 47
  F2  time alive at the k-th interaction            Mapa Fig. 50
  F3  cumulative selections per criterion           Campos Fig. 5
  F4  the same, first 1000 decisions                Campos Fig. 6
  F5  lifetime per arm, and the memory ratio        Campos's 6.7x
  F6  learned conditioning trajectory               Mapa's 0.25/0.40/0.70 levels
  M1-M6                                             memory mechanism (p84_memory_common)

Comparisons are read WITHIN an arm pair (legacy_nomem vs legacy_mem, and so on), never
across stacks, so the only thing varying in a comparison is the MEMORY filter.

The creature is the replication unit, but creatures within a trial share a world, a food
supply and an RNG stream. Every primary test is therefore run twice — at creature level
(all the data) and at trial level (immune to clustering, low-powered) — and a result is
only read as real when the two agree. See stats.compare_arms.

Absolute milliseconds are never compared against the papers: our ms-per-cycle moves with
host load and dispatcher sizing (see the p59/p79 tick-rate work). Only curve shape and
ratios carry across.

Usage:
  PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p84_behaviour_parity
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
from analysis.dl2l_analysis.stats import (
    compare_arms,
    cohens_d,
    icc1,
    kaplan_meier,
    kruskal_test,
    print_comparison,
    print_survival,
    required_n,
    survival_comparison,
)
from analysis.experiments import p84_memory_common

MAX_K = 10
# Campos's four reported selection criteria, mapped one-for-one onto ours.
#
# CAVEAT on the MEMORY share. Since the memory-architecture rework, MemoryFilter narrows the
# candidates to one OBJECT and hands the surviving actions to the operant table rather than
# returning a single action, so it seldom ends the chain and seldom takes the selection
# credit — AFFORDANCE, running next, usually does. The MEMORY share here is therefore a
# structural floor, not a measure of memory's influence, and a low value must NOT be read as
# memory going unused. The honest influence rate is `decided` in memory_decisions (M1/M2/M6 in
# p84_memory_common). This is the price of keeping the two filters separate rather than
# merging them as Campos did, and it is paid deliberately: merging would make memory's
# contribution unmeasurable, which is the whole point of the study.
CRITERIA = ["TARGET_DISTANCE", "AFFORDANCE", "MEMORY", "RANDOM"]
CRITERION_LABEL = {"TARGET_DISTANCE": "Nearest", "AFFORDANCE": "Affordances",
                   "MEMORY": "Memory", "RANDOM": "Random"}
CRITERION_COLOR = {"TARGET_DISTANCE": "#4c9f70", "AFFORDANCE": "#e08a3c",
                   "MEMORY": "#2b5eb8", "RANDOM": "#b04a4a"}

# ACTION_TENDENCY is NOT one of Campos's four, and is not a selection criterion in his
# sense: ActionTendencyFilter constrains the candidate set by dominant drive (Campos 2006
# innate tendencies) rather than choosing among alternatives. Our ActionSelection.selectOne
# nonetheless credits whichever filter first narrows to a single candidate, and because
# DEFAULT_ACTION_TENDENCIES maps TEDIUM -> {WANDER} — a singleton — any cycle where tedium
# dominates is fully DETERMINED by the constraint, with no choice left for the scoring
# filters downstream. Measured at 86% of decisions in the earlier campaign's current arms.
#
# Those decisions are therefore reported separately and excluded from the four-way shares,
# which are computed over decisions where a genuine choice actually happened. Excluding them
# is not cosmetic: leaving them in silently swamps the comparison, and reattributing them to
# the next filter in the chain would be worse still — every downstream filter is a
# pass-through on a one-element list, so the whole 86% would land on TARGET_DISTANCE and
# spuriously manufacture a match with Campos's substantial "Nearest" share.
CONSTRAINT_DETERMINED = "ACTION_TENDENCY"
ALL_SELECTION_TYPES = CRITERIA + [CONSTRAINT_DETERMINED]
CRITERION_LABEL[CONSTRAINT_DETERMINED] = "Tendency (constraint)"
CRITERION_COLOR[CONSTRAINT_DETERMINED] = "#8d8d8d"
ZOOM_N = 1000
CAMPOS_LIFETIME_RATIO = 6.7          # his 1.4e4 s with memory / 2.1e3 s without
MAPA_LEVELS = {"low": 0.25, "medium": 0.40, "high": 0.70}


def arm_pairs(cfg) -> list:
    """(no-memory, memory) pairs present in this run, matched by stack and world."""
    keys = set(cfg.cond_keys)
    pairs = []
    for k in cfg.cond_keys:
        if "nomem" in k:
            partner = k.replace("nomem", "mem")
            if partner in keys:
                pairs.append((k, partner))
    return pairs


# ---------------------------------------------------------------------------
# Per-creature outcome table — the unit every test operates on
# ---------------------------------------------------------------------------

def per_creature_outcomes(intervals, actions, creatures) -> pd.DataFrame:
    """One row per creature: the three quantities the parity claims are argued from."""
    keys = ["condition", "trial", "creature_key"]

    # observed_s/died carry the right-censoring that lifetime_s silently drops: a creature
    # still alive at maxRuntimeMinutes has lifetime_s = NaN and disappears from every
    # .dropna()'d statistic, which biases against exactly the arm that survives best. P4 is
    # argued from the censored pair; lifetime_s is kept for the uncensored descriptive views.
    surv_cols = [c for c in ("lifetime_s", "observed_s", "died") if c in creatures.columns]
    out = creatures[keys + surv_cols].drop_duplicates().copy()
    out["lifetime_s"] = num(out["lifetime_s"])
    if "observed_s" in out:
        out["observed_s"] = num(out["observed_s"])
        out["died"] = out["died"].astype(bool)

    if not intervals.empty:
        mi = intervals.groupby(keys)["interval_s"].mean().rename("mean_interval_s")
        out = out.merge(mi, on=keys, how="left")

    # Feeding as a RATE, not a count. Arms terminate differently — the legacy pair runs to
    # death (~250 s), while the simple and current pairs never die and are cut off at their
    # maxRuntimeMinutes — so an EAT *count* measures "rate x observation window" and is
    # comparable only between arms sharing a window. Dividing by observed_s makes it
    # comparable everywhere, which is what any cross-pair statement must use.
    if "observed_s" in out.columns and not intervals.empty:
        n_eat = intervals.groupby(keys).size().rename("n_eat")
        out = out.merge(n_eat, on=keys, how="left")
        out["eat_per_min"] = out["n_eat"] / (out["observed_s"] / 60.0).clip(lower=1e-9)

    if not actions.empty:
        counts = actions.groupby(keys)["selection_type"].value_counts().unstack(fill_value=0)
        n_all = counts.sum(axis=1)
        n_constraint = counts.get(CONSTRAINT_DETERMINED, 0)
        # Denominator is CHOSEN decisions, not all decisions — see CONSTRAINT_DETERMINED's
        # comment. Shares are therefore "of the decisions where a criterion actually chose",
        # which is the quantity Campos's four-way breakdown reports.
        n_chosen = (n_all - n_constraint).clip(lower=1)
        for crit in CRITERIA:
            out = out.merge((counts.get(crit, 0) / n_chosen).rename(f"share_{crit}"),
                            on=keys, how="left")
        out = out.merge(n_all.rename("n_decisions"), on=keys, how="left")
        out = out.merge((n_constraint / n_all.clip(lower=1))
                        .rename("constraint_determined_frac"), on=keys, how="left")

        # Campos's "random choice is no longer needed": RANDOM's rate late vs early.
        # Also computed over chosen decisions only, so a shift in how often the tendency
        # constraint fires cannot masquerade as random choice being displaced by memory.
        chosen = actions[actions["selection_type"] != CONSTRAINT_DETERMINED]
        ratios = []
        for k, g in chosen.groupby(keys):
            g = g.sort_values("time")
            n = len(g)
            if n < 30:
                continue
            third = n // 3
            is_rand = (g["selection_type"] == "RANDOM").values
            early = is_rand[:third].mean()
            if early > 0:
                ratios.append((*k, is_rand[-third:].mean() / early))
        if ratios:
            out = out.merge(pd.DataFrame(ratios, columns=keys + ["random_slope_ratio"]),
                            on=keys, how="left")
    return out


# ---------------------------------------------------------------------------
# F1 / F2 — Mapa's curves
# ---------------------------------------------------------------------------

def _mean_by_k(intervals, cfg, value):
    out = {}
    for c in cfg.conditions:
        d = intervals[intervals["condition"] == c.key]
        if d.empty:
            continue
        # Creature is the unit; averaging within trial first would discard exactly the
        # replication this design is built on.
        g = d.groupby("k")[value]
        out[c.key] = (g.mean(), g.sem())
    return out


def mapa_figures(intervals, cfg) -> None:
    if intervals.empty:
        print("  (skipping F1/F2 — no interactions recorded)")
        return
    for value, ylabel, title, fname in (
        ("interval_s", "interval (s)",
         "F1 — interval to find and interact (Mapa Fig. 47)\n"
         "shape comparable, absolute seconds are not", "f1_interaction_interval.png"),
        ("cumulative_s", "time alive (s)",
         "F2 — time alive at the k-th interaction (Mapa Fig. 50)",
         "f2_time_alive.png"),
    ):
        stats_by_cond = _mean_by_k(intervals, cfg, value)
        if not stats_by_cond:
            continue
        fig, ax = plt.subplots(figsize=(9, 5.5))
        for c in cfg.conditions:
            if c.key not in stats_by_cond:
                continue
            mean, sem = stats_by_cond[c.key]
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


def interval_trend(intervals, cfg) -> None:
    """Mapa: memory's advantage should grow with k. Silva: in an all-rewarding world the
    interval itself should fall monotonically, which is what the *_simple arms test."""
    print("\n  Interval trends")
    stats_by_cond = _mean_by_k(intervals, cfg, "interval_s")

    for nomem, mem in arm_pairs(cfg):
        if nomem not in stats_by_cond or mem not in stats_by_cond:
            continue
        a, b = stats_by_cond[nomem][0], stats_by_cond[mem][0]
        ks = sorted(set(a.index) & set(b.index))
        if len(ks) < 3:
            continue
        gap = np.array([a[k] - b[k] for k in ks])       # >0 = memory faster
        rho, p = scipy_stats.spearmanr(ks, gap)
        print(f"    {nomem} vs {mem}: memory faster at {int((gap > 0).sum())}/{len(ks)} k; "
              f"gap-vs-k rho={rho:+.3f} (p={p:.4g})")

    for c in cfg.conditions:
        if c.key not in stats_by_cond:
            continue
        mean = stats_by_cond[c.key][0]
        if len(mean) >= 3:
            rho, p = scipy_stats.spearmanr(mean.index, mean.values)
            print(f"    {c.label:<28s} interval-vs-k rho={rho:+.3f} (p={p:.4g})"
                  f"{'   [decreasing]' if rho < 0 and p < .05 else ''}")


# ---------------------------------------------------------------------------
# F3 / F4 — Campos's curves
# ---------------------------------------------------------------------------

def _representative(actions, cond):
    """Campos plots typical single realizations — take the median-length creature."""
    d = actions[actions["condition"] == cond]
    if d.empty:
        return d
    lengths = d.groupby(["trial", "creature_key"]).size().sort_values()
    trial, ck = lengths.index[len(lengths) // 2]
    return d[(d["trial"] == trial) & (d["creature_key"] == ck)].sort_values("time")


def campos_figures(actions, cfg) -> None:
    for limit, fname, title in (
        (None, "f3_cumulative_selections.png",
         "F3 — cumulative selections per criterion, whole life (Campos Fig. 5)"),
        (ZOOM_N, "f4_cumulative_first1000.png",
         f"F4 — first {ZOOM_N} decisions (Campos Fig. 6)"),
    ):
        conds = [c for c in cfg.conditions
                 if not actions[actions["condition"] == c.key].empty]
        if not conds:
            return
        fig, axes = plt.subplots(1, len(conds), figsize=(4.4 * len(conds), 4.6),
                                 squeeze=False)
        for ax, c in zip(axes[0], conds):
            d = _representative(actions, c.key)
            if limit is not None:
                d = d.head(limit)
            if d.empty:
                continue
            idx = np.arange(1, len(d) + 1)
            # ALL_SELECTION_TYPES, not CRITERIA: the tendency-constraint curve is drawn
            # (dashed, grey) rather than omitted. Plotting only Campos's four silently hid
            # the dominant category in the previous campaign's current arms.
            for crit in ALL_SELECTION_TYPES:
                cum = (d["selection_type"] == crit).cumsum().values
                if cum[-1] == 0:
                    continue
                ax.plot(idx, cum, color=CRITERION_COLOR[crit], label=CRITERION_LABEL[crit],
                        ls="--" if crit == CONSTRAINT_DETERMINED else "-",
                        alpha=0.6 if crit == CONSTRAINT_DETERMINED else 1.0)
            ax.set_title(c.label, fontsize=9)
            ax.set_xlabel("decisions")
            ax.set_ylabel("cumulative selections")
            ax.grid(alpha=0.3)
        handles = [plt.Line2D([], [], color=CRITERION_COLOR[c], label=CRITERION_LABEL[c],
                              ls="--" if c == CONSTRAINT_DETERMINED else "-")
                   for c in ALL_SELECTION_TYPES]
        fig.legend(handles=handles, loc="lower center", ncol=len(ALL_SELECTION_TYPES),
                   fontsize=8, frameon=False)
        fig.suptitle(title)
        fig.tight_layout(rect=[0, 0.07, 1, 0.93])
        save(fig, fname, cfg)


def criterion_share_figure(per_creature, cfg) -> None:
    fig, ax = plt.subplots(figsize=(10, 5.5))
    width = 0.8 / max(1, len(cfg.conditions))
    x = np.arange(len(CRITERIA))
    for i, c in enumerate(cfg.conditions):
        d = per_creature[per_creature["condition"] == c.key]
        means = [d[f"share_{crit}"].mean() if f"share_{crit}" in d else 0 for crit in CRITERIA]
        means = [0 if pd.isna(m) else m for m in means]
        ax.bar(x + i * width, means, width, color=c.color, label=c.label, alpha=0.85)
    ax.set_xticks(x + width * (len(cfg.conditions) - 1) / 2)
    ax.set_xticklabels([CRITERION_LABEL[c] for c in CRITERIA])
    ax.set_ylabel("share of a creature's CHOSEN decisions")
    # Per-arm range, not a pooled mean: arms differ enormously here (an arm with the
    # tendency filter off is 0% by construction, one with it on can exceed 80%), so a
    # single averaged number would describe none of them.
    frac = per_creature.get("constraint_determined_frac")
    if frac is not None and frac.notna().any():
        by_arm = per_creature.groupby("condition")["constraint_determined_frac"].mean()
        excluded = f"{100 * by_arm.min():.0f}–{100 * by_arm.max():.0f}% depending on arm"
    else:
        excluded = "n/a"
    ax.set_title("Selection criteria usage, with and without memory\n"
                 f"(over chosen decisions; {excluded} of decisions were determined by the "
                 "tendency constraint and excluded)", fontsize=10)
    ax.legend(fontsize=7)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    save(fig, "f3b_criterion_shares.png", cfg)


def lifetime_figure(per_creature, cfg) -> None:
    """F5 — survival curves, mortality, and P4's ratio against Campos's 6.7x.

    Kaplan-Meier rather than a lifetime boxplot. A creature alive at maxRuntimeMinutes is
    censored, not missing, and a boxplot of lifetime_s can only be drawn over the ones that
    died — which in the v3 campaign meant two arms contributed nothing at all and P4 had no
    denominator. The KM curve uses every creature and needs neither arm fully observed.
    """
    censored = {"observed_s", "died"} <= set(per_creature.columns)
    if not censored:
        print("  (skipping F5 — creatures.parquet predates the died/observed_s columns; "
              "re-extract to compute survival)")
        return

    fig, axes = plt.subplots(1, 2, figsize=(14, 5.5))

    # An arm where nobody died draws as a flat line at S=1 rather than being skipped: "every
    # creature outlived the cap" is a result, and it is the one the reader most needs to see
    # when the ratio below comes back n/a.
    ax = axes[0]
    for c in cfg.conditions:
        d = per_creature[per_creature["condition"] == c.key]
        if d.empty:
            continue
        t, s = kaplan_meier(d["observed_s"].values, d["died"].astype(float).values)
        if len(t) == 0:
            continue
        horizon = float(np.nanmax(d["observed_s"].values))
        ax.step(np.append(t, horizon), np.append(s, s[-1]), where="post",
                color=c.color, label=c.label)
    ax.axhline(0.5, color="#999999", ls=":", lw=1)
    ax.set_xlabel("time alive (s)")
    ax.set_ylabel("survival probability")
    ax.set_ylim(0, 1.02)
    ax.set_title("Kaplan-Meier survival\n(flat to the right edge = still alive at the cap)")
    ax.legend(fontsize=8)
    ax.grid(alpha=0.3)

    ax = axes[1]
    mort = {c.key: float(per_creature[per_creature["condition"] == c.key]["died"].mean())
            for c in cfg.conditions
            if not per_creature[per_creature["condition"] == c.key].empty}
    ax.bar(range(len(mort)), [mort[k] for k in mort],
           color=[next(c.color for c in cfg.conditions if c.key == k) for k in mort])
    ax.set_xticks(range(len(mort)))
    ax.set_xticklabels([next(c.label for c in cfg.conditions if c.key == k) for k in mort],
                       rotation=20, ha="right", fontsize=8)
    ax.set_ylabel("fraction that died before the cap")
    ax.set_ylim(0, 1.02)
    ax.set_title("Mortality within the run")
    ax.grid(axis="y", alpha=0.3)

    fig.suptitle("F5 — survival, with and without memory")
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    save(fig, "f5_lifetime.png", cfg)

    print(f"\n  F5 / P4 — survival (Campos reports a {CAMPOS_LIFETIME_RATIO:.1f}x mean-lifetime ratio)")
    for nomem, mem in arm_pairs(cfg):
        print_survival(survival_comparison(per_creature, nomem, mem))


# ---------------------------------------------------------------------------
# F6 — conditioning trajectory
# ---------------------------------------------------------------------------

def conditioning_figure(conditioning, cfg) -> None:
    if conditioning.empty:
        print("  (skipping F6 — no conditioning data)")
        return
    df = conditioning.copy()
    df["probability"] = num(df["probability"])
    keys = ["condition", "trial", "creature_key", "target", "seq"]
    total = df.groupby(keys)["probability"].transform("sum")
    # Raw probabilities are not shares: varyProbability clamps at 0 while the compensating
    # -delta/(n-1) is applied unconditionally, so a target's raw sum drifts off 100.
    # ActionProbabilityFilter normalises at selection time; so must we.
    df["share"] = df["probability"] / total.where(total > 0, np.nan)

    fig, ax = plt.subplots(figsize=(9, 5.5))
    approach = df[df["action"] == "APPROACH"]
    for c in cfg.conditions:
        d = approach[approach["condition"] == c.key]
        if len(d) < 20:
            continue
        d = d.assign(bucket=pd.qcut(num(d["seq"]), 20, labels=False, duplicates="drop"))
        s = d.groupby("bucket")["share"].mean()
        ax.plot(s.index, s.values, marker="o", ms=3, color=c.color, label=c.label)
    for name, level in MAPA_LEVELS.items():
        ax.axhline(level, ls="--", lw=1, color="#888")
        ax.text(0.01, level, f" Mapa {name} ({level:.2f})", va="bottom", fontsize=7,
                color="#666", transform=ax.get_yaxis_transform())
    ax.set_xlabel("reinforcement events (20 equal-count buckets)")
    ax.set_ylabel("normalised APPROACH share")
    ax.set_title("F6 — learned conditioning against Mapa's initial levels\n"
                 "descriptive: we do not replicate her initial-conditioning sweep")
    ax.legend(fontsize=7)
    ax.grid(alpha=0.3)
    fig.tight_layout()
    save(fig, "f6_conditioning.png", cfg)


# ---------------------------------------------------------------------------
# Statistics
# ---------------------------------------------------------------------------

OUTCOMES = [
    # A rate, so it is comparable across arms with different termination conditions; the raw
    # count is not. See per_creature_outcomes.
    ("eat_per_min", "feeding rate (EAT/min)"),
    # Conditional on dying: survivors have lifetime_s = NaN and are dropped here. That makes
    # this "how long did the ones that died last", NOT "does memory extend life" — P4 is
    # decided by the censoring-aware survival_comparison in lifetime_figure instead. Kept
    # because the conditional comparison is still informative once mortality is known.
    ("lifetime_s", "lifetime (s), among those that died"),
    ("mean_interval_s", "mean interaction interval (s)"),
    ("share_RANDOM", "RANDOM share of chosen"),
    ("share_AFFORDANCE", "AFFORDANCE share of chosen"),
    ("share_TARGET_DISTANCE", "TARGET_DISTANCE share of chosen"),
    ("random_slope_ratio", "RANDOM late/early ratio"),
    # Not a parity claim — reported so the denominator behind every share above is visible
    # rather than implicit, and so a between-arm difference in how often the tendency
    # constraint fires is legible instead of silently reshaping the other shares.
    ("constraint_determined_frac", "decisions determined by tendency"),
]


def run_tests(per_creature, cfg) -> list:
    print("\n" + "=" * 76)
    print("  Memory vs no-memory, within each pair")
    print("  (creature-level is the headline; trial-level is the clustering check)")
    print("=" * 76)
    results = []
    for nomem, mem in arm_pairs(cfg):
        print(f"\n  --- {nomem}  vs  {mem} ---")
        for col, label in OUTCOMES:
            if col not in per_creature.columns:
                continue
            sub = per_creature[per_creature[col].notna()]
            res = compare_arms(sub, col, nomem, mem, label=label)
            print_comparison(res)
            res["pair"] = (nomem, mem)
            results.append(res)

    print("\n  --- across all arms ---")
    for col, label in OUTCOMES:
        if col not in per_creature.columns:
            continue
        groups, labels = [], []
        for c in cfg.conditions:
            v = per_creature[per_creature["condition"] == c.key][col].dropna().values
            if len(v):
                groups.append(v)
                labels.append(c.key)
        if len(groups) >= 2:
            print(f"    {label}")
            kruskal_test(groups, labels)
    return results


def sizing_report(per_creature, cfg, cluster_size=None) -> None:
    """Turn this run into the sample size for the next one.

    Reports, per outcome, the observed standardised effect and the intra-class correlation
    across trials, then the creatures-per-arm needed at 80% power — inflated by the design
    effect, because creatures inside a trial are not independent samples.
    """
    if cluster_size is None:
        n = per_creature.groupby(["condition", "trial"])["creature_key"].nunique()
        cluster_size = float(n.mean()) if len(n) else 1.0

    print("\n" + "=" * 76)
    print(f"  SAMPLE SIZE for the next run (alpha=0.05, power=0.80, "
          f"{cluster_size:.0f} creatures/trial)")
    print("=" * 76)

    for nomem, mem in arm_pairs(cfg):
        print(f"\n  --- {nomem} vs {mem} ---")
        print(f"    {'outcome':<30s} {'d':>7s} {'ICC':>6s} {'DEFF':>6s} "
              f"{'creatures':>10s} {'trials':>7s}")
        for col, label in OUTCOMES:
            if col not in per_creature.columns:
                continue
            a = per_creature[(per_creature["condition"] == nomem)][col].dropna()
            b = per_creature[(per_creature["condition"] == mem)][col].dropna()
            if len(a) < 2 or len(b) < 2:
                continue
            d = cohens_d(a, b)
            mem_df = per_creature[per_creature["condition"] == mem]
            icc = icc1([g[col].dropna().values for _, g in mem_df.groupby("trial")])
            req = required_n(d, icc=icc, cluster_size=cluster_size)
            n_c = req["n_clustered"]
            trials = req["clusters"]
            print(f"    {label:<30s} {d:+7.3f} {icc:6.3f} {req['design_effect']:6.2f} "
                  f"{n_c:10.1f} {trials:7.0f}"
                  if np.isfinite(n_c) else
                  f"    {label:<30s} {d:+7.3f} {icc:6.3f} {req['design_effect']:6.2f} "
                  f"{'no effect':>10s} {'-':>7s}")
    print("\n  Read the largest 'trials' across the outcomes you intend to claim.")
    print("  An outcome with d near zero needs an impractical n — that is a real finding")
    print("  about the effect, not a reason to keep adding trials.")


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------

def run(cfg) -> None:
    setup(cfg)
    print(f"Loading {cfg.name} from {cfg.data_dir}")

    creatures = load_all(cfg, "creatures.parquet")
    actions = load_all(cfg, "actions.parquet")
    mouth = load_all(cfg, "mouth_interactions.parquet")
    # Not load_all: legacy-minimal trials have 70-125M engram rows each (a 700x gap vs
    # the current stack's ~170K/trial - itself a finding, see D2). Loading that
    # concatenated across 48 trials OOM-killed the campaign's first analysis attempt.
    # See load_engrams_sampled's docstring for why per-trial sampling is statistically
    # sound here (every downstream use is an aggregate, never a row-level join).
    engrams = p84_memory_common.load_engrams_sampled(cfg)
    decisions = load_all(cfg, "memory_decisions.parquet")
    conditioning = load_all(cfg, "conditioning.parquet")
    episodes = load_all(cfg, "consolidation_episodes.parquet")
    traces = load_all(cfg, "memory_traces.parquet")

    if creatures.empty or actions.empty:
        print("No creatures/actions data — nothing to analyse.")
        return

    actions["time"] = num(actions["time"])
    creatures = attach_born_time_and_ticks(creatures, actions)
    attach_tick_rank, _ = make_tick_rank_attacher(actions)
    actions = attach_tick_rank(actions, creatures)
    if not decisions.empty:
        decisions = attach_tick_rank(decisions.rename(columns={"time_ms": "time"}), creatures)
    engrams = p84_memory_common.attach_engram_life_decile(engrams, creatures)

    intervals = interaction_intervals(mouth, creatures, max_k=MAX_K)
    per_creature = per_creature_outcomes(intervals, actions, creatures)
    print(f"  {len(per_creature)} creatures across {per_creature['trial'].nunique()} trials "
          f"x {len(cfg.conditions)} arms")

    mapa_figures(intervals, cfg)
    campos_figures(actions, cfg)
    criterion_share_figure(per_creature, cfg)
    lifetime_figure(per_creature, cfg)
    conditioning_figure(conditioning, cfg)
    if not intervals.empty:
        interval_trend(intervals, cfg)

    run_tests(per_creature, cfg)
    p84_memory_common.run_all(cfg, engrams=engrams, decisions=decisions,
                              creatures=creatures, episodes=episodes, traces=traces)
    sizing_report(per_creature, cfg)

    print(f"\nFigures saved → {cfg.fig_dir}")
