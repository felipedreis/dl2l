"""
PyTorch Dataset for DL2L trajectory tuples.

Each sample is (s_t, a_t, emotion_target) where:
  s_t           : float32 [input_dim]   — normalised perception features
  a_t           : float32 [action_dim]  — one-hot action type
  emotion_target: float32 [emotion_dim] — absolute arousal levels in [min, max]
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch.utils.data import Dataset


class TrajectoryDataset(Dataset):
    def __init__(self, parquet_path: str, stats_path: str):
        stats = json.loads(Path(stats_path).read_text())
        df = pd.read_parquet(parquet_path)

        feature_cols = stats["perception_feature_order"]
        action_cols  = [f"a_{a}" for a in stats["action_index_order"]]
        emotion_cols = [f"final_{e}" for e in stats["emotion_index_order"]]

        means = np.array(stats["feature_means"], dtype=np.float32)
        stds  = np.array(stats["feature_stds"],  dtype=np.float32)

        s = df[feature_cols].values.astype(np.float32)
        # Normalise only the continuous dims (first 3: distance, angle, direction).
        # One-hot dims are already in {0, 1} and must not be shifted.
        n_continuous = 3
        s[:, :n_continuous] = (s[:, :n_continuous] - means[:n_continuous]) / (
            stds[:n_continuous] + 1e-8
        )

        self.s       = torch.from_numpy(s)
        self.a       = torch.from_numpy(df[action_cols].values.astype(np.float32))
        self.emotion = torch.from_numpy(df[emotion_cols].values.astype(np.float32))

    def __len__(self) -> int:
        return len(self.s)

    def __getitem__(self, idx: int):
        return self.s[idx], self.a[idx], self.emotion[idx]
