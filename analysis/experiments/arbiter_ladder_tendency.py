"""Analysis: p107_tendency — A2 under three action-tendency variants, learning vs frozen operant table, pilot and scarce worlds.

CAMPOS arms come from p107_operant (a2n, a2f, sn, sf); MAPA (m*) and no filter (x*) from p107_tendency. Same seeds.
    PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p107_tendency
Recipe: docs/experiments/p107_tendency_recipe.md.
"""
from __future__ import annotations

import duckdb

from analysis.dl2l_analysis.config import REPO_ROOT, Condition, ExperimentAnalysis
from analysis.experiments import arbiter_ladder as al

OP, TE = REPO_ROOT / "ml" / "data_p107_operant", REPO_ROOT / "ml" / "data_p107_tendency"
ARMS = [("a2n", "CAMPOS learning", OP), ("a2f", "CAMPOS frozen", OP), ("mn", "MAPA learning", TE), ("mf", "MAPA frozen", TE),
        ("xn", "NONE learning", TE), ("xf", "NONE frozen", TE),
        ("sn", "scarce CAMPOS learning", OP), ("sf", "scarce CAMPOS frozen", OP), ("smn", "scarce MAPA learning", TE),
        ("smf", "scarce MAPA frozen", TE), ("sxn", "scarce NONE learning", TE), ("sxf", "scarce NONE frozen", TE)]
PAIRS = [("mn", "mf"), ("xn", "xf"), ("smn", "smf"), ("sxn", "sxf"), ("mn", "a2n"), ("xn", "a2n"), ("smn", "sn"), ("sxn", "sn"),
         ("a2n", "a2f"), ("sn", "sf")]
COLORS = ["#d8733b", "#9467bd", "#1f77b4", "#aec7e8", "#d62728", "#ff9896", "#2ca02c", "#8c564b", "#17becf", "#9edae5", "#e377c2", "#f7b6d2"]


def _extra(cfg) -> None:
    con = duckdb.connect()
    rows = {}
    for cond in cfg.cond_keys:
        for t in cfg.trial_range:
            d = cfg.data_dir_for(cond) / cond / f"trial_{t}"
            if not (d / "arbitration_decisions.parquet").exists():
                continue
            g = con.sql(f"SELECT avg((object_type = 'GRAY_APPLE')::INT) FROM '{d}/mouth_interactions.parquet' WHERE interaction_type = 'EAT'").fetchone()[0]
            r = con.sql(f"""SELECT avg((operant_conf >= 0.8 AND candidate_count >= 2)::INT) / nullif(avg((candidate_count >= 2)::INT), 0),
                 avg((least(best_q, coalesce(runnerup_q, 0)) < -0.5)::INT),
                 avg((chosen_action = 'SLEEP')::INT), avg((chosen_action = 'AVOID')::INT), avg((chosen_action = 'APPROACH')::INT),
                 avg((chosen_action = 'EAT' AND chosen_object_type IN ('RED_APPLE', 'GREEN_APPLE'))::INT),
                 avg((chosen_action = 'EAT' AND chosen_object_type = 'GRAY_APPLE')::INT)
                 FROM '{d}/arbitration_decisions.parquet'""").fetchone()
            rows[(cond, t)] = (g or 0.0,) + tuple(x or 0.0 for x in r)
    print("\ncond trial | gray share of EATs | decisive | next to known-bad gray | SLEEP | AVOID | APPROACH | EAT food | EAT gray (shares of decisions)")
    for (cond, t), r in sorted(rows.items()):
        print(f"{cond:4s} {t} | " + " | ".join(f"{x:.4f}" for x in r))
    for a, b in PAIRS:
        ds = [rows[(a, t)][0] - rows[(b, t)][0] for t in cfg.trial_range if (a, t) in rows and (b, t) in rows]
        print(f"{a}-{b}: gray-share diffs {[round(x, 4) for x in ds]}")


def run(cfg) -> None:
    conds = [Condition(k, lab, COLORS[i]) for i, (k, lab, _) in enumerate(ARMS)]
    merged = ExperimentAnalysis(name="p107_tendency", conditions=conds, trials=3, data_dir=TE, cond_dir={k: d for k, _, d in ARMS})
    al.ARMS = [k for k, _, _ in ARMS]
    al.PAIRS = PAIRS
    al.run(merged)
    _extra(merged)
