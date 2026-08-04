"""
PyTorch Dataset for DL2L trajectory tuples.

Single-encoder sample: (s_t, a_t, emotion_target)
  s_t           : float32 [input_dim]          — normalised perception features
  a_t           : float32 [action_dim]         — one-hot action type
  emotion_target: float32 [emotion_dim]        — absolute arousal levels in [min, max]

Dual-encoder sample: (s_t, h_t, a_t, emotion_target)
  h_t           : float32 [internal_state_dim] — live homeostatic dims at action time
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow as pa
import torch
from torch.utils.data import Dataset


def _load_source(parquet_path: str):
    """Prefers a sibling .arrow (Feather v2) file when present (written by
    ml/scripts/prepare_dataset.py's --emit-arrow) over the .parquet itself -
    pyarrow.ipc.open_file is the random-access/zero-copy reader matching
    Feather v2's file (not stream) format. This is a different Arrow format
    for a different reason than the write path's ArrowStreamWriter
    (docs/plans/arrow-ipc-write-path.md): that one is sequential-only,
    appropriate for append-only logging; this dataset is finished, static,
    and read many times over training, so the random-access file format is
    the right fit. Falls back to pd.read_parquet when no .arrow sibling
    exists (the common case until --emit-arrow is used).
    """
    arrow_path = Path(parquet_path).with_suffix(".arrow")
    if arrow_path.exists():
        with pa.memory_map(str(arrow_path), "r") as source:
            return pa.ipc.open_file(source).read_all()
    return pd.read_parquet(parquet_path)


def _columns_as_array(source, cols: list[str]) -> np.ndarray:
    """Extracts `cols` as one 2D float32 array from either a pandas DataFrame
    or a pyarrow Table (see _load_source) - keeps the rest of __init__'s
    normalization/tensor-building logic identical regardless of which path
    loaded the data. `to_numpy(zero_copy_only=False)` per pyarrow column
    (not `.to_pandas()` on the whole table first) is what actually realizes
    the zero-copy read for fixed-width columns.
    """
    if isinstance(source, pa.Table):
        return np.stack(
            [source.column(c).to_numpy(zero_copy_only=False) for c in cols], axis=1
        ).astype(np.float32)
    return source[cols].values.astype(np.float32)


def _column_names(source) -> list[str]:
    return source.column_names if isinstance(source, pa.Table) else list(source.columns)


class TrajectoryDataset(Dataset):
    def __init__(self, parquet_path: str, stats_path: str, dual: bool = False):
        stats = json.loads(Path(stats_path).read_text())
        df = _load_source(parquet_path)

        feature_cols = stats["perception_feature_order"]
        action_cols  = [f"a_{a}" for a in stats["action_index_order"]]
        emotion_cols = [f"final_{e}" for e in stats["emotion_index_order"]]

        means = np.array(stats["feature_means"], dtype=np.float32)
        stds  = np.array(stats["feature_stds"],  dtype=np.float32)

        s = _columns_as_array(df, feature_cols)
        n_continuous = 3
        s[:, :n_continuous] = (s[:, :n_continuous] - means[:n_continuous]) / (
            stds[:n_continuous] + 1e-8
        )

        self.s       = torch.from_numpy(s)
        self.a       = torch.from_numpy(_columns_as_array(df, action_cols))
        self.emotion = torch.from_numpy(_columns_as_array(df, emotion_cols))

        self.dual = dual
        self.h: torch.Tensor | None = None
        if dual:
            ht_cols = stats.get("internal_state_feature_order", [])
            if not ht_cols:
                raise ValueError(
                    "dual=True but stats.json has no internal_state_feature_order. "
                    "Run prepare_dataset.py with --dual first."
                )
            missing = [c for c in ht_cols if c not in _column_names(df)]
            if missing:
                raise ValueError(
                    f"Dual parquet missing h_t columns: {missing}. "
                    "Use train_dual.parquet / val_dual.parquet."
                )
            self.h = torch.from_numpy(_columns_as_array(df, ht_cols))

    def __len__(self) -> int:
        return len(self.s)

    def __getitem__(self, idx: int):
        if self.dual:
            return self.s[idx], self.h[idx], self.a[idx], self.emotion[idx]
        return self.s[idx], self.a[idx], self.emotion[idx]
