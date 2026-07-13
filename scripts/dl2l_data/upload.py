#!/usr/bin/env python3
"""
Upload a DL2L experiment (Parquet files + db backups) to the HF dataset repo.

Directory layout expected locally:
    <data-dir>/
      manifest.json
      <condition>/
        [trial_N/]
          *.parquet
          db_backup.sql.gz

Layout created on HuggingFace:
    <repo>/<prefix>/<condition>/[trial_N/]…

Usage:
    python3 -m dl2l_data.upload \
        --experiment 20260709_memory_vs_wm_v1 \
        --data-dir   ml/data_20260709_memory_vs_wm_v1 \
        [--repo      felipedreis/dl2l-experiments] \
        [--prefix    20260709_memory_vs_wm_v1]   # defaults to --experiment
        [--condition 1_baseline]   # upload one condition; omit for all
        [--token     <hf-token>]   # optional; falls back to HF_TOKEN / cached login

Drop-in successor to scripts/exp_upload.py — adds --prefix so the experiment
spec's `upload.prefix` can diverge from the experiment name, and recurses into
trial_N/ subdirectories (the old script only uploaded flat files one level
under the condition dir).
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
    p.add_argument("--data-dir", required=True,
                   help="Local directory produced by dl2l_data.extract")
    p.add_argument("--repo", default=DEFAULT_REPO,
                   help="HF dataset repo id")
    p.add_argument("--prefix", default=None,
                   help="Dataset path prefix (default: --experiment)")
    p.add_argument("--condition", default=None,
                   help="Upload a single condition only (default: all)")
    p.add_argument("--token", default=None,
                   help="HF token (optional)")
    args = p.parse_args()

    if args.token:
        login(token=args.token)

    api = HfApi()
    root = Path(args.data_dir)
    exp = args.experiment
    prefix = args.prefix or exp

    api.create_repo(repo_id=args.repo, repo_type=REPO_TYPE, exist_ok=True)
    print(f"Uploading {exp} → {args.repo}/{prefix}")

    manifest = root / "manifest.json"
    if manifest.exists():
        upload_file(api, manifest, args.repo, f"{prefix}/manifest.json",
                    f"manifest: {exp}")

    if args.condition:
        conditions = [args.condition]
    else:
        conditions = sorted(d.name for d in root.iterdir() if d.is_dir())

    for cond in conditions:
        cond_dir = root / cond
        if not cond_dir.is_dir():
            print(f"  skipping {cond} (not found)")
            continue
        files = sorted(f for f in cond_dir.rglob("*") if f.is_file())
        print(f"\n{cond}  ({len(files)} files)")
        for f in files:
            rel = f.relative_to(cond_dir)
            upload_file(api, f, args.repo, f"{prefix}/{cond}/{rel.as_posix()}",
                        f"upload {exp}/{cond}/{rel.as_posix()}")

    print(f"\nDone — https://huggingface.co/datasets/{args.repo}/tree/main/{prefix}")


if __name__ == "__main__":
    main()
