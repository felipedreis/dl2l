"""Low-level postgres access via `docker exec <container> psql`.

Shared by scripts/dl2l_data/extract.py and scripts/pg_extract.py — previously
duplicated verbatim in both scripts/exp_extract.py and scripts/pg_extract.py.

`docker_cmd` defaults to "docker" but can be set to e.g. "sudo docker" for the
Raspberry Pi cluster nodes, which require sudo to run docker.
"""

import csv
import gzip
import io
import subprocess
import sys
from pathlib import Path

DB_USER = "postgres"
DB_NAME = "l2l"

KNOWN_TYPES = [
    "RED_APPLE", "GREEN_APPLE", "GRAY_APPLE", "ROTTEN_APPLE",
    "CACTUS", "ALOE", "Self",
]


def _docker_argv(docker_cmd: str) -> list:
    return docker_cmd.split()


def psql_copy(container: str, sql: str, docker_cmd: str = "docker") -> list:
    """Run COPY (<sql>) TO STDOUT WITH CSV HEADER via docker exec stdin.
    Returns a list of rows (first row = header strings), empty list on error.
    """
    copy = f"COPY ({sql}) TO STDOUT WITH CSV HEADER;\n"
    argv = _docker_argv(docker_cmd) + [
        "exec", "-i", container, "psql", "-U", DB_USER, "-d", DB_NAME,
    ]
    result = subprocess.run(argv, input=copy, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"psql error: {result.stderr.strip()}", file=sys.stderr)
        return []
    return list(csv.reader(io.StringIO(result.stdout)))


def psql_query(container: str, sql: str, docker_cmd: str = "docker") -> list:
    """Run a plain SELECT (not COPY) and return rows as list-of-lists."""
    argv = _docker_argv(docker_cmd) + [
        "exec", "-i", container, "psql", "-U", DB_USER, "-d", DB_NAME,
        "-t", "-A", "-F", ",",
    ]
    result = subprocess.run(argv, input=sql + ";\n", capture_output=True, text=True)
    if result.returncode != 0:
        print(f"psql error: {result.stderr.strip()}", file=sys.stderr)
        return []
    return [line.split(",") for line in result.stdout.strip().splitlines() if line]


def rows_to_df(rows: list):
    import pandas as pd

    if len(rows) < 2:
        return pd.DataFrame()
    return pd.DataFrame(rows[1:], columns=rows[0])


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


def pg_dump(container: str, out_path: Path, docker_cmd: str = "docker") -> None:
    """pg_dump the `data` schema and gzip it to out_path."""
    print(f"  pg_dump → {out_path.name} …", file=sys.stderr)
    argv = _docker_argv(docker_cmd) + [
        "exec", container,
        "pg_dump", "-U", DB_USER, "-d", DB_NAME,
        "--schema=data", "--no-owner", "--no-acl",
    ]
    result = subprocess.run(argv, capture_output=True)
    if result.returncode != 0:
        print(f"  pg_dump error: {result.stderr.decode()}", file=sys.stderr)
        return
    with gzip.open(out_path, "wb") as f:
        f.write(result.stdout)
    mb = out_path.stat().st_size / 1024 / 1024
    print(f"  backup: {mb:.1f} MB", file=sys.stderr)
