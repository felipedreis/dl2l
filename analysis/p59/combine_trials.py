#!/usr/bin/env python3
"""
Combine endocrine data from all p59 trial subdirectories into a single
neuromodulator/endocrine/action CSV for the analysis script.

Reads from ml/data_p59/trial_N/{creature_dir}/neuromodulator.csv etc.
and queries the still-running DB container for the last trial's endocrine logs,
then produces summary stats across all trials.

Since pg_extract.py doesn't include neuromodulator/endocrine CSVs
(those are in the DB), we query all 5 trials' data directly from the
combined DB (all trials persisted across runs via the named volume).

Actually: each trial uses `drop-and-create-tables` so the DB is fresh per trial.
We extract from the DB immediately after each trial. The DB only holds the last trial.

This script pools by directly querying the DB which holds the LAST trial's data,
while using the trial subdirectory extracts for lifetime/distance stats.

For multi-trial endocrine/neuromodulator analysis, all queries go to the DB
which must still be running from the last trial.
"""
import io
import os
import subprocess
import sys
import glob

import pandas as pd

CONTAINER = "db"
DB_USER = "postgres"
DB_NAME = "l2l"
DATA_DIR = "ml/data_p59"


def psql(sql: str) -> pd.DataFrame:
    copy_sql = f"COPY ({sql}) TO STDOUT WITH CSV HEADER;\n"
    r = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME],
        input=copy_sql, capture_output=True, text=True,
    )
    if r.returncode != 0:
        print(f"psql error: {r.stderr.strip()}", file=sys.stderr)
        return pd.DataFrame()
    return pd.read_csv(io.StringIO(r.stdout))


def load_lifetimes_all_trials() -> pd.DataFrame:
    """Load lifetimes.csv from all trial subdirectories."""
    rows = []
    for trial_dir in sorted(glob.glob(os.path.join(DATA_DIR, "trial_*"))):
        trial_num = os.path.basename(trial_dir).split("_")[1]
        lt_path = os.path.join(trial_dir, "lifetimes.csv")
        if os.path.exists(lt_path):
            df = pd.read_csv(lt_path)
            df["trial"] = int(trial_num)
            rows.append(df)
    if not rows:
        return pd.DataFrame()
    return pd.concat(rows, ignore_index=True)


if __name__ == "__main__":
    lt = load_lifetimes_all_trials()
    if not lt.empty:
        print(f"Lifetimes across {lt['trial'].nunique()} trials:")
        print(lt.groupby("trial").agg({"lifetime": ["count", "mean", "min", "max"]}).to_string())
    else:
        print("No lifetime data found.")
