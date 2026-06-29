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
    """For each action find the first regulation event strictly after action_time.

    Returns the action row augmented with:
      - final_<dim>  : emotion levels AFTER regulation (training target / s_{t+1})
      - initial_<dim>: emotion levels at decision time (h_t proxy for dual encoder),
                       taken from the last regulation event AT OR BEFORE action_time.
                       Falls back to the next-event values when no prior event exists.
    """
    if emotions.empty or actions.empty:
        return pd.DataFrame()

    final_cols = [f"final_{e}" for e in EMOTION_INDEX_ORDER]

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
            for ec in final_cols:
                row[ec] = first[ec] if ec in first.index else np.nan

            # h_t: emotion state at decision time = final state of the last
            # regulation event that fired at or before the action.
            prevs = emo_group[emo_group["regulation_time"] <= t_act]
            if not prevs.empty:
                prev = prevs.iloc[-1]
            else:
                prev = first  # no prior event; reuse next as fallback
            for ec in final_cols:
                col_name = ec.replace("final_", "initial_")
                row[col_name] = prev[ec] if ec in prev.index else np.nan
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
            df[["creatureKey", "action_time"]].reset_index(drop=True),
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
        print("  Warning: only one creature — val split is a 20% time-based tail.")
        cut = int(len(df_out) * TRAIN_FRACTION)
        df_train = df_out.iloc[:cut]
        df_val   = df_out.iloc[cut:]

    print(f"  train: {len(df_train)} samples, val: {len(df_val)} samples")

    df_train.to_parquet(os.path.join(out, "train.parquet"), index=False)
    df_val  .to_parquet(os.path.join(out, "val.parquet"),   index=False)

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
            df_dual_train = df_dual[df_dual["creatureKey"].isin(train_keys)]
            df_dual_val   = df_dual[~df_dual["creatureKey"].isin(train_keys)]
            if df_dual_val.empty:
                cut = int(len(df_dual) * TRAIN_FRACTION)
                df_dual_train = df_dual.iloc[:cut]
                df_dual_val   = df_dual.iloc[cut:]

            df_dual_train.to_parquet(os.path.join(out, "train_dual.parquet"), index=False)
            df_dual_val  .to_parquet(os.path.join(out, "val_dual.parquet"),   index=False)

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
