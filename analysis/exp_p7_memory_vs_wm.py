#!/usr/bin/env python3
"""
EXP-P7: Memory Filter vs. World Model — statistical analysis and report.

Compares creature lifetime and action-selection quality across five configurations:
  P7-1  baseline             (dist + afford + rand, no consolidation)
  P7-2  memory_only          (+ memory filter, no consolidation)
  P7-3  memory_consolidation (+ memory filter + Mapa consolidation)
  P7-4  jepa_only            (+ world model, no consolidation)
  P7-5  jepa_consolidation   (+ world model + adapter training)

Statistical tests:
  - Kruskal-Wallis H across all groups
  - Pairwise Mann-Whitney U (vs. baseline) with Bonferroni correction

Usage:
    python3 analysis/exp_p7_memory_vs_wm.py --wd ~/dl2l-shared/data
"""

import argparse
import glob
import math
import os
import textwrap

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy import stats

ROOT    = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIG_DIR = os.path.join(ROOT, "docs", "figures", "exp_p7")
RPT_DIR = os.path.join(ROOT, "docs", "reports")
os.makedirs(FIG_DIR, exist_ok=True)
os.makedirs(RPT_DIR, exist_ok=True)

SAMPLES = [
    ("p7_1_baseline",              "Baseline\n(dist+afford+rand)"),
    ("p7_2_memory_only",           "Memory\n(no consol.)"),
    ("p7_3_memory_consolidation",  "Memory\n+Mapa consol."),
    ("p7_4_jepa_only",             "JEPA\n(no consol.)"),
    ("p7_5_jepa_consolidation",    "JEPA\n+adapter consol."),
]

COLORS = {
    "p7_1_baseline":              "#636363",
    "p7_2_memory_only":           "#7570b3",
    "p7_3_memory_consolidation":  "#1b9e77",
    "p7_4_jepa_only":             "#d95f02",
    "p7_5_jepa_consolidation":    "#e7298a",
}


# ── data loading ──────────────────────────────────────────────────────────────

def load_lifetimes(sample_dir: str) -> np.ndarray:
    values = []
    for trial_dir in sorted(glob.glob(os.path.join(sample_dir, "trial_*"))):
        lt_file = os.path.join(trial_dir, "lifetimes.csv")
        if os.path.exists(lt_file):
            df = pd.read_csv(lt_file)
            if "lifetime" in df.columns:
                values.extend(df["lifetime"].dropna().tolist())
    return np.array(values)


def load_distances(sample_dir: str) -> np.ndarray:
    values = []
    for trial_dir in sorted(glob.glob(os.path.join(sample_dir, "trial_*"))):
        d_file = os.path.join(trial_dir, "distances.csv")
        if os.path.exists(d_file):
            df = pd.read_csv(d_file)
            col = "distances" if "distances" in df.columns else df.columns[-1]
            values.extend(df[col].dropna().tolist())
    return np.array(values)


def load_actions(sample_dir: str) -> pd.DataFrame:
    """Aggregate trajectory_actions across all trial_* / creature subdirs."""
    frames = []
    for trial_dir in sorted(glob.glob(os.path.join(sample_dir, "trial_*"))):
        for f in glob.glob(os.path.join(trial_dir, "*:*", "trajectory_actions.csv")):
            frames.append(pd.read_csv(f))
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def load_arousal_series(sample_dir: str, n_points: int = 200) -> pd.DataFrame:
    """
    Load and resample arousal_history.csv from all creatures/trials.
    Returns a DataFrame with columns [hunger, sleep] indexed 0..1 (normalised time).
    """
    frames = []
    for trial_dir in sorted(glob.glob(os.path.join(sample_dir, "trial_*"))):
        for f in glob.glob(os.path.join(trial_dir, "*:*", "arousal_history.csv")):
            df = pd.read_csv(f)
            if df.empty or "time" not in df.columns:
                continue
            df = df.sort_values("time")
            t_min, t_max = df["time"].min(), df["time"].max()
            if t_max <= t_min:
                continue
            df["t_norm"] = (df["time"] - t_min) / (t_max - t_min)
            grid = np.linspace(0, 1, n_points)
            resampled = {}
            for col in ["hunger", "sleep"]:
                if col in df.columns:
                    resampled[col] = np.interp(grid, df["t_norm"].values, df[col].values)
            if resampled:
                resampled["t_norm"] = grid
                frames.append(pd.DataFrame(resampled))
    if not frames:
        return pd.DataFrame()
    return pd.concat(frames, ignore_index=True)


def load_efficiency_series(sample_dir: str, n_points: int = 200) -> pd.DataFrame:
    frames = []
    for trial_dir in sorted(glob.glob(os.path.join(sample_dir, "trial_*"))):
        for f in glob.glob(os.path.join(trial_dir, "*:*", "behavioural_efficiency.csv")):
            df = pd.read_csv(f)
            if df.empty or "time" not in df.columns:
                continue
            df = df.sort_values("time")
            t_min, t_max = df["time"].min(), df["time"].max()
            if t_max <= t_min:
                continue
            df["t_norm"] = (df["time"] - t_min) / (t_max - t_min)
            grid = np.linspace(0, 1, n_points)
            resampled = {}
            for col in ["simpleEfficiency", "complexEfficiency"]:
                if col in df.columns:
                    resampled[col] = np.interp(grid, df["t_norm"].values, df[col].values)
            if resampled:
                resampled["t_norm"] = grid
                frames.append(pd.DataFrame(resampled))
    if not frames:
        return pd.DataFrame()
    return pd.concat(frames, ignore_index=True)


# ── statistics ────────────────────────────────────────────────────────────────

def cohens_d(a: np.ndarray, b: np.ndarray) -> float:
    if len(a) < 2 or len(b) < 2:
        return float("nan")
    pooled_std = math.sqrt(((len(a) - 1) * np.var(a, ddof=1) +
                            (len(b) - 1) * np.var(b, ddof=1)) /
                           (len(a) + len(b) - 2))
    return (np.mean(a) - np.mean(b)) / pooled_std if pooled_std > 0 else float("nan")


def pairwise_mwu_vs_baseline(groups: dict, baseline_key: str, alpha: float = 0.05):
    baseline = groups[baseline_key]
    comparisons = [(k, v) for k, v in groups.items() if k != baseline_key]
    n_comp = len(comparisons)
    alpha_adj = alpha / n_comp

    results = []
    for name, values in comparisons:
        if len(values) < 2:
            results.append({"group": name, "U": float("nan"), "p_raw": float("nan"),
                            "p_adj": float("nan"), "significant": False,
                            "d": float("nan"), "median_diff": float("nan")})
            continue
        U, p_raw = stats.mannwhitneyu(values, baseline, alternative="two-sided")
        p_adj = min(p_raw * n_comp, 1.0)
        results.append({
            "group":        name,
            "U":            U,
            "p_raw":        p_raw,
            "p_adj":        p_adj,
            "significant":  p_adj < alpha,
            "d":            cohens_d(values, baseline),
            "median_diff":  np.median(values) - np.median(baseline),
        })
    return results, alpha_adj


# ── figures ───────────────────────────────────────────────────────────────────

def plot_lifetime_boxplot(groups: dict, out_path: str):
    fig, ax = plt.subplots(figsize=(11, 5))
    data   = [groups.get(k, np.array([])) for k, _ in SAMPLES]
    labels = [lbl for _, lbl in SAMPLES]
    bps = ax.boxplot(data, tick_labels=labels, patch_artist=True,
                     medianprops=dict(color="black", linewidth=2))
    for patch, (k, _) in zip(bps["boxes"], SAMPLES):
        patch.set_facecolor(COLORS[k])
        patch.set_alpha(0.7)
    ax.set_ylabel("Lifetime (seconds)")
    ax.set_title("EXP-P7: Creature Lifetime by Configuration")
    ax.grid(axis="y", linestyle="--", alpha=0.5)
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  → {out_path}")


def plot_distance_boxplot(groups_dist: dict, out_path: str):
    fig, ax = plt.subplots(figsize=(11, 5))
    data   = [groups_dist.get(k, np.array([])) for k, _ in SAMPLES]
    labels = [lbl for _, lbl in SAMPLES]
    bps = ax.boxplot(data, tick_labels=labels, patch_artist=True,
                     medianprops=dict(color="black", linewidth=2))
    for patch, (k, _) in zip(bps["boxes"], SAMPLES):
        patch.set_facecolor(COLORS[k])
        patch.set_alpha(0.7)
    ax.set_ylabel("Total distance traveled (units)")
    ax.set_title("EXP-P7: Total Distance Traveled by Configuration")
    ax.grid(axis="y", linestyle="--", alpha=0.5)
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  → {out_path}")


def plot_filter_usage(groups_actions: dict, out_path: str):
    """Stacked bar: fraction of decisions made by each filter type per sample."""
    fig, ax = plt.subplots(figsize=(11, 5))
    all_types = ["TARGET_DISTANCE", "AFFORDANCE", "MEMORY", "WORLD_MODEL", "RANDOM"]
    bar_colors = ["#1b9e77", "#d95f02", "#7570b3", "#e7298a", "#a6761d"]

    x = np.arange(len(SAMPLES))
    bottom = np.zeros(len(SAMPLES))
    for ftype, color in zip(all_types, bar_colors):
        fracs = []
        for name, _ in SAMPLES:
            df = groups_actions.get(name, pd.DataFrame())
            if df.empty or "selection_type" not in df.columns:
                fracs.append(0.0)
            else:
                fracs.append((df["selection_type"] == ftype).sum() / max(len(df), 1) * 100)
        ax.bar(x, fracs, bottom=bottom, label=ftype, color=color)
        bottom += np.array(fracs)
    ax.set_xticks(x)
    ax.set_xticklabels([lbl for _, lbl in SAMPLES], fontsize=9)
    ax.set_ylabel("% of decisions")
    ax.set_title("EXP-P7: Filter Selection Breakdown by Configuration")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(axis="y", linestyle="--", alpha=0.4)
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  → {out_path}")


def plot_action_type_breakdown(groups_actions: dict, out_path: str):
    """Stacked bar: fraction of each action_type per condition (eat/sleep/wander/etc.)."""
    all_action_types = []
    for df in groups_actions.values():
        if not df.empty and "action_type" in df.columns:
            all_action_types.extend(df["action_type"].unique().tolist())
    all_action_types = sorted(set(all_action_types))
    cmap = matplotlib.colormaps.get_cmap("tab10").resampled(max(len(all_action_types), 1))
    fig, ax = plt.subplots(figsize=(11, 5))
    x = np.arange(len(SAMPLES))
    bottom = np.zeros(len(SAMPLES))
    for i, atype in enumerate(all_action_types):
        fracs = []
        for name, _ in SAMPLES:
            df = groups_actions.get(name, pd.DataFrame())
            if df.empty or "action_type" not in df.columns:
                fracs.append(0.0)
            else:
                fracs.append((df["action_type"] == atype).sum() / max(len(df), 1) * 100)
        ax.bar(x, fracs, bottom=bottom, label=atype, color=cmap(i))
        bottom += np.array(fracs)
    ax.set_xticks(x)
    ax.set_xticklabels([lbl for _, lbl in SAMPLES], fontsize=9)
    ax.set_ylabel("% of actions")
    ax.set_title("EXP-P7: Action Type Distribution by Configuration")
    ax.legend(loc="upper right", fontsize=7, ncol=2)
    ax.grid(axis="y", linestyle="--", alpha=0.4)
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  → {out_path}")


def plot_arousal_over_time(groups_arousal: dict, out_path: str):
    n_cols = len(SAMPLES)
    fig, axes = plt.subplots(1, n_cols, figsize=(4 * n_cols, 4), sharey=True)
    for ax, (name, lbl) in zip(axes, SAMPLES):
        df = groups_arousal.get(name, pd.DataFrame())
        color = COLORS[name]
        if df.empty:
            ax.set_title(lbl.replace("\n", " "))
            continue
        for col, ls in [("hunger", "-"), ("sleep", "--")]:
            if col in df.columns:
                grouped = df.groupby("t_norm")[col].agg(["mean", "std"])
                ax.plot(grouped.index, grouped["mean"], lw=2, ls=ls, color=color, label=col)
                ax.fill_between(grouped.index,
                                grouped["mean"] - grouped["std"],
                                grouped["mean"] + grouped["std"],
                                alpha=0.15, color=color)
        ax.set_title(lbl.replace("\n", " "), fontsize=9)
        ax.set_xlabel("Normalised time")
        ax.legend(fontsize=7)
        ax.grid(linestyle="--", alpha=0.4)
    axes[0].set_ylabel("Arousal level")
    fig.suptitle("EXP-P7: Arousal (hunger/sleep) over normalised lifetime", fontsize=11)
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  → {out_path}")


def plot_efficiency_over_time(groups_eff: dict, out_path: str):
    fig, axes = plt.subplots(1, 2, figsize=(13, 5))
    for col, ax, title in [
        ("simpleEfficiency",  axes[0], "Simple Behavioural Efficiency"),
        ("complexEfficiency", axes[1], "Complex Behavioural Efficiency"),
    ]:
        for name, lbl in SAMPLES:
            df = groups_eff.get(name, pd.DataFrame())
            if df.empty or col not in df.columns:
                continue
            grouped = df.groupby("t_norm")[col].agg(["mean", "std"])
            ax.plot(grouped.index, grouped["mean"], lw=2,
                    color=COLORS[name], label=lbl.replace("\n", " "))
            ax.fill_between(grouped.index,
                            grouped["mean"] - grouped["std"],
                            grouped["mean"] + grouped["std"],
                            alpha=0.12, color=COLORS[name])
        ax.set_title(title)
        ax.set_xlabel("Normalised time")
        ax.set_ylabel("Efficiency")
        ax.legend(fontsize=7)
        ax.grid(linestyle="--", alpha=0.4)
    fig.suptitle("EXP-P7: Behavioural Efficiency over normalised lifetime", fontsize=11)
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  → {out_path}")


def plot_sleep_wm_rate(groups_actions: dict, out_path: str):
    """For P7-4/P7-5: fraction of WORLD_MODEL decisions that chose SLEEP."""
    fig, ax = plt.subplots(figsize=(8, 4))
    names, rates, ns, bar_colors = [], [], [], []
    for name, lbl in SAMPLES:
        df = groups_actions.get(name, pd.DataFrame())
        if df.empty or "selection_type" not in df.columns:
            continue
        wm = df[df["selection_type"] == "WORLD_MODEL"]
        if len(wm) == 0:
            continue
        rate = (wm["action_type"] == "SLEEP").sum() / len(wm) * 100
        names.append(lbl.replace("\n", " "))
        rates.append(rate)
        ns.append(len(wm))
        bar_colors.append(COLORS[name])
    if not names:
        plt.close(fig)
        return
    ax.bar(range(len(names)), rates, color=bar_colors)
    ax.set_xticks(range(len(names)))
    ax.set_xticklabels(names, fontsize=9)
    ax.set_ylabel("% SLEEP among WORLD_MODEL decisions")
    ax.set_title("EXP-P7: SLEEP rate in WORLD_MODEL decisions (Mode-2 bias check)")
    for i, (r, n) in enumerate(zip(rates, ns)):
        ax.text(i, r + 0.5, f"{r:.1f}%\n(n={n})", ha="center", fontsize=8)
    ax.grid(axis="y", linestyle="--", alpha=0.4)
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  → {out_path}")


# ── report ────────────────────────────────────────────────────────────────────

def build_report(groups: dict, groups_dist: dict, groups_actions: dict,
                 mwu_results: list, kw_H: float, kw_p: float,
                 alpha_bonf: float) -> str:
    lines = []

    def h(level, text):
        lines.append("#" * level + " " + text)
        lines.append("")

    h(1, "EXP-P7: Memory Filter vs. World Model")

    lines.append("## Purpose")
    lines.append("")
    lines.append(textwrap.dedent("""\
        Determine whether the JEPA neural world model adds measurable value over a
        symbolic memory-based action filter (Mapa 2009) for improving creature lifetime
        and action quality in the DL2L artificial-life simulator.
        Five conditions are compared: a no-filter baseline, memory filter alone,
        memory filter with Mapa consolidation, JEPA world model alone, and JEPA
        with adapter consolidation during sleep.
    """))

    lines.append("## Assumptions")
    lines.append("")
    lines.append(textwrap.dedent("""\
        - Creature lifetime (wall-clock seconds) is a valid proxy for survival fitness.
        - All conditions use identical world configuration: 1 holder, 10 creatures,
          2100×1600 world, reposition enabled, matching p9 training density (~255 apples/Mpx).
        - P7-1 (baseline) provides the null distribution for hypothesis testing.
        - JEPA training used the `internal_critic` variant (best val L_pred = 0.1683).
        - Significance level α=0.05; Bonferroni-corrected for 4 pairwise comparisons.
        - 5 trials × 10 creatures = 50 lifetime observations per condition.
    """))

    lines.append("## Hypotheses")
    lines.append("")
    lines.append(textwrap.dedent("""\
        **H1**: Memory filter (P7-2) significantly improves lifetime vs. baseline (P7-1).
        **H2**: Mapa consolidation (P7-3) further improves lifetime vs. memory-only (P7-2).
        **H3**: JEPA filter (P7-4) significantly improves lifetime vs. baseline (P7-1).
        **H4**: JEPA + adapter consolidation (P7-5) improves vs. JEPA-only (P7-4).
        **H5**: The best JEPA variant outperforms the best memory-filter variant.
    """))

    lines.append("## Results")
    lines.append("")

    # Lifetime summary table
    lines.append("### Creature Lifetime")
    lines.append("")
    lines.append("| Configuration | n | Median (s) | Mean (s) | Std (s) |")
    lines.append("|---|---|---|---|---|")
    for name, lbl in SAMPLES:
        v = groups.get(name, np.array([]))
        lbl_clean = lbl.replace("\n", " ")
        if len(v) > 0:
            lines.append(f"| {lbl_clean} | {len(v)} | {np.median(v):.1f} | {np.mean(v):.1f} | {np.std(v, ddof=1):.1f} |")
        else:
            lines.append(f"| {lbl_clean} | 0 | — | — | — |")
    lines.append("")

    # Distance table
    lines.append("### Distance Traveled")
    lines.append("")
    lines.append("| Configuration | n | Median | Mean | Std |")
    lines.append("|---|---|---|---|---|")
    for name, lbl in SAMPLES:
        v = groups_dist.get(name, np.array([]))
        lbl_clean = lbl.replace("\n", " ")
        if len(v) > 0:
            lines.append(f"| {lbl_clean} | {len(v)} | {np.median(v):.0f} | {np.mean(v):.0f} | {np.std(v, ddof=1):.0f} |")
        else:
            lines.append(f"| {lbl_clean} | 0 | — | — | — |")
    lines.append("")

    # Filter usage summary
    lines.append("### Filter Usage (% of decisions)")
    lines.append("")
    lines.append("| Configuration | RANDOM | AFFORDANCE | MEMORY | WORLD_MODEL |")
    lines.append("|---|---|---|---|---|")
    for name, lbl in SAMPLES:
        df = groups_actions.get(name, pd.DataFrame())
        lbl_clean = lbl.replace("\n", " ")
        if df.empty or "selection_type" not in df.columns:
            lines.append(f"| {lbl_clean} | — | — | — | — |")
            continue
        total = max(len(df), 1)
        r   = (df["selection_type"] == "RANDOM").sum() / total * 100
        aff = (df["selection_type"] == "AFFORDANCE").sum() / total * 100
        mem = (df["selection_type"] == "MEMORY").sum() / total * 100
        wm  = (df["selection_type"] == "WORLD_MODEL").sum() / total * 100
        lines.append(f"| {lbl_clean} | {r:.1f}% | {aff:.1f}% | {mem:.1f}% | {wm:.1f}% |")
    lines.append("")

    # Kruskal-Wallis
    lines.append("### Statistical Tests")
    lines.append("")
    lines.append("**Kruskal-Wallis (lifetime, all groups):**")
    lines.append("")
    sig = "significant" if kw_p < 0.05 else "not significant"
    lines.append(f"H = {kw_H:.3f}, p = {kw_p:.4f} → **{sig}** (α=0.05)")
    lines.append("")

    lines.append(f"**Pairwise Mann-Whitney U vs. baseline (Bonferroni α={alpha_bonf:.4f}):**")
    lines.append("")
    lines.append("| vs. Baseline | U | p_raw | p_adj | Significant | Cohen's d | Median diff (s) |")
    lines.append("|---|---|---|---|---|---|---|")
    for r in mwu_results:
        sig_mark = "✓" if r["significant"] else "✗"
        U_str = f"{r['U']:.0f}" if not math.isnan(r["U"]) else "—"
        p_raw_str = f"{r['p_raw']:.4f}" if not math.isnan(r["p_raw"]) else "—"
        p_adj_str = f"{r['p_adj']:.4f}" if not math.isnan(r["p_adj"]) else "—"
        d_str  = f"{r['d']:+.3f}" if not math.isnan(r["d"]) else "—"
        md_str = f"{r['median_diff']:+.1f}" if not math.isnan(r["median_diff"]) else "—"
        lines.append(f"| {r['group']} | {U_str} | {p_raw_str} | {p_adj_str} | {sig_mark} | {d_str} | {md_str} |")
    lines.append("")

    lines.append("## Analysis")
    lines.append("")

    lines.append("### Figures")
    lines.append("")
    for fname, caption in [
        ("lifetime_boxplot.png",     "Creature lifetime distribution across all five conditions."),
        ("distance_boxplot.png",     "Total distance traveled per creature (proxy for food-seeking activity)."),
        ("filter_usage.png",         "Fraction of action decisions made by each selection filter."),
        ("action_type_breakdown.png","Distribution of action types (EAT, SLEEP, WANDER, OBSERVE …) per condition."),
        ("arousal_over_time.png",    "Mean arousal (hunger/sleep drives) over normalised lifetime."),
        ("efficiency_over_time.png", "Simple and complex behavioural efficiency over normalised lifetime."),
        ("sleep_wm_rate.png",        "SLEEP rate among WORLD_MODEL decisions (Mode-2 SLEEP bias check)."),
    ]:
        lines.append(f"![{caption}](../figures/exp_p7/{fname})")
        lines.append(f"*{caption}*")
        lines.append("")

    lines.append("### Interpretation")
    lines.append("")

    # Auto-generate interpretation
    sig_groups = [r for r in mwu_results if r["significant"]]
    if kw_p >= 0.05:
        lines.append(
            "The Kruskal-Wallis test found no significant difference in lifetime across "
            "conditions (H={:.3f}, p={:.4f}). No configuration reliably changed survival "
            "at this sample size (n=50 per condition). This may indicate that both the "
            "memory filter and the JEPA world model are not yet strong enough signal sources "
            "to overcome the variance in creature behaviour.".format(kw_H, kw_p)
        )
    else:
        lines.append(
            f"The Kruskal-Wallis test detected a significant difference across conditions "
            f"(H={kw_H:.3f}, p={kw_p:.4f})."
        )
        lines.append("")
        if sig_groups:
            lines.append("Pairwise comparisons vs. baseline (Bonferroni corrected):")
            for r in sig_groups:
                direction = "increased" if r["median_diff"] > 0 else "decreased"
                lines.append(
                    f"- **{r['group']}**: lifetime {direction} by {abs(r['median_diff']):.1f}s "
                    f"(Cohen's d = {r['d']:+.3f}, p_adj = {r['p_adj']:.4f})."
                )
        else:
            lines.append(
                "Despite the overall Kruskal-Wallis significance, no individual comparison "
                "survives Bonferroni correction. The effect may be distributed across "
                "multiple conditions rather than concentrated in a single one."
            )
    lines.append("")

    # H5 comparison of best memory vs best JEPA
    best_mem  = max(["p7_2_memory_only", "p7_3_memory_consolidation"],
                    key=lambda k: np.median(groups.get(k, np.array([0]))))
    best_jepa = max(["p7_4_jepa_only", "p7_5_jepa_consolidation"],
                    key=lambda k: np.median(groups.get(k, np.array([0]))))
    v_mem  = groups.get(best_mem, np.array([]))
    v_jepa = groups.get(best_jepa, np.array([]))
    if len(v_mem) >= 2 and len(v_jepa) >= 2:
        U5, p5 = stats.mannwhitneyu(v_jepa, v_mem, alternative="two-sided")
        d5 = cohens_d(v_jepa, v_mem)
        winner = "JEPA" if np.median(v_jepa) > np.median(v_mem) else "Memory"
        lines.append(
            f"**H5 (best JEPA vs. best Memory):** {best_jepa} vs. {best_mem} — "
            f"U={U5:.0f}, p={p5:.4f}, d={d5:+.3f}. "
            f"**{winner}** variant has higher median lifetime."
        )
        lines.append("")

    lines.append("## Conclusions")
    lines.append("")
    lines.append("_See interpretation above. Complete conclusions pending additional trials if needed._")
    lines.append("")

    return "\n".join(lines)


# ── main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wd",    required=True, help="Data root directory (contains p7_1_baseline/, …)")
    parser.add_argument("--alpha", type=float, default=0.05)
    args = parser.parse_args()

    print("Loading data …")
    groups         = {}
    groups_dist    = {}
    groups_actions = {}
    groups_arousal = {}
    groups_eff     = {}

    for name, _ in SAMPLES:
        sample_dir = os.path.join(args.wd, name)
        if not os.path.isdir(sample_dir):
            print(f"  MISSING: {sample_dir}")
            continue
        groups[name]         = load_lifetimes(sample_dir)
        groups_dist[name]    = load_distances(sample_dir)
        groups_actions[name] = load_actions(sample_dir)
        groups_arousal[name] = load_arousal_series(sample_dir)
        groups_eff[name]     = load_efficiency_series(sample_dir)
        print(f"  {name}: {len(groups[name])} lifetimes, "
              f"{len(groups_actions[name])} actions")

    # Statistics
    valid = [v for v in groups.values() if len(v) >= 2]
    if len(valid) >= 2:
        kw_H, kw_p = stats.kruskal(*valid)
    else:
        kw_H, kw_p = float("nan"), float("nan")
    print(f"\nKruskal-Wallis: H={kw_H:.3f}, p={kw_p:.4f}")

    baseline_key = "p7_1_baseline"
    mwu_results, alpha_bonf = pairwise_mwu_vs_baseline(groups, baseline_key, args.alpha)
    for r in mwu_results:
        sig = "SIG" if r["significant"] else "n.s."
        print(f"  MWU {r['group']:35s}  p_adj={r['p_adj']:.4f}  d={r['d']:+.3f}  [{sig}]")

    # Figures
    print("\nGenerating figures …")
    plot_lifetime_boxplot(groups,        os.path.join(FIG_DIR, "lifetime_boxplot.png"))
    plot_distance_boxplot(groups_dist,   os.path.join(FIG_DIR, "distance_boxplot.png"))
    plot_filter_usage(groups_actions,    os.path.join(FIG_DIR, "filter_usage.png"))
    plot_action_type_breakdown(groups_actions, os.path.join(FIG_DIR, "action_type_breakdown.png"))
    plot_arousal_over_time(groups_arousal, os.path.join(FIG_DIR, "arousal_over_time.png"))
    plot_efficiency_over_time(groups_eff,  os.path.join(FIG_DIR, "efficiency_over_time.png"))
    plot_sleep_wm_rate(groups_actions,   os.path.join(FIG_DIR, "sleep_wm_rate.png"))

    # Report
    print("\nBuilding report …")
    report = build_report(groups, groups_dist, groups_actions,
                          mwu_results, kw_H, kw_p, alpha_bonf)
    out = os.path.join(RPT_DIR, "EXP_P7_MEMORY_FILTER_VS_WORLD_MODEL.md")
    with open(out, "w") as f:
        f.write(report)
    print(f"Report → {out}")


if __name__ == "__main__":
    main()
