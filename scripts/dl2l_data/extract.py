#!/usr/bin/env python3
"""
Comprehensive extractor for one DL2L simulation condition.
Writes Parquet (default) or CSV files + preserves the raw-table Arrow IPC dump
(the backup, see below) to <out>/<condition>/[trial_N/].

Usage:
    python3 -m dl2l_data.extract \
        --experiment 20260709_memory_vs_wm_v1 \
        --condition  1_baseline \
        --trial      1 \
        --out        ml/data_20260709_memory_vs_wm_v1 \
        --raw-dir    /path/to/trial's/raw/arrow/dir \
        [--format parquet|csv] \
        [--skip-backup]

Each condition gets its own subdirectory (optionally under trial_N/) with one
Parquet/CSV file per logical table. A manifest.json at the experiment root is
created/updated with metadata (creature count, etc.).

Issue #79 (see docs/plans/parquet-write-path.md): this used to run each of
`tables.py`'s SQL queries via `psql` against a live Postgres container/
instance (`docker exec`/`singularity exec`). Postgres itself turned out to be
the bottleneck for BDActor's write path (an append-only telemetry workload,
never queried mid-run), so the JVM started writing raw Parquet directly at
trial shutdown instead - no DB layer on the write side at all. That direct-
Parquet write path (`ParquetBackend`) in turn got replaced by an off-heap
Arrow IPC stream writer (`ArrowIpcBackend`, see docs/plans/arrow-ipc-write-path.md)
once Parquet's on-heap row-group buffers + CPU-expensive encoding proved to be
a second, independent OOM contributor on CCAD. Extraction runs the *exact
same* `tables.py` SQL (unchanged, and untouched by that write-path swap) via
its own in-process embedded DuckDB - used purely as a query engine, unrelated
to whichever write path produced the raw files - instead of a live database:
no psql, no container/instance, no network. The raw dump itself *is* the
backup now (no restore step needed) - `--skip-backup` controls whether it's
kept alongside the final output or deleted once extraction succeeds.
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


def _open_raw_views(raw_dir: Path):
    """Opens an embedded DuckDB and registers each raw Arrow IPC stream file (one per
    table, written by ArrowIpcBackend - see docs/plans/arrow-ipc-write-path.md) as a view
    under the same `data.<table>` schema-qualified name tables.py's SQL already
    references - so that SQL runs completely unchanged against files instead of a live
    database.

    Unlike the direct-Parquet era's fixed `RAW_TABLES` list (which had to be hand-kept in
    sync with the Java write path), this just globs whatever `*.arrow` files are actually
    present - Arrow IPC files are self-describing, so no schema copy is needed here at
    all. Missing tables still warn (a query referencing one will simply fail loudly)."""
    import duckdb
    import pyarrow as pa

    conn = duckdb.connect()
    conn.execute("CREATE SCHEMA data")

    arrow_files = sorted(raw_dir.glob("*.arrow"))
    if not arrow_files:
        print(f"  WARNING: no *.arrow files found in raw dir {raw_dir} - "
              f"every query will fail", file=sys.stderr)

    for path in arrow_files:
        table = path.stem
        # A trial killed mid-batch (watchdog halt, OOM, SIGKILL, disk quota) truncates the
        # stream after its last complete record batch by design - ArrowStreamWriter never
        # seeks back to patch a footer. Batches are read ONE AT A TIME and the truncated tail
        # discarded, so a killed trial yields everything that was flushed.
        #
        # read_all() cannot do this: it raises on the partial trailing batch and loses the
        # whole file. That cost most of a CCAD campaign - a 1.8 GB dump with 14 intact tables
        # aborted extraction outright with "Expected to be able to read 534192 bytes for
        # message body, got 187360". Note the exception is OSError, not ArrowInvalid, so the
        # previous handler did not even catch it.
        batches = []
        schema = None
        try:
            # RecordBatchStreamReader deserializes into owned buffers (unlike the
            # random-access File format's zero-copy reads), so the memory map need not
            # outlive this call.
            with pa.memory_map(str(path), "r") as source:
                reader = pa.ipc.open_stream(source)
                schema = reader.schema
                while True:
                    try:
                        batches.append(reader.read_next_batch())
                    except StopIteration:
                        break
        except (pa.lib.ArrowInvalid, OSError) as e:
            if schema is None:
                # Killed before even the schema message was flushed - nothing to salvage.
                print(f"  WARNING: {path.name} unreadable ({e}) - "
                      f"treating data.{table} as missing", file=sys.stderr)
                continue
            print(f"  NOTE: {path.name} truncated - kept {len(batches)} complete "
                  f"batch(es), discarded the partial tail", file=sys.stderr)

        arrow_table = pa.Table.from_batches(batches, schema) if batches \
            else schema.empty_table()
        # DuckDB scans a registered Arrow object zero-copy (no data duplicated into
        # DuckDB's own storage) and keeps its own reference for the registration's
        # lifetime - no extra keep-alive bookkeeping needed here.
        conn.register(f"_raw_{table}", arrow_table)
        conn.execute(f"CREATE VIEW data.{table} AS SELECT * FROM _raw_{table}")

    return conn


def _dedup_creatures(creatures_df):
    """Collapses TABLES["creatures"]'s birth+death row pair for each creature into
    one row - deferred from #81, still true under the Arrow write path (see
    ArrowIpcBackend's javadoc: no upsert semantics either way). CreatureState is
    written twice per creature with the same (creature_key, creature_sequential):
    once at birth (dead_time=0, lifetime_s=NULL) and again at death (real
    dead_time/lifetime_s) if it died before the trial ended. TABLES["creatures"]'s
    query stays verbatim (untouched here) and returns both rows, which otherwise
    doubles `n_creatures`/manifest.json (confirmed live: "Found 20 creatures" for
    a 10-creature trial). Sorting ascending by dead_time and keeping the last row
    per creature keeps the death row when one exists, or the sole birth row
    unchanged when the creature was still alive at trial end - correct either way,
    no special-casing needed."""
    return (
        creatures_df.sort_values(["creature_key", "dead_time"])
        .groupby(["creature_key", "creature_sequential"], as_index=False)
        .tail(1)
        .sort_values("creature_key")
        .reset_index(drop=True)
    )


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
                   help="Directory containing the raw per-table Arrow IPC (.arrow) files "
                        "BDActor dumped at trial shutdown (saveDir/raw)")
    p.add_argument("--trial", type=int, default=None,
                   help="Trial number; output placed in <out>/<cond>/trial_N/")
    p.add_argument("--format", choices=["parquet", "csv"], default="parquet",
                   help="Output file format for the per-table extracts")
    p.add_argument("--skip-backup", action="store_true",
                   help="Delete the raw-table Arrow IPC dump after a successful "
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
    creatures_df = _dedup_creatures(creatures_df)
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
        raw_mb = sum(f.stat().st_size for f in raw_dir.glob("*.arrow")) / 1024 / 1024
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
