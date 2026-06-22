"""
Task 2.3 — TorchScript export + model_contract.json.

Exports four .pt files and model_contract.json into src/main/resources/models/.
Run check_collapse.py first; this script does not re-validate.

Usage:
    cd ml
    python -m scripts.export_model [--ckpt ml/checkpoints] [--out src/main/resources/models]
"""

import argparse
import json
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jepa.export import export
from jepa.model  import SpeciesModel, IndividualAdapter


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--ckpt",   default="checkpoints")
    p.add_argument("--out",    default="../src/main/resources/models")
    p.add_argument("--device", default="cpu")
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

    model = SpeciesModel(
        input_dim  = stats["input_dim"],
        action_dim = stats["action_dim"],
        latent_dim = stats["latent_dim"],
        emotion_dim= stats["emotion_dim"],
        min_arousal= stats["min_arousal"],
        max_arousal= stats["max_arousal"],
    ).to(device)
    model.load_state_dict(torch.load(ckpt / "best.pt", map_location=device))

    adapter = IndividualAdapter(stats["latent_dim"]).to(device)

    print(f"Exporting to {out_dir} …")
    export(model.encoder, model.predictor, model.critic, adapter, stats, out_dir)
    print("Export complete.")


if __name__ == "__main__":
    main()
