#!/usr/bin/env python3
"""
Comprehensive extractor for one DL2L simulation condition.
Writes Parquet (default) or CSV files + preserves the raw-table Parquet dump
(the backup, see below) to <out>/<condition>/[trial_N/].

Usage:
    python3 -m dl2l_data.extract \
        --experiment 20260709_memory_vs_wm_v1 \
        --condition  1_baseline \
        --trial      1 \
        --out        ml/data_20260709_memory_vs_wm_v1 \
        --raw-dir    /path/to/trial's/raw/parquet/dir \
        [--format parquet|csv] \
        [--skip-backup]

Each condition gets its own subdirectory (optionally under trial_N/) with one
Parquet/CSV file per logical table. A manifest.json at the experiment root is
created/updated with metadata (creature count, etc.).

Issue #79 (see docs/plans/parquet-write-path.md): this used to run each of
`tables.py`'s SQL queries via `psql` against a live Postgres container/
instance (`docker exec`/`singularity exec`). Postgres itself turned out to be
the bottleneck for BDActor's write path (an append-only telemetry workload,
never queried mid-run) and the JVM now writes one raw Parquet file per table
directly at trial shutdown instead (`ParquetBackend`, `--raw-dir`) - no DB
layer on the write side at all. Extraction runs the *exact same* `tables.py`
SQL (unchanged) via its own in-process embedded DuckDB - used purely as a
query engine over those Parquet files, unrelated to the (DuckDB-free) write
path - instead of a live database: no psql, no container/instance, no
network. The raw dump itself *is* the backup now (already columnar, no
restore step needed) - `--skip-backup` controls whether it's kept alongside
the final output or deleted once extraction succeeds.
"""

import argparse
import csv
import shutil
import sys
from pathlib import Path

try:
    from .manifest import update_manifest
    from .tables import TABLE_ORDER, TABLES
except ImportError:  # running as a standalone script, not `-m dl2l_data.extract`
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from dl2l_data.manifest import update_manifest
    from dl2l_data.tables import TABLE_ORDER, TABLES

# Every raw table BDActor dumps - must match PersistenceExtension.Impl.TABLES
# (src/main/java/br/cefetmg/lsi/l2l/creature/bd/PersistenceExtension.java).
RAW_TABLES = [
    "change_stimulus_state", "stimulus_state", "creature_state", "emotional_state",
    "internal_dynamic_state", "eye_state", "object_seen_state", "mouth_interactions_state",
    "nose_state", "object_smelt_state", "chosen_action_state", "body_state",
    "behavioural_efficiency_state", "regulation_batch_stat", "engram_state",
    "sleep_episode_state", "consolidation_episode_stat", "consolidation_batch_stat",
    "memory_trace_stat", "expectancy_state", "neuromodulator_state_log", "endocrine_state_log",
]


def _open_raw_views(raw_dir: Path):
    """Opens an embedded DuckDB and registers each raw Parquet file as a view under the
    same `data.<table>` schema-qualified name tables.py's SQL already references - so
    that SQL runs completely unchanged against files instead of a live database."""
    import duckdb

    conn = duckdb.connect()
    conn.execute("CREATE SCHEMA data")
    for table in RAW_TABLES:
        path = raw_dir / f"{table}.parquet"
        if not path.exists():
            print(f"  WARNING: {path.name} missing from raw dir - "
                  f"queries referencing data.{table} will fail", file=sys.stderr)
            continue
        # read_parquet's path argument is a SQL string literal - escape embedded quotes.
        escaped = str(path).replace("'", "''")
        conn.execute(f"CREATE VIEW data.{table} AS SELECT * FROM read_parquet('{escaped}')")
    return conn


def save(df, out_dir: Path, table: str, fmt: str, condition: str, trial) -> None:
    if df.empty:
        print(f"  (empty) {table}", file=sys.stderr)
        return
    df["condition"] = condition
    if trial is not None:
        df["trial"] = trial

    if fmt == "parquet":
        path = out_dir / f"{table}.parquet"
        df.to_parquet(path, index=False)
    else:
        path = out_dir / f"{table}.csv"
        df.to_csv(path, index=False, quoting=csv.QUOTE_MINIMAL)
    print(f"  → {path.name} ({len(df):,} rows)", file=sys.stderr)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--experiment", required=True,
                   help="Experiment name, e.g. 20260709_memory_vs_wm_v1")
    p.add_argument("--condition", required=True,
                   help="Condition key, e.g. 1_baseline")
    p.add_argument("--out", required=True,
                   help="Base output dir; condition subdir created inside it")
    p.add_argument("--raw-dir", required=True,
                   help="Directory containing the raw per-table Parquet files "
                        "BDActor dumped at trial shutdown (saveDir/raw)")
    p.add_argument("--trial", type=int, default=None,
                   help="Trial number; output placed in <out>/<cond>/trial_N/")
    p.add_argument("--format", choices=["parquet", "csv"], default="parquet",
                   help="Output file format for the per-table extracts")
    p.add_argument("--skip-backup", action="store_true",
                   help="Delete the raw-table Parquet dump after a successful "
                        "extraction instead of keeping it alongside the output")
    args = p.parse_args()

    cond = args.condition
    raw_dir = Path(args.raw_dir)
    trial = args.trial
    if trial is not None:
        out_dir = Path(args.out) / cond / f"trial_{trial}"
    else:
        out_dir = Path(args.out) / cond
    out_dir.mkdir(parents=True, exist_ok=True)

    trial_label = f" trial={trial}" if trial is not None else ""
    print(f"Extracting {args.experiment}/{cond}{trial_label} from raw dir "
          f"'{raw_dir}' …", file=sys.stderr)

    conn = _open_raw_views(raw_dir)

    creatures_sql, _ = TABLES["creatures"]
    creatures_df = conn.execute(creatures_sql).df()
    if len(creatures_df) == 0:
        print("No creatures found — aborting.", file=sys.stderr)
        sys.exit(1)
    n_creatures = len(creatures_df)
    print(f"Found {n_creatures} creatures", file=sys.stderr)
    save(creatures_df, out_dir, "creatures", args.format, cond, trial)

    for table in TABLE_ORDER:
        if table == "creatures":
            continue
        sql, post_process = TABLES[table]
        df = conn.execute(sql).df()
        if post_process is not None:
            df = post_process(df)
        save(df, out_dir, table, args.format, cond, trial)

    conn.close()

    has_backup = not args.skip_backup
    if args.skip_backup:
        shutil.rmtree(raw_dir, ignore_errors=True)
        print("  raw-table backup discarded (--skip-backup)", file=sys.stderr)
    else:
        raw_mb = sum(f.stat().st_size for f in raw_dir.glob("*.parquet")) / 1024 / 1024
        print(f"  raw-table backup kept: {raw_dir} ({raw_mb:.1f} MB)", file=sys.stderr)

    manifest_path = update_manifest(
        exp_dir=Path(args.out),
        experiment=args.experiment,
        condition=cond,
        trial=trial,
        n_creatures=n_creatures,
        has_backup=has_backup,
    )
    print(f"  manifest → {manifest_path}", file=sys.stderr)
    print("Extraction complete.", file=sys.stderr)


if __name__ == "__main__":
    main()
