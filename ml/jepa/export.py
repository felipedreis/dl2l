"""
TorchScript export and model_contract.json generation.

Exports four .pt files and one contract JSON into src/main/resources/models/.
The Java loader (Phase 5, Task 5.1) verifies the model_hash at startup.
"""

from __future__ import annotations

import hashlib
import json
from datetime import date
from pathlib import Path

import torch

from .model import Encoder, Predictor, Critic, IndividualAdapter


def _sha256_files(paths: list[Path]) -> str:
    h = hashlib.sha256()
    for p in sorted(paths, key=lambda x: x.name):
        h.update(p.read_bytes())
    return h.hexdigest()


def export(
    encoder:   Encoder,
    predictor: Predictor,
    critic:    Critic,
    adapter:   IndividualAdapter,
    stats:     dict,
    out_dir:   Path,
) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)

    input_dim  = stats["input_dim"]
    action_dim = stats["action_dim"]
    latent_dim = stats["latent_dim"]

    encoder.eval()
    predictor.eval()
    critic.eval()
    adapter.eval()

    dummy_s = torch.zeros(1, input_dim)
    dummy_z = torch.zeros(1, latent_dim)
    dummy_a = torch.zeros(1, action_dim)

    pts: list[Path] = []
    for module, name, example in [
        (encoder,   "species_encoder",   dummy_s),
        (predictor, "species_predictor", (dummy_z, dummy_a)),
        (critic,    "species_critic",    (dummy_z, dummy_a)),
        (adapter,   "species_adapter",   dummy_z),
    ]:
        traced = torch.jit.trace(module, example)
        path = out_dir / f"{name}.pt"
        torch.jit.save(traced, str(path))
        pts.append(path)
        print(f"  Saved {path}")

    model_hash = _sha256_files(pts)

    contract = {
        "schema_version":          1,
        "input_dim":               input_dim,
        "latent_dim":              latent_dim,
        "action_dim":              action_dim,
        "emotion_dim":             stats["emotion_dim"],
        "live_emotion_dims":       stats["live_emotion_dims"],
        "emotion_index_order":     stats["emotion_index_order"],
        "action_index_order":      stats["action_index_order"],
        "perception_feature_order": stats["perception_feature_order"],
        "min_arousal":             stats["min_arousal"],
        "max_arousal":             stats["max_arousal"],
        "model_hash":              model_hash,
        "trained_on":              date.today().isoformat(),
    }

    contract_path = out_dir / "model_contract.json"
    contract_path.write_text(json.dumps(contract, indent=2))
    print(f"  Saved {contract_path}")
    print(f"  model_hash = {model_hash}")
