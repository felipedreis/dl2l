"""
Export a fresh (random-weights) DualSpeciesModel with the updated Critic architecture.

Used to regenerate src/main/resources/models/ after the Critic input dimension changed
from latent_dim=64 to combined_latent_dim=80 (= latent_dim + internal_latent_dim).

Usage (from repo root):
    cd ml && python -m scripts.export_fresh_dual [--out ../src/main/resources/models]
"""

import argparse
import json
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jepa.export import export
from jepa.model  import DualSpeciesModel, IndividualAdapter


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--stats", default="checkpoints/exp_b/stats.json")
    p.add_argument("--out",   default="../src/main/resources/models")
    args = p.parse_args()

    stats = json.loads(Path(args.stats).read_text())
    out_dir = Path(args.out)

    model = DualSpeciesModel(
        input_dim           = stats["input_dim"],
        internal_state_dim  = stats["internal_state_dim"],
        action_dim          = stats["action_dim"],
        latent_dim          = stats["latent_dim"],
        internal_latent_dim = stats["internal_latent_dim"],
        emotion_dim         = stats["emotion_dim"],
        min_arousal         = stats["min_arousal"],
        max_arousal         = stats["max_arousal"],
    )
    adapter = IndividualAdapter(stats["latent_dim"])

    print(f"Exporting fresh dual-encoder model (random weights) to {out_dir} …")
    export(model.encoder, model.predictor, model.critic, adapter, stats, out_dir,
           internal_encoder=model.internal_encoder)
    print("Done.")


if __name__ == "__main__":
    main()
