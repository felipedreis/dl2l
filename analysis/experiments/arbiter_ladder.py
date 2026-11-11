"""Analysis: arbiter_ladder — the A0 / A1 / A2 (..) arms of the arbiter ladder, paired by seed (#106).

Plan: docs/plans/issue-106-arbiter-ladder-analysis.md (measures: docs/plans/arbiter-ladder-pilot.md).
The unit of replication is the trial (creatures within a trial share a world, a food supply and a seed),
so every comparison is made on trial means, paired by trial index = seed index.

    PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p107_pilot

Descriptive: no hypothesis tests at 3 pairs. A new arm (A3, A5) needs its key in ARMS and, if it logs new
telemetry, a block in `arm_telemetry`.
"""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow.parquet as pq

from analysis.dl2l_analysis.config import ExperimentAnalysis
from analysis.dl2l_analysis.figures import plt, save
from analysis.dl2l_analysis.report import ReportBuilder
from analysis.dl2l_analysis.stats import kaplan_meier, survival_comparison

DRIVES = ["hunger", "sleep", "pain", "tedium"]
H_STAR = 0.18            # FocusTracker hStar / Constants.MIN_AROUSAL_LEVEL: the set point a deficit is measured from
WINDOW_S = 180.0         # common window for d(H): A0 creatures live about 4-8 minutes
UPPER_FACTOR = 2.12      # sqrt(2 / chi2_{0.20, df=2}): one-sided 80% upper bound factor for an SD from 3 pairs
ARMS = ["a0", "a1", "a2", "a3", "a5"]
# (later arm, earlier arm): difference = later - earlier. A3 against A2 is what episodic memory adds; A3 against A5 is the paper's main
# contrast (arbitration against the same two controllers in a fixed order).
PAIRS = [("a1", "a0"), ("a2", "a1"), ("a2", "a0"), ("a3", "a2"), ("a3", "a5")]
SURVIVAL_PAIRS = [("a0", "a1"), ("a1", "a2"), ("a2", "a3"), ("a3", "a5")]
OPERANT_PRIOR = (0.0, 1.0, 1.0)   # (Q, SE, sigma) of an unseen operant cell (OperantEstimator)
EARLY_LIFE = 0.2                  # recipe 5: the first 20% of a creature's life
MIN_CELL_UPDATES = 30             # recipe 5: cells with fewer updates are not in the calibration statistic
SOURCE_LABELS = ["SINGLE", "GREEDY", "THOMPSON", "EPSILON"]

# (column, label, higher-is-what) — the per-creature measures that get paired comparisons
CREATURE_METRICS = [
    ("dH_life", "d(H) over life"),
    ("dH_window", f"d(H), first {WINDOW_S:.0f} s"),
    ("eat_per_min", "EATs per minute"),
    ("eat_rg_per_min", "RED+GREEN EATs per minute"),
    ("feeding_deciles", "D: life deciles with an EAT"),
    ("observed_s", "observed time (s)"),
    ("died", "died (share)"),
    ("switch_argmax_per100", "focus transfers per 100 cycles (argmax approx.)"),
    ("open_argmax_per100", "focus opens per 100 cycles (argmax approx.)"),
    ("switch_tracker_per100", "focus transfers per 100 cycles (tracker)"),
    ("open_tracker_per100", "focus opens per 100 cycles (tracker)"),
    ("focused_share", "focused share of cycles (tracker)"),
    ("episodes", "episodes per creature"),
    ("early_food_share", f"food share of EATs, first {EARLY_LIFE:.0%} of life (A3 criterion)"),
]


# ---------------------------------------------------------------- pure functions (see test_arbiter_ladder)

def deficit(levels: np.ndarray, h_star: float = H_STAR) -> np.ndarray:
    """d(H) per row: Euclidean norm of the positive deviations from the set point. `levels` is rows x drives."""
    return np.sqrt((np.maximum(0.0, np.asarray(levels, float) - h_star) ** 2).sum(axis=1))


def argmax_labels(levels: np.ndarray, h_star: float = H_STAR) -> np.ndarray:
    """Index of the most urgent drive per row, -1 when no drive is above the set point."""
    u = np.maximum(0.0, np.asarray(levels, float) - h_star)
    lab = u.argmax(axis=1)
    lab[u.max(axis=1) <= 0.0] = -1
    return lab


def switch_counts(labels: np.ndarray) -> tuple[int, int]:
    """(transfers, opens) in one creature's time-ordered label series, -1 meaning no focus.
    A transfer is a change from one drive to another; an open is none -> drive. Closes are not counted."""
    lab = np.asarray(labels)
    if len(lab) < 2:
        return 0, 0
    prev, cur = lab[:-1], lab[1:]
    transfers = int(((prev >= 0) & (cur >= 0) & (prev != cur)).sum())
    opens = int(((prev < 0) & (cur >= 0)).sum())
    return transfers, opens


def paired_differences(trial_means: pd.DataFrame, later: str, earlier: str) -> dict | None:
    """Per-trial differences later - earlier of a (trial x arm) frame; None if either arm is missing."""
    if later not in trial_means or earlier not in trial_means:
        return None
    d = (trial_means[later] - trial_means[earlier]).dropna()
    if len(d) == 0:
        return None
    sd = float(d.std(ddof=1)) if len(d) > 1 else float("nan")
    return {"n": len(d), "diffs": d.round(4).tolist(), "mean": float(d.mean()), "sd": sd,
            "upper80_sd": UPPER_FACTOR * sd if len(d) == 3 else float("nan")}


def calibration_z(df: pd.DataFrame) -> pd.DataFrame:
    """Descriptive z of realised values against the executed action's estimate, by close type (#105 Q11)."""
    d = df.dropna(subset=["episode_id", "realised_value", "executed_q", "executed_se", "executed_sigma"]).copy()
    d["z"] = (d["realised_value"] - d["executed_q"]) / np.sqrt(d["executed_se"] ** 2 + d["executed_sigma"] ** 2)
    d = d[np.isfinite(d["z"])]
    g = d.groupby("close_type")["z"]
    return pd.DataFrame({"n": g.size(), "mean": g.mean(), "variance": g.var(), "rms": g.apply(lambda s: float(np.sqrt((s ** 2).mean())))})


def operant_updates_z(u: pd.DataFrame) -> pd.DataFrame:
    """z of every operant update against the cell's estimate *before* it (recipe 5): the previous row of the same
    (creature, object type, action) in write order, or the unseen-cell prior for its first update.
    `u` holds the `operant_updates` rows of one trial; the row's q / se / sigma are the cell after the update."""
    key = ["creature_key", "object_type", "action"]
    u = u.sort_values(key + ["seq"], kind="stable").copy()
    g = u.groupby(key)
    prior_q, prior_se, prior_sigma = OPERANT_PRIOR
    q0 = g["q"].shift(1).fillna(prior_q)
    se0 = g["se"].shift(1).fillna(prior_se)
    sigma0 = g["sigma"].shift(1).fillna(prior_sigma)
    u["z"] = (u["sample"] - q0) / np.sqrt(se0 ** 2 + sigma0 ** 2)
    return u


def operant_calibration(u: pd.DataFrame, min_updates: int = MIN_CELL_UPDATES) -> dict:
    """The per-trial operant calibration statistic of recipe 5. A cell is (object type, action) over the trial's creatures. The
    statistic is the variance of z in each cell with at least `min_updates` updates whose samples are not all equal, averaged over those
    cells weighted by their update counts. Cells with constant samples (EAT on a gray apple: always -1) are left out of it and reported
    apart, with the pooled variance of all z for comparison.
    Returns {"statistic", "cells" (DataFrame), "constant_cells" (DataFrame), "pooled_variance", "n_updates"}."""
    z = operant_updates_z(u)
    z = z[np.isfinite(z["z"])]
    cells = z.groupby(["object_type", "action"]).agg(n=("z", "size"), mean_z=("z", "mean"), variance=("z", "var"),
                                                     constant=("sample", lambda s: bool(s.nunique() == 1)))
    eligible = cells[(cells["n"] >= min_updates) & ~cells["constant"]]
    stat = float(np.average(eligible["variance"], weights=eligible["n"])) if len(eligible) else float("nan")
    return {"statistic": stat, "cells": eligible, "constant_cells": cells[(cells["n"] >= min_updates) & cells["constant"]],
            "pooled_variance": float(z["z"].var()) if len(z) > 1 else float("nan"), "n_updates": int(len(z))}


def episodic_z(d: pd.DataFrame) -> pd.DataFrame:
    """z of the realised value of every decision the episodic controller made, against its estimate of the executed action (#115).
    Only rows whose executed action was a candidate of the decision (CHOSEN or OTHER_CANDIDATE) carry the deciding controller's own estimate:
    a VALUE_OF row is estimated afresh by the cheapest controller, so it is left out. `d` is `arbitration_decisions`."""
    e = d[(d["deciding_controller"] == "EPISODIC") & d["executed_source"].isin(["CHOSEN", "OTHER_CANDIDATE"])]
    e = e.dropna(subset=["episode_id", "realised_value", "executed_q", "executed_se", "executed_sigma"]).copy()
    e["z"] = (e["realised_value"] - e["executed_q"]) / np.sqrt(e["executed_se"] ** 2 + e["executed_sigma"] ** 2)
    return e[np.isfinite(e["z"])]


def episodic_calibration(d: pd.DataFrame) -> dict:
    """Mean and variance of the episodic z, overall and by close type (a timed-out APPROACH is zeroed by the buffer, so it is shown apart)."""
    z = episodic_z(d)
    by = z.groupby("close_type")["z"].agg(n="size", mean="mean", variance="var")
    return {"n": int(len(z)), "mean": float(z["z"].mean()) if len(z) else float("nan"),
            "variance": float(z["z"].var()) if len(z) > 1 else float("nan"), "by_close_type": by}


def controller_share_by_decile(d: pd.DataFrame, controller: str = "EPISODIC") -> list[float]:
    """Share of decisions decided by `controller` in each decile of a creature's decision sequence (recipe 5: controller share over life)."""
    d = d.copy()
    d["rank"] = d.groupby(KEY).cumcount()
    d["n"] = d.groupby(KEY)["rank"].transform("size")
    d["decile"] = (d["rank"] / d["n"] * 10).astype(int).clip(upper=9)
    share = (d["deciding_controller"] == controller).groupby(d["decile"]).mean()
    return [round(float(share.get(i, float("nan"))), 4) for i in range(10)]


# ---------------------------------------------------------------- loading

def load(cfg: ExperimentAnalysis, fname: str, columns: list[str] | None = None) -> pd.DataFrame:
    """load_all with a column projection (drives and arbitration_decisions are large)."""
    frames = []
    for cond in cfg.cond_keys:
        base = cfg.data_dir_for(cond)
        for trial in cfg.trial_range:
            path = base / cond / f"trial_{trial}" / fname
            if path.exists():
                df = pd.read_parquet(path, columns=columns)
                df["condition"], df["trial"] = cond, trial
                frames.append(df)
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


KEY = ["condition", "trial", "creature_key"]


def creature_frame(cfg) -> pd.DataFrame:
    cr = load(cfg, "creatures.parquet", ["creature_key", "born_time", "observed_s", "died"])
    cr["died"] = cr["died"].astype(float)

    dr = load(cfg, "drives.parquet", ["creature_key", "time"] + [f"final_{d}" for d in DRIVES])
    dr = dr.sort_values(KEY + ["time"], kind="stable").reset_index(drop=True)
    lev = dr[[f"final_{d}" for d in DRIVES]].to_numpy()
    dr["dH"] = deficit(lev)
    dr["label"] = argmax_labels(lev)
    dr = dr.merge(cr[KEY + ["born_time"]], on=KEY, how="left")
    dr["elapsed_s"] = (dr["time"] - dr["born_time"]) / 1000.0
    g = dr.groupby(KEY)
    out = pd.DataFrame({
        "dH_life": g["dH"].mean(),
        "dH_window": dr[dr["elapsed_s"] <= WINDOW_S].groupby(KEY)["dH"].mean(),
        "drive_rows": g.size(),
    })
    sw = g["label"].apply(lambda s: switch_counts(s.to_numpy()))
    out["switch_argmax_per100"] = [100.0 * t / n for (t, _), n in zip(sw, out["drive_rows"])]
    out["open_argmax_per100"] = [100.0 * o / n for (_, o), n in zip(sw, out["drive_rows"])]
    out = out.reset_index().merge(cr[KEY + ["observed_s", "died", "born_time"]], on=KEY, how="left")

    # Feeding
    mo = load(cfg, "mouth_interactions.parquet", ["creature_key", "time", "interaction_type", "object_type"])
    eat = mo[mo["interaction_type"] == "EAT"].merge(out[KEY + ["born_time", "observed_s"]], on=KEY, how="left")
    frac = ((eat["time"] - eat["born_time"]) / 1000.0 / eat["observed_s"]).clip(0, 0.999)
    eat["decile"] = (frac * 10).astype(int)
    eat["rg"] = eat["object_type"].isin(["RED_APPLE", "GREEN_APPLE"])
    n_eat = eat.groupby(KEY).size().rename("n_eat")
    n_rg = eat[eat["rg"]].groupby(KEY).size().rename("n_eat_rg")
    early_food = eat[frac < EARLY_LIFE].groupby(KEY)["rg"].mean().rename("early_food_share")
    deciles = eat.groupby(KEY)["decile"].nunique().rename("feeding_deciles")
    out = out.merge(n_eat.reset_index(), on=KEY, how="left").merge(n_rg.reset_index(), on=KEY, how="left") \
             .merge(deciles.reset_index(), on=KEY, how="left")
    out = out.merge(early_food.reset_index(), on=KEY, how="left")   # NaN when the creature had no EAT in that window
    for c in ("n_eat", "n_eat_rg", "feeding_deciles"):
        out[c] = out[c].fillna(0)
    out["eat_per_min"] = out["n_eat"] / (out["observed_s"] / 60.0)
    out["eat_rg_per_min"] = out["n_eat_rg"] / (out["observed_s"] / 60.0)

    # Tracker-based focus (A1, A2): actions.focal_drive
    ac = load(cfg, "actions.parquet", ["creature_key", "time", "focal_drive"])
    ac = ac.sort_values(KEY + ["time"], kind="stable").reset_index(drop=True)
    code = {d: i for i, d in enumerate(DRIVES)}
    ac["label"] = ac["focal_drive"].map(code).fillna(-1).astype(int)
    rows = []
    for key, s in ac.groupby(KEY):
        t, o = switch_counts(s["label"].to_numpy())
        n = len(s)
        rows.append((*key, 100.0 * t / n, 100.0 * o / n, float((s["label"] >= 0).mean()), n))
    tr = pd.DataFrame(rows, columns=KEY + ["switch_tracker_per100", "open_tracker_per100", "focused_share", "cycles"])
    focal_arms = ac.groupby("condition")["focal_drive"].apply(lambda s: s.notna().any())
    tr.loc[~tr["condition"].map(focal_arms).fillna(False), ["switch_tracker_per100", "open_tracker_per100", "focused_share"]] = np.nan
    out = out.merge(tr, on=KEY, how="left")

    # Episodes (A1, A2)
    ep = load(cfg, "episode_returns.parquet", ["creature_key", "episode_id", "close_type"])
    if not ep.empty:
        n_ep = ep.drop_duplicates(KEY + ["episode_id"]).groupby(KEY).size().rename("episodes")
        out = out.merge(n_ep.reset_index(), on=KEY, how="left")
    else:
        out["episodes"] = np.nan
    return out


# ---------------------------------------------------------------- telemetry tables

def episode_tables(cfg) -> tuple[pd.DataFrame, pd.DataFrame]:
    ep = load(cfg, "episode_returns.parquet", ["creature_key", "episode_id", "close_type"])
    if ep.empty:
        return pd.DataFrame(), pd.DataFrame()
    ep_close = ep.drop_duplicates(KEY + ["episode_id", "close_type"])
    mix = ep_close.groupby(["condition", "close_type"]).size().unstack(fill_value=0)
    mix = mix.div(mix.sum(axis=1), axis=0).round(3)
    steps = ep.groupby(KEY + ["episode_id"]).size().rename("steps").reset_index()
    length = steps.groupby("condition")["steps"].describe()[["count", "50%", "mean", "max"]].round(1)
    return mix, length


def a2_telemetry(cfg) -> dict:
    cols = ["creature_key", "seq", "focal_drive", "candidate_count", "source", "n_tied_at_max", "executed_source",
            "deciding_controller", "episode_id", "close_type", "realised_value", "executed_q", "executed_se", "executed_sigma",
            "n_consulted", "affordable_controller", "efficiency", "novelty_bonus"]   # the last four: dumps from before #114 lack some
    out = {}
    for cond in [c for c in cfg.cond_keys if (cfg.data_dir_for(c) / c / "trial_1" / "arbitration_decisions.parquet").exists()]:
        parts = []
        for trial in cfg.trial_range:
            p = cfg.data_dir_for(cond) / cond / f"trial_{trial}" / "arbitration_decisions.parquet"
            if p.exists():
                have = set(pq.read_schema(p).names)
                d = pd.read_parquet(p, columns=[c for c in cols if c in have])
                d["condition"], d["trial"] = cond, trial
                parts.append(d)
        d = pd.concat(parts, ignore_index=True)
        d["rank"] = d.groupby(KEY).cumcount()
        d["n"] = d.groupby(KEY)["rank"].transform("size")
        d["late"] = d["rank"] >= d["n"] / 2
        d["decile"] = (d["rank"] / d["n"] * 10).astype(int).clip(upper=9)
        foc = d[d["focal_drive"].notna() & (d["source"] != "SINGLE")]
        late = foc[foc["late"]]
        out[cond] = {
            "decisions": len(d),
            "source_mix": d["source"].value_counts(normalize=True).reindex(SOURCE_LABELS, fill_value=0).round(4).to_dict(),
            "executed_not_chosen": float((d["executed_source"] == "OTHER_CANDIDATE").mean()),
            "informed_share": float((late["n_tied_at_max"] < late["candidate_count"]).mean()) if len(late) else float("nan"),
            "controller_by_decile": d.groupby("decile")["deciding_controller"].agg(lambda s: s.value_counts(normalize=True).round(3).to_dict()).to_dict(),
            "z": calibration_z(d),
            "episodic": episodic_calibration(d),
            "episodic_share_by_decile": controller_share_by_decile(d, "EPISODIC"),
            "consulted_episodic": float((d["n_consulted"] >= 2).mean()) if "n_consulted" in d else float("nan"),
            "affordable_episodic": float((d["affordable_controller"] == "EPISODIC").mean()) if "affordable_controller" in d else float("nan"),
            "novelty_mean": float(d["novelty_bonus"].mean()) if "novelty_bonus" in d else float("nan"),
        }
    return out


def operant_calibration_table(cfg) -> tuple[pd.DataFrame, dict]:
    """Per condition and trial: the operant calibration statistic of recipe 5, from `operant_updates`. Also returns, per condition,
    the per-cell detail pooled over its trials (for the report)."""
    rows, detail = [], {}
    for cond in cfg.cond_keys:
        parts = []
        for trial in cfg.trial_range:
            p = cfg.data_dir_for(cond) / cond / f"trial_{trial}" / "operant_updates.parquet"
            if not p.exists():
                continue
            u = pd.read_parquet(p, columns=["creature_key", "seq", "object_type", "action", "sample", "q", "se", "sigma"])
            r = operant_calibration(u)
            rows.append({"condition": cond, "trial": trial, "statistic": r["statistic"], "pooled_variance": r["pooled_variance"],
                         "n_updates": r["n_updates"], "cells": len(r["cells"]), "constant_cells": len(r["constant_cells"])})
            u["trial"] = trial
            parts.append(u)
        if parts:
            detail[cond] = operant_calibration(pd.concat(parts, ignore_index=True))
    return pd.DataFrame(rows), detail


# ---------------------------------------------------------------- tables and figures

def trial_means(cr: pd.DataFrame, metric: str) -> pd.DataFrame:
    return cr.groupby(["trial", "condition"])[metric].mean().unstack("condition")


def md_table(df: pd.DataFrame) -> str:
    """Markdown table without the optional `tabulate` dependency."""
    cols = [df.index.name or ""] + [str(c) for c in df.columns]
    lines = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for idx, row in df.iterrows():
        lines.append("| " + " | ".join([str(idx)] + [fmt(v) if isinstance(v, (float, np.floating)) else str(v) for v in row]) + " |")
    return "\n".join(lines)


def fmt(x, nd=3):
    return "n/a" if x is None or (isinstance(x, float) and math.isnan(x)) else f"{x:.{nd}f}"


def paired_table(cr: pd.DataFrame) -> str:
    lines = ["| measure | contrast | per-seed differences | mean | SD (2 df) | 80% upper bound on SD |", "|---|---|---|---|---|---|"]
    for col, label in CREATURE_METRICS:
        tm = trial_means(cr, col)
        for later, earlier in PAIRS:
            r = paired_differences(tm, later, earlier)
            if r is None:
                continue
            lines.append(f"| {label} | {later.upper()} - {earlier.upper()} | {r['diffs']} | {fmt(r['mean'])} | {fmt(r['sd'])} | {fmt(r['upper80_sd'])} |")
    return "\n".join(lines)


def present_arms(cr: pd.DataFrame) -> list[str]:
    """The ladder arms that have data in this experiment, in ladder order."""
    have = set(cr["condition"])
    return [a for a in ARMS if a in have]


def arm_table(cr: pd.DataFrame, cfg) -> str:
    arms = present_arms(cr)
    lines = ["| measure | " + " | ".join(cfg.label_by_key.get(a, a) for a in arms) + " |", "|---|" + "---|" * len(arms)]
    for col, label in CREATURE_METRICS:
        cells = []
        for a in arms:
            v = cr[cr["condition"] == a][col].dropna()
            tm = cr[cr["condition"] == a].groupby("trial")[col].mean().dropna()
            cells.append("n/a" if v.empty else f"{fmt(float(v.mean()))} (trials {', '.join(fmt(float(x)) for x in tm)})")
        lines.append(f"| {label} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def survival_text(cr: pd.DataFrame) -> str:
    out = []
    for a, b in SURVIVAL_PAIRS:
        r = survival_comparison(cr, a, b)
        if not r:
            continue
        km = lambda m: "beyond the cap" if (m is None or math.isnan(m)) else f"{m:.0f} s"
        out.append(f"- {a.upper()} vs {b.upper()}: deaths {r['deaths_a']}/{r['n_a']} vs {r['deaths_b']}/{r['n_b']} "
                   f"(Fisher p = {r['p_mortality']:.3g}); KM median {km(r['km_median_a'])} vs {km(r['km_median_b'])}; "
                   f"log-rank p = {fmt(r['p_logrank'], 4)}")
    return "\n".join(out)


def figures(cr: pd.DataFrame, cfg) -> None:
    colors = cfg.palette
    ARMS = present_arms(cr)   # noqa: N806  (shadows the module list on purpose: only arms with data are drawn)
    # d(H)
    fig, ax = plt.subplots(1, 2, figsize=(10, 4))
    for axis, col, title in [(ax[0], "dH_life", "d(H) over life"), (ax[1], "dH_window", f"d(H), first {WINDOW_S:.0f} s")]:
        for i, a in enumerate(ARMS):
            d = cr[cr["condition"] == a]
            axis.scatter(np.full(len(d), i) + (d["trial"] - 2) * 0.12, d[col], color=colors.get(a), alpha=0.6, s=18)
            tm = d.groupby("trial")[col].mean()
            axis.scatter(np.full(len(tm), i) + (tm.index.to_numpy() - 2) * 0.12, tm, color=colors.get(a), marker="D", edgecolor="k", s=60)
        axis.set_xticks(range(len(ARMS)), [a.upper() for a in ARMS])
        axis.set(title=title, ylabel="mean deficit d(H)")
        axis.grid(axis="y", alpha=0.3)
    fig.suptitle("creatures (dots), trial means (diamonds); x-offset = trial")
    fig.tight_layout()
    save(fig, "deficit.png", cfg)

    # Kaplan-Meier
    fig, ax = plt.subplots(figsize=(6.5, 4))
    for a in ARMS:
        d = cr[cr["condition"] == a]
        t, s = kaplan_meier(d["observed_s"].to_numpy(), d["died"].to_numpy())
        if len(t):
            tmax = d["observed_s"].max()
            ax.step(np.append(t, tmax), np.append(s, s[-1]), where="post", color=colors.get(a), label=f"{a.upper()} (n={len(d)}, deaths {int(d['died'].sum())})")
    ax.set(xlabel="observed time (s)", ylabel="survival (KM)", title="Survival, censored at the cap", ylim=(0, 1.02), xscale="symlog", xlim=(0, None))
    ax.legend()
    ax.grid(alpha=0.3)
    fig.tight_layout()
    save(fig, "survival.png", cfg)

    # Feeding and focus
    fig, ax = plt.subplots(1, 3, figsize=(13, 4))
    for axis, col, title in [(ax[0], "eat_per_min", "EATs per minute"), (ax[1], "feeding_deciles", "D: life deciles with an EAT"),
                              (ax[2], "switch_argmax_per100", "focus transfers / 100 cycles (argmax approx.)")]:
        data = [cr[cr["condition"] == a][col].dropna().to_numpy() for a in ARMS]
        bp = axis.boxplot(data, tick_labels=[a.upper() for a in ARMS], patch_artist=True)
        for patch, a in zip(bp["boxes"], ARMS):
            patch.set_facecolor(colors.get(a))
            patch.set_alpha(0.7)
        axis.set_title(title)
        axis.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    save(fig, "feeding_and_focus.png", cfg)


# ---------------------------------------------------------------- run

def run(cfg: ExperimentAnalysis) -> None:
    cr = creature_frame(cfg)
    cr.to_csv(cfg.fig_dir / "creature_metrics.csv", index=False)
    print(f"{len(cr)} creatures across {cr['condition'].nunique()} arms x {cr['trial'].nunique()} trials")

    arm_md = arm_table(cr, cfg)
    paired_md = paired_table(cr)
    surv_md = survival_text(cr)
    mix, length = episode_tables(cfg)
    a2 = a2_telemetry(cfg)
    op_cal, op_detail = operant_calibration_table(cfg)
    print(arm_md, "\n", surv_md, "\n", paired_md, sep="")
    figures(cr, cfg)

    tele_md = []
    for cond, t in a2.items():
        z = md_table(t["z"]) if len(t["z"]) else "no closed steps"
        ctl = {k: v for k, v in t["controller_by_decile"].items()}
        tele_md.append(f"**{cond.upper()}** ({t['decisions']:,} decisions). Source mix: {t['source_mix']}. "
                       f"Executed action not the chosen one: {t['executed_not_chosen']:.2%}. "
                       f"Informed share (late half, focused multi-candidate): {fmt(t['informed_share'])}. "
                       f"Deciding controller by life decile: {ctl}.\n\nDescriptive z by close type:\n\n{z}")
        if t["episodic"]["n"]:
            ec = t["episodic"]
            tele_md[-1] += (f"\n\nEpisodic calibration (decisions the episodic controller made, per closed episode): n = {ec['n']:,}, "
                            f"mean z {fmt(ec['mean'])}, variance {fmt(ec['variance'])} (an honest SE gives 0 and 1). "
                            f"Episodic share of decisions by life decile: {t['episodic_share_by_decile']}. "
                            f"Consulted the episodic controller in {fmt(t['consulted_episodic'])} of decisions, affordable in {fmt(t['affordable_episodic'])}; "
                            f"mean novelty bonus {fmt(t['novelty_mean'], 4)}.\n\n{md_table(ec['by_close_type'])}")
    print("\n".join(tele_md))

    rb = ReportBuilder(cfg, "Report: arbiter-ladder pilot (A0 / A1 / A2), analysis skeleton")
    rb.purpose("Descriptive pilot of the arbiter ladder: spread of the outcomes #107 will pre-register, and a first run of the "
               "#106 analysis module end to end. Plan: `docs/plans/arbiter-ladder-pilot.md`, `docs/plans/issue-106-arbiter-ladder-analysis.md`; "
               "recipe: `docs/experiments/p107_pilot_stage2_recipe.md`. No pilot number enters a campaign result.")
    rb.assumptions("The trial is the unit; creatures within a trial share a world, a food supply and a seed. The same seed is used in every "
                   "arm (pairs the initial world only; Akka scheduling keeps trajectories nondeterministic). d(H) is measured from the set "
                   f"point {H_STAR}. A0's focus switches use an argmax-of-urgency approximation, because the regulated emotion is not persisted. "
                   "A0 lives minutes and A1/A2 up to the cap, so d(H) over life is not like for like; the common-window d(H) is. "
                   "A1 and A2 use the tuned focus thresholds 0.4 / 0.15. DATA CAVEAT: in creatures that survive to the cap, `mouth_interactions` holds a whole number of 128-row blocks per creature (and `body_states` exactly 217,728 rows), so the last partial block is missing: EAT counts of A1/A2 survivors are lower bounds, short by up to 127 each (up to about 8% in A1, 2% in A2); A0 creatures, which died, are not quantized. The ordering of arms is not affected; the feeding rates are not exact.")
    rb.hypotheses("None: this is a descriptive pilot. Predictions recorded before the data are in the recipe.")
    rb.result("Per-arm measures (creature mean, then trial means)", arm_md)
    rb.result("Drive deficit", "Per creature (dots) and per trial (diamonds).", "deficit.png")
    rb.result("Survival", surv_md or "no data", "survival.png")
    rb.result("Feeding and focus", "EATs per minute, life deciles with an EAT, and the argmax-approximated focus transfers per 100 cycles.", "feeding_and_focus.png")
    if len(mix):
        rb.result("Episodes (A1, A2)", f"Close-type mix per arm:\n\n{md_table(mix)}\n\nSteps per episode:\n\n{md_table(length)}")
    if tele_md:
        rb.result("A2 decision telemetry", "\n\n".join(tele_md))
    if len(op_cal):
        cal = op_cal.set_index(["condition", "trial"])
        detail_md = "\n\n".join(f"**{c.upper()}** cells (>= {MIN_CELL_UPDATES} updates, outcomes vary):\n\n{md_table(d['cells'])}"
                                   + (f"\n\nConstant-outcome cells, left out:\n\n{md_table(d['constant_cells'])}" if len(d["constant_cells"]) else "")
                                   for c, d in op_detail.items())
        rb.result("Operant calibration (recipe 5)", "Per trial: variance of z per (object type, action) cell with enough updates and varying outcomes, "
                  "weighted by update count; adoption range [0.8, 1.25] for A2 (mean over trials). `pooled_variance` is the unrestricted variance of all z, "
                  "for comparison: constant cells drag it down.\n\n" + md_table(cal) + "\n\n" + detail_md)
    rb.result("Paired differences by seed", "Differences of trial means, later arm minus earlier arm; SD has 2 degrees of freedom, so size on "
              "the 80% upper bound (2.12 x SD), not the point estimate.\n\n" + paired_md)
    rb.analysis("To be written from the tables above (and the sample-size computation of #107).")
    rb.write("p107_pilot_report_skeleton.md" if cfg.name == "p107_pilot" else None)
