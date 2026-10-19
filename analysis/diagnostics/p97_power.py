"""Sample size for the #97 feeding-fix check, from the p84 `legacy_nomem` baseline (2026-10-17).

Outcome per creature: D = number of life deciles (of 10) containing at least one EAT. The
broken baseline loses its feeding at ~40% of life, so D is small; "feeding sustained across
life" (#96's criterion) means D close to 10.

Creatures inside a trial share a world, so they are clustered: the effective sample is
creatures / (1 + (m - 1) * ICC). ICC is estimated from the baseline by one-way ANOVA.

    python3 analysis/diagnostics/p97_power.py ml/data_p84_behaviour_parity/legacy_nomem
"""
import glob
import sys
from math import ceil

import numpy as np
import pandas as pd
from scipy import stats

ROOT = sys.argv[1] if len(sys.argv) > 1 else "ml/data_p84_behaviour_parity/legacy_nomem"
ALPHA, POWER = 0.05, 0.90


def load(table):
    parts = []
    for f in sorted(glob.glob(f"{ROOT}/trial_*/{table}.parquet")):
        parts.append(pd.read_parquet(f))
    return pd.concat(parts, ignore_index=True)


creatures = load("creatures").drop_duplicates(["trial", "creature_key"]).set_index(["trial", "creature_key"])
eats = load("mouth_interactions")
eats = eats[eats.interaction_type == "EAT"] if "interaction_type" in eats else eats
eats = eats.join(creatures[["born_time", "observed_s"]], on=["trial", "creature_key"])
frac = (eats.time - eats.born_time) / 1000 / eats.observed_s
eats["dec"] = (frac.clip(0, 0.999) * 10).astype(int)

D = (eats.groupby(["trial", "creature_key"]).dec.nunique()
     .reindex(creatures.index, fill_value=0).rename("D").reset_index())

n_trials, m = D.trial.nunique(), int(D.groupby("trial").size().median())
mean, sd = D.D.mean(), D.D.std(ddof=1)

# One-way ANOVA ICC (balanced: m creatures per trial).
groups = [g.D.values for _, g in D.groupby("trial")]
msb = m * np.var([g.mean() for g in groups], ddof=1)
msw = np.mean([np.var(g, ddof=1) for g in groups])
icc = max(0.0, (msb - msw) / (msb + (m - 1) * msw))
de = 1 + (m - 1) * icc

print(f"baseline: {len(D)} creatures in {n_trials} trials of {m}")
print(f"D (deciles with an EAT): mean {mean:.2f}  SD {sd:.2f}  ICC {icc:.3f}  design effect {de:.2f}")
print("D distribution:", D.D.value_counts().sort_index().to_dict())

za, zb = stats.norm.ppf(1 - ALPHA / 2), stats.norm.ppf(POWER)
print(f"\nTwo-arm comparison (fix vs baseline), alpha={ALPHA}, power={POWER}, "
      f"SD={sd:.2f} assumed equal, {m} creatures per trial")
print(f"{'fixed arm mean D':>18} {'delta':>6} {'d':>6} {'creatures/arm':>14} {'trials/arm':>11}")
for target in (9.5, 9.0, 8.0, 7.0, 6.5):
    delta = target - mean
    n = ceil(2 * ((za + zb) * sd / delta) ** 2 * de)
    print(f"{target:>18.1f} {delta:>6.2f} {delta / sd:>6.2f} {n:>14d} {ceil(n / m):>11d}")

# Precision for the absolute claim "every decile fed": fraction of creatures with D == 10.
print("\nAbsolute claim (P[D >= 9] among fixed-arm creatures), half-width of the 95% CI "
      "at p=0.8, cluster-adjusted:")
for trials in (4, 6, 8, 12, 16):
    n = trials * m
    hw = 1.96 * np.sqrt(0.8 * 0.2 / n * de)
    print(f"  {trials:>2} trials ({n:>3} creatures): +/- {hw:.3f}")
