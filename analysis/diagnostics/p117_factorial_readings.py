"""The pre-registered readings of docs/experiments/p117_factorial_recipe.md (section 3), from the arbiter_ladder module's per-creature output
(docs/reports/figures/p117_factorial/creature_metrics.csv) and the decision tables.

    /opt/homebrew/bin/python3.13 analysis/diagnostics/p117_factorial_readings.py

F0: a cell is ceiling-limited if >= 11 of its 12 trials end with every creature alive at the cap; floor-limited if all its creatures die within 600 s.
F1: A3 - A5 per cell; F2: each prior removed minus both on, per controller. Primary: observed time (trial means, paired by seed: paired t, two-sided,
and the sign count) and the creature-level log-rank test; secondary: deaths, d(H) over life.
"""
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from analysis.dl2l_analysis.stats import survival_comparison  # noqa: E402

CAP_S = 3600.0
ROOT = Path("ml/data_p117_factorial")
CELLS = [(1, 1), (1, 0), (0, 1), (0, 0)]


def key(arm, cell):
    return f"{arm}_L{cell[0]}T{cell[1]}"


def main():
    cr = pd.read_csv("docs/reports/figures/p117_factorial/creature_metrics.csv")
    cr["alive_at_cap"] = (cr["died"] == 0)
    print("== Per cell (creatures pooled; trial means in brackets)")
    rows = []
    for arm in ("a3", "a5"):
        for cell in CELLS:
            k = key(arm, cell)
            d = cr[cr.condition == k]
            t = d.groupby("trial").agg(all_alive=("alive_at_cap", "all"), obs=("observed_s", "mean"), dead=("died", "sum"),
                                       max_obs=("observed_s", "max"), dH=("dH_life", "mean"))
            ceiling = int(t["all_alive"].sum()) >= 11
            floor = bool((d["died"] == 1).all() and (d["observed_s"] <= 600).all())
            rows.append({"cell": k, "deaths": f"{int(d.died.sum())}/{len(d)}", "obs mean s": round(d.observed_s.mean()),
                         "trials all alive": int(t["all_alive"].sum()), "dH life": round(d.dH_life.mean(), 3),
                         "EAT/min": round(d.eat_per_min.mean(), 1), "F0": "CEILING" if ceiling else ("FLOOR" if floor else "readable")})
    print(pd.DataFrame(rows).to_string(index=False))

    def contrast(later, earlier):
        tm = cr.groupby(["trial", "condition"])[["observed_s", "dH_life", "died"]].mean().unstack("condition")
        out = {}
        for m in ("observed_s", "dH_life", "died"):
            diff = (tm[m][later] - tm[m][earlier]).dropna()
            p = stats.ttest_1samp(diff, 0).pvalue if diff.std() > 0 else float("nan")
            out[m] = (diff.mean(), diff.std(ddof=1), int((diff > 0).sum()), int((diff < 0).sum()), p)
        s = survival_comparison(cr, later, earlier)
        return out, s

    def show(title, pairs):
        print(f"\n== {title}  (later - earlier; per-seed paired, 12 seeds)")
        for later, earlier in pairs:
            out, s = contrast(later, earlier)
            o, dh, de = out["observed_s"], out["dH_life"], out["died"]
            print(f"{later} - {earlier}: observed time {o[0]:+.0f} s (SD {o[1]:.0f}; +{o[2]}/-{o[3]} seeds; paired t p = {o[4]:.3g}); "
                  f"deaths share {de[0]:+.3f} (+{de[2]}/-{de[3]}); d(H) {dh[0]:+.3f} (p = {dh[4]:.3g}); "
                  f"deaths {s['deaths_a']}/{s['n_a']} vs {s['deaths_b']}/{s['n_b']}, log-rank p = {s['p_logrank']:.3g}")

    show("F1: arbitration (A3) - fixed order (A5), per cell", [(key("a3", c), key("a5", c)) for c in CELLS])
    show("F2: prior removed - both on, per controller",
         [(key(a, c), key(a, (1, 1))) for a in ("a3", "a5") for c in CELLS[1:]])

    print("\n== B (budget) and controller share, per cell")
    for arm in ("a3", "a5"):
        for cell in CELLS:
            k = key(arm, cell)
            parts = [pd.read_parquet(ROOT / k / f"trial_{t}" / "arbitration_decisions.parquet",
                                     columns=["efficiency", "affordable_controller", "deciding_controller", "executed_source"])
                     for t in range(1, 13)]
            d = pd.concat(parts)
            print(f"{k}: decisions {len(d):,}; efficiency mean {d.efficiency.mean():.2f}; episodic affordable "
                  f"{(d.affordable_controller == 'EPISODIC').mean():.3f}, decides {(d.deciding_controller == 'EPISODIC').mean():.3f}; "
                  f"executed != chosen {(d.executed_source == 'OTHER_CANDIDATE').mean():.4f}")


if __name__ == "__main__" and len(sys.argv) == 1:
    main()


def figure():
    """Kaplan-Meier survival per cell, A3 and A5 overlaid: docs/reports/figures/p117_factorial/factorial_km.png."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from analysis.dl2l_analysis.stats import kaplan_meier

    cr = pd.read_csv("docs/reports/figures/p117_factorial/creature_metrics.csv")
    titles = {(1, 1): "lock on, tendency on", (1, 0): "lock on, tendency off", (0, 1): "lock off, tendency on", (0, 0): "lock off, tendency off"}
    fig, axes = plt.subplots(1, 4, figsize=(16, 3.8), sharey=True)
    for ax, cell in zip(axes, CELLS):
        for arm, color, label in (("a3", "#1b7837", "A3 arbitration"), ("a5", "#762a83", "A5 fixed order")):
            d = cr[cr.condition == key(arm, cell)]
            t, s = kaplan_meier(d["observed_s"].to_numpy(), d["died"].to_numpy())
            if len(t):
                ax.step(np.append(np.insert(t, 0, 0), CAP_S), np.append(np.insert(s, 0, 1.0), s[-1]), where="post", color=color,
                        label=f"{label} ({int(d.died.sum())}/{len(d)} died)")
            else:
                ax.plot([0, CAP_S], [1, 1], color=color, label=f"{label} (0/{len(d)} died)")
        ax.set(title=titles[cell], xlabel="observed time (s)", xlim=(0, CAP_S), ylim=(0, 1.02))
        ax.grid(alpha=0.3)
        ax.legend(fontsize=8, loc="lower left")
    axes[0].set_ylabel("survival (Kaplan-Meier)")
    fig.suptitle("p117_factorial: survival per cell, 60 creatures per arm (12 trials x 5), censored at the 60-minute cap")
    fig.tight_layout()
    out = Path("docs/reports/figures/p117_factorial/factorial_km.png")
    fig.savefig(out, dpi=130)
    print(f"  -> {out}")


if __name__ == "__main__" and len(sys.argv) > 1 and sys.argv[1] == "figure":
    figure()
