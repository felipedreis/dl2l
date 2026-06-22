"""
Task 2.1 — Trajectory tuple assembly.

Reads CSVs produced by the Java extractors (ChosenActionStateExtractor,
InternalDynamicStateExtractor, TrajectoryPerceptionExtractor) and assembles
(s_t, a_t, emotion_target) tuples suitable for JEPA training.

Usage:
    python -m scripts.prepare_dataset --wd <extractor-output-dir> [--out ml/data]
"""

import argparse
import glob
import json
import os
import sys

import numpy as np
import pandas as pd

# ── Constants matching Java source ────────────────────────────────────────────
ACTION_INDEX_ORDER = ["APPROACH", "AVOID", "EAT", "ESCAPE", "PLAY",
                      "SLEEP", "TOUCH", "TURN", "WANDER"]
OBJECT_TYPES       = ["GRAY_APPLE", "GREEN_APPLE", "RED_APPLE"]
EMOTION_INDEX_ORDER = ["hunger", "sleep", "apathy", "stress", "pain",
                        "tedium", "fear", "curiosity", "fertility"]

MIN_AROUSAL = 0.18
MAX_AROUSAL = 7.0

LIVE_VARIANCE_THRESHOLD = 1e-6
TRAIN_FRACTION          = 0.80
# Only keep AFFORDANCE and RANDOM selections; MEMORY is dead in current codebase.
VALID_SELECTION_TYPES   = {"AFFORDANCE", "RANDOM"}


def load_glob(pattern: str) -> pd.DataFrame:
    files = glob.glob(pattern, recursive=True)
    if not files:
        return pd.DataFrame()
    return pd.concat([pd.read_csv(f) for f in files], ignore_index=True)


def find_target_perception(
    actions: pd.DataFrame,
    perceptions: pd.DataFrame,
) -> pd.DataFrame:
    """For each action, find the last perception of its target object at or before action_time."""
    if perceptions.empty or actions.empty:
        return actions.assign(distance=np.nan, angle=np.nan, direction=np.nan, object_type=np.nan)

    result_rows = []
    for ck, act_group in actions.groupby("creatureKey"):
        perc_group = perceptions[perceptions["creatureKey"] == ck].sort_values("time")
        for _, act in act_group.iterrows():
            t_act  = act["action_time"]
            tk     = act["target_key"]
            # Candidate: same object_key, time <= action_time
            cands = perc_group[
                (perc_group["object_key"] == tk) & (perc_group["time"] <= t_act)
            ]
            if cands.empty:
                continue
            last = cands.iloc[-1]
            result_rows.append({
                **act.to_dict(),
                "distance":    last["distance"],
                "angle":       last["angle"],
                "direction":   last["direction"],
                "object_type": last["object_type"],
            })

    if not result_rows:
        return pd.DataFrame()
    return pd.DataFrame(result_rows)


def find_next_emotion(
    actions: pd.DataFrame,
    emotions: pd.DataFrame,
) -> pd.DataFrame:
    """For each action, find the first regulation event strictly after action_time."""
    if emotions.empty or actions.empty:
        return pd.DataFrame()

    emotion_cols = [f"final_{e}" for e in EMOTION_INDEX_ORDER]
    result_rows = []
    for ck, act_group in actions.groupby("creatureKey"):
        emo_group = emotions[emotions["creatureKey"] == ck].sort_values("regulation_time")
        for _, act in act_group.iterrows():
            t_act = act["action_time"]
            nexts = emo_group[emo_group["regulation_time"] > t_act]
            if nexts.empty:
                continue
            first = nexts.iloc[0]
            row = {**act.to_dict()}
            for ec in emotion_cols:
                row[ec] = first[ec]
            result_rows.append(row)

    if not result_rows:
        return pd.DataFrame()
    return pd.DataFrame(result_rows)


def one_hot(series: pd.Series, categories: list[str]) -> pd.DataFrame:
    return pd.get_dummies(series, prefix="", prefix_sep="").reindex(
        columns=categories, fill_value=0
    ).astype(np.float32)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wd",  required=True, help="Extractor output directory")
    parser.add_argument("--out", default="ml/data", help="Output directory for parquet/stats")
    args = parser.parse_args()

    wd  = args.wd
    out = args.out
    os.makedirs(out, exist_ok=True)

    print("Loading CSVs …")
    actions    = load_glob(os.path.join(wd, "**", "*trajectory_actions.csv"))
    emotions   = load_glob(os.path.join(wd, "**", "*trajectory_emotions.csv"))
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

    # Filter to valid selection types
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
    # s_t: continuous dims
    continuous_cols = ["distance", "angle", "direction"]
    # s_t: one-hot object type
    type_ohe = one_hot(df["object_type"], OBJECT_TYPES)
    type_ohe.columns = [f"type_{c}" for c in type_ohe.columns]

    # a_t: one-hot action type
    action_ohe = one_hot(df["action_type"], ACTION_INDEX_ORDER)
    action_ohe.columns = [f"a_{c}" for c in action_ohe.columns]

    # emotion target columns
    emotion_cols = [f"final_{e}" for e in EMOTION_INDEX_ORDER]

    # Build flat dataframe
    feature_order = continuous_cols + [f"type_{t}" for t in OBJECT_TYPES]
    df_out = pd.concat(
        [
            df[["creatureKey", "action_time"]].reset_index(drop=True),
            df[continuous_cols].reset_index(drop=True),
            type_ohe.reset_index(drop=True),
            action_ohe.reset_index(drop=True),
            df[emotion_cols].reset_index(drop=True),
        ],
        axis=1,
    )

    # ── Normalisation stats (computed on full dataset, applied to train) ───
    means = df_out[continuous_cols].mean().values.tolist()
    stds  = df_out[continuous_cols].std().values.tolist()

    # ── Detect live emotion dims ───────────────────────────────────────────
    var_per_dim = df_out[emotion_cols].var()
    live_dims   = [i for i, ec in enumerate(emotion_cols)
                   if var_per_dim[ec] > LIVE_VARIANCE_THRESHOLD]
    print(f"  live emotion dims: {live_dims} "
          f"({[EMOTION_INDEX_ORDER[i] for i in live_dims]})")

    # ── Train / val split (stratified by creatureKey) ─────────────────────
    creature_keys = sorted(df_out["creatureKey"].unique())
    n_train = max(1, int(len(creature_keys) * TRAIN_FRACTION))
    rng = np.random.default_rng(42)
    rng.shuffle(creature_keys)
    train_keys = set(creature_keys[:n_train])

    df_train = df_out[df_out["creatureKey"].isin(train_keys)]
    df_val   = df_out[~df_out["creatureKey"].isin(train_keys)]

    if df_val.empty:
        # With a single creature, duplicate to allow a val split of same creature
        print("  Warning: only one creature — val split is a 20% time-based tail.")
        cut = int(len(df_out) * TRAIN_FRACTION)
        df_train = df_out.iloc[:cut]
        df_val   = df_out.iloc[cut:]

    print(f"  train: {len(df_train)} samples, val: {len(df_val)} samples")

    df_train.to_parquet(os.path.join(out, "train.parquet"), index=False)
    df_val  .to_parquet(os.path.join(out, "val.parquet"),   index=False)

    stats = {
        "input_dim":               len(feature_order),
        "action_dim":              len(ACTION_INDEX_ORDER),
        "emotion_dim":             len(EMOTION_INDEX_ORDER),
        "latent_dim":              64,
        "live_emotion_dims":       live_dims,
        "perception_feature_order": feature_order,
        "action_index_order":      ACTION_INDEX_ORDER,
        "emotion_index_order":     EMOTION_INDEX_ORDER,
        "feature_means":           means,
        "feature_stds":            stds,
        "min_arousal":             MIN_AROUSAL,
        "max_arousal":             MAX_AROUSAL,
        "n_train":                 int(len(df_train)),
        "n_val":                   int(len(df_val)),
    }
    stats_path = os.path.join(out, "stats.json")
    with open(stats_path, "w") as f:
        json.dump(stats, f, indent=2)
    print(f"  Stats saved to {stats_path}")
    print("Done.")


if __name__ == "__main__":
    main()
