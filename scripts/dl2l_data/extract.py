#!/usr/bin/env python3
"""
Comprehensive extractor for one DL2L simulation condition.
Writes Parquet (default) or CSV files + a compressed pg_dump backup to
<out>/<condition>/[trial_N/].

Usage:
    python3 -m dl2l_data.extract \
        --experiment 20260709_memory_vs_wm_v1 \
        --condition  1_baseline \
        --trial      1 \
        --out        ml/data_20260709_memory_vs_wm_v1 \
        [--container db] \
        [--format parquet|csv] \
        [--docker-cmd docker] \
        [--runtime docker|singularity] \
        [--skip-backup]

Each condition gets its own subdirectory (optionally under trial_N/) with one
Parquet/CSV file per table plus a db_backup.sql.gz.  A manifest.json at the
experiment root is created/updated with metadata (creature count, etc.).

Drop-in successor to the old scripts/exp_extract.py — same interface, output
layout, and table set; adds --format and --docker-cmd (the latter used by the
Raspberry Pi trial runner, which needs "sudo docker"), and --runtime (used by
CCAD, where postgres runs as a `singularity instance` — psql is invoked
inside that already-running instance, not via a bare host-installed client).
"""

import argparse
import csv
import sys
from pathlib import Path

try:
    from .db import DB_NAME, DB_USER, pg_dump, psql_copy  # noqa: F401
    from .manifest import update_manifest
    from .tables import TABLE_ORDER, TABLES
except ImportError:  # running as a standalone script, not `-m dl2l_data.extract`
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from dl2l_data.db import DB_NAME, DB_USER, pg_dump, psql_copy  # noqa: F401
    from dl2l_data.manifest import update_manifest
    from dl2l_data.tables import TABLE_ORDER, TABLES


def _rows_to_df(rows: list):
    import pandas as pd

    if len(rows) < 2:
        return pd.DataFrame()
    return pd.DataFrame(rows[1:], columns=rows[0])


def save(rows: list, out_dir: Path, table: str, fmt: str, condition: str, trial) -> None:
    df = _rows_to_df(rows)
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
    p.add_argument("--container", default="db",
                   help="Docker container name for the PostgreSQL DB")
    p.add_argument("--trial", type=int, default=None,
                   help="Trial number; output placed in <out>/<cond>/trial_N/")
    p.add_argument("--format", choices=["parquet", "csv"], default="parquet",
                   help="Output file format for the per-table extracts")
    p.add_argument("--docker-cmd", default="docker",
                   help='Docker invocation prefix, e.g. "sudo docker" on Pi nodes')
    p.add_argument("--runtime", choices=["docker", "singularity"], default="docker",
                   help='Container runtime "psql"/"pg_dump" run inside of. '
                        '"singularity" (CCAD) execs into instance://<container> '
                        'instead of a docker exec; --docker-cmd is ignored then.')
    p.add_argument("--skip-backup", action="store_true",
                   help="Skip pg_dump (faster, use when DB is still running long)")
    args = p.parse_args()

    cond = args.condition
    container = args.container
    trial = args.trial
    docker_cmd = args.docker_cmd
    runtime = args.runtime
    if trial is not None:
        out_dir = Path(args.out) / cond / f"trial_{trial}"
    else:
        out_dir = Path(args.out) / cond
    out_dir.mkdir(parents=True, exist_ok=True)

    trial_label = f" trial={trial}" if trial is not None else ""
    print(f"Extracting {args.experiment}/{cond}{trial_label} from container "
          f"'{container}' …", file=sys.stderr)

    creatures_rows = psql_copy(container, TABLES["creatures"][0], docker_cmd, runtime)
    if len(creatures_rows) < 2:
        print("No creatures found — aborting.", file=sys.stderr)
        sys.exit(1)
    n_creatures = len(creatures_rows) - 1
    print(f"Found {n_creatures} creatures", file=sys.stderr)
    save(creatures_rows, out_dir, "creatures", args.format, cond, trial)

    for table in TABLE_ORDER:
        if table == "creatures":
            continue
        sql, post_process = TABLES[table]
        rows = psql_copy(container, sql, docker_cmd, runtime)
        if post_process is not None:
            rows = post_process(rows)
        save(rows, out_dir, table, args.format, cond, trial)

    if not args.skip_backup:
        pg_dump(container, out_dir / "db_backup.sql.gz", docker_cmd, runtime)

    manifest_path = update_manifest(
        exp_dir=Path(args.out),
        experiment=args.experiment,
        condition=cond,
        trial=trial,
        n_creatures=n_creatures,
        has_backup=not args.skip_backup,
    )
    print(f"  manifest → {manifest_path}", file=sys.stderr)
    print("Extraction complete.", file=sys.stderr)


if __name__ == "__main__":
    main()
