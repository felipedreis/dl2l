"""
JEPA world-model architecture for the DL2L species base model.

Three modules trained jointly:
  Encoder   : s_t  -> z_t          (input_dim -> latent_dim)
  Predictor : z_t, a_t -> z_{t+1}  (latent_dim + action_dim -> latent_dim)
  Critic    : z_t, a_t -> emotion   (latent_dim + action_dim -> emotion_dim, bounded)

An IndividualAdapter wraps the Predictor output with a small additive MLP.
The species base (Encoder + Predictor + Critic) is frozen post-training;
only the per-creature adapter is updated at sleep consolidation time.
"""

import torch
import torch.nn as nn
from typing import Tuple


def _mlp(in_dim: int, hidden: int, out_dim: int, norm: bool = True) -> nn.Sequential:
    layers: list[nn.Module] = [nn.Linear(in_dim, hidden)]
    if norm:
        layers.append(nn.LayerNorm(hidden))
    layers.append(nn.ReLU())
    layers.append(nn.Linear(hidden, hidden))
    if norm:
        layers.append(nn.LayerNorm(hidden))
    layers.append(nn.ReLU())
    layers.append(nn.Linear(hidden, out_dim))
    return nn.Sequential(*layers)


class Encoder(nn.Module):
    """Enc(s_t) -> z_t  [latent_dim]"""

    def __init__(self, input_dim: int, latent_dim: int, hidden: int = 128):
        super().__init__()
        self.net = _mlp(input_dim, hidden, latent_dim, norm=True)

    def forward(self, s: torch.Tensor) -> torch.Tensor:
        return self.net(s)


class Predictor(nn.Module):
    """Pred(z_t, a_t) -> z_{t+1}_hat  [latent_dim]

    in_latent_dim: input latent size (defaults to latent_dim).  Set this to
    combined_latent_dim when used inside DualSpeciesModel so the Predictor can
    receive concat(z_world, z_internal) as input while still outputting a
    pure world-latent of size latent_dim.
    """

    def __init__(self, latent_dim: int, action_dim: int, hidden: int = 128,
                 in_latent_dim: int = None):
        super().__init__()
        in_dim = in_latent_dim if in_latent_dim is not None else latent_dim
        self.net = _mlp(in_dim + action_dim, hidden, latent_dim, norm=True)

    def forward(self, z: torch.Tensor, a: torch.Tensor) -> torch.Tensor:
        return self.net(torch.cat([z, a], dim=-1))


class Critic(nn.Module):
    """Crit(z_t, a_t) -> emotion_pred  [emotion_dim], bounded to [min_arousal, max_arousal].

    The bound is mandatory: Task 6.3 scores actions via exp(-cost) where
    cost = sum(aversive dims). Unbounded output makes exp(-cost) numerically unstable.
    """

    def __init__(
        self,
        latent_dim: int,
        action_dim: int,
        emotion_dim: int,
        min_arousal: float,
        max_arousal: float,
        hidden: int = 128,
    ):
        super().__init__()
        self.net = _mlp(latent_dim + action_dim, hidden, emotion_dim, norm=False)
        self.min_arousal = min_arousal
        self.range = max_arousal - min_arousal

    def forward(self, z: torch.Tensor, a: torch.Tensor) -> torch.Tensor:
        raw = self.net(torch.cat([z, a], dim=-1))
        return self.min_arousal + self.range * torch.sigmoid(raw)


class IndividualAdapter(nn.Module):
    """Additive delta on top of Predictor output. One instance per creature.

    Usage:
        z_next = predictor(z, a) + adapter(predictor(z, a))

    The species base is frozen; only the adapter is updated during sleep consolidation.
    """

    def __init__(self, latent_dim: int, hidden: int = 32):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(latent_dim, hidden),
            nn.ReLU(),
            nn.Linear(hidden, latent_dim),
        )
        # Identity init (LoRA / ControlNet / Houlsby pattern): zero the output
        # projection so adapter(z) == 0 at start, making
        # predictor(z, a) + adapter(predictor(z, a)) == predictor(z, a).
        # The first Linear keeps its default Kaiming init so gradients still
        # flow back through it during sleep consolidation.
        nn.init.zeros_(self.net[-1].weight)
        nn.init.zeros_(self.net[-1].bias)

    def forward(self, z: torch.Tensor) -> torch.Tensor:
        return self.net(z)


class SpeciesModel(nn.Module):
    """Combines Encoder + Predictor + Critic for joint training and export."""

    def __init__(
        self,
        input_dim: int,
        action_dim: int,
        latent_dim: int,
        emotion_dim: int,
        min_arousal: float = 0.18,
        max_arousal: float = 7.0,
        hidden: int = 128,
    ):
        super().__init__()
        self.encoder   = Encoder(input_dim, latent_dim, hidden)
        self.predictor = Predictor(latent_dim, action_dim, hidden)
        self.critic    = Critic(latent_dim, action_dim, emotion_dim,
                                min_arousal, max_arousal, hidden)

    def forward(
        self, s_t: torch.Tensor, a_t: torch.Tensor
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """Returns (z_t, z_next_hat, emotion_pred)."""
        z_t    = self.encoder(s_t)
        z_next = self.predictor(z_t, a_t)
        emotion = self.critic(z_t, a_t)
        return z_t, z_next, emotion


class InternalEncoder(nn.Module):
    """Enc(h_t) -> z_internal  [internal_latent_dim]

    Encodes the creature's live homeostatic state (hunger, sleep, pain, tedium)
    into a small latent vector that is concatenated with z_world before the
    Predictor and Critic in the dual-encoder architecture.

    No LayerNorm: the 4-dim input is already structured and normalised.
    """

    def __init__(self, internal_state_dim: int, internal_latent_dim: int, hidden: int = 32):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(internal_state_dim, hidden),
            nn.ReLU(),
            nn.Linear(hidden, internal_latent_dim),
            nn.ReLU(),
        )

    def forward(self, h: torch.Tensor) -> torch.Tensor:
        return self.net(h)


class DualSpeciesModel(nn.Module):
    """Dual-encoder species model: WorldEncoder + InternalEncoder combined at Predictor level.

    Architecture:
        s_t (perception)     -> WorldEncoder   -> z_world   [latent_dim]
        h_t (live emotions)  -> InternalEncoder -> z_internal [internal_latent_dim]

        concat(z_world, z_internal) + a_t -> Predictor -> z_next [latent_dim]
        z_next + a_t                      -> Critic    -> emotion_pred [emotion_dim]

    L_pred target: sg(WorldEncoder(s_{t+1})) — world latent only.
    SIGReg: applied to z_world only.
    IndividualAdapter wraps the WorldEncoder output before concatenation.
    """

    def __init__(
        self,
        input_dim: int,
        internal_state_dim: int,
        action_dim: int,
        latent_dim: int,
        internal_latent_dim: int,
        emotion_dim: int,
        min_arousal: float = 0.18,
        max_arousal: float = 7.0,
        hidden: int = 128,
    ):
        super().__init__()
        self.encoder          = Encoder(input_dim, latent_dim, hidden)
        self.internal_encoder = InternalEncoder(internal_state_dim, internal_latent_dim)
        combined_latent_dim   = latent_dim + internal_latent_dim
        # Predictor takes concat(z_world, z_internal) as input but outputs
        # a pure world-latent (latent_dim) so L_pred and the Critic stay
        # in the same 64-dim space as the single-encoder model.
        self.predictor        = Predictor(latent_dim, action_dim, hidden,
                                          in_latent_dim=combined_latent_dim)
        self.critic           = Critic(latent_dim, action_dim, emotion_dim,
                                       min_arousal, max_arousal, hidden)

    def forward(
        self,
        s_t: torch.Tensor,
        h_t: torch.Tensor,
        a_t: torch.Tensor,
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """Returns (z_world, z_next_hat, emotion_pred).

        z_world is returned (not z_combined) so the training loop can apply
        SIGReg and compute L_pred against the next world latent only.
        """
        z_world    = self.encoder(s_t)
        z_internal = self.internal_encoder(h_t)
        z_combined = torch.cat([z_world, z_internal], dim=-1)
        z_next     = self.predictor(z_combined, a_t)
        emotion    = self.critic(z_next, a_t)
        return z_world, z_next, emotion
