"""
Task 2.3 — Collapse check for the trained species encoder.

Exit code 0 = no collapse (both criteria pass).
Exit code 1 = collapse detected (CI-catchable failure).

Usage:
    cd ml
    python -m scripts.check_collapse [--ckpt ml/checkpoints] [--data ml/data]
"""

import argparse
import json
import sys
from pathlib import Path

import torch
from torch.utils.data import DataLoader

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jepa.dataset  import TrajectoryDataset
from jepa.evaluate import check_collapse, collect_latents
from jepa.model    import SpeciesModel, DualSpeciesModel, InternalCriticModel, InternalPredictorModel


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--ckpt",    default="checkpoints")
    p.add_argument("--data",    default="data")
    p.add_argument("--batch",   type=int, default=512)
    p.add_argument("--device",  default=None)
    p.add_argument("--variant", default="single",
                   choices=["single", "dual", "internal_critic", "internal_predictor"])
    return p.parse_args()


def main():
    args = parse_args()
    ckpt_dir = Path(args.ckpt)
    data_dir = Path(args.data)

    stats_path = ckpt_dir / "stats.json"
    if not stats_path.exists():
        stats_path = data_dir / "stats.json"
    stats = json.loads(stats_path.read_text())

    if args.device:
        device = torch.device(args.device)
    else:
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    dual_variant = args.variant in ("dual", "internal_critic", "internal_predictor")
    dual_kwargs = dict(
        input_dim           = stats["input_dim"],
        internal_state_dim  = stats["internal_state_dim"],
        action_dim          = stats["action_dim"],
        latent_dim          = stats["latent_dim"],
        internal_latent_dim = stats["internal_latent_dim"],
        emotion_dim         = stats["emotion_dim"],
        min_arousal         = stats["min_arousal"],
        max_arousal         = stats["max_arousal"],
    ) if dual_variant else {}

    if args.variant == "single":
        model = SpeciesModel(
            input_dim   = stats["input_dim"],
            action_dim  = stats["action_dim"],
            latent_dim  = stats["latent_dim"],
            emotion_dim = stats["emotion_dim"],
            min_arousal = stats["min_arousal"],
            max_arousal = stats["max_arousal"],
        ).to(device)
    elif args.variant == "dual":
        model = DualSpeciesModel(**dual_kwargs).to(device)
    elif args.variant == "internal_critic":
        model = InternalCriticModel(**dual_kwargs).to(device)
    else:
        model = InternalPredictorModel(**dual_kwargs).to(device)

    model.load_state_dict(torch.load(ckpt_dir / "best.pt", map_location=device))

    val_file   = data_dir / ("val_dual.parquet" if dual_variant else "val.parquet")
    val_ds     = TrajectoryDataset(str(val_file), str(stats_path), dual=dual_variant)
    val_loader = DataLoader(val_ds, batch_size=args.batch, shuffle=False)

    print("Collecting latents on validation set …")
    z = collect_latents(model.encoder, val_loader, device)
    print(f"  Latent matrix: {z.shape}")

    results = check_collapse(z, stats["latent_dim"])

    print(f"\nPer-dim variance (min: {results['min_dim_var']:.2e}):")
    for i, v in enumerate(results["per_dim_var"]):
        flag = " ← DEAD" if i in results["dead_dims"] else ""
        print(f"  dim {i:>3}: {v:.4e}{flag}")

    print(f"\nEffective rank: {results['effective_rank']:.2f}  "
          f"(threshold: {results['eff_rank_threshold']:.2f})")

    dead_msg = "no dead dims" if not results["dead_dims"] else f"{len(results['dead_dims'])} dead dims"
    print(f"\n{'PASS' if results['pass_var'] else 'FAIL'} — per-dim variance ({dead_msg})")
    print(f"{'PASS' if results['pass_eff_rank'] else 'FAIL'} — effective rank")

    if results["pass_var"] and results["pass_eff_rank"]:
        print("\nCollapse check: PASS")
        sys.exit(0)
    else:
        print("\nCollapse check: FAIL — model may have collapsed; do not export.")
        sys.exit(1)


if __name__ == "__main__":
    main()
