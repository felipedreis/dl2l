"""Analysis: p97_feeding_fix — does feeding persist through life after the #96 fix?

Protocol: docs/experiments/p97_feeding_fix_recipe.md. Outcome per creature is D, the number of
life deciles (of 10) containing at least one EAT. The fixed arm is compared with the historical,
unseeded, pre-fix p84 `legacy_nomem` data (a historical control, not a concurrent one).

    PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p97_feeding_fix
"""
from __future__ import annotations

import glob

import numpy as np
import pandas as pd
from scipy import stats

from analysis.dl2l_analysis.config import REPO_ROOT, ExperimentAnalysis
from analysis.dl2l_analysis.figures import plt, save
from analysis.dl2l_analysis.loading import load_all, num
from analysis.dl2l_analysis.report import ReportBuilder

BASELINE = REPO_ROOT / "ml" / "data_p84_behaviour_parity" / "legacy_nomem"
FIXED, OLD = "#2b5eb8", "#bdbdbd"
THRESH, LOWER_BOUND = 9, 0.6


def baseline(table):
    parts = []
    for f in sorted(glob.glob(f"{BASELINE}/trial_*/{table}.parquet")):
        df = pd.read_parquet(f)
        df["trial"] = int(f.split("trial_")[1].split("/")[0])
        parts.append(df)
    return pd.concat(parts, ignore_index=True)


def spread_by_creature(creatures, body, lo=0.4):
    """Median-able per-creature position spread (std of final_x/final_y, hypot) over life fraction >= lo."""
    cr = creatures.drop_duplicates(["trial", "creature_key"]).set_index(["trial", "creature_key"])
    b = body.join(cr[["born_time", "observed_s"]], on=["trial", "creature_key"])
    b = b[((b.time - b.born_time) / 1000 / b.observed_s) >= lo]
    g = b.groupby(["trial", "creature_key"])
    return np.hypot(g.final_x.std(), g.final_y.std())


def v1_arm():
    """The first fixed run (arc + contact range only), when this is the v2 analysis."""
    d = REPO_ROOT / "ml" / "data_p97_feeding_fix" / "legacy_nomem"
    parts = {}
    for t in ("creatures", "mouth_interactions", "body_states"):
        fs = sorted(glob.glob(f"{d}/trial_*/{t}.parquet"))
        parts[t] = pd.concat(pd.read_parquet(f).assign(trial=int(f.split("trial_")[1].split("/")[0])) for f in fs)
    return parts


def per_creature(creatures, mouth):
    creatures = creatures.drop_duplicates(["trial", "creature_key"]).set_index(["trial", "creature_key"])
    eats = mouth[mouth.interaction_type == "EAT"] if "interaction_type" in mouth else mouth
    eats = eats.join(creatures[["born_time", "observed_s"]], on=["trial", "creature_key"])
    frac = (num(eats.time) - eats.born_time) / 1000 / eats.observed_s
    eats = eats.assign(dec=(frac.clip(0, 0.999) * 10).astype(int))
    D = (eats.groupby(["trial", "creature_key"]).dec.nunique()
         .reindex(creatures.index, fill_value=0).rename("D").reset_index())
    return D, eats, creatures


def cliffs_delta(a, b):
    a, b = np.asarray(a), np.asarray(b)
    return (np.sum(a[:, None] > b[None, :]) - np.sum(a[:, None] < b[None, :])) / (len(a) * len(b))


def cluster_ci(D, thresh, level=0.95, boot=10000, seed=1):
    """Trial-level bootstrap CI for P[D >= thresh]: resamples trials, keeps creatures together."""
    rng = np.random.default_rng(seed)
    g = [(grp.D >= thresh).values for _, grp in D.groupby("trial")]
    ps = [np.concatenate([g[i] for i in rng.integers(0, len(g), len(g))]).mean() for _ in range(boot)]
    a = (1 - level) / 2
    return float(np.mean(np.concatenate(g))), float(np.quantile(ps, a)), float(np.quantile(ps, 1 - a))


def run(cfg: ExperimentAnalysis) -> None:
    creatures, mouth = load_all(cfg, "creatures.parquet"), load_all(cfg, "mouth_interactions.parquet")
    D, eats, cr = per_creature(creatures, mouth)
    D0, eats0, cr0 = per_creature(baseline("creatures"), baseline("mouth_interactions"))

    p, lo, hi = cluster_ci(D, THRESH)
    u = stats.mannwhitneyu(D.D, D0.D, alternative="greater")
    delta = cliffs_delta(D.D, D0.D)
    med = D.D.median()
    h1 = lo > LOWER_BOUND and med >= THRESH
    rate = lambda e, c: np.bincount(e.dec, minlength=10) / len(c)  # EATs per creature per decile
    print(f"fixed D mean {D.D.mean():.2f} median {med}  P[D>={THRESH}] {p:.2f} CI [{lo:.2f}, {hi:.2f}]")
    print(f"vs baseline D mean {D0.D.mean():.2f}: U p={u.pvalue:.2g}, Cliff's delta {delta:.2f}")

    extra = ""
    if cfg.name.endswith("_v2") and (REPO_ROOT / "ml" / "data_p97_feeding_fix").exists():
        v1 = v1_arm()
        D1, eats1, cr1 = per_creature(v1["creatures"], v1["mouth_interactions"])
        s2 = spread_by_creature(creatures, load_all(cfg, "body_states.parquet"))
        s1 = spread_by_creature(v1["creatures"], v1["body_states"])
        h4 = s2.median() > 3 * s1.median()
        tm2, tm1 = D.groupby("trial").D.mean(), D1.groupby("trial").D.mean().reindex(D.groupby("trial").D.mean().index)
        w = stats.wilcoxon(tm2, tm1, alternative="greater")
        extra = (f" v1 (arc+contact only): mean D {D1.D.mean():.2f}. Late-life position spread (median std, px): "
                 f"v1 {s1.median():.1f}, v2 {s2.median():.1f}; **H4 {'holds' if h4 else 'does not hold'}** (needs >3x). "
                 f"H5 paired by seed, Wilcoxon on trial means: p = {w.pvalue:.3g}.")
        print(extra)

    fig, ax = plt.subplots(1, 2, figsize=(11, 4))
    x = np.arange(10)
    ax[0].bar(x - 0.2, rate(eats0, cr0), 0.4, color=OLD, label=f"pre-fix p84 (n={len(D0)})")
    ax[0].bar(x + 0.2, rate(eats, cr), 0.4, color=FIXED, label=f"fixed (n={len(D)})")
    ax[0].set(xlabel="life decile", ylabel="EATs per creature", title="Feeding over life")
    ax[0].legend()
    bins = np.arange(-0.5, 11.5)
    ax[1].hist(D0.D, bins, color=OLD, alpha=0.8, label="pre-fix p84")
    ax[1].hist(D.D, bins, color=FIXED, alpha=0.7, label="fixed")
    ax[1].set(xlabel="D = life deciles with an EAT", ylabel="creatures", title="Per-creature D")
    ax[1].legend()
    fig.tight_layout()
    save(fig, "feeding_over_life.png", cfg)

    rb = ReportBuilder(cfg, "Report: feeding at speed (#96) and seeded runs (#95)")
    rb.purpose("Check that the mouth-arc and EAT-affordance fixes keep creatures feeding through "
               "life in the legacy configuration. Protocol: `docs/experiments/p97_feeding_fix_recipe.md`.")
    rb.assumptions("Historical control (unseeded, pre-fix image); creatures within a trial are "
                   "clustered, so intervals resample trials; the arc fix also changes perception.")
    rb.hypotheses(f"H1: P[D >= {THRESH}] has a 95% lower bound > {LOWER_BOUND} and median D >= {THRESH}. "
                  "H2 (descriptive): D exceeds the pre-fix baseline.")
    rb.result("Feeding over life", f"Fixed: mean D {D.D.mean():.2f}, median {med}, "
              f"P[D>={THRESH}] = {p:.2f} (95% trial-bootstrap CI {lo:.2f}-{hi:.2f}). "
              f"Pre-fix: mean D {D0.D.mean():.2f}. Mann-Whitney p = {u.pvalue:.2g}, "
              f"Cliff's delta {delta:.2f}. **H1 {'holds' if h1 else 'does not hold'}.**" + extra,
              "feeding_over_life.png")
    rb.analysis("See the written report in docs/reports.")
    rb.write()
