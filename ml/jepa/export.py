"""
TorchScript export and model_contract.json generation.

Single-encoder: exports 4 .pt files (encoder, predictor, critic, adapter).
Dual-encoder:   exports 5 .pt files (+ internal_encoder).

The Java loader verifies the model_hash at startup.
"""

from __future__ import annotations

import hashlib
import json
from datetime import date
from pathlib import Path
from typing import Optional

import torch

from .model import Encoder, InternalEncoder, Predictor, Critic, IndividualAdapter


def _sha256_files(paths: list[Path]) -> str:
    h = hashlib.sha256()
    for p in sorted(paths, key=lambda x: x.name):
        h.update(p.read_bytes())
    return h.hexdigest()


def export(
    encoder:           Encoder,
    predictor:         Predictor,
    critic:            Critic,
    adapter:           IndividualAdapter,
    stats:             dict,
    out_dir:           Path,
    internal_encoder:  Optional[InternalEncoder] = None,
) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)

    input_dim  = stats["input_dim"]
    action_dim = stats["action_dim"]
    latent_dim = stats["latent_dim"]

    dual = internal_encoder is not None
    internal_state_dim  = stats.get("internal_state_dim", 0)
    internal_latent_dim = stats.get("internal_latent_dim", 0)
    # Predictor input = z_world (+ z_internal when dual) + action
    predictor_in_dim = (latent_dim + internal_latent_dim + action_dim) if dual \
                       else (latent_dim + action_dim)

    encoder.eval()
    predictor.eval()
    critic.eval()
    adapter.eval()

    dummy_s        = torch.zeros(1, input_dim)
    dummy_z_world  = torch.zeros(1, latent_dim)
    dummy_a        = torch.zeros(1, action_dim)
    dummy_pred_in  = torch.zeros(1, predictor_in_dim - action_dim)  # latent part
    # Dual critic takes concat(z_next[latent_dim], z_internal[internal_latent_dim]).
    dummy_z_critic = torch.zeros(1, latent_dim + internal_latent_dim) if dual \
                     else dummy_z_world

    pts: list[Path] = []
    for module, name, example in [
        (encoder,   "species_encoder",   dummy_s),
        (predictor, "species_predictor", (dummy_pred_in, dummy_a)),
        (critic,    "species_critic",    (dummy_z_critic, dummy_a)),
        (adapter,   "species_adapter",   dummy_z_world),
    ]:
        traced = torch.jit.trace(module, example)
        path = out_dir / f"{name}.pt"
        torch.jit.save(traced, str(path))
        pts.append(path)
        print(f"  Saved {path}")

    if dual:
        internal_encoder.eval()
        dummy_h = torch.zeros(1, internal_state_dim)
        traced_ie = torch.jit.trace(internal_encoder, dummy_h)
        ie_path = out_dir / "species_internal_encoder.pt"
        torch.jit.save(traced_ie, str(ie_path))
        pts.append(ie_path)
        print(f"  Saved {ie_path}")

    model_hash = _sha256_files(pts)

    contract: dict = {
        "schema_version":           1,
        "input_dim":                input_dim,
        "latent_dim":               latent_dim,
        "action_dim":               action_dim,
        "emotion_dim":              stats["emotion_dim"],
        "live_emotion_dims":        stats["live_emotion_dims"],
        "emotion_index_order":      stats["emotion_index_order"],
        "action_index_order":       stats["action_index_order"],
        "perception_feature_order": stats["perception_feature_order"],
        "min_arousal":              stats["min_arousal"],
        "max_arousal":              stats["max_arousal"],
        "has_internal_encoder":     dual,
        "model_hash":               model_hash,
        "trained_on":               date.today().isoformat(),
    }
    if dual:
        contract["internal_state_feature_order"] = stats["internal_state_feature_order"]
        contract["internal_state_dim"]           = internal_state_dim
        contract["internal_latent_dim"]          = internal_latent_dim

    contract_path = out_dir / "model_contract.json"
    contract_path.write_text(json.dumps(contract, indent=2))
    print(f"  Saved {contract_path}")
    print(f"  model_hash = {model_hash}")
    print(f"  dual_encoder = {dual}")
