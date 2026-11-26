"""Aggregates behind the p117_kalman report figures, computed once from the extracted Parquet and cached as JSON.

    python3 analysis/diagnostics/p117_kalman_aggregates.py <experiment>   # writes docs/reports/figures/<experiment>/aggregates.json
"""
import json
import pathlib
import sys

import duckdb
import numpy as np
import pandas as pd
import yaml

sys.path.insert(0, ".")
from analysis.experiments import arbiter_ladder as al  # noqa: E402


def main(name):
    spec = yaml.safe_load(open(f"experiments/{name}.yml"))
    data = pathlib.Path(spec["data_dir"])
    arms = [c["key"] for c in spec["conditions"]]
    c = duckdb.connect()
    q = lambda s: c.sql(s).df()
    g = lambda arm, t: f"read_parquet('{data}/{arm}/trial_*/{t}.parquet', union_by_name=true)"
    out = {"arms": arms}

    # 1. Decisions: who decides, how the pick was made, what was executed
    dec, conf = {}, {}
    for a in arms:
        d = q(f"""SELECT deciding_controller AS ctl, source, (executed_action = 'EAT') AS eat, count(*) AS n
                  FROM {g(a, 'arbitration_decisions')} GROUP BY ALL""")
        dec[a] = d.to_dict("records")
        h = q(f"""SELECT floor(least(operant_conf, 0.9999) * 50) / 50 AS bin, count(*) AS n
                  FROM {g(a, 'arbitration_decisions')} WHERE operant_conf IS NOT NULL GROUP BY 1 ORDER BY 1""")
        conf[a] = h.to_dict("records")
    out["decisions"], out["operant_conf_hist"] = dec, conf

    # 2. What is eaten (fruit only: mouth_interactions has no plants), overall and by 10-minute window of life
    eat = {}
    for a in arms:
        e = q(f"""SELECT m.object_type, floor((m.time - cr.born_time) / 600000.0) AS win, count(*) AS n
                  FROM {g(a, 'mouth_interactions')} m JOIN {g(a, 'creatures')} cr USING (creature_key, trial)
                  WHERE m.interaction_type = 'EAT' GROUP BY ALL""")
        eat[a] = e.to_dict("records")
    out["eats"] = eat

    # 3. Drive levels over life: per creature and minute the mean level, then per arm median and quartiles over the creatures alive
    drv = {}
    for a in arms:
        d = q(f"""WITH x AS (
                    SELECT d.trial, d.creature_key, floor((d.time - cr.born_time) / 60000.0) AS minute,
                           avg(d.final_hunger) AS hunger, avg(d.final_pain) AS pain, avg(d.final_sleep) AS sleep
                    FROM {g(a, 'drives')} d JOIN {g(a, 'creatures')} cr USING (creature_key, trial) GROUP BY ALL)
                  SELECT minute, count(*) AS alive,
                         quantile_cont(hunger, 0.5) AS hunger_med, quantile_cont(hunger, 0.25) AS hunger_q1, quantile_cont(hunger, 0.75) AS hunger_q3, quantile_cont(hunger, 0.9) AS hunger_q90, avg((hunger >= 3)::int) AS share_hunger_ge3,
                         quantile_cont(pain, 0.5) AS pain_med, quantile_cont(pain, 0.75) AS pain_q3,
                         quantile_cont(sleep, 0.5) AS sleep_med
                  FROM x WHERE minute >= 0 GROUP BY 1 ORDER BY 1""")
        drv[a] = d.to_dict("records")
    out["drives"] = drv

    # 4. Cause of death: the highest drive in each dead creature's last drives row, and its time of death
    death = {}
    for a in arms:
        d = q(f"""WITH last AS (
                    SELECT d.trial, d.creature_key, arg_max(d.final_hunger, d.time) AS hunger, arg_max(d.final_pain, d.time) AS pain,
                           arg_max(d.final_sleep, d.time) AS sleep, arg_max(d.final_tedium, d.time) AS tedium
                    FROM {g(a, 'drives')} d GROUP BY ALL)
                  SELECT cr.trial, cr.creature_key, cr.observed_s, l.hunger, l.pain, l.sleep, l.tedium
                  FROM {g(a, 'creatures')} cr JOIN last l USING (creature_key, trial) WHERE cr.died""")
        death[a] = d.to_dict("records")
    out["deaths"] = death

    # 5. How episodes end
    eps = {}
    for a in arms:
        d = q(f"""SELECT close_type, count(*) AS n FROM (
                    SELECT DISTINCT trial, creature_key, episode_id, close_type FROM {g(a, 'episode_returns')}) GROUP BY 1""")
        eps[a] = d.to_dict("records")
    out["episodes"] = eps

    # 6. Operant calibration statistic per trial (recipe 5), and one-step z by basis for the Kalman arms
    cal, zb = {}, {}
    for a in arms:
        vals = []
        for p in sorted(data.glob(f"{a}/trial_*/operant_updates.parquet")):
            r = al.operant_calibration(pd.read_parquet(p))
            vals.append(None if r["statistic"] is None or np.isnan(r["statistic"]) else float(r["statistic"]))
        cal[a] = vals
        cols = q(f"DESCRIBE SELECT * FROM {g(a, 'operant_updates')}")["column_name"].tolist()
        if "q_prior" in cols:
            zb[a] = q(f"""SELECT basis_prior AS basis, count(*) AS n,
                                 avg((sample - q_prior) / sqrt(se_prior * se_prior + sigma * sigma)) AS mean_z,
                                 var_pop((sample - q_prior) / sqrt(se_prior * se_prior + sigma * sigma)) AS var_z
                          FROM {g(a, 'operant_updates')} WHERE q_prior IS NOT NULL GROUP BY 1""").to_dict("records")
    out["calibration"], out["z_by_basis"] = cal, zb

    path = pathlib.Path(f"docs/reports/figures/{name}/aggregates.json")
    path.write_text(json.dumps(out, default=float))
    print(path, path.stat().st_size)


if __name__ == "__main__":
    main(sys.argv[1])
