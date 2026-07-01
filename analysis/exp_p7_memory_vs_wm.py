#!/usr/bin/env python3
"""
EXP-P7: Memory Filter vs. World Model — statistical analysis and report.

Compares creature lifetime and action-selection quality across six configurations:
  P7-1  baseline           (dist + afford + rand, no consolidation)
  P7-2  memory_only        (+ memory filter, no consolidation)
  P7-3  memory_consolidation (+ memory filter + Mapa consolidation)
  P7-4  jepa_only          (+ world model, no consolidation)
  P7-5  jepa_consolidation (+ world model + adapter training)

Statistical tests:
  - Kruskal-Wallis H across all groups
  - Pairwise Mann-Whitney U (vs. baseline) with Bonferroni correction

Usage:
    python3 analysis/exp_p7_memory_vs_wm.py --wd data/exp_p7 [--baseline-wd ml/data/raw_p7]
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
    ("p7_3_memory_consolidation",  "Memory\n+ Mapa consol."),
    ("p7_4_jepa_only",             "JEPA\n(no consol.)"),
    ("p7_5_jepa_consolidation",    "JEPA\n+ adapter consol."),
]


# ── data loading ─────────────────────────────────────────────────────────────

def load_lifetimes(sample_dir: str) -> np.ndarray:
    """Aggregate lifetime values from all trial_* subdirectories."""
    values = []
    for trial_dir in sorted(glob.glob(os.path.join(sample_dir, "trial_*"))):
        lt_file = os.path.join(trial_dir, "lifetimes.csv")
        if os.path.exists(lt_file):
            df = pd.read_csv(lt_file)
            if "lifetime" in df.columns:
                values.extend(df["lifetime"].dropna().tolist())
    return np.array(values)


def load_actions(sample_dir: str) -> pd.DataFrame:
    """Aggregate chosen_action_state across all trial_* subdirectories."""
    frames = []
    for trial_dir in sorted(glob.glob(os.path.join(sample_dir, "trial_*"))):
        for cas_file in glob.glob(os.path.join(trial_dir, "*", "chosen_action_state.csv")):
            frames.append(pd.read_csv(cas_file))
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def load_engrams(sample_dir: str) -> pd.DataFrame:
    """Load engram_state for memory quality metrics."""
    frames = []
    for trial_dir in sorted(glob.glob(os.path.join(sample_dir, "trial_*"))):
        for f in glob.glob(os.path.join(trial_dir, "*", "engram_state.csv")):
            frames.append(pd.read_csv(f))
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


# ── statistics ───────────────────────────────────────────────────────────────

def cohens_d(a: np.ndarray, b: np.ndarray) -> float:
    """Pooled Cohen's d."""
    if len(a) < 2 or len(b) < 2:
        return float("nan")
    pooled_std = math.sqrt(((len(a) - 1) * np.var(a, ddof=1) +
                            (len(b) - 1) * np.var(b, ddof=1)) /
                           (len(a) + len(b) - 2))
    return (np.mean(a) - np.mean(b)) / pooled_std if pooled_std > 0 else float("nan")


def pairwise_mwu_vs_baseline(groups: dict, baseline_key: str, alpha: float = 0.05):
    """Mann-Whitney U test for each group vs. baseline; Bonferroni correction."""
    baseline = groups[baseline_key]
    comparisons = [(k, v) for k, v in groups.items() if k != baseline_key]
    n_comp = len(comparisons)
    alpha_adj = alpha / n_comp  # Bonferroni

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

def plot_lifetime_boxplot(groups: dict, labels: list, out_path: str):
    fig, ax = plt.subplots(figsize=(10, 5))
    data = [groups.get(k, np.array([])) for k, _ in SAMPLES]
    ax.boxplot(data, labels=labels, patch_artist=True,
               boxprops=dict(facecolor="#b3cde3"),
               medianprops=dict(color="black", linewidth=2))
    ax.set_ylabel("Lifetime (seconds)")
    ax.set_title("EXP-P7: Creature Lifetime by Configuration")
    ax.grid(axis="y", linestyle="--", alpha=0.5)
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_filter_usage(groups_actions: dict, out_path: str):
    """Stacked bar: fraction of decisions made by each filter type per sample."""
    fig, ax = plt.subplots(figsize=(10, 5))
    all_types = ["TARGET_DISTANCE", "AFFORDANCE", "MEMORY", "WORLD_MODEL", "RANDOM"]
    colors    = ["#1b9e77", "#d95f02", "#7570b3", "#e7298a", "#a6761d"]

    x = np.arange(len(SAMPLES))
    bottom = np.zeros(len(SAMPLES))

    for ftype, color in zip(all_types, colors):
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


def plot_engram_quality(groups_engrams: dict, out_path: str):
    """Box plot of |emotionDelta| × eligibility per sample (proxy for learning quality)."""
    fig, ax = plt.subplots(figsize=(10, 5))
    data = []
    labels = []
    for name, lbl in SAMPLES:
        df = groups_engrams.get(name, pd.DataFrame())
        if not df.empty and "emotion_delta" in df.columns and "eligibility" in df.columns:
            quality = (df["emotion_delta"].abs() * df["eligibility"]).dropna().values
        else:
            quality = np.array([])
        data.append(quality)
        labels.append(lbl)

    ax.boxplot(data, labels=labels, patch_artist=True,
               boxprops=dict(facecolor="#ccebc5"),
               medianprops=dict(color="black", linewidth=2))
    ax.set_ylabel("|emotionDelta| × eligibility")
    ax.set_title("EXP-P7: Engram Quality (|Δemotion| × eligibility)")
    ax.grid(axis="y", linestyle="--", alpha=0.5)
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


# ── report ───────────────────────────────────────────────────────────────────

def build_report(groups: dict, mwu_results: list, kw_H: float, kw_p: float,
                 alpha_bonf: float, data_dir: str) -> str:
    lines = []

    def h(level, text):
        lines.append("#" * level + " " + text)

    h(1, "EXP-P7: Memory Filter vs. World Model")
    lines.append("")
    lines.append("## Purpose")
    lines.append("")
    lines.append(textwrap.dedent("""\
        Determine whether the JEPA neural world model adds measurable value over a
        symbolic memory-based action filter (Mapa 2009) for improving creature lifetime
        and action quality in the DL2L artificial-life simulator.
    """))

    lines.append("## Assumptions")
    lines.append("")
    lines.append(textwrap.dedent("""\
        - Creature lifetime (wall-clock seconds) is a valid proxy for survival fitness.
        - All samples use identical world configuration (1 holder, 10 creatures, 855 apples,
          8000×6000 world, reposition enabled).
        - Sample P7-1 (baseline) provides the null distribution for hypothesis testing.
        - JEPA training data collected in P7-0 (same configuration, 10 trials) is
          sufficient to train a meaningful world model.
        - Significance level α=0.05; Bonferroni-corrected for 4 pairwise comparisons.
    """))

    lines.append("## Hypotheses")
    lines.append("")
    lines.append(textwrap.dedent("""\
        **H1**: Memory filter (P7-2) significantly improves lifetime vs. baseline (P7-1).
        **H2**: Mapa consolidation (P7-3) further improves lifetime vs. memory filter alone (P7-2).
        **H3**: JEPA filter (P7-4) significantly improves lifetime vs. baseline (P7-1).
        **H4**: JEPA consolidation (P7-5) further improves lifetime vs. JEPA alone (P7-4).
        **H5**: The best JEPA variant outperforms the best memory-filter variant.
    """))

    lines.append("## Results")
    lines.append("")

    # Summary table
    lines.append("### Lifetime summary")
    lines.append("")
    lines.append("| Configuration | n | Median (s) | Mean (s) | Std (s) |")
    lines.append("|---------------|---|-----------|---------|--------|")
    for name, lbl in SAMPLES:
        v = groups.get(name, np.array([]))
        lbl_clean = lbl.replace("\n", " ")
        if len(v) > 0:
            lines.append(f"| {lbl_clean} | {len(v)} | {np.median(v):.1f} | {np.mean(v):.1f} | {np.std(v, ddof=1):.1f} |")
        else:
            lines.append(f"| {lbl_clean} | 0 | — | — | — |")
    lines.append("")

    # Kruskal-Wallis
    lines.append("### Kruskal-Wallis test (all groups)")
    lines.append("")
    sig = "significant" if kw_p < 0.05 else "not significant"
    lines.append(f"H = {kw_H:.3f}, p = {kw_p:.4f} → **{sig}** (α=0.05)")
    lines.append("")

    # Pairwise MWU
    lines.append("### Pairwise Mann-Whitney U vs. baseline (Bonferroni α={:.4f})".format(alpha_bonf))
    lines.append("")
    lines.append("| vs. Baseline | U | p_raw | p_adj | Significant | Cohen's d | Median diff (s) |")
    lines.append("|---|---|---|---|---|---|---|")
    for r in mwu_results:
        sig_mark = "✓" if r["significant"] else "✗"
        lines.append(
            f"| {r['group']} "
            f"| {r['U']:.0f} "
            f"| {r['p_raw']:.4f} "
            f"| {r['p_adj']:.4f} "
            f"| {sig_mark} "
            f"| {r['d']:+.3f} "
            f"| {r['median_diff']:+.1f} |"
        )
    lines.append("")

    lines.append("## Analysis")
    lines.append("")
    lines.append("### Figures")
    lines.append("")
    lines.append("![Lifetime boxplot](../figures/exp_p7/lifetime_boxplot.png)")
    lines.append("")
    lines.append("![Filter usage](../figures/exp_p7/filter_usage.png)")
    lines.append("")
    lines.append("![Engram quality](../figures/exp_p7/engram_quality.png)")
    lines.append("")
    lines.append("### Interpretation")
    lines.append("")

    # Auto-generate interpretation from results
    sig_groups = [r for r in mwu_results if r["significant"]]
    if not sig_groups:
        lines.append(
            "No configuration produced a statistically significant improvement over the "
            "baseline after Bonferroni correction. This may indicate insufficient trial "
            "count, high variance in creature lifetimes, or that none of the tested "
            "mechanisms reliably extends lifetime at this effect size."
        )
    else:
        lines.append("Significant improvements over baseline:")
        for r in sig_groups:
            direction = "increased" if r["median_diff"] > 0 else "decreased"
            lines.append(
                f"- **{r['group']}**: median lifetime {direction} by {abs(r['median_diff']):.1f}s "
                f"(d={r['d']:+.3f}, p_adj={r['p_adj']:.4f})."
            )
        lines.append("")

    lines.append("## Conclusions")
    lines.append("")
    lines.append("_To be completed after experiments are run._")
    lines.append("")

    return "\n".join(lines)


# ── main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wd",           required=True, help="Validation data root (data/exp_p7/)")
    parser.add_argument("--baseline-wd",  default=None,  help="Optional P7-0 training data root")
    parser.add_argument("--alpha",        type=float, default=0.05)
    args = parser.parse_args()

    # Load data
    groups         = {}
    groups_actions = {}
    groups_engrams = {}

    for name, _ in SAMPLES:
        sample_dir = os.path.join(args.wd, name)
        groups[name]         = load_lifetimes(sample_dir)
        groups_actions[name] = load_actions(sample_dir)
        groups_engrams[name] = load_engrams(sample_dir)
        n = len(groups[name])
        print(f"  {name}: {n} lifetime observations")

    # Kruskal-Wallis
    valid = [v for v in groups.values() if len(v) >= 2]
    if len(valid) >= 2:
        kw_H, kw_p = stats.kruskal(*valid)
    else:
        kw_H, kw_p = float("nan"), float("nan")
    print(f"\nKruskal-Wallis: H={kw_H:.3f}, p={kw_p:.4f}")

    # Pairwise MWU vs. baseline
    baseline_key = "p7_1_baseline"
    mwu_results, alpha_bonf = pairwise_mwu_vs_baseline(groups, baseline_key, args.alpha)
    for r in mwu_results:
        sig = "SIG" if r["significant"] else "n.s."
        print(f"  MWU {r['group']:35s} U={r['U']:8.0f}  p_adj={r['p_adj']:.4f}  d={r['d']:+.3f}  [{sig}]")

    # Figures
    short_labels = [lbl for _, lbl in SAMPLES]
    plot_lifetime_boxplot(groups, short_labels,
                          os.path.join(FIG_DIR, "lifetime_boxplot.png"))
    plot_filter_usage(groups_actions,
                      os.path.join(FIG_DIR, "filter_usage.png"))
    plot_engram_quality(groups_engrams,
                        os.path.join(FIG_DIR, "engram_quality.png"))
    print(f"\nFigures written to {FIG_DIR}/")

    # Report
    report = build_report(groups, mwu_results, kw_H, kw_p, alpha_bonf, args.wd)
    out = os.path.join(RPT_DIR, "EXP_P7_MEMORY_FILTER_VS_WORLD_MODEL.md")
    with open(out, "w") as f:
        f.write(report)
    print(f"Report written to {out}")


if __name__ == "__main__":
    main()
