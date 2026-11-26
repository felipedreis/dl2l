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


if __name__ == "__main__":
    main(sys.argv[1])
