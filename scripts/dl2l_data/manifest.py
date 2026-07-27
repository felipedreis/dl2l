"""manifest.json read/update logic, factored out of scripts/exp_extract.py."""

import fcntl
import json
import os
from datetime import datetime, timezone
from pathlib import Path


def update_manifest(
    exp_dir: Path,
    experiment: str,
    condition: str,
    trial,
    n_creatures: int,
    has_backup: bool,
) -> Path:
    """Create or update <exp_dir>/manifest.json with this trial's metadata.

    Returns the manifest path.

    CONFIRMED LIVE (2026-07-27): up to trials x conditions extract.py
    processes (one per SLURM array task on CCAD) run concurrently and all
    read-modify-write this SAME shared file — an earlier version here had
    no locking at all, so one writer's truncate-then-write could interleave
    with a concurrent reader/writer, corrupting the file
    (json.JSONDecodeError: "Expecting value" from an empty read, or "Extra
    data" from two writes landing back-to-back) and crashing that trial's
    extraction outright — even though every actual data file (parquet,
    pg_dump) had already been written successfully by that point. Fixed
    with an flock-based lock (best-effort under NFS — even where locking
    itself is a no-op on a given NFS config, the atomic rename below still
    guarantees no reader ever observes a partially-written file) plus an
    atomic write (write to a temp file, then os.replace into place).
    """
    manifest_path = exp_dir / "manifest.json"
    lock_path = exp_dir / ".manifest.lock"
    exp_dir.mkdir(parents=True, exist_ok=True)

    with open(lock_path, "w") as lockf:
        fcntl.flock(lockf, fcntl.LOCK_EX)
        try:
            if manifest_path.exists():
                with open(manifest_path) as f:
                    manifest = json.load(f)
            else:
                manifest = {
                    "experiment": experiment,
                    "conditions": {},
                    "created_at": datetime.now(timezone.utc).isoformat(),
                }

            cond_entry = manifest["conditions"].setdefault(condition, {"trials": {}})
            trial_key = str(trial) if trial is not None else "default"
            cond_entry["trials"][trial_key] = {
                "creature_count": n_creatures,
                "extracted_at": datetime.now(timezone.utc).isoformat(),
                "has_backup": has_backup,
            }
            manifest["updated_at"] = datetime.now(timezone.utc).isoformat()

            tmp_path = manifest_path.with_suffix(".json.tmp")
            with open(tmp_path, "w") as f:
                json.dump(manifest, f, indent=2)
            os.replace(tmp_path, manifest_path)
        finally:
            fcntl.flock(lockf, fcntl.LOCK_UN)
    return manifest_path
