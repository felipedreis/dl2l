"""
Task 2.1 — Trajectory tuple assembly.

Reads CSVs produced by the Java extractors (ChosenActionStateExtractor,
InternalDynamicStateExtractor, TrajectoryPerceptionExtractor) and assembles
(s_t, a_t, emotion_target) tuples suitable for JEPA training.

For dual-encoder training (--dual flag) also joins the creature's internal
homeostatic state (h_t = live emotion dims at action time) and writes a
separate train_dual.parquet / val_dual.parquet alongside the standard files.

Usage:
    python -m scripts.prepare_dataset --wd <extractor-output-dir> [--out ml/data] [--dual]
"""

import argparse
import glob
import json
import os
import re
import sys

import numpy as np
import pandas as pd

# ── Constants matching Java source ────────────────────────────────────────────
ACTION_INDEX_ORDER = ["APPROACH", "AVOID", "EAT", "ESCAPE", "PLAY",
                      "SLEEP", "TOUCH", "TURN", "WANDER"]

# All world object types that can appear in perceptions.
FRUIT_TYPES = ["GRAY_APPLE", "GREEN_APPLE", "RED_APPLE", "ROTTEN_APPLE"]
PLANT_TYPES = ["ALOE", "CACTUS"]
OBJECT_TYPES = FRUIT_TYPES + PLANT_TYPES

EMOTION_INDEX_ORDER = ["hunger", "sleep", "apathy", "stress", "pain",
                       "tedium", "fear", "curiosity", "fertility"]

# Live emotion dims used as internal state (h_t) for the dual encoder.
# Indices into EMOTION_INDEX_ORDER: hunger=0, sleep=1, pain=4, tedium=5.
LIVE_EMOTION_INDICES = [0, 1, 4, 5]
LIVE_EMOTION_NAMES   = [EMOTION_INDEX_ORDER[i] for i in LIVE_EMOTION_INDICES]

MIN_AROUSAL = 0.18
MAX_AROUSAL = 7.0

# Trial-based split: first 8 trials = train, last 2 = val.
# Trials are numbered from the directory name (trial_1 … trial_10).
TRAIN_TRIALS = set(range(1, 9))   # 1–8
VAL_TRIALS   = set(range(9, 11))  # 9–10

# Only keep AFFORDANCE and RANDOM selections; MEMORY is dead in current codebase.
VALID_SELECTION_TYPES = {"AFFORDANCE", "RANDOM"}

_TRIAL_RE = re.compile(r'trial_(\d+)')


def _trial_id(path: str) -> int:
    m = _TRIAL_RE.search(path)
    return int(m.group(1)) if m else -1


def load_glob(pattern: str) -> pd.DataFrame:
    """Load CSVs matching pattern, tagging each row with its trial_id from the path."""
    files = glob.glob(pattern, recursive=True)
    if not files:
        return pd.DataFrame()
    frames = []
    for f in files:
        df = pd.read_csv(f)
        df["trial_id"] = _trial_id(f)
        frames.append(df)
    return pd.concat(frames, ignore_index=True)


def find_target_perception(
    actions: pd.DataFrame,
    perceptions: pd.DataFrame,
) -> pd.DataFrame:
    """For each action, find the last perception of its target object at or before action_time.

    Self-targeted actions (target_key == creatureKey — e.g. WANDER, self-directed SLEEP)
    are kept with zeroed perception features to represent an undefined/no-object perception.
    External actions with no prior perception are dropped.
    """
    if perceptions.empty or actions.empty:
        return actions.assign(distance=np.nan, angle=np.nan, direction=np.nan, object_type=np.nan)

    # Rename perceptions so merge_asof can join on (creatureKey, target_key, action_time).
    # merge_asof requires the on-key to be globally sorted — sort by action_time alone;
    # within-group order is preserved automatically for by-groups.
    perc = (
        perceptions
        .rename(columns={"object_key": "target_key", "time": "action_time"})
        [["trial_id", "creatureKey", "target_key", "action_time",
          "distance", "angle", "direction", "object_type"]]
        .sort_values("action_time")
    )
    acts = actions.sort_values("action_time")

    joined = pd.merge_asof(
        acts, perc,
        on="action_time",
        by=["trial_id", "creatureKey", "target_key"],
        direction="backward",
    )

    # Self-targeted rows with NaN → zeroed context (not dropped).
    self_mask = joined["target_key"] == joined["creatureKey"]
    for col in ("distance", "angle", "direction"):
        joined.loc[self_mask & joined[col].isna(), col] = 0.0
    joined.loc[self_mask & joined["object_type"].isna(), "object_type"] = None

    # External rows with no perception match → drop.
    external_no_perc = (~self_mask) & joined["distance"].isna()
    return joined[~external_no_perc].reset_index(drop=True)


def find_next_emotion(
    actions: pd.DataFrame,
    emotions: pd.DataFrame,
) -> pd.DataFrame:
    """For each action find the first regulation event strictly after action_time,
    and the last regulation event at or before action_time (for h_t / initial state).

    Uses merge_asof for O(n log n) instead of O(n²) per-row iteration.
    """
    if emotions.empty or actions.empty:
        return pd.DataFrame()

    final_cols   = [f"final_{e}" for e in EMOTION_INDEX_ORDER]
    initial_cols = [f"initial_{e}" for e in EMOTION_INDEX_ORDER]

    # Sort by on-key only (merge_asof requires global monotonic sort on the on column).
    emo = (
        emotions
        .rename(columns={"regulation_time": "action_time"})
        [["trial_id", "creatureKey", "action_time"] + final_cols]
        .sort_values("action_time")
    )
    acts = actions.sort_values("action_time")

    # Next emotion: first regulation strictly AFTER action_time (within same trial).
    # merge_asof forward uses >=; shift by +1 ms to get strictly >.
    acts_shifted = acts.copy()
    acts_shifted["action_time"] = acts_shifted["action_time"] + 1
    next_emo = pd.merge_asof(
        acts_shifted,
        emo.rename(columns={c: c + "_next" for c in final_cols}),
        on="action_time",
        by=["trial_id", "creatureKey"],
        direction="forward",
    )
    next_emo["action_time"] = next_emo["action_time"] - 1  # restore

    first_final = f"final_{EMOTION_INDEX_ORDER[0]}_next"
    next_emo = next_emo.dropna(subset=[first_final])

    # Previous emotion: last regulation AT OR BEFORE action_time (for h_t).
    prev_emo = pd.merge_asof(
        next_emo[["trial_id", "creatureKey", "action_time"]].sort_values("action_time"),
        emo.rename(columns={c: c + "_prev" for c in final_cols}),
        on="action_time",
        by=["trial_id", "creatureKey"],
        direction="backward",
    )

    result = next_emo.copy()
    for e in EMOTION_INDEX_ORDER:
        result[f"final_{e}"]   = next_emo[f"final_{e}_next"].values
        prev_col = prev_emo[f"final_{e}_prev"].values
        next_col = next_emo[f"final_{e}_next"].values
        result[f"initial_{e}"] = np.where(np.isnan(prev_col), next_col, prev_col)

    result = result.drop(columns=[c for c in result.columns if c.endswith(("_next", "_prev"))],
                         errors="ignore")
    return result.reset_index(drop=True)


def one_hot(series: pd.Series, categories: list[str]) -> pd.DataFrame:
    return pd.get_dummies(series, prefix="", prefix_sep="").reindex(
        columns=categories, fill_value=0
    ).astype(np.float32)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wd",   required=True, help="Extractor output directory")
    parser.add_argument("--out",  default="ml/data", help="Output directory for parquet/stats")
    parser.add_argument("--dual", action="store_true",
                        help="Also write dual-encoder parquet files (train_dual / val_dual) "
                             "with h_t internal-state columns")
    args = parser.parse_args()

    wd  = args.wd
    out = args.out
    os.makedirs(out, exist_ok=True)

    print("Loading CSVs …")
    actions     = load_glob(os.path.join(wd, "**", "*trajectory_actions.csv"))
    emotions    = load_glob(os.path.join(wd, "**", "*trajectory_emotions.csv"))
    perceptions = load_glob(os.path.join(wd, "**", "*trajectory_perceptions.csv"))

    if actions.empty:
        sys.exit("No trajectory_actions.csv found — run the Java extractor first.")
    if emotions.empty:
        sys.exit("No trajectory_emotions.csv found — run the Java extractor first.")
    if perceptions.empty:
        sys.exit("No trajectory_perceptions.csv found — run the Java extractor first.")

    print(f"  actions: {len(actions)} rows, creatures: {actions['creatureKey'].nunique()}")
    print(f"  emotions: {len(emotions)} rows")
    print(f"  perceptions: {len(perceptions)} rows")

    actions = actions[actions["selection_type"].isin(VALID_SELECTION_TYPES)].copy()
    print(f"  actions after selection-type filter: {len(actions)}")

    print("Joining target perception …")
    df = find_target_perception(actions, perceptions)
    print(f"  tuples with target perception: {len(df)}")
    if df.empty:
        sys.exit("No tuples after target-perception join.")

    print("Joining next emotion …")
    df = find_next_emotion(df, emotions)
    print(f"  tuples with next emotion: {len(df)}")
    if df.empty:
        sys.exit("No tuples after emotion join.")

    # ── Feature engineering ────────────────────────────────────────────────
    continuous_cols = ["distance", "angle", "direction"]
    type_ohe = one_hot(df["object_type"], OBJECT_TYPES)
    type_ohe.columns = [f"type_{c}" for c in type_ohe.columns]

    action_ohe = one_hot(df["action_type"], ACTION_INDEX_ORDER)
    action_ohe.columns = [f"a_{c}" for c in action_ohe.columns]

    emotion_cols = [f"final_{e}" for e in EMOTION_INDEX_ORDER]
    feature_order = continuous_cols + [f"type_{t}" for t in OBJECT_TYPES]

    df_out = pd.concat(
        [
            df[["trial_id", "creatureKey", "action_time"]].reset_index(drop=True),
            df[continuous_cols].reset_index(drop=True),
            type_ohe.reset_index(drop=True),
            action_ohe.reset_index(drop=True),
            df[emotion_cols].reset_index(drop=True),
        ],
        axis=1,
    )

    # ── Normalisation stats ────────────────────────────────────────────────
    means = df_out[continuous_cols].mean().values.tolist()
    stds  = df_out[continuous_cols].std().values.tolist()

    # Use the predefined live dims rather than auto-detecting from variance.
    # Auto-detection fails when some drives (e.g. pain, tedium) have zero variance
    # in a particular training dataset even though the model architecture expects them.
    live_dims = list(LIVE_EMOTION_INDICES)
    print(f"  live emotion dims: {live_dims} "
          f"({[EMOTION_INDEX_ORDER[i] for i in live_dims]})")

    # ── Train / val split by trial (trials 1–8 = train, 9–10 = val) ──────
    present_trials = sorted(df_out["trial_id"].unique())
    print(f"  trials present: {present_trials}")
    df_train = df_out[df_out["trial_id"].isin(TRAIN_TRIALS)]
    df_val   = df_out[df_out["trial_id"].isin(VAL_TRIALS)]

    if df_val.empty:
        print("  Warning: no val-trial data found — falling back to 20% time-based tail.")
        cut = int(len(df_out) * 0.80)
        df_train = df_out.iloc[:cut]
        df_val   = df_out.iloc[cut:]

    print(f"  train: {len(df_train)} samples ({df_train['trial_id'].nunique()} trials)")
    print(f"  val:   {len(df_val)} samples ({df_val['trial_id'].nunique()} trials)")

    drop_cols = ["trial_id"] if "trial_id" in df_train.columns else []
    df_train.drop(columns=drop_cols).to_parquet(os.path.join(out, "train.parquet"), index=False)
    df_val  .drop(columns=drop_cols).to_parquet(os.path.join(out, "val.parquet"),   index=False)

    stats = {
        "input_dim":                len(feature_order),
        "action_dim":               len(ACTION_INDEX_ORDER),
        "emotion_dim":              len(EMOTION_INDEX_ORDER),
        "latent_dim":               64,
        "live_emotion_dims":        live_dims,
        "perception_feature_order": feature_order,
        "action_index_order":       ACTION_INDEX_ORDER,
        "emotion_index_order":      EMOTION_INDEX_ORDER,
        "feature_means":            means,
        "feature_stds":             stds,
        "min_arousal":              MIN_AROUSAL,
        "max_arousal":              MAX_AROUSAL,
        "n_train":                  int(len(df_train)),
        "n_val":                    int(len(df_val)),
    }

    # ── Dual-encoder parquet (optional) ───────────────────────────────────
    if args.dual:
        internal_cols_available = [f"initial_{e}" for e in LIVE_EMOTION_NAMES
                                   if f"initial_{e}" in df.columns]
        if not internal_cols_available:
            print("  WARNING: --dual requested but no initial_* emotion columns found in "
                  "trajectory_emotions.csv. Dual parquet will NOT be written.")
        else:
            # Align index with df_out (df was filtered/joined, so reset index)
            df_internal = df[internal_cols_available].reset_index(drop=True)
            # Rename to ht_<name> for clarity
            rename_map = {f"initial_{e}": f"ht_{e}" for e in LIVE_EMOTION_NAMES
                          if f"initial_{e}" in internal_cols_available}
            df_internal = df_internal.rename(columns=rename_map)

            df_dual = pd.concat([df_out.reset_index(drop=True), df_internal], axis=1)
            df_dual_train = df_dual[df_dual["trial_id"].isin(TRAIN_TRIALS)]
            df_dual_val   = df_dual[df_dual["trial_id"].isin(VAL_TRIALS)]
            if df_dual_val.empty:
                cut = int(len(df_dual) * 0.80)
                df_dual_train = df_dual.iloc[:cut]
                df_dual_val   = df_dual.iloc[cut:]

            drop_cols_dual = ["trial_id"] if "trial_id" in df_dual_train.columns else []
            df_dual_train.drop(columns=drop_cols_dual).to_parquet(
                os.path.join(out, "train_dual.parquet"), index=False)
            df_dual_val.drop(columns=drop_cols_dual).to_parquet(
                os.path.join(out, "val_dual.parquet"),   index=False)

            ht_cols_written = list(rename_map.values())
            print(f"  dual parquet written: h_t columns = {ht_cols_written}")

            stats["internal_state_feature_order"] = ht_cols_written
            stats["internal_state_dim"]           = len(ht_cols_written)
            stats["internal_latent_dim"]           = 16

    stats_path = os.path.join(out, "stats.json")
    with open(stats_path, "w") as f:
        json.dump(stats, f, indent=2)
    print(f"  Stats saved to {stats_path}")
    print("Done.")


if __name__ == "__main__":
    main()
