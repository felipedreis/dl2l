"""Parquet loading and enrichment helpers shared across experiment analyses.

Extracted verbatim (generalized off ExperimentAnalysis instead of hardcoded
globals) from the duplicated blocks in:
  - analysis/exp_rotten_fruit_v1.py            (load_all L81-91, num L94-95,
    tick-rank/born_time block L110-190)
  - analysis/exp_20260709_memory_vs_wm_v1.py   (load_all L83-95, num L98-99,
    tick-rank/born_time block L114-179, attach_tick_rank L463-486)
"""

from __future__ import annotations

import pandas as pd


def num(s):
    """pd.to_numeric with coercion — used everywhere a parquet column needs
    to be forced numeric (object dtype survives the parquet round-trip for
    some columns)."""
    return pd.to_numeric(s, errors="coerce")


def load_all(cfg, fname: str) -> pd.DataFrame:
    """Load one parquet file across every condition x trial of an experiment,
    concatenating into a single DataFrame tagged with condition/trial columns.

    Mirrors both scripts' `load_all(fname)` — generalized so per-condition
    data directories (cfg.data_dir_for) work for experiments like
    20260709_memory_vs_wm_v1 that pull different conditions from different
    ml/data_*/ roots.
    """
    frames = []
    for cond in cfg.cond_keys:
        base = cfg.data_dir_for(cond)
        for trial in cfg.trial_range:
            path = base / cond / f"trial_{trial}" / fname
            if path.exists():
                df = pd.read_parquet(path)
                df["condition"] = cond
                df["trial"] = trial
                frames.append(df)
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def attach_born_time_and_ticks(creatures: pd.DataFrame, actions: pd.DataFrame) -> pd.DataFrame:
    """Attach tick_count (decision cycles) and WORLD_MODEL-inference-corrected
    lifetime to the creatures frame.

    Mirrors the "Tick counts and inference correction" block duplicated in
    both scripts (rotten_fruit L150-165, memory_vs_wm L150-172).
    """
    creatures = creatures.copy()
    creatures["lifetime_s"] = num(creatures["lifetime_s"])
    creatures["born_time"] = num(creatures["born_time"])

    tick_counts = (
        actions.groupby(["creature_key", "condition", "trial"])
        .size().reset_index(name="tick_count")
    )
    creatures = creatures.merge(tick_counts, on=["creature_key", "condition", "trial"], how="left")
    creatures["tick_count"] = creatures["tick_count"].fillna(0).astype(int)

    if "selection_type" in actions.columns and "inference_ms" in actions.columns:
        wm_overhead = (
            actions[actions["selection_type"] == "WORLD_MODEL"]
            .groupby(["creature_key", "condition", "trial"])["inference_ms"]
            .sum().reset_index(name="total_inference_ms")
        )
        creatures = creatures.merge(wm_overhead, on=["creature_key", "condition", "trial"], how="left")
        creatures["total_inference_ms"] = creatures["total_inference_ms"].fillna(0.0)
        creatures["lifetime_corrected_s"] = (
            creatures["lifetime_s"] - creatures["total_inference_ms"] / 1000.0
        )
    return creatures


def attach_elapsed_s(df: pd.DataFrame, creatures: pd.DataFrame, time_col: str = "time") -> pd.DataFrame:
    """Merge in born_time and compute elapsed_s = (time_col - born_time) / 1000.

    Mirrors the born_lookup/merge/elapsed_s pattern duplicated for drives and
    behav in both scripts (rotten_fruit L117-119, memory_vs_wm L124-130).
    """
    df = df.copy()
    df[time_col] = num(df[time_col])
    born_lookup = creatures[["creature_key", "condition", "trial", "born_time"]].drop_duplicates()
    df = df.merge(born_lookup, on=["creature_key", "condition", "trial"], how="left")
    df["elapsed_s"] = (df[time_col] - num(df["born_time"])) / 1000.0
    return df


def make_tick_rank_attacher(actions: pd.DataFrame):
    """Build an attach_tick_rank(df) closure bound to a given actions frame.

    Mirrors attach_tick_rank in both scripts (rotten_fruit L171-189,
    memory_vs_wm L463-486): nearest-time-joins tick_rank/life_decile from
    actions onto any other timed event table (mouth, perceptions, ...).
    """
    actions_ranked = actions[["creature_key", "condition", "trial", "time", "action_type"]].copy()
    actions_ranked["time"] = num(actions_ranked["time"])
    actions_ranked["tick_rank"] = actions_ranked.groupby(
        ["creature_key", "condition", "trial"]).cumcount()
    actions_for_asof = actions_ranked.sort_values("time").reset_index(drop=True)

    def attach_tick_rank(df: pd.DataFrame, creatures: pd.DataFrame) -> pd.DataFrame:
        born_lookup2 = creatures[
            ["creature_key", "condition", "trial", "born_time", "tick_count"]
        ].drop_duplicates()
        df = df.copy()
        df["time"] = num(df["time"])
        df = df.merge(born_lookup2, on=["creature_key", "condition", "trial"], how="left")
        df_s = df.sort_values("time").reset_index(drop=True)
        df_s = pd.merge_asof(
            df_s,
            actions_for_asof[["creature_key", "condition", "trial", "time", "tick_rank"]],
            on="time", by=["creature_key", "condition", "trial"], direction="nearest",
        )
        df_s["life_frac"] = df_s["tick_rank"] / df_s["tick_count"].clip(lower=1)
        df_s["life_decile"] = (df_s["life_frac"] * 10).clip(upper=9).astype(int)
        return df_s

    return attach_tick_rank, actions_for_asof
