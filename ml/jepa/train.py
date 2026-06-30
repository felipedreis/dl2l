"""
SIGReg training loop for the DL2L species base model.

Supports both single-encoder (SpeciesModel) and dual-encoder (DualSpeciesModel).

Loss = L_pred + λ_sigreg * L_sigreg + λ_crit * L_crit

L_pred   : next-latent prediction MSE (stop-gradient on target encoder output)
L_sigreg : Gaussian regulariser — pushes batch z_world distribution toward N(0, I)
L_crit   : critic MSE against need-weighted homeostatic relief target (dual) or
           absolute emotion levels (single).

Need-weighted relief (dual encoder):
  relief_i = tanh( (emotion_next_i - h_i) / (h_i + ε) )

  where h_i is the current need level (from h_t) and emotion_next_i is the actual
  next emotion level. This normalises the delta by current need so that:
    - EAT when hungry=4.0, next_hunger=1.0 → tanh(-3.0/4.0) ≈ -0.636  (strong relief)
    - SLEEP when hungry=4.0, next_hunger=4.0 → tanh(0/4.0) = 0.0       (no relief)
  The Critic learns a distinct signal for EAT vs SLEEP even from imbalanced data
  because the signal magnitude is proportional to need, not action frequency.
"""

from __future__ import annotations

from typing import Optional, Union

import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from .model import SpeciesModel, DualSpeciesModel


def sigreg_loss(z: torch.Tensor) -> torch.Tensor:
    """L_sigreg = ||mean(z)||^2 + ||(var(z) - 1)||^2  (per-dim, averaged)."""
    mu  = z.mean(dim=0)
    var = z.var(dim=0)
    return mu.pow(2).mean() + (var - 1).pow(2).mean()


def critic_loss(
    emotion_pred: torch.Tensor,
    emotion_curr: torch.Tensor,
    live_dims: list[int],
    h_curr: Optional[torch.Tensor],
) -> torch.Tensor:
    """MSE between Critic output and need-weighted homeostatic relief target.

    Dual-encoder path (h_curr provided):
        relief_i = tanh( (emotion_next_i - h_i) / (h_i + ε) )

    The tanh normalises the emotion delta by current need, so EAT-while-hungry
    produces a strong signal (-0.64) and SLEEP-while-hungry produces near-zero (0.0),
    even when SLEEP examples outnumber EAT 100:1 in training data.

    Single-encoder fallback: standard MSE on absolute emotion levels.
    """
    pred = emotion_pred[:, live_dims]
    if h_curr is not None:
        # h_curr columns are already in live_dims order (from prepare_dataset --dual)
        h_live  = h_curr[:, : len(live_dims)]
        delta   = emotion_curr[:, live_dims] - h_live
        target  = torch.tanh(delta / (h_live + 1e-6))
    else:
        target = emotion_curr[:, live_dims]
    return nn.functional.mse_loss(pred, target)


def train_one_epoch(
    model: Union[SpeciesModel, DualSpeciesModel],
    loader: DataLoader,
    optimizer: torch.optim.Optimizer,
    live_dims: list[int],
    lambda_sigreg: float,
    lambda_crit: float,
    device: torch.device,
) -> dict[str, float]:
    model.train()
    totals = {"pred": 0.0, "sigreg": 0.0, "crit": 0.0, "total": 0.0}
    n_batches = 0
    dual = isinstance(model, DualSpeciesModel)

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
        l_crit   = critic_loss(emotion_pred, emotion_curr, live_dims,
                               h_curr if dual else None)

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
) -> dict[str, float]:
    model.eval()
    totals = {"pred": 0.0, "sigreg": 0.0, "crit": 0.0, "total": 0.0}
    n_batches = 0
    dual = isinstance(model, DualSpeciesModel)

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
        l_crit   = critic_loss(emotion_pred, emotion_curr, live_dims,
                               h_curr if dual else None)
        loss = l_pred + lambda_sigreg * l_sigreg + lambda_crit * l_crit

        totals["pred"]   += l_pred.item()
        totals["sigreg"] += l_sigreg.item()
        totals["crit"]   += l_crit.item()
        totals["total"]  += loss.item()
        n_batches += 1

    if n_batches == 0:
        return totals
    return {k: v / n_batches for k, v in totals.items()}
