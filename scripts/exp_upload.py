#!/usr/bin/env python3
"""
Upload a DL2L experiment (Parquet files + db backups) to the HF dataset repo.

Directory layout expected locally:
    <data-dir>/
      manifest.json
      <condition>/
        *.parquet
        db_backup.sql.gz

Layout created on HuggingFace:
    felipedreis/dl2l-experiments/<experiment>/<condition>/…

Usage:
    python3 scripts/exp_upload.py \
        --experiment 20260709_memory_vs_wm_v1 \
        --data-dir   ml/data_20260709_memory_vs_wm_v1 \
        [--repo      felipedreis/dl2l-experiments] \
        [--condition 1_baseline]   # upload one condition; omit for all
        [--token     <hf-token>]   # optional; falls back to HF_TOKEN / cached login
"""

import argparse
from pathlib import Path

from huggingface_hub import HfApi, login

REPO_TYPE = "dataset"
DEFAULT_REPO = "felipedreis/dl2l-experiments"


def upload_file(api: HfApi, local: Path, repo: str, repo_path: str, msg: str):
    api.upload_file(
        path_or_fileobj=str(local),
        path_in_repo=repo_path,
        repo_id=repo,
        repo_type=REPO_TYPE,
        commit_message=msg,
    )
    print(f"  ✓ {repo_path}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--experiment", required=True,
                   help="Experiment name, e.g. 20260709_memory_vs_wm_v1")
    p.add_argument("--data-dir",   required=True,
                   help="Local directory produced by exp_extract.py")
    p.add_argument("--repo",       default=DEFAULT_REPO,
                   help="HF dataset repo id")
    p.add_argument("--condition",  default=None,
                   help="Upload a single condition only (default: all)")
    p.add_argument("--token",      default=None,
                   help="HF token (optional)")
    args = p.parse_args()

    if args.token:
        login(token=args.token)

    api      = HfApi()
    root     = Path(args.data_dir)
    exp      = args.experiment

    api.create_repo(repo_id=args.repo, repo_type=REPO_TYPE, exist_ok=True)
    print(f"Uploading {exp} → {args.repo}")

    # manifest
    manifest = root / "manifest.json"
    if manifest.exists():
        upload_file(api, manifest, args.repo, f"{exp}/manifest.json",
                    f"manifest: {exp}")

    # conditions
    if args.condition:
        conditions = [args.condition]
    else:
        conditions = sorted(d.name for d in root.iterdir() if d.is_dir())

    for cond in conditions:
        cond_dir = root / cond
        if not cond_dir.is_dir():
            print(f"  skipping {cond} (not found)")
            continue
        files = sorted(cond_dir.iterdir())
        print(f"\n{cond}  ({len(files)} files)")
        for f in files:
            upload_file(api, f, args.repo, f"{exp}/{cond}/{f.name}",
                        f"upload {exp}/{cond}/{f.name}")

    print(f"\nDone — https://huggingface.co/datasets/{args.repo}/tree/main/{exp}")


if __name__ == "__main__":
    main()
