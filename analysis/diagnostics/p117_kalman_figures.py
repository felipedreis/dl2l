"""The two figures of the p117_kalman reports (cluster and local pilot).

  survival.png  Kaplan-Meier survival per arm on a linear time axis (minutes), direct labels with the death count.
  effects.png   The effect of each change: one panel per measure, one row per contrast, the estimate and its 95% interval.
                Deaths: difference in the share of creatures dead at the cap (percentage points; creatures pooled, Wald interval).
                Other measures: mean of the per-seed differences of trial means (paired by seed; t interval).

    python3 analysis/diagnostics/p117_kalman_figures.py <experiment>   # p117_kalman | p117_kalman_local
"""
import pathlib
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches  # noqa: F401
import matplotlib.lines  # noqa: F401
import matplotlib.ticker  # noqa: F401
import numpy as np
import pandas as pd
import yaml
from scipy import stats

INK, INK2, MUTED, GRID, SURFACE = "#0b0b0b", "#52514e", "#8a8984", "#e4e3df", "#fcfcfb"
NAME = {"a3_base": "A3 control", "a3_mc": "A3 + APPROACH credit", "a3_kal": "A3 + Kalman operant",
        "a3_all": "A3 + Kalman + whole-draw", "a5_base": "A5 control"}
ORDER = ["a3_base", "a3_mc", "a3_kal", "a3_all", "a5_base"]
CONTRASTS = {
    "p117_kalman": [("a3_kal", "a3_base"), ("a3_kal", "a5_base"), ("a3_all", "a3_kal"), ("a3_base", "a5_base")],
    "p117_kalman_local": [("a3_mc", "a3_base"), ("a3_kal", "a3_mc"), ("a3_all", "a3_kal"), ("a3_all", "a3_base")],
}
# (column, panel title, which direction is better, scale)
MEASURES = [("died", "Creatures dead at the cap\n(percentage points)", "fewer deaths", 100.0),
            ("dH_life", "d(H) over life\n(drive deficit)", "lower deficit", 1.0),
            ("eat_per_min", "EATs per minute\n(all objects)", "more eating", 1.0),
            ("eat_rg_per_min", "RED + GREEN EATs per minute\n(food)", "more food", 1.0)]


def style(ax):
    ax.set_facecolor(SURFACE)
    for s in ("top", "right", "left"):
        ax.spines[s].set_visible(False)
    ax.spines["bottom"].set_color(GRID)
    ax.tick_params(colors=INK2, labelsize=9, length=0)


def km(t, event):
    order = np.argsort(t)
    t, event = t[order], event[order]
    n = len(t)
    xs, ys, s = [0.0], [1.0], 1.0
    for i, (ti, ei) in enumerate(zip(t, event)):
        if ei:
            s *= 1 - 1 / (n - i)
            xs.append(ti)
            ys.append(s)
    return np.array(xs), np.array(ys)


def survival(cr, arms, color, cap_min, out):
    fig, ax = plt.subplots(figsize=(10.0, 4.8), facecolor=SURFACE)
    style(ax)
    ends = []
    for a in arms:
        d = cr[cr["condition"] == a]
        x, y = km(d["observed_s"].values / 60, d["died"].values.astype(bool))
        x = np.append(x, cap_min)
        y = np.append(y, y[-1])
        ax.step(x, y, where="post", color=color[a], linewidth=2.2, label=NAME[a])
        ends.append((y[-1], a, int(d["died"].sum()), len(d)))
    # direct labels at the right end, nudged apart so they never overlap
    ends.sort()
    placed = []
    for yv, a, deaths, n in ends:
        yl = yv if not placed else max(yv, placed[-1] + 0.035)
        placed.append(yl)
        ax.text(cap_min * 1.01, yl, f"{NAME[a]}: {deaths} of {n} died", va="center", fontsize=9.5, color=INK)
        ax.plot([cap_min, cap_min * 1.008], [yv, yl], color=color[a], linewidth=1)
    ax.set_xlim(0, cap_min)
    lo = min(e[0] for e in ends)
    ax.set_ylim(max(0.0, np.floor((lo - 0.08) * 10) / 10), 1.005)
    ax.set_xlabel("time since birth (minutes)", color=INK2, fontsize=10)
    ax.set_ylabel("share of creatures alive", color=INK2, fontsize=10)
    ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.legend(loc="lower left", frameon=False, fontsize=9)
    ax.set_title("Survival (Kaplan-Meier); creatures alive at the cap are censored", loc="left", fontsize=11.5, color=INK, pad=10)
    fig.tight_layout()
    fig.subplots_adjust(right=0.66)
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def effect(cr, later, earlier, col, scale):
    if col == "died":
        a = cr[cr["condition"] == later]["died"]
        b = cr[cr["condition"] == earlier]["died"]
        p1, p2 = a.mean(), b.mean()
        se = np.sqrt(p1 * (1 - p1) / len(a) + p2 * (1 - p2) / len(b))
        d = p1 - p2
        return d * scale, (d - 1.96 * se) * scale, (d + 1.96 * se) * scale
    tm = cr.groupby(["condition", "trial"])[col].mean().unstack(0)
    diff = (tm[later] - tm[earlier]).dropna().values
    m = diff.mean()
    h = stats.t.ppf(0.975, len(diff) - 1) * diff.std(ddof=1) / np.sqrt(len(diff))
    return m * scale, (m - h) * scale, (m + h) * scale


def effects(cr, contrasts, name, out):
    fig, axes = plt.subplots(1, len(MEASURES), figsize=(13.2, 0.62 * len(contrasts) + 2.2), facecolor=SURFACE, sharey=True)
    ylab = [f"{NAME[l]}\nvs {NAME[e]}" for l, e in contrasts]
    for ax, (col, title, better, scale) in zip(axes, MEASURES):
        style(ax)
        rows = [effect(cr, l, e, col, scale) for l, e in contrasts]
        span = max(max(abs(r[1]), abs(r[2])) for r in rows) * 1.6
        ax.set_xlim(-span, span)
        ax.axvline(0, color=MUTED, linewidth=1)
        for i, (m, lo, hi) in enumerate(rows):
            y = len(rows) - 1 - i
            clear = lo > 0 or hi < 0
            ax.hlines(y, lo, hi, color=INK if clear else MUTED, linewidth=2.2)
            ax.plot(m, y, "o", color=INK if clear else SURFACE, markeredgecolor=INK if clear else MUTED, markersize=8, markeredgewidth=1.8)
            fmt = "{:+.0f}" if col == "died" else "{:+.2f}" if col == "dH_life" else "{:+.1f}"
            # the value sits just past the interval's right end, clear of the zero line and the other rows
            ax.text(hi + span * 0.04, y, fmt.format(m), ha="left", va="center", fontsize=9.5, color=INK)
        ax.set_yticks(range(len(rows)))
        ax.set_yticklabels(ylab[::-1], fontsize=9, color=INK)
        ax.set_ylim(-0.6, len(rows) - 0.3)
        ax.set_title(title, loc="left", fontsize=10.5, color=INK, pad=8)
        ax.grid(axis="x", color=GRID, linewidth=0.8)
        if better:
            lower_is_better = col in ("died", "dH_life")
            ax.set_xlabel(("<- " + better) if lower_is_better else (better + " ->"), color=INK2, fontsize=9,
                          loc="left" if lower_is_better else "right")
    fig.suptitle("Effect of each change: estimate and 95% interval (filled = interval excludes 0)", x=0.01, ha="left", fontsize=12, color=INK)
    n_tr = cr["trial"].nunique()
    fig.text(0.01, 0.01, f"Deaths: difference in the share of the arm's creatures dead at the cap (creatures pooled). Other panels: mean of the "
             f"per-seed differences of trial means ({n_tr} seeds, paired).", fontsize=8.5, color=INK2)
    fig.tight_layout(rect=(0, 0.04, 1, 0.93))
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def main(name):
    spec = yaml.safe_load(open(f"experiments/{name}.yml"))
    fig_dir = pathlib.Path(f"docs/reports/figures/{name}")
    cr = pd.read_csv(fig_dir / "creature_metrics.csv")
    have = set(cr["condition"])
    arms = [a for a in ORDER if a in have]
    color = {c["key"]: c["color"] for c in spec["conditions"]}
    cap_min = float(cr["observed_s"].max()) / 60
    cap_min = 60.0 if cap_min > 40 else 20.0
    survival(cr, arms, color, cap_min, fig_dir / "survival_minutes.png")
    effects(cr, [c for c in CONTRASTS[name] if c[0] in have and c[1] in have], name, fig_dir / "effects.png")
    print(fig_dir / "survival_minutes.png", fig_dir / "effects.png")




# ---------------------------------------------------------------------------------------------------------------------------
# Mechanism and trust figures, from aggregates.json (analysis/diagnostics/p117_kalman_aggregates.py)
# ---------------------------------------------------------------------------------------------------------------------------
import json  # noqa: E402

RED, GREEN, GRAY, VIOLET, MAGENTA, DARK = "#e34948", "#008300", "#a8a7a2", "#6250d6", "#e87ba4", "#52514e"


def hbar_stack(ax, arms, parts, colors, labels):
    """Horizontal 100% bars, one per arm (top to bottom in design order), with the share written in each segment wide enough to hold it."""
    for i, a in enumerate(arms):
        y = len(arms) - 1 - i
        left = 0.0
        tot = sum(parts[a])
        for v, col in zip(parts[a], colors):
            w = v / tot if tot else 0
            ax.barh(y, w, left=left, color=col, height=0.62, edgecolor=SURFACE, linewidth=2)
            if w >= 0.07:
                ax.text(left + w / 2, y, f"{w:.0%}", ha="center", va="center", fontsize=9,
                        color="white" if col not in (GRAY, MAGENTA, "#d3d2cd") else INK)
            left += w
    ax.set_yticks(range(len(arms)))
    ax.set_yticklabels([NAME[a] for a in arms][::-1], fontsize=9.5, color=INK)
    ax.set_xlim(0, 1)
    ax.xaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    used = [i for i in range(len(colors)) if any(parts[a][i] for a in arms)]
    ax.legend(handles=[matplotlib.patches.Patch(color=colors[i], label=labels[i]) for i in used],
              loc="upper left", bbox_to_anchor=(0, -0.14), ncol=min(len(labels), 2), frameon=False, fontsize=9)


def fig_decisions(agg, arms, out):
    parts, eat_rate = {}, {}
    for a in arms:
        d = pd.DataFrame(agg["decisions"][a])
        op = d[d["ctl"] == "OPERANT"]
        parts[a] = [d[(d["ctl"] == "EPISODIC") & (d["source"] != "EPSILON")]["n"].sum(),
                    op[op["source"].isin(["GREEDY", "THOMPSON", "SINGLE"])]["n"].sum(),
                    op[op["source"] == "ABSTAIN_UNIFORM"]["n"].sum(),
                    d[d["source"] == "EPSILON"]["n"].sum()]
        eat_rate[a] = {c: d[(d["ctl"] == c) & d["eat"]]["n"].sum() / d[d["ctl"] == c]["n"].sum() for c in ("OPERANT", "EPISODIC")}
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(10.0, 0.95 * len(arms) * 2 + 2.6), facecolor=SURFACE)
    for ax in (ax1, ax2):
        style(ax)
    hbar_stack(ax1, arms, parts, [VIOLET, MAGENTA, GRAY, DARK],
               ["episodic memory decides", "operant decides from an opinion", "operant has no opinion: random pick", "random floor (3%)"])
    ax1.set_title("Who makes each decision, and how", loc="left", fontsize=11.5, color=INK, pad=8)
    for i, a in enumerate(arms):
        y = len(arms) - 1 - i
        for c, col, dy in (("OPERANT", MAGENTA, 0.13), ("EPISODIC", VIOLET, -0.13)):
            v = eat_rate[a][c] * 100
            ax2.hlines(y + dy, 0, v, color=col, linewidth=2)
            ax2.plot(v, y + dy, "o", color=col, markersize=8, markeredgecolor=SURFACE, markeredgewidth=1.5)
            ax2.text(v + 0.15, y + dy, f"{v:.1f}%", va="center", fontsize=9, color=INK)
    ax2.set_yticks(range(len(arms)))
    ax2.set_yticklabels([NAME[a] for a in arms][::-1], fontsize=9.5, color=INK)
    ax2.set_xlim(0, max(v * 100 for a in arms for v in eat_rate[a].values()) * 1.3)
    ax2.set_ylim(-0.6, len(arms) - 0.4)
    ax2.grid(axis="x", color=GRID, linewidth=0.8)
    ax2.set_title("Of the decisions each controller makes, the share that are EAT", loc="left", fontsize=11.5, color=INK, pad=8)
    ax2.xaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(100, decimals=0))
    ax2.legend(handles=[matplotlib.lines.Line2D([], [], color=MAGENTA, marker="o", label="operant"),
                        matplotlib.lines.Line2D([], [], color=VIOLET, marker="o", label="episodic memory")],
               loc="lower right", ncol=1, frameon=False, fontsize=9)
    fig.tight_layout()
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def fig_eating(agg, arms, color, out):
    parts, over = {}, {}
    alive = {a: pd.DataFrame(agg["drives"][a]).set_index("minute")["alive"] for a in arms}
    for a in arms:
        e = pd.DataFrame(agg["eats"][a])
        e = e[e["win"] >= 0]
        tot = e.groupby("object_type")["n"].sum()
        parts[a] = [tot.get("RED_APPLE", 0), tot.get("GREEN_APPLE", 0), tot.get("GRAY_APPLE", 0)]
        food = e[e["object_type"].isin(["RED_APPLE", "GREEN_APPLE"])].groupby("win")["n"].sum()
        mins = {w: alive[a][(alive[a].index >= w * 10) & (alive[a].index < w * 10 + 10)].sum() for w in food.index}
        over[a] = pd.Series({w: food[w] / mins[w] for w in food.index if mins[w] > 0}).sort_index()
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.2, 3.9), facecolor=SURFACE, gridspec_kw={"width_ratios": [1.3, 1]})
    for ax in (ax1, ax2):
        style(ax)
    hbar_stack(ax1, arms, parts, [RED, GREEN, GRAY], ["red apple (food)", "green apple (food)", "gray apple (feeds nothing)"])
    ax1.set_title("What the creatures eat (fruit EATs; plants are not recorded)", loc="left", fontsize=11.5, color=INK, pad=8)
    for a in arms:
        s = over[a]
        x = s.index * 10 + 5
        ax2.plot(x, s.values, color=color[a], linewidth=2.2, marker="o", markersize=5, label=NAME[a])
    ax2.set_xlim(0, max(max(s.index) for s in over.values()) * 10 + 10)
    ax2.set_ylim(0, None)
    ax2.grid(axis="y", color=GRID, linewidth=0.8)
    ax2.set_xlabel("minutes since birth (10-minute windows)", color=INK2, fontsize=9.5)
    ax2.set_title("Food EATs per creature per minute, over life", loc="left", fontsize=11.5, color=INK, pad=8)
    ax2.legend(frameon=False, fontsize=8.5, loc="lower left")
    fig.tight_layout()
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def fig_drives(agg, arms, color, out):
    fig, ax = plt.subplots(figsize=(10.0, 4.0), facecolor=SURFACE)
    style(ax)
    for a in arms:
        d = pd.DataFrame(agg["drives"][a])
        d["w"] = (d["minute"] // 10) * 10 + 5
        # creature-minute weighted share of the living creatures with hunger at or above 3, per 5-minute window
        d["k"] = d["share_hunger_ge3"] * d["alive"]
        w = d.groupby("w")[["k", "alive"]].sum()
        ax.plot(w.index, w["k"] / w["alive"], color=color[a], linewidth=2.2, marker="o", markersize=4.5, label=NAME[a])
    ax.set_xlim(0, 60 if max(pd.DataFrame(agg["drives"][a])["minute"].max() for a in arms) > 30 else 20)
    ax.set_ylim(0, None)
    ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_xlabel("minutes since birth (10-minute windows)", color=INK2, fontsize=9.5)
    ax.set_title("Share of the living creatures in danger (hunger 3 or more; death comes at 7)", loc="left", fontsize=11.5, color=INK, pad=8)
    ax.legend(frameon=False, fontsize=9, loc="upper left")
    fig.tight_layout()
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def fig_deaths(agg, arms, out):
    fig, ax = plt.subplots(figsize=(10.0, 0.62 * len(arms) + 1.6), facecolor=SURFACE)
    style(ax)
    cause_col = {"hunger": MAGENTA, "pain": RED, "sleep": VIOLET, "tedium": DARK}
    seen = set()
    for i, a in enumerate(arms):
        y = len(arms) - 1 - i
        for r in agg["deaths"][a]:
            cause = max(("hunger", "pain", "sleep", "tedium"), key=lambda k: r[k])
            seen.add(cause)
            ax.plot(r["observed_s"] / 60, y + (hash((r["trial"], r["creature_key"])) % 7 - 3) * 0.05, "o", color=cause_col[cause],
                    markersize=7.5, markeredgecolor=SURFACE, markeredgewidth=1.2)
        ax.text(1.01, y, f"{len(agg['deaths'][a])} deaths", transform=ax.get_yaxis_transform(), va="center", fontsize=9.5, color=INK)
    ax.set_yticks(range(len(arms)))
    ax.set_yticklabels([NAME[a] for a in arms][::-1], fontsize=9.5, color=INK)
    cap = 60 if max(r["observed_s"] for a in arms for r in agg["deaths"][a]) > 1300 else 20
    ax.set_xlim(0, cap)
    ax.set_ylim(-0.6, len(arms) - 0.4)
    ax.grid(axis="x", color=GRID, linewidth=0.8)
    ax.set_xlabel("minute of death", color=INK2, fontsize=9.5)
    total = sum(len(agg["deaths"][a]) for a in arms)
    if seen == {"hunger"}:
        ax.set_title(f"Every death (one dot each) by minute of death: all {total} are starvation (hunger at the lethal level)",
                     loc="left", fontsize=11.5, color=INK, pad=8)
    else:
        ax.set_title("Every death by minute and by the drive that was highest at death", loc="left", fontsize=11.5, color=INK, pad=8)
        ax.legend(handles=[matplotlib.lines.Line2D([], [], color=cause_col[c], marker="o", linestyle="", label=f"{c} highest") for c in sorted(seen)],
                  loc="upper left", bbox_to_anchor=(0, -0.18), ncol=4, frameon=False, fontsize=9)
    fig.tight_layout()
    fig.subplots_adjust(right=0.86)
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def fig_episodes(agg, arms, out):
    parts = {}
    for a in arms:
        d = {r["close_type"]: r["n"] for r in agg["episodes"][a]}
        parts[a] = [d.get("REGULATED", 0), d.get("TIMED_OUT", 0), d.get("PREEMPTED", 0), d.get("DEATH", 0)]
    fig, ax = plt.subplots(figsize=(10.0, 0.55 * len(arms) + 1.7), facecolor=SURFACE)
    style(ax)
    hbar_stack(ax, arms, parts, [GREEN, GRAY, VIOLET, RED],
               ["regulated (drive back below threshold)", "timed out (no relief for 200 cycles)", "pre-empted by another drive", "death"])
    ax.set_title("How focus episodes end (episodic memory learns from these)", loc="left", fontsize=11.5, color=INK, pad=8)
    fig.tight_layout()
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def fig_decisiveness(agg, arms, color, out):
    """Classes of operant decisiveness per decision (histogram bins of 0.02): the share that reaches theta is what lets the
    cheap controller decide alone; everything else escalates to episodic memory when the creature can afford it."""
    parts = {}
    for a in arms:
        h = pd.DataFrame(agg["operant_conf_hist"][a])
        b = h["bin"].round(2)
        parts[a] = [h[b < 0.02]["n"].sum(), h[(b >= 0.02) & (b < 0.5)]["n"].sum(), h[b == 0.5]["n"].sum(),
                    h[(b > 0.5) & (b < 0.8)]["n"].sum(), h[b >= 0.8]["n"].sum()]
    fig, ax = plt.subplots(figsize=(10.0, 0.55 * len(arms) + 2.0), facecolor=SURFACE)
    style(ax)
    hbar_stack(ax, arms, parts, [DARK, GRAY, "#d3d2cd", MAGENTA, VIOLET],
               ["about 0: no opinion on any candidate", "between 0 and 0.5: many close candidates", "0.5: the two best are tied",
                "between 0.5 and 0.8", "0.8 or more: decisive, stops the consultation"])
    ax.set_title("How decisive the operant is, over all decisions (theta = 0.8)", loc="left", fontsize=11.5, color=INK, pad=8)
    fig.tight_layout()
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def fig_calibration(agg, arms, color, out):
    fig, ax = plt.subplots(figsize=(10.0, 0.6 * len(arms) + 1.6), facecolor=SURFACE)
    style(ax)
    ax.axvspan(0.8, 1.25, color=GREEN, alpha=0.08, linewidth=0)
    ax.text(1.025, len(arms) - 0.45, "honest range [0.8, 1.25]", ha="center", fontsize=8.5, color=GREEN)
    ax.axvline(1.0, color=MUTED, linewidth=1)
    for i, a in enumerate(arms):
        y = len(arms) - 1 - i
        v = [x for x in agg["calibration"][a] if x is not None]
        jit = np.linspace(-0.15, 0.15, len(v))
        ax.plot(v, y + jit, "o", color=color[a], markersize=7, markeredgecolor=SURFACE, markeredgewidth=1.2)
        ax.plot([np.mean(v)] * 2, [y - 0.28, y + 0.28], color=INK, linewidth=2.2)
        kind = "Kalman cell" if a in ("a3_kal", "a3_all") else "Welford table"
        ax.text(1.01, y, f"mean {np.mean(v):.2f} ({kind})", transform=ax.get_yaxis_transform(), va="center", fontsize=9.5, color=INK)
    ax.set_yticks(range(len(arms)))
    ax.set_yticklabels([NAME[a] for a in arms][::-1], fontsize=9.5, color=INK)
    ax.set_ylim(-0.6, len(arms) - 0.2)
    ax.set_xlim(0.5, 1.6)
    ax.grid(axis="x", color=GRID, linewidth=0.8)
    ax.set_xlabel("variance of the operant's one-step z per trial (1 = its uncertainty is exactly right; below 1 = too cautious)",
                  color=INK2, fontsize=9)
    ax.set_title("Is the operant's uncertainty honest? One dot per trial, bar = mean", loc="left", fontsize=11.5, color=INK, pad=8)
    fig.tight_layout()
    fig.subplots_adjust(right=0.78)
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def wilson(k, n):
    z = 1.96
    p = k / n
    c = (p + z * z / (2 * n)) / (1 + z * z / n)
    h = z * np.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return c - h, c + h


def fig_reproducibility(out):
    """The two controls, same configuration and seeds, in p117_factorial (older image) and in p117_kalman."""
    import duckdb
    con = duckdb.connect()
    rows = []
    for label, path in (("A3 control", "ml/data_p117_factorial/a3_L0T0"), ("A3 control", "ml/data_p117_kalman/a3_base"),
                        ("A5 control", "ml/data_p117_factorial/a5_L0T0"), ("A5 control", "ml/data_p117_kalman/a5_base")):
        k, n = con.sql(f"SELECT sum(died::int), count(*) FROM read_parquet('{path}/trial_*/creatures.parquet', union_by_name=true)").fetchall()[0]
        rows.append((label, "p117_factorial (2026-11-13)" if "factorial" in path else "p117_kalman (2026-11-25)", int(k), int(n)))
    fig, ax = plt.subplots(figsize=(10.0, 2.9), facecolor=SURFACE)
    style(ax)
    ys = [3, 2.4, 1, 0.4]
    for y, (label, run, k, n) in zip(ys, rows):
        lo, hi = wilson(k, n)
        col = "#2a78d6" if label.startswith("A3") else "#eda100"
        ax.hlines(y, lo * 100, hi * 100, color=col, linewidth=2.2)
        ax.plot(k / n * 100, y, "o", color=col, markersize=8, markeredgecolor=SURFACE, markeredgewidth=1.4)
        ax.text(hi * 100 + 1, y, f"{k} of {n}", va="center", fontsize=9.5, color=INK)
        ax.text(-1, y, f"{label}, {run}", ha="right", va="center", fontsize=9.5, color=INK)
    ax.set_yticks([])
    ax.set_xlim(0, 75)
    ax.set_ylim(0, 3.5)
    ax.grid(axis="x", color=GRID, linewidth=0.8)
    ax.set_xlabel("creatures dead at 60 minutes (%), with 95% interval", color=INK2, fontsize=9.5)
    ax.set_title("Same configuration, same seeds, two runs: how much the controls move", loc="left", fontsize=11.5, color=INK, pad=8)
    fig.tight_layout()
    fig.subplots_adjust(left=0.36)
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    plt.close(fig)


def all_figures(name):
    spec = yaml.safe_load(open(f"experiments/{name}.yml"))
    fig_dir = pathlib.Path(f"docs/reports/figures/{name}")
    agg = json.loads((fig_dir / "aggregates.json").read_text())
    color = {c["key"]: c["color"] for c in spec["conditions"]}
    arms = [a for a in ORDER if a in agg["arms"]]
    fig_drives(agg, arms, color, fig_dir / "drives.png")
    fig_deaths(agg, arms, fig_dir / "deaths.png")
    fig_decisions(agg, arms, fig_dir / "decisions.png")
    fig_eating(agg, arms, color, fig_dir / "eating.png")
    fig_episodes(agg, arms, fig_dir / "episodes.png")
    fig_decisiveness(agg, arms, color, fig_dir / "decisiveness.png")
    fig_calibration(agg, arms, color, fig_dir / "calibration.png")
    if name == "p117_kalman":
        fig_reproducibility(fig_dir / "reproducibility.png")
    print("figures written to", fig_dir)


if __name__ == "__main__":
    main(sys.argv[1])
    all_figures(sys.argv[1])
