"""
Task 2.3 — TorchScript export + model_contract.json.

Exports .pt files and model_contract.json into src/main/resources/models/.
Run check_collapse.py first; this script does not re-validate.

Usage:
    cd ml
    python -m scripts.export_model [--ckpt ml/checkpoints] [--out src/main/resources/models]
                                   [--variant single|dual|internal_critic]
"""

import argparse
import json
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jepa.export import export
from jepa.model  import SpeciesModel, DualSpeciesModel, InternalCriticModel, InternalPredictorModel, IndividualAdapter


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--ckpt",    default="checkpoints")
    p.add_argument("--out",     default="../src/main/resources/models")
    p.add_argument("--device",  default="cpu")
    p.add_argument("--variant", default="single",
                   choices=["single", "dual", "internal_critic", "internal_predictor"])
    return p.parse_args()


def main():
    args    = parse_args()
    ckpt    = Path(args.ckpt)
    out_dir = Path(args.out)
    device  = torch.device(args.device)

    stats_path = ckpt / "stats.json"
    if not stats_path.exists():
        sys.exit(f"stats.json not found at {stats_path}")
    stats = json.loads(stats_path.read_text())

    dual_variant = args.variant in ("dual", "internal_critic", "internal_predictor")

    if dual_variant and "internal_state_dim" not in stats:
        sys.exit(f"--variant {args.variant} requires internal_state_dim in stats.json.")

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

    model.load_state_dict(torch.load(ckpt / "best.pt", map_location=device))
    adapter = IndividualAdapter(stats["latent_dim"]).to(device)

    print(f"Exporting {args.variant} model to {out_dir} …")
    export(model.encoder, model.predictor, model.critic, adapter, stats, out_dir,
           internal_encoder=model.internal_encoder if dual_variant else None,
           model_variant=args.variant)
    print("Export complete.")


if __name__ == "__main__":
    main()
