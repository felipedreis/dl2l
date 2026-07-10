"""
Trajectory tuple assembly for JEPA v2 training.

Reads Parquet files produced by scripts/exp_extract.py and assembles
(s_t, h_t, a_t, final_*) tuples for JEPA training.

h_t is always 8-dimensional:
    [ht_hunger, ht_sleep, ht_pain, ht_tedium,
     nm_dopamine, nm_serotonin, nm_orexin, end_cortisol_tonic]

Neuromodulators and endocrine are logged with seq (not simulation time),
so the join uses normalised within-creature rank as a proxy for time.
This is sound because tonic neuromodulator values change slowly.

Writes train_dual.parquet, val_dual.parquet, stats.json to --out.
Trials 1–13 → train; trials 14–15 → val (configurable via TRAIN_TRIALS / VAL_TRIALS).

Usage:
    cd ml
    python -m scripts.prepare_dataset \\
        --data data_datacollect_v2 \\
        --out  data_prepared_v2
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

# ── Constants ─────────────────────────────────────────────────────────────────
ACTION_INDEX_ORDER = ["APPROACH", "AVOID", "EAT", "ESCAPE", "PLAY",
                      "SLEEP", "TOUCH", "TURN", "WANDER"]

FRUIT_TYPES  = ["GRAY_APPLE", "GREEN_APPLE", "RED_APPLE", "ROTTEN_APPLE"]
PLANT_TYPES  = ["ALOE", "CACTUS"]
OBJECT_TYPES = FRUIT_TYPES + PLANT_TYPES

EMOTION_INDEX_ORDER = ["hunger", "sleep", "apathy", "stress", "pain",
                       "tedium", "fear", "curiosity", "fertility"]

# Live drive names (ht_ prefix) + neuromodulator / endocrine names with prefixes.
# The prefix encodes where each value comes from in encodeInternalState() on the Java side.
INTERNAL_STATE_FEATURE_ORDER = [
    "ht_hunger", "ht_sleep", "ht_pain", "ht_tedium",
    "nm_dopamine", "nm_serotonin", "nm_orexin",
    "end_cortisol_tonic",
]

LIVE_EMOTION_INDICES = [0, 1, 4, 5]  # hunger, sleep, pain, tedium in EMOTION_INDEX_ORDER
LIVE_EMOTION_NAMES   = [EMOTION_INDEX_ORDER[i] for i in LIVE_EMOTION_INDICES]

MIN_AROUSAL = 0.18
MAX_AROUSAL = 7.0

TRAIN_TRIALS = set(range(1, 14))   # 1–13
VAL_TRIALS   = set(range(14, 16))  # 14–15

VALID_SELECTION_TYPES = {"AFFORDANCE", "RANDOM"}

BY_KEY = ["creature_key", "trial"]


# ── Loaders ───────────────────────────────────────────────────────────────────

def load_all(data_dir: Path, filename: str) -> pd.DataFrame:
    """Concat all Parquet files with the given name found anywhere under data_dir."""
    paths = list(data_dir.rglob(filename))
    if not paths:
        return pd.DataFrame()
    frames = [pd.read_parquet(p) for p in paths]
    return pd.concat(frames, ignore_index=True)


def _cast_numeric(df: pd.DataFrame, cols: list[str]) -> pd.DataFrame:
    """Cast listed columns to float64, coercing unparseable strings to NaN."""
    for col in cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    return df


# ── Join helpers ──────────────────────────────────────────────────────────────

def asof_backward(left: pd.DataFrame, right: pd.DataFrame,
                  on: str, by: list[str]) -> pd.DataFrame:
    """Backward merge_asof: for each left row, last right row with key <= left key."""
    return pd.merge_asof(
        left.sort_values(on),
        right.sort_values(on),
        on=on, by=by, direction="backward",
    )


def assign_t_frac(df: pd.DataFrame, by: list[str], sort_col: str) -> pd.DataFrame:
    """Add _t_frac column in [0, 1] = within-group normalised rank by sort_col."""
    df = df.copy().sort_values(by + [sort_col])
    df["_t_frac"] = df.groupby(by).cumcount()
    maxval = df.groupby(by)["_t_frac"].transform("max").clip(lower=1)
    df["_t_frac"] = (df["_t_frac"] / maxval).astype(np.float32)
    return df


def asof_by_frac(left: pd.DataFrame, right: pd.DataFrame,
                 left_sort: str, right_sort: str,
                 by: list[str]) -> pd.DataFrame:
    """Join via normalised rank when tables lack a common time axis."""
    l = assign_t_frac(left,  by, left_sort)
    r = assign_t_frac(right, by, right_sort)
    result = pd.merge_asof(
        l.sort_values("_t_frac"),
        r.sort_values("_t_frac"),
        on="_t_frac", by=by, direction="backward",
    )
    return result.drop(columns=["_t_frac"], errors="ignore")


# ── Feature engineering ───────────────────────────────────────────────────────

def one_hot(series: pd.Series, categories: list[str]) -> pd.DataFrame:
    return pd.get_dummies(series, prefix="", prefix_sep="").reindex(
        columns=categories, fill_value=0
    ).astype(np.float32)


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--data", required=True,
                   help="Root directory containing exp_extract.py Parquet output")
    p.add_argument("--out",  default="data_prepared",
                   help="Output directory for train_dual.parquet, val_dual.parquet, stats.json")
    args = p.parse_args()

    data_dir = Path(args.data)
    out_dir  = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    # ── Load all tables ──────────────────────────────────────────────────────
    print("Loading Parquet tables …")
    actions     = load_all(data_dir, "actions.parquet")
    drives      = load_all(data_dir, "drives.parquet")
    perceptions = load_all(data_dir, "perceptions.parquet")
    nm          = load_all(data_dir, "neuromodulators.parquet")
    endocrine   = load_all(data_dir, "endocrine.parquet")

    if actions.empty:
        sys.exit("No actions.parquet found — run exp_extract.py first.")
    if drives.empty:
        sys.exit("No drives.parquet found — run exp_extract.py first.")

    # Cast numeric columns (extractor writes all non-trial columns as strings).
    actions  = _cast_numeric(actions,  ["time"])
    drives   = _cast_numeric(drives,   ["time"] + [f"init_{e}" for e in EMOTION_INDEX_ORDER]
                                               + [f"final_{e}" for e in EMOTION_INDEX_ORDER])
    if not perceptions.empty:
        perceptions = _cast_numeric(perceptions, ["time", "distance", "angle", "direction"])
    if not nm.empty:
        nm = _cast_numeric(nm, ["seq", "dopamine", "serotonin", "orexin"])
    if not endocrine.empty:
        endocrine = _cast_numeric(endocrine, ["seq", "cortisol_tonic"])

    print(f"  actions:     {len(actions):,} rows, "
          f"{actions['creature_key'].nunique()} creatures, "
          f"{actions['trial'].nunique()} trials")
    print(f"  drives:      {len(drives):,} rows")
    print(f"  perceptions: {len(perceptions):,} rows")
    print(f"  nm:          {len(nm):,} rows")
    print(f"  endocrine:   {len(endocrine):,} rows")

    # Keep only AFFORDANCE and RANDOM selections
    actions = actions[actions["selection_type"].isin(VALID_SELECTION_TYPES)].copy()
    print(f"  actions after selection filter: {len(actions):,}")

    # ── Join drives → actions (backward on time within creature+trial) ────────
    drive_cols = [f"init_{e}" for e in EMOTION_INDEX_ORDER
                  if f"init_{e}" in drives.columns]
    print("Joining drives → actions …")
    df = asof_backward(
        actions,
        drives[BY_KEY + ["time"] + drive_cols],
        on="time", by=BY_KEY,
    )
    print(f"  rows after drive join: {len(df):,}")

    # ── Join perceptions → actions (backward on time, take last seen object) ─
    if not perceptions.empty:
        print("Joining perceptions → actions …")
        perc_cols = ["distance", "angle", "direction", "object_type"]
        df = asof_backward(
            df,
            perceptions[BY_KEY + ["time"] + perc_cols],
            on="time", by=BY_KEY,
        )
        print(f"  rows after perception join: {len(df):,}")
    else:
        print("  WARNING: no perceptions found — perception features will be zero.")
        for col in ["distance", "angle", "direction", "object_type"]:
            df[col] = 0.0 if col != "object_type" else None

    # ── Join neuromodulators → actions by normalised rank ────────────────────
    if not nm.empty:
        print("Joining neuromodulators → actions (t_frac) …")
        nm_cols = [c for c in ["dopamine", "serotonin", "orexin"] if c in nm.columns]
        df = asof_by_frac(
            df, nm[BY_KEY + ["seq"] + nm_cols],
            left_sort="time", right_sort="seq", by=BY_KEY,
        )
        print(f"  rows after nm join: {len(df):,}")
    else:
        print("  WARNING: no neuromodulators found — nm features will be zero.")
        for col in ["dopamine", "serotonin", "orexin"]:
            df[col] = 0.0

    # ── Join endocrine → actions by normalised rank ───────────────────────────
    if not endocrine.empty:
        print("Joining endocrine → actions (t_frac) …")
        endo_cols = [c for c in ["cortisol_tonic"] if c in endocrine.columns]
        df = asof_by_frac(
            df, endocrine[BY_KEY + ["seq"] + endo_cols],
            left_sort="time", right_sort="seq", by=BY_KEY,
        )
        print(f"  rows after endocrine join: {len(df):,}")
    else:
        print("  WARNING: no endocrine found — cortisol_tonic will be zero.")
        df["cortisol_tonic"] = 0.0

    if df.empty:
        sys.exit("No tuples after all joins.")

    # ── Perception features (s_t) ─────────────────────────────────────────────
    continuous_cols = ["distance", "angle", "direction"]
    for col in continuous_cols:
        df[col] = pd.to_numeric(df[col], errors="coerce").fillna(0.0)

    type_ohe = one_hot(df["object_type"], OBJECT_TYPES)
    type_ohe.columns = [f"type_{c}" for c in type_ohe.columns]

    # ── Action OHE (a_t) ──────────────────────────────────────────────────────
    action_ohe = one_hot(df["action_type"], ACTION_INDEX_ORDER)
    action_ohe.columns = [f"a_{c}" for c in action_ohe.columns]

    # ── Internal state (h_t) — 8-dimensional ─────────────────────────────────
    def _fill(col: str, df: pd.DataFrame, default: float = 0.0) -> np.ndarray:
        if col in df.columns:
            return pd.to_numeric(df[col], errors="coerce").fillna(default).values.astype(np.float32)
        return np.full(len(df), default, dtype=np.float32)

    ht_hunger       = _fill("init_hunger",      df)
    ht_sleep        = _fill("init_sleep",        df)
    ht_pain         = _fill("init_pain",         df)
    ht_tedium       = _fill("init_tedium",       df)
    nm_dopamine     = _fill("dopamine",          df)
    nm_serotonin    = _fill("serotonin",         df)
    nm_orexin       = _fill("orexin",            df)
    end_cortisol    = _fill("cortisol_tonic",    df)

    h_raw = np.stack([
        ht_hunger, ht_sleep, ht_pain, ht_tedium,
        nm_dopamine, nm_serotonin, nm_orexin, end_cortisol,
    ], axis=1)  # (N, 8)

    # Compute per-feature mean/std on the TRAIN split only (rows where trial in TRAIN_TRIALS).
    # We'll apply normalisation after the train/val split, using train stats for both.
    h_df = pd.DataFrame(h_raw, columns=INTERNAL_STATE_FEATURE_ORDER)

    # ── Emotion target columns (final_* — used as placeholder; critic trains synthetically) ──
    emotion_df = pd.DataFrame()
    for e in EMOTION_INDEX_ORDER:
        src = f"init_{e}"
        emotion_df[f"final_{e}"] = _fill(src, df)

    # ── Assemble full dataset ─────────────────────────────────────────────────
    perception_feature_order = continuous_cols + [f"type_{t}" for t in OBJECT_TYPES]

    df_out = pd.concat([
        df[["creature_key", "trial", "time"]].reset_index(drop=True),
        df[continuous_cols].reset_index(drop=True),
        type_ohe.reset_index(drop=True),
        action_ohe.reset_index(drop=True),
        emotion_df.reset_index(drop=True),
        h_df.reset_index(drop=True),
    ], axis=1)

    # Drop rows missing critical perception features (NaN after all joins)
    before = len(df_out)
    df_out = df_out.dropna(subset=continuous_cols[:1])  # distance as sentinel
    print(f"  dropped {before - len(df_out)} rows with missing perception data")

    # Sort by creature+trial+time so s_next = s_t[1:] in the training loop
    # picks the true next perception of the same creature, not a cross-creature row.
    df_out = df_out.sort_values(["creature_key", "trial", "time"]).reset_index(drop=True)

    # ── Train / val split ─────────────────────────────────────────────────────
    present = sorted(df_out["trial"].unique())
    print(f"  trials present: {present}")
    df_train = df_out[df_out["trial"].isin(TRAIN_TRIALS)]
    df_val   = df_out[df_out["trial"].isin(VAL_TRIALS)]

    if df_val.empty:
        print("  Warning: no val-trial rows — falling back to 20% tail split.")
        cut = int(len(df_out) * 0.80)
        df_train = df_out.iloc[:cut]
        df_val   = df_out.iloc[cut:]

    # ── Normalisation stats (computed on train split only) ────────────────────
    means = df_train[continuous_cols].mean().values.tolist()
    stds  = df_train[continuous_cols].std().values.tolist()

    # h_t: normalise per-feature using train mean/std; clip serotonin outliers first
    h_means = df_train[INTERNAL_STATE_FEATURE_ORDER].mean().values
    h_stds  = df_train[INTERNAL_STATE_FEATURE_ORDER].std().values.clip(min=1e-6)

    def _normalise_h(df_split: pd.DataFrame) -> pd.DataFrame:
        df_split = df_split.copy()
        df_split[INTERNAL_STATE_FEATURE_ORDER] = (
            (df_split[INTERNAL_STATE_FEATURE_ORDER].values - h_means) / h_stds
        ).astype(np.float32)
        return df_split

    df_train = _normalise_h(df_train)
    df_val   = _normalise_h(df_val)

    drop_meta = ["creature_key", "trial", "time"]
    df_train.drop(columns=drop_meta, errors="ignore").to_parquet(
        out_dir / "train_dual.parquet", index=False)
    df_val.drop(columns=drop_meta, errors="ignore").to_parquet(
        out_dir / "val_dual.parquet", index=False)

    print(f"  train: {len(df_train):,} samples ({df_train['trial'].nunique()} trials)")
    print(f"  val:   {len(df_val):,} samples ({df_val['trial'].nunique()} trials)")

    # ── stats.json ────────────────────────────────────────────────────────────
    live_dims = list(LIVE_EMOTION_INDICES)
    stats = {
        "input_dim":                    len(perception_feature_order),
        "action_dim":                   len(ACTION_INDEX_ORDER),
        "emotion_dim":                  len(EMOTION_INDEX_ORDER),
        "latent_dim":                   64,
        "internal_latent_dim":          16,
        "live_emotion_dims":            live_dims,
        "perception_feature_order":     perception_feature_order,
        "action_index_order":           ACTION_INDEX_ORDER,
        "emotion_index_order":          EMOTION_INDEX_ORDER,
        "internal_state_feature_order": INTERNAL_STATE_FEATURE_ORDER,
        "internal_state_dim":           len(INTERNAL_STATE_FEATURE_ORDER),
        "feature_means":                means,
        "feature_stds":                 stds,
        "h_means":                      h_means.tolist(),
        "h_stds":                       h_stds.tolist(),
        "min_arousal":                  MIN_AROUSAL,
        "max_arousal":                  MAX_AROUSAL,
        "n_train":                      int(len(df_train)),
        "n_val":                        int(len(df_val)),
    }
    stats_path = out_dir / "stats.json"
    stats_path.write_text(json.dumps(stats, indent=2))
    print(f"  stats.json → {stats_path}")
    print("Done.")


if __name__ == "__main__":
    main()
