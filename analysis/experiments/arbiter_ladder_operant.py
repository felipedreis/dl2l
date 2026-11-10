"""Analysis: p107_operant — per-outcome operant table against a frozen one, in the pilot world (a2n, a2f) and a scarce world (sn, sf).

    PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p107_operant
Recipe: docs/experiments/p107_operant_recipe.md (readings R1-R4, H1).
"""
from __future__ import annotations

import duckdb

from analysis.experiments import arbiter_ladder as al

PAIRS = [("a2n", "a2f"), ("sn", "sf")]


def _extra(cfg) -> None:
    """Per-trial gray share of EATs, operant decisiveness, and operant updates, paired by seed."""
    con = duckdb.connect()
    rows = {}
    for cond in cfg.cond_keys:
        for t in cfg.trial_range:
            d = cfg.data_dir_for(cond) / cond / f"trial_{t}"
            gray = con.sql(f"SELECT avg((object_type = 'GRAY_APPLE')::INT) FROM '{d}/mouth_interactions.parquet' "
                           f"WHERE interaction_type = 'EAT'").fetchone()[0]
            conf = con.sql(f"SELECT avg((operant_conf >= 0.8)::INT), avg(operant_conf) FROM '{d}/arbitration_decisions.parquet' "
                           f"WHERE candidate_count >= 2").fetchone()
            upd = (d / "operant_updates.parquet")
            nupd = con.sql(f"SELECT count(*), count(DISTINCT creature_key) FROM '{upd}'").fetchone() if upd.exists() else (0, 0)
            rows[(cond, t)] = (gray, conf[0], conf[1], nupd[0], nupd[1])
    print("\ncond trial | gray share of EATs | share decisive (conf>=0.8) | mean conf | operant updates | creatures with updates")
    for (cond, t), r in sorted(rows.items()):
        print(f"{cond:4s} {t} | {r[0]:.3f} | {r[1]:.4f} | {r[2]:.3f} | {r[3]} | {r[4]}")
    for a, b in PAIRS:
        diffs = [(rows[(a, t)][0] - rows[(b, t)][0], rows[(a, t)][1] - rows[(b, t)][1]) for t in cfg.trial_range if (a, t) in rows and (b, t) in rows]
        print(f"{a}-{b}: gray-share diffs {[round(x[0], 4) for x in diffs]}; decisive-share diffs {[round(x[1], 4) for x in diffs]}")


def run(cfg) -> None:
    al.ARMS = ["a2n", "a2f", "sn", "sf"]
    al.PAIRS = PAIRS
    al.run(cfg)
    _extra(cfg)
