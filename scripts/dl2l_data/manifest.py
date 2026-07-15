"""manifest.json read/update logic, factored out of scripts/exp_extract.py."""

import json
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
    """
    manifest_path = exp_dir / "manifest.json"
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
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    return manifest_path
