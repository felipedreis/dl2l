"""
Collapse check for the DL2L species encoder.

Two failure conditions (either triggers exit code 1 in check_collapse.py):
  1. Any latent dimension with variance < MIN_DIM_VARIANCE (dead neuron).
  2. Effective rank < EFF_RANK_FRACTION * latent_dim (collapsed to low-rank manifold).

Effective rank: exp(H) where H = -sum(p_i * log(p_i)) over normalised singular values
of the centred latent matrix.  Ref: Roy & Vetterli, 2007.
"""

from __future__ import annotations

import numpy as np
import torch
from torch.utils.data import DataLoader

from .model import Encoder

MIN_DIM_VARIANCE    = 1e-4
EFF_RANK_FRACTION   = 0.10   # effective rank must be >= 10% of latent_dim


@torch.no_grad()
def collect_latents(encoder: Encoder, loader: DataLoader, device: torch.device) -> np.ndarray:
    encoder.eval()
    chunks: list[np.ndarray] = []
    for batch in loader:
        s_t = batch[0].to(device)
        z   = encoder(s_t).cpu().numpy()
        chunks.append(z)
    return np.concatenate(chunks, axis=0)   # [N, latent_dim]


def check_collapse(z: np.ndarray, latent_dim: int) -> dict:
    per_dim_var = z.var(axis=0)
    dead_dims   = np.where(per_dim_var < MIN_DIM_VARIANCE)[0].tolist()

    z_centered = z - z.mean(axis=0, keepdims=True)
    _, sigma, _ = np.linalg.svd(z_centered, full_matrices=False)
    p = sigma / (sigma.sum() + 1e-12)
    eff_rank = float(np.exp(-(p * np.log(p + 1e-12)).sum()))
    eff_rank_threshold = EFF_RANK_FRACTION * latent_dim

    return {
        "latent_dim":         latent_dim,
        "per_dim_var":        per_dim_var.tolist(),
        "min_dim_var":        float(per_dim_var.min()),
        "dead_dims":          dead_dims,
        "effective_rank":     eff_rank,
        "eff_rank_threshold": eff_rank_threshold,
        "pass_var":           len(dead_dims) == 0,
        "pass_eff_rank":      eff_rank >= eff_rank_threshold,
    }
