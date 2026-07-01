#!/usr/bin/env python3
"""
EXP-P7 power analysis — compute required sample size from P7-0 training data.

Uses creature lifetime (seconds) as the primary metric.
Power parameters: α=0.05, power=0.80 (β=0.20), two-tailed.
Effect size target: smallest Cohen's d detectable from the P7-0 distribution.

Outputs a single integer (number of trials) to stdout so the runner script can
capture it: VAL_TRIALS=$(python3 analysis/exp_p7_sample_size.py --wd ...)
"""

import argparse
import glob
import math
import os
import sys

import numpy as np
import pandas as pd


def load_lifetimes_from_trials(wd: str) -> np.ndarray:
    """Load all lifetime values across all trial_* subdirectories."""
    values = []
    for trial_dir in sorted(glob.glob(os.path.join(wd, "trial_*"))):
        lt_file = os.path.join(trial_dir, "lifetimes.csv")
        if os.path.exists(lt_file):
            df = pd.read_csv(lt_file)
            if "lifetime" in df.columns:
                values.extend(df["lifetime"].dropna().tolist())
    return np.array(values)


def required_sample_size(mu: float, sigma: float,
                         alpha: float = 0.05, power: float = 0.80,
                         min_effect_fraction: float = 0.20) -> int:
    """
    Two-sample t-test sample size per group.

    We want to detect a difference of min_effect_fraction * mu (20% of mean).
    Returns n per group (= number of trials, since each trial gives ~10 lifetime obs).
    """
    if sigma <= 0:
        return 10  # fallback

    delta = min_effect_fraction * mu
    d = delta / sigma  # Cohen's d

    # Approximate formula: n = (z_alpha/2 + z_beta)^2 * 2 / d^2
    from scipy.stats import norm
    z_alpha = norm.ppf(1 - alpha / 2)
    z_beta  = norm.ppf(power)

    n_per_group = math.ceil(2 * ((z_alpha + z_beta) ** 2) / (d ** 2))
    return max(n_per_group, 5)  # floor at 5 trials


def main():
    parser = argparse.ArgumentParser(description="Compute EXP-P7 validation trial count")
    parser.add_argument("--wd",          required=True, help="P7-0 raw data directory")
    parser.add_argument("--alpha",       type=float, default=0.05)
    parser.add_argument("--power",       type=float, default=0.80)
    parser.add_argument("--effect",      type=float, default=0.20,
                        help="Minimum detectable effect as fraction of mean lifetime")
    args = parser.parse_args()

    lifetimes = load_lifetimes_from_trials(args.wd)

    if len(lifetimes) < 5:
        print(10, flush=True)
        sys.exit(0)

    mu    = float(np.mean(lifetimes))
    sigma = float(np.std(lifetimes, ddof=1))

    n = required_sample_size(mu, sigma, args.alpha, args.power, args.effect)

    msg = (f"# P7-0 lifetime: n={len(lifetimes)}, mean={mu:.1f}s, std={sigma:.1f}s, "
           f"Cohen's d={args.effect * mu / sigma:.3f}, required trials={n}")
    print(msg, file=sys.stderr)
    print(n, flush=True)


if __name__ == "__main__":
    main()
