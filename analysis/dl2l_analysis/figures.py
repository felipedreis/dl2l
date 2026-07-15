"""Matplotlib setup and save-to-fig-dir conventions shared across experiment
analyses.

Extracted from the header boilerplate duplicated in both
analysis/exp_rotten_fruit_v1.py (L24-45) and
analysis/exp_20260709_memory_vs_wm_v1.py (L20-51): Agg backend, warnings
suppression, and the FIG_DIR.mkdir + "fig.savefig(...); plt.close(fig)"
pattern repeated after every figure in both scripts.
"""

from __future__ import annotations

import warnings

import matplotlib

matplotlib.use("Agg")
warnings.filterwarnings("ignore")

import matplotlib.pyplot as plt  # noqa: E402  (must follow matplotlib.use)

DECILE_LABELS = [f"{i * 10}–{i * 10 + 10}%" for i in range(10)]


def setup(cfg) -> None:
    """Ensure cfg.fig_dir exists. Config.__post_init__ already does this,
    but call sites keep this for readability/parity with the old scripts'
    explicit `FIG_DIR.mkdir(parents=True, exist_ok=True)` line."""
    cfg.fig_dir.mkdir(parents=True, exist_ok=True)


def save(fig, name: str, cfg, dpi: int = 150) -> None:
    """Save + close a figure into cfg.fig_dir, printing the same
    "  -> <name>" line both scripts print after every figure."""
    path = cfg.fig_dir / name
    fig.savefig(path, dpi=dpi)
    plt.close(fig)
    print(f"  → {name}")


def boxplot_by_condition(ax, data_by_cond: dict, cfg, ylabel: str = "", title: str = ""):
    """Per-condition boxplot with palette-colored boxes — the pattern used
    for every lifetime/efficiency/hunger boxplot in both source scripts."""
    data = [data_by_cond.get(c.key, []) for c in cfg.conditions]
    bp = ax.boxplot(data, tick_labels=cfg.cond_labels, patch_artist=True)
    for patch, c in zip(bp["boxes"], cfg.conditions):
        patch.set_facecolor(c.color)
        patch.set_alpha(0.7)
    if ylabel:
        ax.set_ylabel(ylabel)
    if title:
        ax.set_title(title)
    ax.grid(axis="y", alpha=0.3)
    return bp
