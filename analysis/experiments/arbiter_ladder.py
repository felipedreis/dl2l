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

from analysis.dl2l_analysis.config import ExperimentAnalysis
from analysis.dl2l_analysis.figures import plt, save
from analysis.dl2l_analysis.report import ReportBuilder
from analysis.dl2l_analysis.stats import kaplan_meier, survival_comparison

DRIVES = ["hunger", "sleep", "pain", "tedium"]
H_STAR = 0.18            # FocusTracker hStar / Constants.MIN_AROUSAL_LEVEL: the set point a deficit is measured from
WINDOW_S = 180.0         # common window for d(H): A0 creatures live about 4-8 minutes
UPPER_FACTOR = 2.12      # sqrt(2 / chi2_{0.20, df=2}): one-sided 80% upper bound factor for an SD from 3 pairs
ARMS = ["a0", "a1", "a2"]
PAIRS = [("a1", "a0"), ("a2", "a1"), ("a2", "a0")]   # (later arm, earlier arm): difference = later - earlier
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
    deciles = eat.groupby(KEY)["decile"].nunique().rename("feeding_deciles")
    out = out.merge(n_eat.reset_index(), on=KEY, how="left").merge(n_rg.reset_index(), on=KEY, how="left") \
             .merge(deciles.reset_index(), on=KEY, how="left")
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
            "deciding_controller", "episode_id", "close_type", "realised_value", "executed_q", "executed_se", "executed_sigma"]
    out = {}
    for cond in [c for c in cfg.cond_keys if (cfg.data_dir_for(c) / c / "trial_1" / "arbitration_decisions.parquet").exists()]:
        parts = []
        for trial in cfg.trial_range:
            p = cfg.data_dir_for(cond) / cond / f"trial_{trial}" / "arbitration_decisions.parquet"
            if p.exists():
                d = pd.read_parquet(p, columns=cols)
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
        }
    return out


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


def arm_table(cr: pd.DataFrame, cfg) -> str:
    lines = ["| measure | " + " | ".join(cfg.label_by_key.get(a, a) for a in ARMS) + " |", "|---|" + "---|" * len(ARMS)]
    for col, label in CREATURE_METRICS:
        cells = []
        for a in ARMS:
            v = cr[cr["condition"] == a][col].dropna()
            tm = cr[cr["condition"] == a].groupby("trial")[col].mean().dropna()
            cells.append("n/a" if v.empty else f"{fmt(float(v.mean()))} (trials {', '.join(fmt(float(x)) for x in tm)})")
        lines.append(f"| {label} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def survival_text(cr: pd.DataFrame) -> str:
    out = []
    for a, b in [("a0", "a1"), ("a1", "a2")]:
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
    rb.result("Paired differences by seed", "Differences of trial means, later arm minus earlier arm; SD has 2 degrees of freedom, so size on "
              "the 80% upper bound (2.12 x SD), not the point estimate.\n\n" + paired_md)
    rb.analysis("To be written from the tables above (and the sample-size computation of #107).")
    rb.write("p107_pilot_report_skeleton.md" if cfg.name == "p107_pilot" else None)
