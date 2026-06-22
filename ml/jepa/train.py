"""
SIGReg training loop for the DL2L species base model.

Loss = L_pred + λ_sigreg * L_sigreg + λ_crit * L_crit

L_pred   : next-latent prediction MSE (stop-gradient on target encoder output)
L_sigreg : Gaussian regulariser — pushes batch latent distribution toward N(0, I)
L_crit   : critic MSE against true finalEmotionalState, masked to live dims only
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from .model import SpeciesModel


def sigreg_loss(z: torch.Tensor) -> torch.Tensor:
    """L_sigreg = ||mean(z)||^2 + ||(var(z) - 1)||^2  (per-dim, averaged)."""
    mu  = z.mean(dim=0)
    var = z.var(dim=0)
    return mu.pow(2).mean() + (var - 1).pow(2).mean()


def train_one_epoch(
    model: SpeciesModel,
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

    for batch in loader:
        s_t, a_t, emotion_target = [x.to(device) for x in batch]

        # Build (s_t, s_{t+1}) pairs by shifting within the batch.
        # Samples are ordered by (creatureKey, action_time), so consecutive rows
        # in the same creature's segment are adjacent in the DataLoader.
        # We drop the last sample per batch (no next state available for it).
        if s_t.shape[0] < 2:
            continue
        s_curr = s_t[:-1]
        a_curr = a_t[:-1]
        emotion_curr = emotion_target[:-1]
        s_next = s_t[1:]

        z_t, z_next_hat, emotion_pred = model(s_curr, a_curr)

        # L_pred: predict next latent, stop-gradient on target
        with torch.no_grad():
            z_next_target = model.encoder(s_next)
        l_pred = nn.functional.mse_loss(z_next_hat, z_next_target)

        # L_sigreg: push batch latent toward N(0, I)
        l_sigreg = sigreg_loss(z_t)

        # L_crit: supervised on live emotion dims only
        l_crit = nn.functional.mse_loss(
            emotion_pred[:, live_dims], emotion_curr[:, live_dims]
        )

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
    model: SpeciesModel,
    loader: DataLoader,
    live_dims: list[int],
    lambda_sigreg: float,
    lambda_crit: float,
    device: torch.device,
) -> dict[str, float]:
    model.eval()
    totals = {"pred": 0.0, "sigreg": 0.0, "crit": 0.0, "total": 0.0}
    n_batches = 0

    for batch in loader:
        s_t, a_t, emotion_target = [x.to(device) for x in batch]
        if s_t.shape[0] < 2:
            continue
        s_curr, a_curr, emotion_curr = s_t[:-1], a_t[:-1], emotion_target[:-1]
        s_next = s_t[1:]

        z_t, z_next_hat, emotion_pred = model(s_curr, a_curr)
        z_next_target = model.encoder(s_next)

        l_pred   = nn.functional.mse_loss(z_next_hat, z_next_target)
        l_sigreg = sigreg_loss(z_t)
        l_crit   = nn.functional.mse_loss(
            emotion_pred[:, live_dims], emotion_curr[:, live_dims]
        )
        loss = l_pred + lambda_sigreg * l_sigreg + lambda_crit * l_crit

        totals["pred"]   += l_pred.item()
        totals["sigreg"] += l_sigreg.item()
        totals["crit"]   += l_crit.item()
        totals["total"]  += loss.item()
        n_batches += 1

    if n_batches == 0:
        return totals
    return {k: v / n_batches for k, v in totals.items()}
