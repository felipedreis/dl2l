#!/usr/bin/env python3
"""
Comprehensive extractor for one DL2L simulation condition.
Writes Parquet files + a compressed pg_dump backup to <out>/<condition>/[trial_N/].

Usage:
    python3 scripts/exp_extract.py \
        --experiment 20260709_memory_vs_wm_v1 \
        --condition  1_baseline \
        --trial      1 \
        --out        ml/data_20260709_memory_vs_wm_v1 \
        [--container db] \
        [--skip-backup]

Each condition gets its own subdirectory (optionally under trial_N/) with one
Parquet file per table plus a db_backup.sql.gz.  A manifest.json at the
experiment root is created/updated with metadata (creature count, etc.).
"""

import argparse
import csv
import gzip
import io
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

DB_USER = "postgres"
DB_NAME = "l2l"

KNOWN_TYPES = [
    "RED_APPLE", "GREEN_APPLE", "GRAY_APPLE", "ROTTEN_APPLE",
    "CACTUS", "ALOE", "Self",
]


# ── psql helpers ──────────────────────────────────────────────────────────────

def psql_copy(container: str, sql: str) -> list:
    copy = f"COPY ({sql}) TO STDOUT WITH CSV HEADER;\n"
    r = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", DB_USER, "-d", DB_NAME],
        input=copy, capture_output=True, text=True,
    )
    if r.returncode != 0:
        print(f"  psql error: {r.stderr.strip()}", file=sys.stderr)
        return []
    return list(csv.reader(io.StringIO(r.stdout)))


def rows_to_df(rows: list) -> pd.DataFrame:
    if len(rows) < 2:
        return pd.DataFrame()
    return pd.DataFrame(rows[1:], columns=rows[0])


def decode_type_hex(hex_str: str) -> str:
    try:
        data = bytes.fromhex(hex_str).decode("latin-1")
    except Exception:
        return "UNKNOWN"
    for name in KNOWN_TYPES:
        if name in data:
            return name
    return "UNKNOWN"


def save(df: pd.DataFrame, path: Path, condition: str, trial: int | None = None):
    if df.empty:
        print(f"  (empty) {path.name}", file=sys.stderr)
        return
    df["condition"] = condition
    if trial is not None:
        df["trial"] = trial
    df.to_parquet(path, index=False)
    print(f"  → {path.name} ({len(df):,} rows)", file=sys.stderr)


def pg_dump(container: str, out_path: Path):
    print(f"  pg_dump → {out_path.name} …", file=sys.stderr)
    r = subprocess.run(
        ["docker", "exec", container,
         "pg_dump", "-U", DB_USER, "-d", DB_NAME,
         "--schema=data", "--no-owner", "--no-acl"],
        capture_output=True,
    )
    if r.returncode != 0:
        print(f"  pg_dump error: {r.stderr.decode()}", file=sys.stderr)
        return
    with gzip.open(out_path, "wb") as f:
        f.write(r.stdout)
    mb = out_path.stat().st_size / 1024 / 1024
    print(f"  backup: {mb:.1f} MB", file=sys.stderr)


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--experiment",  required=True,
                   help="Experiment name, e.g. 20260709_memory_vs_wm_v1")
    p.add_argument("--condition",   required=True,
                   help="Condition key, e.g. 1_baseline")
    p.add_argument("--out",         required=True,
                   help="Base output dir; condition subdir created inside it")
    p.add_argument("--container",   default="db",
                   help="Docker container name for the PostgreSQL DB")
    p.add_argument("--trial",       type=int, default=None,
                   help="Trial number; output placed in <out>/<cond>/trial_N/")
    p.add_argument("--skip-backup", action="store_true",
                   help="Skip pg_dump (faster, use when DB is still running long)")
    args = p.parse_args()

    cond    = args.condition
    c       = args.container
    trial   = args.trial
    if trial is not None:
        out_dir = Path(args.out) / cond / f"trial_{trial}"
    else:
        out_dir = Path(args.out) / cond
    out_dir.mkdir(parents=True, exist_ok=True)

    trial_label = f" trial={trial}" if trial is not None else ""
    print(f"Extracting {args.experiment}/{cond}{trial_label} from container '{c}' …",
          file=sys.stderr)

    # ── creatures ─────────────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, creature_sequential,
               borntime  AS born_time,
               deadtime  AS dead_time,
               CASE WHEN deadtime > 0
                    THEN (deadtime - borntime) / 1000.0
                    ELSE NULL
               END AS lifetime_s,
               gender
        FROM data.creature_state
        ORDER BY creature_key
    """)
    creatures_df = rows_to_df(rows)
    if creatures_df.empty:
        print("No creatures found — aborting.", file=sys.stderr)
        sys.exit(1)
    n_creatures = len(creatures_df)
    print(f"Found {n_creatures} creatures", file=sys.stderr)
    save(creatures_df, out_dir / "creatures.parquet", cond, trial)

    # ── actions ───────────────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT css.key      AS creature_key,
               css.time,
               cas.action   AS action_type,
               cas.actionselectiontype AS selection_type,
               cas.key      AS target_key,
               COALESCE(cas.inference_duration_ms, 0) AS inference_ms
        FROM data.chosen_action_state cas
        JOIN data.change_stimulus_state css
          ON cas.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
    """)
    save(rows_to_df(rows), out_dir / "actions.parquet", cond, trial)

    # ── drives (InternalDynamicState + EmotionalState initial/final) ──────────
    rows = psql_copy(c, """
        SELECT css.key  AS creature_key,
               css.time,
               es_i.hunger_arausal    AS init_hunger,
               es_i.sleep_arausal     AS init_sleep,
               es_i.apathy_arausal    AS init_apathy,
               es_i.stress_arausal    AS init_stress,
               es_i.pain_arausal      AS init_pain,
               es_i.tedium_arausal    AS init_tedium,
               es_i.fear_arausal      AS init_fear,
               es_i.curiosity_arausal AS init_curiosity,
               es_i.fertility_arausal AS init_fertility,
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
        JOIN data.change_stimulus_state css
          ON ids.changestimulusstate_id    = css.id
        JOIN data.emotional_state es_i
          ON ids.initialemotionalstate_id  = es_i.id
        JOIN data.emotional_state es_f
          ON ids.finalemotionalstate_id    = es_f.id
        ORDER BY css.key, css.time
    """)
    save(rows_to_df(rows), out_dir / "drives.parquet", cond, trial)

    # ── behavioural efficiency ─────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT css.key                         AS creature_key,
               css.time,
               bes.complextask::text           AS is_complex,
               bes.behaviouralefficiency       AS efficiency,
               bes.numberofobjects             AS n_objects
        FROM data.behavioural_efficiency_state bes
        JOIN data.change_stimulus_state css
          ON bes.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
    """)
    save(rows_to_df(rows), out_dir / "behavioural_efficiency.parquet", cond, trial)

    # ── body states ───────────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT css.key    AS creature_key,
               css.time,
               bs.initialx AS init_x,  bs.initialy AS init_y,
               bs.finalx   AS final_x, bs.finaly   AS final_y,
               bs.speed
        FROM data.body_state bs
        JOIN data.change_stimulus_state css
          ON bs.stimulusstate_id = css.id
        ORDER BY css.key, css.time
    """)
    save(rows_to_df(rows), out_dir / "body_states.parquet", cond, trial)

    # ── perceptions (ObjectSeenState) ─────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT css.key                    AS creature_key,
               css.time,
               encode(oss.type, 'hex')   AS object_type,
               oss.distance, oss.angle, oss.direction
        FROM data.object_seen_state oss
        JOIN data.change_stimulus_state css
          ON oss.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
    """)
    if len(rows) > 1:
        ti = rows[0].index("object_type")
        for row in rows[1:]:
            row[ti] = decode_type_hex(row[ti])
    save(rows_to_df(rows), out_dir / "perceptions.parquet", cond, trial)

    # ── mouth interactions ────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT css.key            AS creature_key,
               css.time,
               mis.type          AS interaction_type,
               mis.objecttype    AS object_type
        FROM data.mouth_interactions_state mis
        JOIN data.change_stimulus_state css
          ON mis.changestimulusstate_id = css.id
        ORDER BY css.key, css.time
    """)
    save(rows_to_df(rows), out_dir / "mouth_interactions.parquet", cond, trial)

    # ── sleep episodes ────────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, onset_cycle, wake_cycle, duration_ticks
        FROM data.sleep_episode_state
        ORDER BY creature_key, onset_cycle
    """)
    save(rows_to_df(rows), out_dir / "sleep_episodes.parquet", cond, trial)

    # ── neuromodulators ───────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, seq, dopamine, serotonin, orexin, circadian_phase
        FROM data.neuromodulator_state_log
        ORDER BY creature_key, seq
    """)
    save(rows_to_df(rows), out_dir / "neuromodulators.parquet", cond, trial)

    # ── endocrine ─────────────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, seq, cortisol_tonic, stress_level
        FROM data.endocrine_state_log
        ORDER BY creature_key, seq
    """)
    save(rows_to_df(rows), out_dir / "endocrine.parquet", cond, trial)

    # ── expectancy / RPE ──────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, cycle, mode, drive, drive_level,
               target, action, expected, reward, rpe
        FROM data.expectancy_state
        ORDER BY creature_key, cycle
    """)
    save(rows_to_df(rows), out_dir / "expectancy.parquet", cond, trial)

    # ── engrams ───────────────────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, action_type,
               lay_cycle, reinforced_cycle, cycle_gap,
               eligibility, emotion_delta
        FROM data.engram_state
        ORDER BY creature_key, reinforced_cycle
    """)
    save(rows_to_df(rows), out_dir / "engrams.parquet", cond, trial)

    # ── consolidation episodes ────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, onset_cycle, engram_count,
               mean_eligibility, std_eligibility,
               batches_completed, aborted
        FROM data.consolidation_episode_stat
        ORDER BY creature_key, onset_cycle
    """)
    save(rows_to_df(rows), out_dir / "consolidation_episodes.parquet", cond, trial)

    # ── consolidation batches ─────────────────────────────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, onset_cycle, batch_index, batch_size, loss
        FROM data.consolidation_batch_stat
        ORDER BY creature_key, onset_cycle, batch_index
    """)
    save(rows_to_df(rows), out_dir / "consolidation_batches.parquet", cond, trial)

    # ── memory traces (MemoryTraceConsolidator stats) ─────────────────────────
    rows = psql_copy(c, """
        SELECT creature_key, onset_cycle, engram_count, groups_consolidated
        FROM data.memory_trace_stat
        ORDER BY creature_key, onset_cycle
    """)
    save(rows_to_df(rows), out_dir / "memory_traces.parquet", cond, trial)

    # ── pg_dump backup ────────────────────────────────────────────────────────
    if not args.skip_backup:
        pg_dump(c, out_dir / "db_backup.sql.gz")

    # ── manifest ──────────────────────────────────────────────────────────────
    exp_dir       = Path(args.out)
    manifest_path = exp_dir / "manifest.json"
    if manifest_path.exists():
        with open(manifest_path) as f:
            manifest = json.load(f)
    else:
        manifest = {
            "experiment":  args.experiment,
            "conditions":  {},
            "created_at":  datetime.now(timezone.utc).isoformat(),
        }

    cond_entry = manifest["conditions"].setdefault(cond, {"trials": {}})
    trial_key = str(trial) if trial is not None else "default"
    cond_entry["trials"][trial_key] = {
        "creature_count": n_creatures,
        "extracted_at":   datetime.now(timezone.utc).isoformat(),
        "has_backup":     not args.skip_backup,
    }
    manifest["updated_at"] = datetime.now(timezone.utc).isoformat()
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"  manifest → {manifest_path}", file=sys.stderr)

    print("Extraction complete.", file=sys.stderr)


if __name__ == "__main__":
    main()
