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

import copy
from typing import Optional, Union

import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from .model import (SpeciesModel, DualSpeciesModel, InternalCriticModel,
                    InternalPredictorModel, InternalCriticUnifiedModel)

# All model classes that require h_t (internal state) as a third input.
DUAL_ENCODER_MODELS = (DualSpeciesModel, InternalCriticModel, InternalPredictorModel,
                       InternalCriticUnifiedModel)


@torch.no_grad()
def _update_ema(target: nn.Module, online: nn.Module, decay: float) -> None:
    """EMA update: θ_target = decay * θ_target + (1-decay) * θ_online."""
    for p_t, p_o in zip(target.parameters(), online.parameters()):
        p_t.data.mul_(decay).add_(p_o.data, alpha=1.0 - decay)


def sigreg_loss(z: torch.Tensor) -> torch.Tensor:
    """L_sigreg = ||mean(z)||^2 + ||(var(z) - 1)||^2  (per-dim, averaged)."""
    mu  = z.mean(dim=0)
    var = z.var(dim=0)
    return mu.pow(2).mean() + (var - 1).pow(2).mean()


def vicreg_loss(z: torch.Tensor, lambda_cov: float = 1.0) -> torch.Tensor:
    """VICReg regulariser (Bardes et al. 2022).

    L_var  : hinge loss pushing per-dim std above 1  (same role as sigreg variance term)
    L_cov  : penalises off-diagonal entries of the covariance matrix — forces each
             latent dimension to encode different information.

    Returns a scalar combining both terms with equal weight unless lambda_cov differs.
    The mean-centering term from the original VICReg paper is included in L_cov.
    """
    N, D = z.shape
    # Variance term: push each dim's std to >= 1
    std = z.std(dim=0)
    l_var = torch.relu(1.0 - std).mean()

    # Covariance term: penalise off-diagonal entries of cov(z)
    z_c = z - z.mean(dim=0)
    cov = (z_c.T @ z_c) / (N - 1)           # (D, D)
    off_diag = cov.pow(2).sum() - cov.diagonal().pow(2).sum()
    l_cov = off_diag / D

    return l_var + lambda_cov * l_cov


# Which action relieves / costs which drive dimension.
# Keys are action names; values are (dim_name, sign) pairs where
# sign=-1 means relief and sign=+1 means opportunity cost.
# dim_names must match internal_state_feature_order in stats.json (ht_ prefix stripped).
_ACTION_DIM_EFFECTS: dict[str, list[tuple[str, int]]] = {
    "EAT":      [("hunger", -1), ("dopamine", -1), ("serotonin", -1)],
    "APPROACH": [("hunger", -1), ("dopamine", -1)],
    "SLEEP":    [("sleep",  -1), ("hunger", +1), ("orexin", -1)],
    "AVOID":    [("pain",   -1)],
    "ESCAPE":   [("pain",   -1)],
    "PLAY":     [("tedium", -1)],
    "WANDER":   [("tedium", -1)],
}
# Must match the internal_state_feature_order (ht_/nm_/end_ prefixes stripped)
_LIVE_DIM_NAMES = ["hunger", "sleep", "pain", "tedium",
                   "dopamine", "serotonin", "orexin", "cortisol_tonic"]


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
    target_encoder: Optional[nn.Module] = None,
    ema_decay: float = 0.996,
) -> dict[str, float]:
    model.train()
    totals = {"pred": 0.0, "sigreg": 0.0, "crit": 0.0, "total": 0.0}
    n_batches = 0
    dual = isinstance(model, DUAL_ENCODER_MODELS)
    # Use EMA target encoder if provided, else fall back to online encoder (moving target).
    enc_for_target = target_encoder if target_encoder is not None else model.encoder

    for batch in loader:
        if dual:
            s_t, h_t, a_t, emotion_target = [x.to(device) for x in batch]
        else:
            s_t, a_t, emotion_target = [x.to(device) for x in batch]

        if s_t.shape[0] < 2:
            continue

        s_curr = s_t[:-1]
        a_curr = a_t[:-1]
        s_next = s_t[1:]

        if dual:
            h_curr = h_t[:-1]
            z_world, z_next_hat, emotion_pred = model(s_curr, h_curr, a_curr)
        else:
            h_curr = None
            z_world, z_next_hat, emotion_pred = model(s_curr, a_curr)

        with torch.no_grad():
            z_next_target = enc_for_target(s_next)

        l_pred   = nn.functional.mse_loss(z_next_hat, z_next_target)
        l_sigreg = vicreg_loss(z_world)
        l_crit   = critic_loss(emotion_pred, a_curr, live_dims,
                               h_curr if dual else None,
                               action_names if dual else None)

        loss = l_pred + lambda_sigreg * l_sigreg + lambda_crit * l_crit

        optimizer.zero_grad()
        loss.backward()
        optimizer.step()

        if target_encoder is not None:
            _update_ema(target_encoder, model.encoder, ema_decay)

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
    target_encoder: Optional[nn.Module] = None,
) -> dict[str, float]:
    model.eval()
    totals = {"pred": 0.0, "sigreg": 0.0, "crit": 0.0, "total": 0.0}
    n_batches = 0
    dual = isinstance(model, DUAL_ENCODER_MODELS)
    enc_for_target = target_encoder if target_encoder is not None else model.encoder

    for batch in loader:
        if dual:
            s_t, h_t, a_t, emotion_target = [x.to(device) for x in batch]
        else:
            s_t, a_t, emotion_target = [x.to(device) for x in batch]

        if s_t.shape[0] < 2:
            continue

        s_curr, a_curr = s_t[:-1], a_t[:-1]
        s_next = s_t[1:]

        if dual:
            h_curr = h_t[:-1]
            z_world, z_next_hat, emotion_pred = model(s_curr, h_curr, a_curr)
            z_next_target = enc_for_target(s_next)
        else:
            h_curr = None
            z_world, z_next_hat, emotion_pred = model(s_curr, a_curr)
            z_next_target = enc_for_target(s_next)

        l_pred   = nn.functional.mse_loss(z_next_hat, z_next_target)
        l_sigreg = vicreg_loss(z_world)
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
