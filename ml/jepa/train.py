"""
SIGReg training loop for the DL2L species base model.

Supports both single-encoder (SpeciesModel) and dual-encoder (DualSpeciesModel).

Loss = L_pred + λ_sigreg * L_sigreg + λ_crit * L_crit

L_pred   : next-latent prediction MSE (stop-gradient on target encoder output)
L_sigreg : Gaussian regulariser — pushes batch z_world distribution toward N(0, I)
L_crit   : critic MSE against need-satisfaction target (dual) or zero (single).

Need-satisfaction target (dual encoder):
  For each action a taken when the creature's drive levels are h:
    - EAT/APPROACH → target_hunger = -tanh(h_hunger / scale)  (relief ∝ need)
    - SLEEP → target_sleep  = -tanh(h_sleep  / scale)  (relief ∝ need)
              target_hunger = +tanh(h_hunger / scale)  (opportunity cost if hungry)
    - AVOID/ESCAPE → target_pain   = -tanh(h_pain   / scale)
    - PLAY/WANDER  → target_tedium = -tanh(h_tedium / scale)
    - all other dims in all actions → 0

  This produces targets in [-1, 1] that are:
    - Strong when the drive is high (creature genuinely needs that action)
    - Near zero when the drive is already satisfied (action is inappropriate)
    - Positive (costly) for SLEEP-while-hungry
  One-step emotion deltas are NOT used because regulation changes ~0.001/step —
  far too small to carry useful signal over a single timestep.
"""

from __future__ import annotations

from typing import Optional, Union

import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from .model import SpeciesModel, DualSpeciesModel, InternalCriticModel, InternalPredictorModel

# All model classes that require h_t (internal state) as a third input.
DUAL_ENCODER_MODELS = (DualSpeciesModel, InternalCriticModel, InternalPredictorModel)


def sigreg_loss(z: torch.Tensor) -> torch.Tensor:
    """L_sigreg = ||mean(z)||^2 + ||(var(z) - 1)||^2  (per-dim, averaged)."""
    mu  = z.mean(dim=0)
    var = z.var(dim=0)
    return mu.pow(2).mean() + (var - 1).pow(2).mean()


# Which action relieves / costs which drive dimension.
# Keys are action names; values are (dim_name, sign) pairs where
# sign=-1 means relief and sign=+1 means opportunity cost.
# dim_name must match the live emotion dim order: hunger, sleep, pain, tedium.
_ACTION_DIM_EFFECTS: dict[str, list[tuple[str, int]]] = {
    "EAT":      [("hunger", -1)],
    "APPROACH": [("hunger", -1)],   # moving toward food addresses hunger as strongly as eating
    "SLEEP":    [("sleep",  -1), ("hunger", +1)],  # relieves sleep, but costs hunger if hungry
    "AVOID":    [("pain",   -1)],
    "ESCAPE":   [("pain",   -1)],
    "PLAY":     [("tedium", -1)],
    "WANDER":   [("tedium", -1)],
}
_LIVE_DIM_NAMES = ["hunger", "sleep", "pain", "tedium"]


def critic_loss(
    emotion_pred: torch.Tensor,
    action_ohe:   torch.Tensor,
    live_dims:    list[int],
    h_curr:       Optional[torch.Tensor],
    action_names: Optional[list[str]] = None,
    scale:        float = 2.0,
) -> torch.Tensor:
    """MSE between Critic output and need-satisfaction target.

    Dual-encoder path (h_curr and action_names provided):
        For each action, assign targets based on which drive it addresses:
            target_d = -tanh(h_d / scale)  if action relieves drive d
            target_d = +tanh(h_d / scale)  if action costs drive d (opportunity cost)
            target_d = 0                   otherwise

        scale=2.0 means tanh saturates (~0.96) when a drive reaches 4+,
        which is well above the typical resting level (~0.2).

    Single-encoder / fallback: returns zero loss (Critic not trained).
    """
    pred = emotion_pred[:, live_dims]
    n_live = len(live_dims)

    if h_curr is None or action_names is None:
        return torch.zeros(1, device=pred.device, requires_grad=True)

    h_live = h_curr[:, :n_live]          # (B, n_live); already in live_dims order
    target = torch.zeros_like(pred)       # (B, n_live)

    dim_idx = {name: i for i, name in enumerate(_LIVE_DIM_NAMES[:n_live])}

    for action_name, effects in _ACTION_DIM_EFFECTS.items():
        if action_name not in action_names:
            continue
        col = action_names.index(action_name)
        mask = action_ohe[:, col] > 0.5   # (B,) bool
        if not mask.any():
            continue
        for dim_name, sign in effects:
            if dim_name not in dim_idx:
                continue
            d = dim_idx[dim_name]
            target[mask, d] = sign * torch.tanh(h_live[mask, d] / scale)

    return nn.functional.mse_loss(pred, target.detach())


def train_one_epoch(
    model: Union[SpeciesModel, DualSpeciesModel],
    loader: DataLoader,
    optimizer: torch.optim.Optimizer,
    live_dims: list[int],
    lambda_sigreg: float,
    lambda_crit: float,
    device: torch.device,
    action_names: Optional[list[str]] = None,
) -> dict[str, float]:
    model.train()
    totals = {"pred": 0.0, "sigreg": 0.0, "crit": 0.0, "total": 0.0}
    n_batches = 0
    dual = isinstance(model, DUAL_ENCODER_MODELS)

    for batch in loader:
        if dual:
            s_t, h_t, a_t, emotion_target = [x.to(device) for x in batch]
        else:
            s_t, a_t, emotion_target = [x.to(device) for x in batch]

        if s_t.shape[0] < 2:
            continue

        s_curr = s_t[:-1]
        a_curr = a_t[:-1]
        emotion_curr = emotion_target[:-1]
        s_next = s_t[1:]

        if dual:
            h_curr = h_t[:-1]
            z_world, z_next_hat, emotion_pred = model(s_curr, h_curr, a_curr)
            with torch.no_grad():
                z_next_target = model.encoder(s_next)
        else:
            z_world, z_next_hat, emotion_pred = model(s_curr, a_curr)
            with torch.no_grad():
                z_next_target = model.encoder(s_next)

        l_pred   = nn.functional.mse_loss(z_next_hat, z_next_target)
        l_sigreg = sigreg_loss(z_world)
        l_crit   = critic_loss(emotion_pred, a_curr, live_dims,
                               h_curr if dual else None,
                               action_names if dual else None)

        loss = l_pred + lambda_sigreg * l_sigreg + lambda_crit * l_crit

        optimizer.zero_grad()
        loss.backward()
        optimizer.step()

        totals["pred"]   += l_pred.item()
        totals["sigreg"] += l_sigreg.item()
        totals["crit"]   += l_crit.item()
        totals["total"]  += loss.item()
        n_batches += 1

    if n_batches == 0:
        return totals
    return {k: v / n_batches for k, v in totals.items()}


@torch.no_grad()
def evaluate(
    model: Union[SpeciesModel, DualSpeciesModel],
    loader: DataLoader,
    live_dims: list[int],
    lambda_sigreg: float,
    lambda_crit: float,
    device: torch.device,
    action_names: Optional[list[str]] = None,
) -> dict[str, float]:
    model.eval()
    totals = {"pred": 0.0, "sigreg": 0.0, "crit": 0.0, "total": 0.0}
    n_batches = 0
    dual = isinstance(model, DUAL_ENCODER_MODELS)

    for batch in loader:
        if dual:
            s_t, h_t, a_t, emotion_target = [x.to(device) for x in batch]
        else:
            s_t, a_t, emotion_target = [x.to(device) for x in batch]

        if s_t.shape[0] < 2:
            continue

        s_curr, a_curr, emotion_curr = s_t[:-1], a_t[:-1], emotion_target[:-1]
        s_next = s_t[1:]

        if dual:
            h_curr = h_t[:-1]
            z_world, z_next_hat, emotion_pred = model(s_curr, h_curr, a_curr)
            z_next_target = model.encoder(s_next)
        else:
            z_world, z_next_hat, emotion_pred = model(s_curr, a_curr)
            z_next_target = model.encoder(s_next)

        l_pred   = nn.functional.mse_loss(z_next_hat, z_next_target)
        l_sigreg = sigreg_loss(z_world)
        l_crit   = critic_loss(emotion_pred, a_curr, live_dims,
                               h_curr if dual else None,
                               action_names if dual else None)
        loss = l_pred + lambda_sigreg * l_sigreg + lambda_crit * l_crit

        totals["pred"]   += l_pred.item()
        totals["sigreg"] += l_sigreg.item()
        totals["crit"]   += l_crit.item()
        totals["total"]  += loss.item()
        n_batches += 1

    if n_batches == 0:
        return totals
    return {k: v / n_batches for k, v in totals.items()}
