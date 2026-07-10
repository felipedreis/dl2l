"""
TorchScript export and model_contract.json generation.

Single-encoder:   exports 4 .pt files (encoder, predictor, critic, adapter).
Dual-encoder:     exports 5 .pt files (+ internal_encoder).
unified_critic:   exports 4 .pt files (encoder, internal_encoder, adapter, unified_predictor).

The Java loader verifies the model_hash at startup.
"""

from __future__ import annotations

import hashlib
import json
from datetime import date
from pathlib import Path
from typing import Optional

import torch

from .model import Encoder, InternalEncoder, Predictor, Critic, IndividualAdapter, UnifiedPredictor


def _sha256_files(paths: list[Path]) -> str:
    h = hashlib.sha256()
    for p in sorted(paths, key=lambda x: x.name):
        h.update(p.read_bytes())
    return h.hexdigest()


class _UnifiedPredictorWrapper(torch.nn.Module):
    """Wraps UnifiedPredictor so it accepts / returns flat tensors for tracing.

    Java DJL calls predict(NDList([z_world, a, z_internal])) and expects
    NDList([z_next, emotion]) back.  TorchScript does not support NDList natively,
    so we wrap as a module whose forward takes three tensors and returns a tuple,
    then trace it with a tuple example — DJL's NDList maps directly to this.
    """

    def __init__(self, up: UnifiedPredictor):
        super().__init__()
        self.up = up

    def forward(
        self,
        z_world: torch.Tensor,
        a: torch.Tensor,
        z_internal: torch.Tensor,
    ) -> tuple[torch.Tensor, torch.Tensor]:
        return self.up(z_world, a, z_internal)


def export(
    encoder:           Encoder,
    predictor:         Optional[Predictor],
    critic:            Optional[Critic],
    adapter:           IndividualAdapter,
    stats:             dict,
    out_dir:           Path,
    internal_encoder:  Optional[InternalEncoder] = None,
    unified_predictor: Optional[UnifiedPredictor] = None,
    model_variant:     str = "single",  # "single"|"dual"|"internal_critic"|"internal_predictor"|"unified_critic"
) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)

    input_dim  = stats["input_dim"]
    action_dim = stats["action_dim"]
    latent_dim = stats["latent_dim"]

    dual = internal_encoder is not None
    internal_state_dim  = stats.get("internal_state_dim", 0)
    internal_latent_dim = stats.get("internal_latent_dim", 0)
    has_unified = unified_predictor is not None

    dummy_s       = torch.zeros(1, input_dim)
    dummy_z_world = torch.zeros(1, latent_dim)
    dummy_a       = torch.zeros(1, action_dim)
    pts: list[Path] = []

    encoder.eval()
    adapter.eval()
    traced_enc = torch.jit.trace(encoder, dummy_s)
    enc_path = out_dir / "species_encoder.pt"
    torch.jit.save(traced_enc, str(enc_path))
    pts.append(enc_path)
    print(f"  Saved {enc_path}")

    traced_adp = torch.jit.trace(adapter, dummy_z_world)
    adp_path = out_dir / "species_adapter.pt"
    torch.jit.save(traced_adp, str(adp_path))
    pts.append(adp_path)
    print(f"  Saved {adp_path}")

    if dual:
        internal_encoder.eval()
        dummy_h = torch.zeros(1, internal_state_dim)
        traced_ie = torch.jit.trace(internal_encoder, dummy_h)
        ie_path = out_dir / "species_internal_encoder.pt"
        torch.jit.save(traced_ie, str(ie_path))
        pts.append(ie_path)
        print(f"  Saved {ie_path}")

    if has_unified:
        unified_predictor.eval()
        dummy_z_int = torch.zeros(1, internal_latent_dim)
        wrapper = _UnifiedPredictorWrapper(unified_predictor)
        traced_up = torch.jit.trace(wrapper, (dummy_z_world, dummy_a, dummy_z_int))
        up_path = out_dir / "species_unified_predictor.pt"
        torch.jit.save(traced_up, str(up_path))
        pts.append(up_path)
        print(f"  Saved {up_path}")
    else:
        # Predictor sees combined latent for dual and internal_predictor; world-only otherwise.
        predictor_latent_in = (latent_dim + internal_latent_dim) \
                              if model_variant in ("dual", "internal_predictor") else latent_dim
        # Critic sees combined latent for dual and internal_critic; world-only otherwise.
        critic_latent_in = (latent_dim + internal_latent_dim) \
                           if model_variant in ("dual", "internal_critic") else latent_dim

        predictor.eval()
        critic.eval()
        dummy_pred_in  = torch.zeros(1, predictor_latent_in)
        dummy_z_critic = torch.zeros(1, critic_latent_in)
        for module, name, example in [
            (predictor, "species_predictor", (dummy_pred_in, dummy_a)),
            (critic,    "species_critic",    (dummy_z_critic, dummy_a)),
        ]:
            traced = torch.jit.trace(module, example)
            path = out_dir / f"{name}.pt"
            torch.jit.save(traced, str(path))
            pts.append(path)
            print(f"  Saved {path}")

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
        "has_unified_predictor":    has_unified,
        "model_variant":            model_variant,
        "model_hash":               model_hash,
        "trained_on":               date.today().isoformat(),
    }
    if dual:
        contract["internal_state_feature_order"] = stats["internal_state_feature_order"]
        contract["internal_state_dim"]           = internal_state_dim
        contract["internal_latent_dim"]          = internal_latent_dim
        contract["critic_output"]                = "need_satisfaction_tanh_v4"

    contract_path = out_dir / "model_contract.json"
    contract_path.write_text(json.dumps(contract, indent=2))
    print(f"  Saved {contract_path}")
    print(f"  model_hash = {model_hash}")
    print(f"  dual_encoder = {dual}")
    print(f"  unified_predictor = {has_unified}")
