"""Per-arm comparison figure of a p117_kalman report (replaces the wide per-arm table).

One small panel per measure, one dot per trial (trial mean over the trial's creatures), a bar at the arm mean with its value written above it.
Same arm order and colours as the spec (experiments/<name>.yml), which uses the dataviz reference palette's first slots.

    python3 analysis/diagnostics/p117_kalman_arm_figure.py <experiment>   # p117_kalman | p117_kalman_local
"""
import sys
import pathlib

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import yaml

TEXT, MUTED, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"
PANELS = [("died", "Creatures dead at the cap (%)", 100.0, "{:.0f}%"),
          ("dH_life", "d(H) over life (lower is better)", 1.0, "{:.2f}"),
          ("eat_per_min", "EATs per minute", 1.0, "{:.1f}"),
          ("eat_rg_per_min", "RED + GREEN EATs per minute", 1.0, "{:.1f}")]


def main(name):
    spec = yaml.safe_load(open(f"experiments/{name}.yml"))
    fig_dir = pathlib.Path(f"docs/reports/figures/{name}")
    cr = pd.read_csv(fig_dir / "creature_metrics.csv")
    arms = [c["key"] for c in spec["conditions"] if c["key"] in set(cr["condition"])]
    label = {c["key"]: c["label"] for c in spec["conditions"]}
    color = {c["key"]: c["color"] for c in spec["conditions"]}
    short = {"a3_base": "A3\ncontrol", "a3_mc": "A3 +\nAPPROACH", "a3_kal": "A3 +\nKalman", "a3_all": "A3 + Kalman\n+ whole-draw", "a5_base": "A5\ncontrol"}
    n_trials = cr["trial"].nunique()
    fig, axes = plt.subplots(2, 2, figsize=(10.5, 7.6), facecolor=SURFACE)
    rng = np.random.default_rng(1)
    for ax, (col, title, scale, fmt) in zip(axes.ravel(), PANELS):
        ax.set_facecolor(SURFACE)
        tm = cr.groupby(["condition", "trial"])[col].mean().unstack(0) * scale
        top = float(np.nanmax(tm.values))
        for i, a in enumerate(arms):
            v = tm[a].dropna().values
            ax.bar(i, v.mean(), width=0.55, color=color[a], alpha=0.28, zorder=1, linewidth=0)
            ax.hlines(v.mean(), i - 0.275, i + 0.275, color=color[a], linewidth=2.5, zorder=3)
            ax.scatter(i + rng.uniform(-0.17, 0.17, len(v)), v, s=34, color=color[a], edgecolor=SURFACE, linewidth=1.2, zorder=4)
            ax.text(i, top * 1.07, fmt.format(v.mean()), ha="center", va="bottom", fontsize=11, color=TEXT, fontweight="bold")
        ax.set_xticks(range(len(arms)))
        ax.set_xticklabels([short[a] for a in arms], fontsize=9, color=TEXT)
        ax.set_ylim(0, top * 1.22)
        if col == "died":
            ax.set_yticks(range(0, 101, 20))
        ax.set_title(title, loc="left", fontsize=11.5, color=TEXT, pad=10)
        ax.grid(axis="y", color=GRID, linewidth=0.8, zorder=0)
        ax.tick_params(axis="y", colors=MUTED, labelsize=9, length=0)
        ax.tick_params(axis="x", length=0)
        for s in ("top", "right", "left"):
            ax.spines[s].set_visible(False)
        ax.spines["bottom"].set_color(GRID)
    cap = spec.get("name", name)
    fig.suptitle(f"{cap}: per-arm comparison", x=0.01, ha="left", fontsize=13, color=TEXT, fontweight="bold")
    fig.text(0.01, 0.005, f"Dot = one trial (mean of its {cr.groupby(['condition','trial']).size().max()} creatures; {n_trials} trials per arm); line and number = arm mean of the trial means.",
             fontsize=8.5, color=MUTED, ha="left", va="bottom")
    fig.tight_layout(rect=(0, 0.03, 1, 0.95))
    out = fig_dir / "arms.png"
    fig.savefig(out, dpi=170, facecolor=SURFACE)
    print(out)


if __name__ == "__main__":
    main(sys.argv[1])
