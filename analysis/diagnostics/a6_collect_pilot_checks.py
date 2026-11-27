"""Checks C1-C6 of docs/experiments/a6_collect_pilot_recipe.md on ml/data_a6_collect_pilot."""
import duckdb

ROOT = "ml/data_a6_collect_pilot/*/trial_*"
c = duckdb.connect()
for t in ["decision_steps", "cycle_outcomes", "decision_percepts", "drive_outcomes", "arbitration_decisions", "creatures"]:
    c.execute(f"CREATE VIEW {t} AS SELECT * FROM read_parquet('{ROOT}/{t}.parquet', union_by_name=true)")


def q(title, sql):
    print(f"\n## {title}")
    print(c.execute(sql).df().to_string(index=False))


q("C1 rows per table and trial", """
  SELECT 'decision_steps' t, trial, count(*) n FROM decision_steps GROUP BY trial
  UNION ALL SELECT 'cycle_outcomes', trial, count(*) FROM cycle_outcomes GROUP BY trial
  UNION ALL SELECT 'decision_percepts', trial, count(*) FROM decision_percepts GROUP BY trial
  UNION ALL SELECT 'arbitration_decisions', trial, count(*) FROM arbitration_decisions GROUP BY trial ORDER BY 1, 2""")
q("C1 decision_steps vs arbitration_decisions per creature (rows that differ)", """
  SELECT count(*) creatures, sum(CASE WHEN d.n = a.n THEN 1 ELSE 0 END) equal FROM
  (SELECT trial, creature_key, count(*) n FROM decision_steps GROUP BY ALL) d
  JOIN (SELECT trial, creature_key, count(*) n FROM arbitration_decisions GROUP BY ALL) a USING (trial, creature_key)""")
q("drive_outcomes: distinct actions (find the BASELINE marker)", "SELECT action, count(*) n FROM drive_outcomes GROUP BY 1 ORDER BY 2 DESC")
q("C2 conservation: cycle_outcomes vs non-baseline drive_outcomes (sums per trial)", """
  SELECT 'cycle_outcomes' src, trial, round(sum(d_hunger),4) d_hunger, round(sum(d_sleep),4) d_sleep, round(sum(d_pain),4) d_pain, round(sum(d_tedium),4) d_tedium, count(*) n
  FROM cycle_outcomes GROUP BY trial
  UNION ALL SELECT 'drive_outcomes', trial, round(sum(d_hunger),4), round(sum(d_sleep),4), round(sum(d_pain),4), round(sum(d_tedium),4), count(*)
  FROM drive_outcomes WHERE action <> 'BASELINE' GROUP BY trial ORDER BY 2, 1""")
q("C4 share of decisions by hunger level", """
  SELECT trial, count(*) decisions, round(100*avg(CASE WHEN h_hunger >= 4 THEN 1 ELSE 0 END),2) pct_h4,
         round(100*avg(CASE WHEN h_hunger >= 6 THEN 1 ELSE 0 END),2) pct_h6, round(max(h_hunger),2) max_h
  FROM decision_steps GROUP BY trial ORDER BY trial""")
q("C5 percepts per decision, rows per creature-hour", """
  SELECT p.trial, round(avg(n),2) mean_percepts, quantile_cont(n,0.5) p50, quantile_cont(n,0.95) p95 FROM
  (SELECT trial, creature_key, cycle, count(*) n FROM decision_percepts GROUP BY ALL) p GROUP BY p.trial ORDER BY 1""")
q("C5 hours observed and rows per creature-hour", """
  SELECT c.trial, round(sum(observed_s)/3600,2) creature_hours,
    round((SELECT count(*) FROM decision_steps d WHERE d.trial=c.trial)/(sum(observed_s)/3600)) steps_per_h,
    round((SELECT count(*) FROM decision_percepts d WHERE d.trial=c.trial)/(sum(observed_s)/3600)) percepts_per_h,
    round((SELECT count(*) FROM cycle_outcomes d WHERE d.trial=c.trial)/(sum(observed_s)/3600)) outcomes_per_h
  FROM creatures c GROUP BY c.trial ORDER BY 1""")
q("C6 survival at the 20-minute cap", "SELECT trial, count(*) creatures, sum(CASE WHEN died THEN 1 ELSE 0 END) died, round(avg(observed_s),0) mean_observed_s FROM creatures GROUP BY trial ORDER BY 1")

# C3: lag between a K row and the decision of the act that caused it.
# drive_outcomes (non-baseline) and cycle_outcomes have one row per attributed K each, in the same order per creature; the K values must match row by row.
q("C3 lag: pair K rows by order per creature, then find the latest decision with the same action and target at or before the stamp", """
  WITH d AS (SELECT trial, creature_key, action, target_key, d_hunger, d_pain, row_number() OVER (PARTITION BY trial, creature_key ORDER BY seq) rn
             FROM drive_outcomes WHERE action <> 'BASELINE'),
       o AS (SELECT trial, creature_key, cycle, d_hunger, d_pain, row_number() OVER (PARTITION BY trial, creature_key ORDER BY cycle, d_hunger, d_pain) rn
             FROM cycle_outcomes),
       j AS (SELECT o.trial, o.creature_key, o.cycle, d.action, d.target_key, abs(o.d_hunger-d.d_hunger)+abs(o.d_pain-d.d_pain) mism
             FROM o JOIN d USING (trial, creature_key, rn))
  SELECT count(*) paired, round(100*avg(CASE WHEN mism < 1e-9 THEN 1 ELSE 0 END),1) pct_k_equal FROM j""")
q("C3 lag distribution (cycles between the stamp and the latest same-action, same-target decision at or before it)", """
  WITH d AS (SELECT trial, creature_key, action, target_key, d_hunger, d_pain, seq, row_number() OVER (PARTITION BY trial, creature_key ORDER BY seq) rn
             FROM drive_outcomes WHERE action <> 'BASELINE'),
       o AS (SELECT trial, creature_key, cycle, d_hunger, d_pain, row_number() OVER (PARTITION BY trial, creature_key ORDER BY cycle, d_hunger, d_pain) rn
             FROM cycle_outcomes),
       j AS (SELECT o.trial, o.creature_key, o.cycle, d.action, d.target_key FROM o JOIN d USING (trial, creature_key, rn)),
       m AS (SELECT j.trial, j.creature_key, j.cycle, j.action, j.target_key, max(s.cycle) dec_cycle
             FROM j JOIN decision_steps s ON s.trial=j.trial AND s.creature_key=j.creature_key AND s.action=j.action AND s.target_key=j.target_key AND s.cycle <= j.cycle
             GROUP BY ALL)
  SELECT CASE WHEN lag >= 5 THEN '5+' ELSE cast(lag AS varchar) END lag, count(*) n, round(100.0*count(*)/sum(count(*)) OVER (),1) pct
  FROM (SELECT cycle - dec_cycle lag FROM m) GROUP BY 1 ORDER BY 1""")
