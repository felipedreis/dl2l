"""Statistics helpers shared across experiment analyses.

Extracted verbatim from the duplicated cond_stats/kruskal_test functions in
analysis/exp_rotten_fruit_v1.py (L195-215) and
analysis/exp_20260709_memory_vs_wm_v1.py (L183-206).
"""

from __future__ import annotations

import math

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


def cliffs_delta(a, b) -> float:
    """Cliff's delta: P(b > a) - P(a > b), in [-1, 1].

    The effect size that belongs with Mann-Whitney — it is a monotone function of U, so it
    reports the same comparison the test does, rather than pairing a rank test with a
    mean-difference effect size like Cohen's d that assumes what the rank test avoids.
    Positive means b tends to exceed a. |d| >= .147 small, .33 medium, .474 large
    (Romano et al. 2006).
    """
    a = np.asarray(a, dtype=float); b = np.asarray(b, dtype=float)
    a = a[~np.isnan(a)]; b = b[~np.isnan(b)]
    if len(a) == 0 or len(b) == 0:
        return float("nan")
    # Via the Mann-Whitney U statistic: delta = 2U/(n*m) - 1.
    u = scipy_stats.mannwhitneyu(b, a, alternative="two-sided").statistic
    return float(2.0 * u / (len(a) * len(b)) - 1.0)


def icc1(values_by_cluster: list) -> float:
    """One-way random-effects ICC(1) — the between-cluster share of total variance.

    Creatures in one trial share a world, a food supply and an RNG stream, so they are not
    independent samples. This quantifies how much, and feeds `design_effect`.
    """
    groups = [np.asarray(g, dtype=float) for g in values_by_cluster]
    groups = [g[~np.isnan(g)] for g in groups]
    groups = [g for g in groups if len(g) > 1]
    if len(groups) < 2:
        return 0.0
    k = len(groups)
    n_total = sum(len(g) for g in groups)
    n_bar = n_total / k
    grand = np.concatenate(groups).mean()
    msb = sum(len(g) * (g.mean() - grand) ** 2 for g in groups) / (k - 1)
    msw = sum(((g - g.mean()) ** 2).sum() for g in groups) / (n_total - k)
    if msw == 0:
        return 0.0
    return float(np.clip((msb - msw) / (msb + (n_bar - 1) * msw), 0.0, 0.99))


def design_effect(icc: float, cluster_size: float) -> float:
    """1 + (m-1)*ICC — the factor by which clustering inflates the needed sample."""
    return 1.0 + (cluster_size - 1.0) * icc


def required_n(delta_or_d: float, alpha: float = 0.05, power: float = 0.80,
               icc: float = 0.0, cluster_size: float = 1.0,
               nonparametric_inflation: float = 1.15) -> dict:
    """Per-group sample size for a two-sample comparison at a given standardised effect.

    `delta_or_d` is Cohen's d (a standardised mean difference). The 1.15 factor is the
    usual conservative allowance for using a rank test instead of a t-test. Returns both
    the independent-sample figure and the clustered one, plus the implied cluster count.
    """
    if not np.isfinite(delta_or_d) or abs(delta_or_d) < 1e-9:
        return {"n_independent": float("inf"), "n_clustered": float("inf"),
                "clusters": float("inf"), "design_effect": float("nan")}
    z = scipy_stats.norm.ppf(1 - alpha / 2) + scipy_stats.norm.ppf(power)
    n_ind = 2 * (z ** 2) / (delta_or_d ** 2) * nonparametric_inflation
    deff = design_effect(icc, cluster_size)
    n_clu = n_ind * deff
    return {"n_independent": n_ind, "n_clustered": n_clu,
            "clusters": np.ceil(n_clu / cluster_size) if cluster_size > 0 else float("nan"),
            "design_effect": deff}


def cohens_d(a, b) -> float:
    """Pooled-SD standardised mean difference — used only to size the next run."""
    a = np.asarray(a, dtype=float); b = np.asarray(b, dtype=float)
    a = a[~np.isnan(a)]; b = b[~np.isnan(b)]
    if len(a) < 2 or len(b) < 2:
        return float("nan")
    sp = np.sqrt(((len(a) - 1) * a.std(ddof=1) ** 2 + (len(b) - 1) * b.std(ddof=1) ** 2)
                 / (len(a) + len(b) - 2))
    return float((b.mean() - a.mean()) / sp) if sp > 0 else float("nan")


def compare_arms(df, value: str, arm_a: str, arm_b: str, arm_col: str = "condition",
                 cluster_col: str = "trial", label: str = "") -> dict:
    """The standard p84 two-arm comparison, run at both levels of the design.

    Creature level is the headline (all the data); trial level is the sensitivity check
    (immune to clustering but low-powered). Agreement between them is what licenses
    reading the creature-level result — disagreement means the effect is riding on
    within-trial pseudo-replication and must be reported as inconclusive.
    """
    a = df[df[arm_col] == arm_a][value].astype(float)
    b = df[df[arm_col] == arm_b][value].astype(float)
    out = {"label": label or value, "arm_a": arm_a, "arm_b": arm_b,
           "n_a": len(a), "n_b": len(b)}
    if len(a) < 2 or len(b) < 2:
        out["verdict"] = "insufficient data"
        return out

    out["mean_a"], out["mean_b"] = float(a.mean()), float(b.mean())
    out["p_creature"] = float(scipy_stats.mannwhitneyu(a, b, alternative="two-sided").pvalue)
    out["cliffs_delta"] = cliffs_delta(a, b)
    out["cohens_d"] = cohens_d(a, b)
    out["icc"] = icc1([g[value].values for _, g in
                       df[df[arm_col] == arm_b].groupby(cluster_col)])

    tm = df[df[arm_col].isin([arm_a, arm_b])].groupby([arm_col, cluster_col])[value].mean()
    ta = tm.xs(arm_a, level=arm_col).values
    tb = tm.xs(arm_b, level=arm_col).values
    out["n_clusters"] = (len(ta), len(tb))
    if len(ta) >= 2 and len(tb) >= 2:
        out["p_trial"] = float(scipy_stats.mannwhitneyu(ta, tb, alternative="two-sided").pvalue)
        # Mann-Whitney on small clusters has a floor: with perfect separation the smallest
        # attainable two-sided p is 2 / C(n1+n2, n1) — 0.1 at 3v3, 0.029 at 4v4. If that
        # floor is above alpha the trial-level test CANNOT be significant no matter how
        # large the true effect, so calling the pair "clustering-sensitive" would blame
        # pseudo-replication for what is really just too few clusters. Say so instead.
        min_p = 2.0 / math.comb(len(ta) + len(tb), len(ta))
        out["min_attainable_p_trial"] = float(min_p)
        if min_p > 0.05:
            out["verdict"] = (f"cluster-level underpowered (min attainable p={min_p:.3f} "
                              f"at {len(ta)}v{len(tb)} clusters)")
        else:
            agree = (out["p_creature"] < 0.05) == (out["p_trial"] < 0.05)
            out["verdict"] = "consistent" if agree else "clustering-sensitive"
    else:
        out["p_trial"] = float("nan")
        out["verdict"] = "too few clusters for a sensitivity check"
    return out


def print_comparison(res: dict) -> None:
    if res.get("verdict") == "insufficient data":
        print(f"    {res['label']:<34s} insufficient data")
        return
    print(f"    {res['label']:<34s} {res['arm_a']} {res['mean_a']:.3f} "
          f"vs {res['arm_b']} {res['mean_b']:.3f}")
    print(f"      creature-level p={res['p_creature']:.4g} (n={res['n_a']}/{res['n_b']})   "
          f"Cliff's delta={res['cliffs_delta']:+.3f}")
    print(f"      trial-level    p={res['p_trial']:.4g} "
          f"(n={res['n_clusters'][0]}/{res['n_clusters'][1]})   "
          f"ICC={res['icc']:.3f}  ->  {res['verdict']}")


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


# ---------------------------------------------------------------------------
# Right-censored survival
# ---------------------------------------------------------------------------
#
# A creature still alive when the run hits maxRuntimeMinutes is CENSORED, not missing: we
# know it lived at least that long. Dropping it (which is what happens when lifetime_s is
# NULL and the caller calls .dropna()) throws that information away and biases the
# comparison against whichever arm survives best, because that arm loses the most rows.
# In the p84 v3 campaign it was worse than a bias — two arms had zero deaths, so the
# survival ratio had no denominator at all and P4 was simply uncomputable.
#
# These are hand-rolled rather than taken from `lifelines` to avoid adding a dependency for
# ~50 lines of standard estimator. Both are the textbook forms (Kalbfleisch & Prentice).


def kaplan_meier(times, events):
    """Kaplan-Meier survival curve for right-censored data.

    `times` is the observed duration per subject (lifetime if it died, follow-up if it did
    not) and `events` is 1 for a death and 0 for a censored observation. Returns
    (t, S) as arrays stepping down at each distinct death time.
    """
    t = np.asarray(times, dtype=float)
    e = np.asarray(events, dtype=float)
    keep = ~np.isnan(t)
    t, e = t[keep], e[keep]
    if len(t) == 0:
        return np.array([]), np.array([])

    order = np.argsort(t)
    t, e = t[order], e[order]

    death_times = np.unique(t[e == 1])
    surv, out_t, out_s = 1.0, [0.0], [1.0]
    for dt in death_times:
        at_risk = np.sum(t >= dt)          # censored subjects are at risk until they leave
        deaths = np.sum((t == dt) & (e == 1))
        if at_risk > 0:
            surv *= 1.0 - deaths / at_risk
        out_t.append(float(dt))
        out_s.append(surv)
    return np.array(out_t), np.array(out_s)


def km_median(times, events) -> float:
    """Median survival from the KM curve, or NaN if the curve never reaches 0.5.

    NaN is the honest answer, not a failure: it means over half the arm was still alive when
    observation stopped, so the median is beyond the horizon and no cap-dependent number
    should be quoted for it.
    """
    t, s = kaplan_meier(times, events)
    if len(t) == 0:
        return float("nan")
    below = np.where(s <= 0.5)[0]
    return float(t[below[0]]) if len(below) else float("nan")


def logrank_test(times_a, events_a, times_b, events_b) -> tuple:
    """Two-sample log-rank test. Returns (chi2, p, observed_a, expected_a).

    The right comparison for "does memory extend life?" when runs are capped: it uses every
    creature, censored ones included, and needs neither arm to be fully observed.
    """
    ta, ea = np.asarray(times_a, float), np.asarray(events_a, float)
    tb, eb = np.asarray(times_b, float), np.asarray(events_b, float)
    ta, ea = ta[~np.isnan(ta)], ea[~np.isnan(ta)]
    tb, eb = tb[~np.isnan(tb)], eb[~np.isnan(tb)]
    if len(ta) == 0 or len(tb) == 0:
        return float("nan"), float("nan"), 0.0, 0.0

    all_times = np.unique(np.concatenate([ta[ea == 1], tb[eb == 1]]))
    obs_a = exp_a = var = 0.0
    for t in all_times:
        n_a, n_b = np.sum(ta >= t), np.sum(tb >= t)
        n = n_a + n_b
        d_a = np.sum((ta == t) & (ea == 1))
        d_b = np.sum((tb == t) & (eb == 1))
        d = d_a + d_b
        if n < 2 or d == 0:
            continue
        obs_a += d_a
        exp_a += d * n_a / n
        # Hypergeometric variance of d_a under the null.
        var += d * (n_a / n) * (1 - n_a / n) * (n - d) / (n - 1)

    if var <= 0:
        return float("nan"), float("nan"), float(obs_a), float(exp_a)
    chi2 = (obs_a - exp_a) ** 2 / var
    p = float(scipy_stats.chi2.sf(chi2, df=1))
    return float(chi2), p, float(obs_a), float(exp_a)


def survival_comparison(df, arm_a: str, arm_b: str, arm_col: str = "condition",
                        time_col: str = "observed_s", event_col: str = "died") -> dict:
    """Censoring-aware replacement for a mean-lifetime comparison between two arms.

    Reports mortality rate (with Fisher's exact test — "did more of them die?" is a real
    question in its own right when a cap truncates the study), KM median survival per arm,
    and the log-rank test. `median_ratio` is NaN whenever either median is beyond the
    horizon; that is a statement about the cap, not a missing result.
    """
    a = df[df[arm_col] == arm_a]
    b = df[df[arm_col] == arm_b]
    if a.empty or b.empty:
        return {}

    ta, ea = a[time_col].values, a[event_col].astype(float).values
    tb, eb = b[time_col].values, b[event_col].astype(float).values

    deaths_a, deaths_b = int(np.nansum(ea)), int(np.nansum(eb))
    n_a, n_b = len(ea), len(eb)
    _, p_mort = scipy_stats.fisher_exact([[deaths_a, n_a - deaths_a],
                                          [deaths_b, n_b - deaths_b]])

    med_a, med_b = km_median(ta, ea), km_median(tb, eb)
    chi2, p_lr, _, _ = logrank_test(ta, ea, tb, eb)

    return {
        "arm_a": arm_a, "arm_b": arm_b,
        "n_a": n_a, "n_b": n_b,
        "deaths_a": deaths_a, "deaths_b": deaths_b,
        "mortality_a": deaths_a / n_a if n_a else float("nan"),
        "mortality_b": deaths_b / n_b if n_b else float("nan"),
        "p_mortality": float(p_mort),
        "km_median_a": med_a, "km_median_b": med_b,
        "median_ratio": (med_b / med_a) if (med_a and not math.isnan(med_a)
                                            and not math.isnan(med_b)) else float("nan"),
        "logrank_chi2": chi2, "p_logrank": p_lr,
    }


def print_survival(res: dict) -> None:
    if not res:
        return
    print(f"    {res['arm_a']} vs {res['arm_b']}")
    print(f"      mortality   {res['deaths_a']}/{res['n_a']} ({res['mortality_a']:.0%})"
          f"  vs {res['deaths_b']}/{res['n_b']} ({res['mortality_b']:.0%})"
          f"   Fisher p={res['p_mortality']:.4f}")
    med_a, med_b = res["km_median_a"], res["km_median_b"]
    fmt = lambda m: "beyond cap" if math.isnan(m) else f"{m:.0f}s"
    print(f"      KM median   {fmt(med_a)} vs {fmt(med_b)}"
          + (f"   ratio={res['median_ratio']:.2f}x"
             if not math.isnan(res["median_ratio"]) else "   ratio=n/a (censored)"))
    if not math.isnan(res["p_logrank"]):
        print(f"      log-rank    chi2={res['logrank_chi2']:.3f}  p={res['p_logrank']:.4f}")
