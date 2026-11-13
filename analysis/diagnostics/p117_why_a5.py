"""Why does the fixed order (A5) beat arbitration (A3) in p117_factorial? Per-decision analysis of the decision tables, the engram store and the drives.
Report: docs/reports/p117_factorial_mechanism.md.

    /opt/homebrew/bin/python3.13 analysis/diagnostics/p117_why_a5.py            # tables to stdout
    /opt/homebrew/bin/python3.13 analysis/diagnostics/p117_why_a5.py figures    # figures to docs/reports/figures/p117_factorial/
"""
import sys
from pathlib import Path

import duckdb
import numpy as np
import pandas as pd

R = "ml/data_p117_factorial"
FIG = Path("docs/reports/figures/p117_factorial")
CELLS = ["a3_L1T1", "a5_L1T1", "a3_L1T0", "a5_L1T0", "a3_L0T0", "a5_L0T0"]
con = duckdb.connect()


def decisions(c):
    return f"read_parquet('{R}/{c}/trial_*/arbitration_decisions.parquet', filename=true)"


def trial(col="filename"):
    return f"regexp_extract({col},'trial_([0-9]+)',1)::int"


def operant_handover():
    """A food/aloe EAT available to the operant (its best Q >= 0.5): why it decided, and how often it ate food."""
    out = []
    for c in ("a3_L0T0", "a5_L0T0", "a3_L1T0", "a5_L1T0"):
        df = con.sql(f"""select case when affordable_controller='OPERANT' then 'episodic unaffordable'
                                 when operant_conf>=0.8 then 'operant decisive: stop' else 'operant more decisive than episodic' end why,
                            count(*) n, avg(case when chosen_action='EAT' and chosen_object_type in ('RED_APPLE','GREEN_APPLE') then 1.0 else 0 end) eat_food
                         from {decisions(c)} where candidate_count>1 and best_q>=0.5 and deciding_controller='OPERANT' group by all""").df()
        df.insert(0, "cell", c)
        out.append(df)
    return pd.concat(out)


def drives_over_time():
    """Mean drive levels and efficiency per 2-minute bin of age, per cell (all creatures alive in that bin)."""
    rows = []
    for c in CELLS:
        d = con.sql(f"""with dr as (select {trial()} t, creature_key k, time, final_hunger h, final_sleep s, final_pain p, final_tedium td
                                     from read_parquet('{R}/{c}/trial_*/drives.parquet', filename=true)),
                             b as (select {trial()} t, creature_key k, born_time from read_parquet('{R}/{c}/trial_*/creatures.parquet', filename=true))
                        select floor((time-born_time)/120000.0)*2 age_min, avg(h) hunger, avg(s) sleep, avg(p) pain, avg(td) tedium, count(distinct (dr.t, dr.k)) alive
                        from dr join b using (t, k) group by all order by age_min""").df()
        e = con.sql(f"""with x as (select {trial()} t, creature_key k, time_ms, efficiency, deciding_controller from {decisions(c)}),
                             b as (select {trial()} t, creature_key k, born_time from read_parquet('{R}/{c}/trial_*/creatures.parquet', filename=true))
                        select floor((time_ms-born_time)/120000.0)*2 age_min, avg(efficiency) efficiency,
                               avg(case when deciding_controller='EPISODIC' then 1.0 else 0 end) episodic_share,
                               avg(case when efficiency>=0.3 then 1.0 else 0 end) affordable
                        from x join b using (t, k) group by all order by age_min""").df()
        m = d.merge(e, on="age_min", how="left")
        m.insert(0, "cell", c)
        rows.append(m)
    return pd.concat(rows)


def figures():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    t = drives_over_time()
    t = t[(t.age_min <= 58) & (t.alive >= 5)]
    colors = {"a3": "#1b7837", "a5": "#762a83"}
    styles = {"L1T1": ":", "L1T0": "--", "L0T0": "-"}
    fig, axes = plt.subplots(2, 3, figsize=(16, 7.5))
    for ax, col, title in zip(axes.flat, ["hunger", "pain", "sleep", "tedium", "efficiency", "episodic_share"],
                              ["hunger", "pain", "sleep", "tedium", "efficiency e (episodic needs e >= 0.3)", "share of decisions made by episodic memory"]):
        for c in CELLS:
            d = t[t.cell == c]
            ax.plot(d.age_min, d[col], color=colors[c[:2]], ls=styles[c[3:]], label=c.replace("_", " "))
        if col == "efficiency":
            ax.axhline(0.3, color="grey", lw=0.8)
        ax.set(title=title, xlabel="age (minutes)")
        ax.grid(alpha=0.3)
    axes[0, 0].legend(fontsize=8, ncol=2)
    fig.suptitle("p117_factorial: means over the creatures alive at each age (2-minute bins, bins with at least 5 creatures); "
                 "dotted lock on/tendency on, dashed lock on/tendency off, solid both off")
    fig.tight_layout()
    fig.savefig(FIG / "mechanism_drives_over_time.png", dpi=120)
    print(f"  -> {FIG / 'mechanism_drives_over_time.png'}")

    # operant handover: P(eat food | food available to the operant) by number of candidates
    rows = []
    for c in ("a3_L0T0", "a5_L0T0"):
        rows.append(con.sql(f"""select '{c}' cell, candidate_count n, count(*) k,
                     avg(case when chosen_action='EAT' and chosen_object_type in ('RED_APPLE','GREEN_APPLE') then 1.0 else 0 end) p
                     from {decisions(c)} where deciding_controller='OPERANT' and best_q>=0.5 and candidate_count between 2 and 30 group by all""").df())
    h = pd.concat(rows)
    fig, ax = plt.subplots(figsize=(6.5, 4))
    for c, col in (("a3_L0T0", colors["a3"]), ("a5_L0T0", colors["a5"])):
        d = h[(h.cell == c) & (h.k >= 200)].sort_values("n")
        ax.plot(d.n, d.p, "o-", color=col, label=c.replace("_", " "))
    nn = np.arange(2, 31)
    # chance that Q=0.85 (SE~0) beats n-1 independent N(0,1) draws
    from scipy.stats import norm
    ax.plot(nn, norm.cdf(0.85) ** (nn - 1), color="grey", ls="--", label="P(0.85 beats n-1 draws of N(0,1))")
    ax.set(xlabel="candidates in the decision", ylabel="P(EAT food | operant decides, food EAT is its best)",
           title="The habit's Thompson draw over many untried candidates")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(FIG / "mechanism_operant_draw.png", dpi=120)
    print(f"  -> {FIG / 'mechanism_operant_draw.png'}")


if __name__ == "__main__":
    pd.set_option("display.width", 250)
    if len(sys.argv) > 1 and sys.argv[1] == "figures":
        figures()
    else:
        print(operant_handover().round(3).to_string(index=False))
        print(drives_over_time().round(3).to_string(index=False))
