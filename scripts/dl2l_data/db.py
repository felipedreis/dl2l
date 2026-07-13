"""Low-level postgres access via `docker exec <container> psql` (or, on
CCAD, `singularity exec instance://<name> psql` — see `runtime` below).

Shared by scripts/dl2l_data/extract.py and scripts/pg_extract.py — previously
duplicated verbatim in both scripts/exp_extract.py and scripts/pg_extract.py.

`docker_cmd` defaults to "docker" but can be set to e.g. "sudo docker" for the
Raspberry Pi cluster nodes, which require sudo to run docker.

`runtime` defaults to "docker". Pass "singularity" on CCAD, where postgres
runs as a `singularity instance` rather than a docker container — there's no
`docker exec` equivalent there, and no reason to assume a bare `psql` client
is installed on the cluster host itself, so `psql` is invoked *inside* the
already-running instance instead (`singularity exec instance://<name> psql
...`), reusing the client bundled in the postgres image itself. Singularity's
exec syntax differs enough from docker's (an `instance://` prefix, no `-i`
flag) that it isn't expressed as just another `docker_cmd` string prefix.
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


def _exec_argv(container: str, command: list, runtime: str = "docker",
               docker_cmd: str = "docker", interactive: bool = False) -> list:
    """Build the argv to run `command` inside a running container/instance."""
    if runtime == "singularity":
        return ["singularity", "exec", f"instance://{container}"] + command
    argv = _docker_argv(docker_cmd) + ["exec"]
    if interactive:
        argv.append("-i")
    return argv + [container] + command


def psql_copy(container: str, sql: str, docker_cmd: str = "docker",
              runtime: str = "docker") -> list:
    """Run COPY (<sql>) TO STDOUT WITH CSV HEADER via docker exec stdin.
    Returns a list of rows (first row = header strings), empty list on error.
    """
    copy = f"COPY ({sql}) TO STDOUT WITH CSV HEADER;\n"
    argv = _exec_argv(container, ["psql", "-U", DB_USER, "-d", DB_NAME],
                       runtime=runtime, docker_cmd=docker_cmd, interactive=True)
    result = subprocess.run(argv, input=copy, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"psql error: {result.stderr.strip()}", file=sys.stderr)
        return []
    return list(csv.reader(io.StringIO(result.stdout)))


def psql_query(container: str, sql: str, docker_cmd: str = "docker",
               runtime: str = "docker") -> list:
    """Run a plain SELECT (not COPY) and return rows as list-of-lists."""
    argv = _exec_argv(container, ["psql", "-U", DB_USER, "-d", DB_NAME,
                                  "-t", "-A", "-F", ","],
                       runtime=runtime, docker_cmd=docker_cmd, interactive=True)
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


def pg_dump(container: str, out_path: Path, docker_cmd: str = "docker",
            runtime: str = "docker") -> None:
    """pg_dump the `data` schema and gzip it to out_path."""
    print(f"  pg_dump → {out_path.name} …", file=sys.stderr)
    argv = _exec_argv(container, ["pg_dump", "-U", DB_USER, "-d", DB_NAME,
                                  "--schema=data", "--no-owner", "--no-acl"],
                       runtime=runtime, docker_cmd=docker_cmd, interactive=False)
    result = subprocess.run(argv, capture_output=True)
    if result.returncode != 0:
        print(f"  pg_dump error: {result.stderr.decode()}", file=sys.stderr)
        return
    with gzip.open(out_path, "wb") as f:
        f.write(result.stdout)
    mb = out_path.stat().st_size / 1024 / 1024
    print(f"  backup: {mb:.1f} MB", file=sys.stderr)
