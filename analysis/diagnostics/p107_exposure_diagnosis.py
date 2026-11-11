"""Why the per-outcome operant table makes A2 regulate worse (p107_operant, p107_tendency): exposure vs choice.

For each decision, "at food" / "at gray" = a perception of that object type within mouth range (10 + object radius) in the
preceding 250 ms (perceptions are logged at about 5 Hz, decisions at about 30 Hz). Reports, learning vs frozen arm: share of
decisions at food / at gray, P(choose EAT | at), P(executed EAT | at), P(asleep | at). Result (2026-11-11): learners eat at least
as readily when at food, but spend half as long at food and 2-3x as long next to gray apples they refuse.

    python3.13 analysis/diagnostics/p107_exposure_diagnosis.py
"""
import duckdb
c=duckdb.connect()
ARMS=[('a2n','a2f','ml/data_p107_operant','CAMPOS'),('mn','mf','ml/data_p107_tendency','MAPA'),('xn','xf','ml/data_p107_tendency','NONE')]
def stats(base,arm,kind):
    rad = "CASE object_type WHEN 'RED_APPLE' THEN 20 WHEN 'GREEN_APPLE' THEN 25 ELSE 20 END"
    types = "('RED_APPLE','GREEN_APPLE')" if kind=='food' else "('GRAY_APPLE')"
    q=f"""
    WITH d AS (SELECT trial, creature_key, time_ms, chosen_action, chosen_object_type, executed_action, executed_object_type
               FROM read_parquet('{base}/{arm}/trial_*/arbitration_decisions.parquet', union_by_name=true, hive_partitioning=false)),
         p AS (SELECT trial, creature_key, time AS ptime FROM read_parquet('{base}/{arm}/trial_*/perceptions.parquet', union_by_name=true)
               WHERE object_type IN {types} AND distance <= {rad}),
         j AS (SELECT d.*, p.ptime FROM d ASOF LEFT JOIN p ON d.trial=p.trial AND d.creature_key=p.creature_key AND d.time_ms >= p.ptime)
    SELECT count(*) n,
           avg((ptime IS NOT NULL AND time_ms - ptime <= 250)::INT) exposure,
           avg((chosen_action='EAT' AND chosen_object_type IN {types})::INT) FILTER (WHERE ptime IS NOT NULL AND time_ms - ptime <= 250) p_choose,
           avg((executed_action='EAT' AND executed_object_type IN {types})::INT) FILTER (WHERE ptime IS NOT NULL AND time_ms - ptime <= 250) p_exec,
           avg((executed_action='SLEEP')::INT) FILTER (WHERE ptime IS NOT NULL AND time_ms - ptime <= 250) p_sleep_at
    FROM j"""
    return c.sql(q).fetchone()
for ln, fz, base, name in ARMS:
    for kind in ['food','gray']:
        for arm in [ln, fz]:
            n,e,pc,pe,ps=stats(base,arm,kind)
            print(f"{name:6s} {kind:4s} {arm:4s} decisions {n:>8}  at {kind} {e:.4f}  P(choose EAT | at) {pc:.3f}  P(executed EAT | at) {pe:.3f}  P(asleep | at) {ps:.3f}")
