"""Sample size for the A1 / A2 campaigns (#107), from the pilot's paired-by-seed differences.

Reads docs/reports/figures/<experiment>/creature_metrics.csv (written by analysis/experiments/arbiter_ladder.py).
Unit = trial (seed); n below is the number of paired trials per arm. Paired t-test power by the non-central t.

    python3.13 analysis/diagnostics/p107_sample_size.py [--experiment p107_pilot]

Caveat built into every number: the SD of 3 paired differences has 2 degrees of freedom, so the point estimate is
unreliable; the table is sized on the point estimate AND on the one-sided 80% upper bound (2.12 x SD).
"""
from __future__ import annotations

import argparse
import math
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats

REPO = Path(__file__).resolve().parents[2]
UPPER = 2.12
ALPHA, POWER = 0.05, 0.80


def _finite_power(p, ncp):
    """scipy's non-central t returns NaN for very large ncp; there the power is 1 (checked against simulation)."""
    return 1.0 if (p is None or not np.isfinite(p)) and ncp > 8 else float(p)


def power_superiority(n, delta, sd, alpha=ALPHA):
    df = n - 1
    tcrit = stats.t.ppf(1 - alpha / 2, df)
    ncp = abs(delta) * math.sqrt(n) / sd
    return _finite_power(stats.nct.sf(tcrit, df, ncp) + stats.nct.cdf(-tcrit, df, ncp), ncp)


def power_noninferiority(n, margin, true_diff, sd, alpha=ALPHA):
    """One-sided: H0 diff >= margin (worse by the margin or more); lower is better. True diff true_diff < margin."""
    df = n - 1
    tcrit = stats.t.ppf(1 - alpha, df)
    ncp = (margin - true_diff) * math.sqrt(n) / sd
    return _finite_power(stats.nct.sf(tcrit, df, ncp), ncp)


def smallest_n(power_fn, target=POWER, n_max=500):
    for n in range(2, n_max + 1):
        if power_fn(n) >= target:
            return n
    return float("inf")


def paired(cm: pd.DataFrame, metric: str, later: str, earlier: str):
    tm = cm.groupby(["trial", "condition"])[metric].mean().unstack("condition")
    if later not in tm or earlier not in tm:
        return None
    d = (tm[later] - tm[earlier]).dropna()
    return d.to_numpy() if len(d) >= 2 else None


def fmt_n(n):
    return ">500" if n == float("inf") else str(n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--experiment", default="p107_pilot")
    a = ap.parse_args()
    cm = pd.read_csv(REPO / "docs" / "reports" / "figures" / a.experiment / "creature_metrics.csv")
    a1_mean = float(cm[cm.condition == "a1"]["dH_life"].mean())
    out = [f"# Sample size for the A1 / A2 campaigns (#107), from `{a.experiment}`", "",
           f"Paired t-test on trial-level differences (unit = seed), alpha {ALPHA}, power {POWER}, non-central t. "
           f"`n` = paired trials per arm. SD has {len(cm.trial.unique()) - 1} degrees of freedom, so each row is sized on the point SD and on the "
           f"one-sided 80% upper bound ({UPPER} x SD). A1 pilot mean d(H) over life = {a1_mean:.3f}.", ""]

    # 1. superiority contrasts
    out += ["## Superiority (two-sided), true difference = the pilot's observed mean", "",
            "| contrast | measure | per-seed differences | mean | SD | n (point SD) | n (upper-bound SD) |", "|---|---|---|---|---|---|---|"]
    for later, earlier in [("a1", "a0"), ("a2", "a1"), ("a2", "a0")]:
        for metric, label in [("dH_window", "d(H), first 180 s"), ("dH_life", "d(H) over life"), ("eat_rg_per_min", "RED+GREEN EATs/min (lower bound)"),
                              ("feeding_deciles", "D (life deciles with an EAT)")]:
            d = paired(cm, metric, later, earlier)
            if d is None:
                continue
            sd, mean = float(d.std(ddof=1)), float(d.mean())
            if sd == 0 or mean == 0:
                n1 = n2 = "n/a (SD or mean is 0)"
            else:
                n1 = fmt_n(smallest_n(lambda n: power_superiority(n, mean, sd)))
                n2 = fmt_n(smallest_n(lambda n: power_superiority(n, mean, UPPER * sd)))
            out.append(f"| {later.upper()} - {earlier.upper()} | {label} | {np.round(d, 3).tolist()} | {mean:.3f} | {sd:.3f} | {n1} | {n2} |")

    # 2. non-inferiority A2 vs A1 on d(H) over life
    d = paired(cm, "dH_life", "a2", "a1")
    out += ["", "## Non-inferiority of A2 against A1 on d(H) over life (one-sided; lower is better)", "",
            "Margins are a decision for #107; shown: 0.06 (draft section 7.1), and 10% / 20% of the A1 pilot mean. "
            "'True diff = 0' is the conservative design assumption; 'true diff = observed' uses the pilot's mean difference.", "",
            "| margin M | true diff | n (point SD) | n (upper-bound SD) |", "|---|---|---|---|"]
    if d is not None:
        sd, mean = float(d.std(ddof=1)), float(d.mean())
        for m in [0.06, 0.10 * a1_mean, 0.20 * a1_mean]:
            for tdiff, tl in [(0.0, "0"), (mean, f"{mean:.3f} (observed)")]:
                n1 = fmt_n(smallest_n(lambda n: power_noninferiority(n, m, tdiff, sd)))
                n2 = fmt_n(smallest_n(lambda n: power_noninferiority(n, m, tdiff, UPPER * sd)))
                out.append(f"| {m:.3f} | {tl} | {n1} | {n2} |")

    # 3. creature-level cross-check
    out += ["", "## Creature-level cross-check (what more creatures per trial would buy)", "",
            "ICC(1) of d(H) over life across trials within an arm, and the per-group sample size at Cohen's d for A2 vs A1 with the design effect "
            "for 5 creatures per trial (`dl2l_analysis.stats.required_n`).", ""]
    import sys
    sys.path.insert(0, str(REPO))
    from analysis.dl2l_analysis.stats import cohens_d, icc1, required_n
    rows = []
    for arm in ["a0", "a1", "a2", "a2u"]:
        g = cm[cm.condition == arm]
        if len(g):
            rows.append(f"- {arm.upper()}: ICC(1) {icc1([x['dH_life'].to_numpy() for _, x in g.groupby('trial')]):.3f}")
    out += rows
    a2v, a1v = cm[cm.condition == "a2"]["dH_life"], cm[cm.condition == "a1"]["dH_life"]
    icc = icc1([x["dH_life"].to_numpy() for _, x in cm[cm.condition == "a1"].groupby("trial")])
    dd = cohens_d(a1v, a2v)
    r = required_n(dd, icc=icc, cluster_size=5)
    out += ["", f"A2 vs A1: Cohen's d {dd:.1f} (A2's within-arm spread is tiny), ICC(A1) {icc:.3f}, design effect {r['design_effect']:.2f}; "
                f"the formula gives {r['n_clustered']:.1f} creatures per group, i.e. one trial of 5 already has far more than 80% power. "
                "Not informative at this effect size; the paired-trial table above is the one to use.",
            "", "## Reading", "",
            "- The pilot effects are large against the between-seed SD, so the paired-trial counts are small; the binding questions are the choice of margin, "
            "what A1 vs A2 is meant to show, and the untrained-table control (a result on its own, not a sizing input).",
            "- A0's focus-switch statistic cannot be sized: it is an approximation (the regulated emotion is not persisted). Survival is censored at the cap in "
            "A1 and A2 and is better treated as a guard (\"not lower\") than a powered outcome.",
            "- With 3 seeds the SD estimate is crude; if the campaign's first trials show a larger SD, resize."]
    text = "\n".join(out)
    (REPO / "docs" / "reports" / f"{a.experiment}_sample_size.md").write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
