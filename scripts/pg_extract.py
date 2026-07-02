#!/usr/bin/env python3
"""
Direct PostgreSQL extractor for DL2L simulation data.
Runs queries via `docker exec <container> psql` piping SQL through stdin.
No network configuration or psycopg2 needed — just the running db container.

Covers every Java extractor in src/.../analysis/extractor/:
  Ensemble (root output dir):
    lifetimes.csv            — creature lifetimes (LifetimesExtractor)
    distances.csv            — total traveled distance per creature (TraveledDistanceExtractor)
    perception_coverage.csv  — all perceptions across all creatures (PerceptionCoverageExtractor)

  Per-creature (output/{creature_key}:{seq}/):
    trajectory_emotions.csv      — regulation events with full emotional state
    trajectory_actions.csv       — chosen actions with selection type and target
    trajectory_perceptions.csv   — object perceptions at action time
    sleep_episodes.csv           — sleep episode stats
    reg_hist.csv                 — regulation batch histogram
    arousal_history.csv          — time-binned hunger + sleep arousal (ArousalHistoryExtractor)
    accumulated_choices.csv      — accumulated EAT/PLAY/TOUCH counts by selection type
                                   (AccumulatedChoicesOverTimeExtractor)
    behavioural_efficiency.csv   — simple/complex task efficiency over time
                                   (BehaviouralEfficiencyExtractor)
    eaten_nutrients.csv          — accumulated eaten-nutrient counts by object type
                                   (AccEatenNutrientsOverTimeExtractor)
    choices_over_time.csv        — per-event EAT/PLAY/TOUCH choices with selection type
                                   (ChoicesOverTimeExtractor)
    engrams.csv                  — operant conditioning engram records (EngramStateExtractor)
    consolidation_batches.csv    — per-batch sleep consolidation stats (ConsolidationBatchExtractor)

Usage:
    python3 scripts/pg_extract.py --out /tmp/output [--container db]

The container must be the name of the running PostgreSQL Docker container.
"""

import argparse
import csv
import io
import os
import subprocess
import sys

DB_USER = "postgres"
DB_NAME = "l2l"

KNOWN_TYPES = [
    "RED_APPLE", "GREEN_APPLE", "GRAY_APPLE", "ROTTEN_APPLE",
    "CACTUS", "ALOE", "Self",
]

# Time bucketing constants matching the Java extractors.
AROUSAL_TIME_CONST  = 1.0 / 60000.0   # ms → minutes
AROUSAL_TIME_PREC   = 3               # decimal places (0.001 min bins)
CHOICES_TIME_CONST  = 1.0 / 60000.0
CHOICES_TIME_PREC   = 5               # 0.01 min bins (Java uses precision=5 → 0.00001)
EFFICIENCY_TIME_PREC = 3
NUTRIENTS_TIME_PREC  = 1

ACTION_SELECTION_TYPES = ["AFFORDANCE", "MEMORY", "RANDOM"]
FRUIT_TYPES = ["RED_APPLE", "GREEN_APPLE", "GRAY_APPLE", "ROTTEN_APPLE"]


def psql_copy(container: str, sql: str) -> list:
    """Run COPY (<sql>) TO STDOUT WITH CSV HEADER via docker exec stdin.
    Returns a list of rows (first row = header strings), empty list on error.
    """
    copy = f"COPY ({sql}) TO STDOUT WITH CSV HEADER;\n"
    result = subprocess.run(
        ["docker", "exec", "-i", container,
         "psql", "-U", DB_USER, "-d", DB_NAME],
        input=copy, capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"psql error: {result.stderr.strip()}", file=sys.stderr)
        return []
    return list(csv.reader(io.StringIO(result.stdout)))


def psql_query(container: str, sql: str) -> list:
    """Run a plain SELECT (not COPY) and return rows as list-of-lists."""
    result = subprocess.run(
        ["docker", "exec", "-i", container,
         "psql", "-U", DB_USER, "-d", DB_NAME, "-t", "-A", "-F", ","],
        input=sql + ";\n", capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"psql error: {result.stderr.strip()}", file=sys.stderr)
        return []
    return [line.split(",") for line in result.stdout.strip().splitlines() if line]


def decode_type_hex(hex_str: str) -> str:
    """Decode a Java-serialized WorldObjectType from psql hex output."""
    try:
        data = bytes.fromhex(hex_str).decode("latin-1")
    except Exception:
        return "UNKNOWN"
    for name in KNOWN_TYPES:
        if name in data:
            return name
    return "UNKNOWN"


def write_csv(path: str, rows: list):
    """Write rows (first = header) to CSV file. Skips if no data rows."""
    if len(rows) < 2:
        return
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="") as f:
        csv.writer(f).writerows(rows)


def pivot_long_to_wide(rows: list, time_col: str, key_col: str, val_col: str,
                       all_keys: list) -> list:
    """Pivot a long-format (time, key, value) list into wide format.

    Returns [header] + data rows where header = [time_col] + all_keys.
    Accumulates values within each time bucket (sum, not last).
    """
    from collections import defaultdict
    buckets: dict = defaultdict(lambda: defaultdict(float))
    time_idx = None
    key_idx  = None
    val_idx  = None

    header = rows[0]
    time_idx = header.index(time_col)
    key_idx  = header.index(key_col)
    val_idx  = header.index(val_col)

    for row in rows[1:]:
        t   = row[time_idx]
        k   = row[key_idx]
        v   = float(row[val_idx]) if row[val_idx] else 0.0
        buckets[t][k] += v

    out_header = [time_col] + all_keys
    out_rows   = [out_header]
    for t in sorted(buckets.keys(), key=lambda x: float(x)):
        out_rows.append([t] + [buckets[t].get(k, 0) for k in all_keys])
    return out_rows


def accumulate_wide(rows: list, value_cols: list) -> list:
    """In-place cumulative sum on value columns (skips header row)."""
    acc = {col: 0.0 for col in value_cols}
    result = [rows[0]]
    for row in rows[1:]:
        new_row = list(row)
        for i, col in enumerate(value_cols, start=1):
            col_idx = rows[0].index(col)
            acc[col] += float(new_row[col_idx]) if new_row[col_idx] else 0.0
            new_row[col_idx] = acc[col]
        result.append(new_row)
    return result


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out",       required=True)
    p.add_argument("--container", default="db")
    args = p.parse_args()

    c   = args.container
    out = args.out
    os.makedirs(out, exist_ok=True)

    # ── Creature list ─────────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, creature_sequential,
               borntime,
               CASE WHEN deadtime > 0
                    THEN (deadtime - borntime) / 1000.0
                    ELSE NULL
               END AS lifetime_s
        FROM data.creature_state
        ORDER BY creature_key
    """)
    if len(rows) < 2:
        print("No creatures found in database.", file=sys.stderr)
        sys.exit(1)

    header, data = rows[0], rows[1:]
    print(f"Found {len(data)} creatures", file=sys.stderr)

    # ── Ensemble: lifetimes.csv ───────────────────────────────────────────────
    lt_rows = [["ids", "lifetime"]]
    for r in data:
        if r[3]:  # lifetime_s not NULL
            lt_rows.append([r[0], r[3]])
    write_csv(os.path.join(out, "lifetimes.csv"), lt_rows)

    # ── Ensemble: distances.csv ───────────────────────────────────────────────
    dist_rows = psql_copy(c, """
        SELECT css.key AS ids, SUM(bs.speed) AS distances
        FROM data.body_state bs
        JOIN data.change_stimulus_state css ON bs.stimulusstate_id = css.id
        GROUP BY css.key
        ORDER BY css.key
    """)
    write_csv(os.path.join(out, "distances.csv"), dist_rows)

    # ── Ensemble: perception_coverage.csv ────────────────────────────────────
    perc_cov = psql_copy(c, """
        SELECT css.key AS "creatureKey",
               encode(oss.type, 'hex') AS object_type,
               oss.distance, oss.angle, css.time
        FROM data.object_seen_state oss
        JOIN data.change_stimulus_state css ON oss.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
    """)
    if len(perc_cov) > 1:
        type_col = perc_cov[0].index("object_type")
        for row in perc_cov[1:]:
            row[type_col] = decode_type_hex(row[type_col])
    write_csv(os.path.join(out, "perception_coverage.csv"), perc_cov)

    # ── Per-creature CSVs ─────────────────────────────────────────────────────
    for creature_key, creature_seq, born_time, _ in data:
        k    = creature_key
        bt   = born_time          # wall-clock ms at creature birth
        cdir = os.path.join(out, f"{k}:{creature_seq}")

        # ── trajectory_emotions.csv ───────────────────────────────────────────
        rows = psql_copy(c, f"""
            SELECT css.key AS "creatureKey", css.time AS regulation_time,
                   es_f.hunger_arausal    AS final_hunger,
                   es_f.sleep_arausal     AS final_sleep,
                   es_f.apathy_arausal    AS final_apathy,
                   es_f.stress_arausal    AS final_stress,
                   es_f.pain_arausal      AS final_pain,
                   es_f.tedium_arausal    AS final_tedium,
                   es_f.fear_arausal      AS final_fear,
                   es_f.curiosity_arausal AS final_curiosity,
                   es_f.fertility_arausal AS final_fertility
            FROM data.internal_dynamic_state ids
            JOIN data.change_stimulus_state css ON ids.changestimulusstate_id = css.id
            JOIN data.emotional_state es_f      ON ids.finalemotionalstate_id  = es_f.id
            WHERE css.key = {k}
            ORDER BY css.time
        """)
        write_csv(os.path.join(cdir, "trajectory_emotions.csv"), rows)

        # ── trajectory_actions.csv ────────────────────────────────────────────
        rows = psql_copy(c, f"""
            SELECT css.key AS "creatureKey", css.time AS action_time,
                   cas.action               AS action_type,
                   cas.actionselectiontype  AS selection_type,
                   cas.key                  AS target_key,
                   COALESCE(cas.inference_duration_ms, 0) AS inference_time_ms
            FROM data.chosen_action_state cas
            JOIN data.change_stimulus_state css ON cas.changestimulusstate_id = css.id
            WHERE css.key = {k}
            ORDER BY css.time
        """)
        write_csv(os.path.join(cdir, "trajectory_actions.csv"), rows)

        # ── trajectory_perceptions.csv ────────────────────────────────────────
        rows = psql_copy(c, f"""
            SELECT css.key AS "creatureKey", css.time,
                   oss.key                    AS object_key,
                   encode(oss.type, 'hex')    AS object_type,
                   oss.distance, oss.angle, oss.direction
            FROM data.object_seen_state oss
            JOIN data.change_stimulus_state css ON oss.changestimulusstate_id = css.id
            WHERE css.key = {k}
            ORDER BY css.time
        """)
        if len(rows) > 1:
            type_col = rows[0].index("object_type")
            for row in rows[1:]:
                row[type_col] = decode_type_hex(row[type_col])
        write_csv(os.path.join(cdir, "trajectory_perceptions.csv"), rows)

        # ── sleep_episodes.csv ────────────────────────────────────────────────
        rows = psql_copy(c, f"""
            SELECT css.key AS creature_key,
                   ses.onset_cycle, ses.wake_cycle, ses.duration_ticks,
                   ses.engram_count, ses.mean_eligibility, ses.std_eligibility,
                   ses.batches_completed, ses.aborted
            FROM data.sleep_episode_state ses
            JOIN data.change_stimulus_state css ON ses.changestimulusstate_id = css.id
            WHERE css.key = {k}
            ORDER BY ses.onset_cycle
        """)
        write_csv(os.path.join(cdir, "sleep_episodes.csv"), rows)

        # ── reg_hist.csv ──────────────────────────────────────────────────────
        rows = psql_copy(c, f"""
            SELECT css.key AS "creatureKey",
                   rbs.regulating_count, rbs.batches
            FROM data.regulation_batch_stat rbs
            JOIN data.change_stimulus_state css ON rbs.changestimulusstate_id = css.id
            WHERE css.key = {k}
            ORDER BY rbs.regulating_count
        """)
        write_csv(os.path.join(cdir, "reg_hist.csv"), rows)

        # ── arousal_history.csv ───────────────────────────────────────────────
        # Time-binned (0.001 min = ~60 ms) average hunger + sleep arousal.
        # Matches ArousalHistoryExtractor / InternalDynamicState.getArousalOverTime.
        rows = psql_copy(c, f"""
            SELECT ROUND(((css.time - {bt}) / 60000.0)::NUMERIC, 3) AS time,
                   AVG(es.hunger_arausal) AS hunger,
                   AVG(es.sleep_arausal)  AS sleep
            FROM data.internal_dynamic_state ids
            JOIN data.change_stimulus_state css ON ids.changestimulusstate_id = css.id
            JOIN data.emotional_state es        ON ids.finalemotionalstate_id = es.id
            WHERE css.key = {k}
            GROUP BY 1 ORDER BY 1
        """)
        write_csv(os.path.join(cdir, "arousal_history.csv"), rows)

        # ── accumulated_choices.csv ───────────────────────────────────────────
        # EAT/PLAY/TOUCH actions binned by 0.01 min, grouped by selection type,
        # then accumulated. Matches AccumulatedChoicesOverTimeExtractor.
        raw = psql_copy(c, f"""
            SELECT ROUND(((css.time - {bt}) / 60000.0)::NUMERIC, 5) AS time,
                   cas.actionselectiontype AS selection_type,
                   COUNT(*) AS count
            FROM data.chosen_action_state cas
            JOIN data.change_stimulus_state css ON cas.changestimulusstate_id = css.id
            WHERE css.key = {k}
              AND cas.action IN ('EAT', 'PLAY', 'TOUCH')
            GROUP BY 1, 2 ORDER BY 1, 2
        """)
        if len(raw) > 1:
            wide = pivot_long_to_wide(raw, "time", "selection_type", "count",
                                      ACTION_SELECTION_TYPES)
            wide = accumulate_wide(wide, ACTION_SELECTION_TYPES)
            write_csv(os.path.join(cdir, "accumulated_choices.csv"), wide)

        # ── behavioural_efficiency.csv ────────────────────────────────────────
        # Average efficiency per time bin (0.001 min), separate simple/complex columns.
        # Matches BehaviouralEfficiencyExtractor.
        raw = psql_copy(c, f"""
            SELECT ROUND(CAST((css.time - {bt}) / 60000.0 AS NUMERIC), 3) AS time,
                   bes.complextask,
                   AVG(bes.behaviouralefficiency) AS efficiency
            FROM data.behavioural_efficiency_state bes
            JOIN data.change_stimulus_state css ON bes.changestimulusstate_id = css.id
            WHERE css.key = {k}
            GROUP BY 1, 2 ORDER BY 1, 2
        """)
        if len(raw) > 1:
            wide = pivot_long_to_wide(raw, "time", "complextask", "efficiency",
                                      ["false", "true"])
            # Rename columns to match Java output.
            wide[0] = ["time", "simpleEfficiency", "complexEfficiency"]
            write_csv(os.path.join(cdir, "behavioural_efficiency.csv"), wide)

        # ── eaten_nutrients.csv ───────────────────────────────────────────────
        # Accumulated EAT interactions per object type over time.
        # Matches AccEatenNutrientsOverTimeExtractor.
        raw = psql_copy(c, f"""
            SELECT ROUND(((css.time - {bt}) / 60000.0)::NUMERIC, 1) AS time,
                   mis.objecttype AS object_type,
                   COUNT(*) AS count
            FROM data.mouth_interactions_state mis
            JOIN data.change_stimulus_state css ON mis.changestimulusstate_id = css.id
            WHERE css.key = {k}
              AND mis.type = 'EAT'
            GROUP BY 1, 2 ORDER BY 1, 2
        """)
        if len(raw) > 1:
            wide = pivot_long_to_wide(raw, "time", "object_type", "count", FRUIT_TYPES)
            wide = accumulate_wide(wide, FRUIT_TYPES)
            write_csv(os.path.join(cdir, "eaten_nutrients.csv"), wide)

        # ── choices_over_time.csv ─────────────────────────────────────────────
        # Per-event EAT/PLAY/TOUCH actions with elapsed time and selection type.
        # Matches ChoicesOverTimeExtractor (raw event log, not binned).
        rows = psql_copy(c, f"""
            SELECT (css.time - {bt}) AS elapsed_ms,
                   cas.action AS action_type,
                   cas.actionselectiontype AS selection_type
            FROM data.chosen_action_state cas
            JOIN data.change_stimulus_state css ON cas.changestimulusstate_id = css.id
            WHERE css.key = {k}
              AND cas.action IN ('EAT', 'PLAY', 'TOUCH')
            ORDER BY css.time
        """)
        write_csv(os.path.join(cdir, "choices_over_time.csv"), rows)

        # ── engrams.csv ───────────────────────────────────────────────────────
        # Operant conditioning engram records.
        # Matches EngramStateExtractor / EngramState.getForCreature.
        rows = psql_copy(c, f"""
            SELECT es.creature_key, es.action_type,
                   es.lay_cycle, es.reinforced_cycle, es.cycle_gap,
                   es.eligibility, es.emotion_delta
            FROM data.engram_state es
            WHERE es.creaturekey = {k}
            ORDER BY es.reinforced_cycle
        """)
        write_csv(os.path.join(cdir, "engrams.csv"), rows)

        # ── consolidation_batches.csv ─────────────────────────────────────────
        # Per-batch stats for every sleep episode (onset_cycle, batch_index, loss).
        # Matches ConsolidationBatchExtractor.
        rows = psql_copy(c, f"""
            SELECT cbs.creature_key, cbs.onset_cycle,
                   cbs.batch_index, cbs.batch_size, cbs.loss
            FROM data.consolidation_batch_stat cbs
            WHERE cbs.creature_key = {k}
            ORDER BY cbs.onset_cycle, cbs.batch_index
        """)
        write_csv(os.path.join(cdir, "consolidation_batches.csv"), rows)

        print(f"  {k}:{creature_seq} done", file=sys.stderr)

    print("Extraction complete.", file=sys.stderr)


if __name__ == "__main__":
    main()
