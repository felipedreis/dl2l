"""
Upload trained models and dataset to Hugging Face Hub.

Uploads all four model variants (single / dual / internal_critic / internal_predictor)
to the model repo, and the training/validation parquet files to a separate dataset repo.

Usage:
    cd ml
    python -m scripts.upload_hf \
        --repo felipedreis/dl2l-jepa \
        --data-repo felipedreis/dl2l-experiments \
        --ckpt checkpoints_p9 \
        --data data_p9 \
        --data-prefix p9 \
        [--token <hf-token>]   # optional: falls back to HF_TOKEN env var or cached login
"""

import argparse
import json
import sys
from pathlib import Path

from huggingface_hub import HfApi, login


VARIANTS = ["single", "dual", "internal_critic", "internal_predictor"]


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--repo",        required=True, help="HF model repo id, e.g. felipedreis/dl2l-jepa")
    p.add_argument("--data-repo",   default=None,  help="HF dataset repo id (default: same as --repo)")
    p.add_argument("--ckpt",        default="checkpoints_p9", help="Directory with variant subdirs")
    p.add_argument("--data",        default="data_p9", help="Dataset parquet directory")
    p.add_argument("--data-prefix", default="data", help="Path prefix inside dataset repo (e.g. p9)")
    p.add_argument("--token",       default=None, help="HF token (optional; falls back to env/cache)")
    return p.parse_args()


def main():
    args = parse_args()
    ckpt_root = Path(args.ckpt)
    data_dir  = Path(args.data)
    data_repo = args.data_repo or args.repo

    if args.token:
        login(token=args.token)

    api = HfApi()

    # Ensure model repo exists.
    try:
        api.repo_info(repo_id=args.repo, repo_type="model")
        print(f"Model repo {args.repo} exists — uploading to it.")
    except Exception:
        print(f"Creating model repo {args.repo} …")
        api.create_repo(repo_id=args.repo, repo_type="model", exist_ok=True)

    # Upload each variant's model files.
    for variant in VARIANTS:
        variant_dir = ckpt_root / variant
        if not variant_dir.exists():
            print(f"  Skipping {variant} — no checkpoint dir found at {variant_dir}")
            continue

        model_files = list(variant_dir.glob("*.pt")) + list(variant_dir.glob("*.json"))
        if not model_files:
            print(f"  Skipping {variant} — no .pt/.json files in {variant_dir}")
            continue

        print(f"  Uploading {variant} ({len(model_files)} files) …")
        for f in sorted(model_files):
            api.upload_file(
                path_or_fileobj=str(f),
                path_in_repo=f"models/{variant}/{f.name}",
                repo_id=args.repo,
                repo_type="model",
                commit_message=f"Upload {variant}/{f.name}",
            )

    # Upload dataset parquet files to the dataset repo.
    parquet_files = list(data_dir.glob("*.parquet")) + list(data_dir.glob("stats.json"))
    if parquet_files:
        data_repo_type = "dataset" if data_repo != args.repo else "model"
        try:
            api.repo_info(repo_id=data_repo, repo_type=data_repo_type)
            print(f"Dataset repo {data_repo} exists — uploading to it.")
        except Exception:
            print(f"Creating dataset repo {data_repo} …")
            api.create_repo(repo_id=data_repo, repo_type=data_repo_type, exist_ok=True)

        prefix = args.data_prefix
        print(f"  Uploading dataset ({len(parquet_files)} files) to {data_repo}/{prefix}/ …")
        for f in sorted(parquet_files):
            api.upload_file(
                path_or_fileobj=str(f),
                path_in_repo=f"{prefix}/{f.name}",
                repo_id=data_repo,
                repo_type=data_repo_type,
                commit_message=f"Upload {prefix}/{f.name}",
            )
    else:
        print(f"  No parquet files found in {data_dir}, skipping dataset upload.")

    print(f"\nDone.")
    print(f"  Models: https://huggingface.co/{args.repo}")
    if data_repo != args.repo:
        print(f"  Dataset: https://huggingface.co/datasets/{data_repo}")


if __name__ == "__main__":
    main()
