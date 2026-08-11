"""Salvage a trial whose raw dump survived but whose `creature_state` did not.

`ArrowIpcBackend` writes in batches of `DEFAULT_BATCH_ROWS` (4096), so a trial killed
mid-run loses each table's buffered remainder. For a high-volume table that is a rounding
error; for a low-volume one it is the whole table. `creature_state` holds roughly two rows per
creature (birth, and death if it died), so it never fills a batch and is lost in its entirety —
and `extract.main` aborts with "No creatures found", discarding an otherwise intact dump.

Confirmed on the p84 CCAD campaign: a `current_nomem` trial retained 11.9M
`change_stimulus_state` rows, 843,776 decisions and 32,768 mouth interactions — every count an
exact multiple of 4096 — beside a 736-byte, zero-row `creature_state`.

This module runs the same `tables.py` SQL against the dump, skipping the creature gate, and
reconstructs a minimal `creatures` table from observed activity:

    creature_key   distinct keys appearing in change_stimulus_state
    born_time      that creature's first recorded activity
    dead_time      0 — a death would have been observed, and was not
    died           False
    observed_s     span of recorded activity, i.e. a right-censored follow-up time

`born_time` is first-activity rather than true birth, so it is a slight over-estimate of birth
and thus a slight UNDER-estimate of `observed_s`. `died = False` is sound for a trial killed by
the scheduler: the creature outlived the observation, which is exactly what censoring encodes.
Anything needing a real death time must not use a recovered trial.

Recovered trials are marked with `recovered = True` in every table so they can never be
silently pooled with cleanly extracted ones.

    PYTHONPATH=scripts python3 -m dl2l_data.recover \\
        --condition current_nomem --trial 1 \\
        --raw-dir <path>/raw --out <data-dir>
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .extract import _open_raw_views, save
from .tables import TABLES


def reconstruct_creatures(conn):
    """Minimal creature registry from observed activity — see module docstring."""
    return conn.execute(
        """
        SELECT key AS creature_key,
               key AS creature_sequential,
               MIN(time) AS born_time,
               0        AS dead_time,
               NULL     AS lifetime_s,
               FALSE    AS died,
               (MAX(time) - MIN(time)) / 1000.0 AS observed_s,
               NULL     AS gender
        FROM data.change_stimulus_state
        GROUP BY key
        ORDER BY key
        """
    ).df()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--condition", required=True)
    ap.add_argument("--trial", required=True)
    ap.add_argument("--raw-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--format", default="parquet")
    args = ap.parse_args()

    raw = Path(args.raw_dir)
    out = Path(args.out) / args.condition / f"trial_{args.trial}"
    out.mkdir(parents=True, exist_ok=True)

    conn = _open_raw_views(raw)

    creatures = reconstruct_creatures(conn)
    if creatures.empty:
        print("No activity at all — nothing to recover.", file=sys.stderr)
        return 1
    creatures["recovered"] = True
    save(creatures, out, "creatures", args.format, args.condition, args.trial)
    print(f"  reconstructed {len(creatures)} creatures from activity "
          f"(born_time = first activity; died = False, censored)")

    for table, (sql, post) in TABLES.items():
        if table == "creatures":
            continue
        try:
            df = conn.execute(sql).df()
        except Exception as e:
            print(f"  (skipped) {table}: {type(e).__name__}", file=sys.stderr)
            continue
        if df.empty:
            print(f"  (empty) {table}", file=sys.stderr)
            continue
        df["recovered"] = True
        save(df, out, table, args.format, args.condition, args.trial)

    print(f"Recovered {args.condition}/trial_{args.trial} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
