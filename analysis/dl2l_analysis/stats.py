"""Statistics helpers shared across experiment analyses.

Extracted verbatim from the duplicated cond_stats/kruskal_test functions in
analysis/exp_rotten_fruit_v1.py (L195-215) and
analysis/exp_20260709_memory_vs_wm_v1.py (L183-206).
"""

from __future__ import annotations

import numpy as np
from scipy import stats as scipy_stats


def cond_stats(series_by_cond: dict, label: str = "", cfg=None) -> None:
    """Print mean +/- std per condition for a dict {cond_key: values_array}.

    If cfg (ExperimentAnalysis) is given, conditions are printed in cfg's
    order with cfg's labels; otherwise iterates series_by_cond as given.
    """
    print(f"\n  {label}")
    items = (
        [(c.key, c.label) for c in cfg.conditions] if cfg is not None
        else [(k, k) for k in series_by_cond]
    )
    for ck, cl in items:
        vals = np.asarray(series_by_cond.get(ck, []), dtype=float)
        vals = vals[~np.isnan(vals)]
        if len(vals):
            print(f"    {cl:<22s}  {np.mean(vals):8.2f} ± {np.std(vals):6.2f}  (n={len(vals)})")


def kruskal_test(groups: list, labels: list) -> None:
    """Kruskal-Wallis across all groups, then pairwise Mann-Whitney U with
    Bonferroni-corrected alpha. Prints results; returns nothing (matches the
    original scripts, which only ever used this for its printed output)."""
    clean = [
        (np.asarray(g, dtype=float)[~np.isnan(np.asarray(g, dtype=float))], l)
        for g, l in zip(groups, labels)
    ]
    clean = [(g, l) for g, l in clean if len(g) > 0]
    if len(clean) < 2:
        return
    stat, p = scipy_stats.kruskal(*[g for g, _ in clean])
    print(f"    Kruskal-Wallis: H={stat:.3f}, p={p:.4f}")
    pairs = [(i, j) for i in range(len(clean)) for j in range(i + 1, len(clean))]
    alpha = 0.05 / len(pairs)
    for i, j in pairs:
        _, pw = scipy_stats.mannwhitneyu(clean[i][0], clean[j][0], alternative="two-sided")
        sig = "***" if pw < alpha else ("*" if pw < 0.05 else "ns")
        print(f"      {clean[i][1]} vs {clean[j][1]}: p={pw:.4f} {sig}")
