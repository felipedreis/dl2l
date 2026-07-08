#!/usr/bin/env python3
"""
p59 v2 validation: orexin / cortisol / endocrine circadian analysis.

Figures produced:
  1. Neuroendocrine mean cycle (4 panels, phase-averaged)
  2. SLEEP share vs. orexin tonic over cognitive time
  3. Action distribution per filter (stacked bar by actionselectiontype + action heatmap)
  4. HPA time series — cortisol tonic over circadian cycles (per-creature + aggregate)

Queries the running 'db' Docker container via psql — no psycopg2 needed.
Run from the dl2l project root:
    python3 analysis/p59/circadian_neuroendocrine.py
"""

import io
import os
import subprocess
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
import pandas as pd

import glob

# ── Config ────────────────────────────────────────────────────────────────────
CONTAINER               = "db"
DB_USER                 = "postgres"
DB_NAME                 = "l2l"
OREXIN_GATE             = 15.0
CORTISOL_STRESS_THRESHOLD = 3.0
PHASE_BINS              = 60      # bins across [0, 2π] for Fig 1
TICK_BIN_SIZE           = 200     # ticks per window (= one circadian period)
OUT_DIR                 = os.path.dirname(os.path.abspath(__file__))
DATA_DIR                = os.path.join(os.path.dirname(os.path.dirname(OUT_DIR)), "ml", "data_p59")

# ── DB helpers ────────────────────────────────────────────────────────────────

def psql(sql: str) -> pd.DataFrame:
    copy_sql = f"COPY ({sql}) TO STDOUT WITH CSV HEADER;\n"
    r = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME],
        input=copy_sql, capture_output=True, text=True,
    )
    if r.returncode != 0:
        print(f"psql error: {r.stderr.strip()}", file=sys.stderr)
        return pd.DataFrame()
    return pd.read_csv(io.StringIO(r.stdout))


def load_neuromodulator() -> pd.DataFrame:
    print("  loading neuromodulator_state_log …", file=sys.stderr)
    df = psql("""
        SELECT creature_key, seq, circadian_phase, dopamine, serotonin, orexin
        FROM   data.neuromodulator_state_log
        ORDER  BY creature_key, seq
    """)
    print(f"  {len(df):,} rows", file=sys.stderr)
    return df


def load_endocrine() -> pd.DataFrame:
    print("  loading endocrine_state_log …", file=sys.stderr)
    df = psql("""
        SELECT creature_key, seq, cortisol_tonic, stress_level
        FROM   data.endocrine_state_log
        ORDER  BY creature_key, seq
    """)
    print(f"  {len(df):,} rows", file=sys.stderr)
    return df


def load_actions() -> pd.DataFrame:
    """Load actions from extracted trajectory_actions.csv (all trial subdirs) or DB."""
    trial_dirs = sorted(glob.glob(os.path.join(DATA_DIR, "trial_*")))
    if trial_dirs:
        print(f"  loading trajectory_actions.csv from {len(trial_dirs)} trial(s) …", file=sys.stderr)
        parts = []
        for tdir in trial_dirs:
            trial_num = int(os.path.basename(tdir).split("_")[1])
            for cdir in sorted(glob.glob(os.path.join(tdir, "*:*"))):
                fpath = os.path.join(cdir, "trajectory_actions.csv")
                if os.path.exists(fpath):
                    df = pd.read_csv(fpath)
                    df["trial"] = trial_num
                    creature_key = int(os.path.basename(cdir).split(":")[0])
                    df["creature_key"] = creature_key
                    parts.append(df)
        if not parts:
            print("  no trajectory_actions.csv found, falling back to DB …", file=sys.stderr)
            return _load_actions_from_db()
        df = pd.concat(parts, ignore_index=True)
        # Rename columns to match DB query format.
        if "action_type" in df.columns and "action" not in df.columns:
            df = df.rename(columns={"action_type": "action", "selection_type": "filter_type"})
        # Assign a synthetic seq (row number per creature per trial for tick-based plots).
        df = df.sort_values(["trial", "creature_key", "action_time"]).reset_index(drop=True)
        df["seq"] = df.groupby(["trial", "creature_key"]).cumcount()
        print(f"  {len(df):,} rows across {df.get('trial', pd.Series([1])).nunique()} trial(s)", file=sys.stderr)
        return df
    return _load_actions_from_db()


def _load_actions_from_db() -> pd.DataFrame:
    print("  loading chosen_action_state from DB …", file=sys.stderr)
    df = psql("""
        SELECT css.key          AS creature_key,
               css.time         AS wall_ms,
               cas.action,
               cas.actionselectiontype AS filter_type
        FROM   data.chosen_action_state cas
        JOIN   data.change_stimulus_state css ON cas.changestimulusstate_id = css.id
        ORDER  BY css.key, css.time
    """)
    nm_seq_range = psql("""
        SELECT creature_key, MIN(seq) AS seq_min, MAX(seq) AS seq_max
        FROM   data.neuromodulator_state_log
        GROUP  BY creature_key
    """).set_index("creature_key")
    rows = []
    for ck, grp in df.groupby("creature_key"):
        if ck not in nm_seq_range.index:
            continue
        t_min = grp["wall_ms"].min()
        t_max = grp["wall_ms"].max()
        seq_max = int(nm_seq_range.loc[ck, "seq_max"])
        elapsed = grp["wall_ms"] - t_min
        duration = max(t_max - t_min, 1)
        grp = grp.copy()
        grp["seq"] = (elapsed / duration * seq_max).round().astype(int)
        rows.append(grp)
    df = pd.concat(rows, ignore_index=True) if rows else pd.DataFrame()
    print(f"  {len(df):,} rows", file=sys.stderr)
    return df


# ── Figure 1 — Mean neuroendocrine cycle ──────────────────────────────────────

def figure1(nm: pd.DataFrame, endo: pd.DataFrame, out_path: str):
    """Mean neuroendocrine cycle binned by circadian phase."""

    nm = nm.copy()
    nm["phase_bin"] = pd.cut(
        nm["circadian_phase"], bins=PHASE_BINS,
        labels=False, include_lowest=True,
    )
    bin_edges = np.linspace(0, 2 * np.pi, PHASE_BINS + 1)
    bin_centers = (bin_edges[:-1] + bin_edges[1:]) / 2

    nm_avg = nm.groupby("phase_bin", observed=True)[
        ["circadian_phase", "dopamine", "serotonin", "orexin"]
    ].mean()

    nm_phase  = nm[["creature_key", "seq", "circadian_phase"]]
    creatures = endo["creature_key"].unique()
    parts = []
    for ck in creatures:
        e = endo[endo["creature_key"] == ck].sort_values("seq").reset_index(drop=True)
        n = nm_phase[nm_phase["creature_key"] == ck].sort_values("seq").reset_index(drop=True)
        if e.empty or n.empty:
            continue
        parts.append(pd.merge_asof(e, n, on="seq", direction="nearest",
                                   suffixes=("", "_nm")))
    if not parts:
        print("  figure1: no data to plot", file=sys.stderr)
        return
    endo_ph = pd.concat(parts, ignore_index=True)
    endo_ph["phase_bin"] = pd.cut(
        endo_ph["circadian_phase"], bins=PHASE_BINS,
        labels=False, include_lowest=True,
    )
    endo_avg = endo_ph.groupby("phase_bin", observed=True)[
        ["cortisol_tonic", "stress_level"]
    ].mean()

    fig, axes = plt.subplots(4, 1, figsize=(11, 10), sharex=True)
    fig.suptitle(
        "Neuroendocrine Mean Cycle — averaged across all creatures & circadian cycles\n"
        "(x-axis = circadian phase, 0 → 2π represents one full day)",
        fontsize=11,
    )

    x_nm   = bin_centers[nm_avg.index.astype(int)]
    x_endo = bin_centers[endo_avg.index.astype(int)]

    axes[0].plot(x_nm, nm_avg["circadian_phase"].values,
                 color="#9b59b6", linewidth=1.8)
    axes[0].set_ylabel("Phase (rad)")
    axes[0].set_title("(a) Circadian oscillator phase")
    axes[0].grid(True, alpha=0.25)

    ax2r = axes[1].twinx()
    l1, = axes[1].plot(x_nm, nm_avg["dopamine"].values,
                       label="Dopamine",  color="#e74c3c", linewidth=1.5)
    l2, = axes[1].plot(x_nm, nm_avg["serotonin"].values,
                       label="Serotonin", color="#27ae60", linewidth=1.5)
    l3, = ax2r.plot(x_nm, nm_avg["orexin"].values,
                    label="Orexin",    color="#e67e22", linewidth=1.8)
    l4  = ax2r.axhline(OREXIN_GATE, color="#e67e22", linestyle="--", alpha=0.5,
                       label=f"Orexin gate ({OREXIN_GATE})")
    axes[1].set_ylabel("DA / 5-HT tonic", color="#333333")
    ax2r.set_ylabel("Orexin tonic", color="#e67e22")
    ax2r.tick_params(axis="y", labelcolor="#e67e22")
    axes[1].set_title("(b) Neuromodulators: DA, 5-HT (left) | Orexin (right)")
    axes[1].legend(handles=[l1, l2, l3, l4], fontsize=8, loc="upper left")
    axes[1].grid(True, alpha=0.25)

    axes[2].plot(x_endo, endo_avg["cortisol_tonic"].values,
                 color="#8e44ad", linewidth=1.8)
    axes[2].axhline(CORTISOL_STRESS_THRESHOLD, color="#c0392b", linestyle="--",
                    alpha=0.7, label=f"Stress threshold ({CORTISOL_STRESS_THRESHOLD})")
    axes[2].set_ylabel("Cortisol tonic")
    axes[2].set_title("(c) HPA axis: cortisol tonic (v2 — negative feedback)")
    axes[2].legend(fontsize=8)
    axes[2].grid(True, alpha=0.25)

    axes[3].plot(x_endo, endo_avg["stress_level"].values,
                 color="#c0392b", linewidth=1.8)
    axes[3].set_ylabel("Stress level")
    axes[3].set_title("(d) STRESS affect")
    axes[3].set_xlabel("Circadian phase (rad)")
    axes[3].xaxis.set_major_formatter(
        ticker.FuncFormatter(lambda v, _: f"{v/np.pi:.1f}π")
    )
    axes[3].grid(True, alpha=0.25)

    plt.tight_layout()
    plt.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"Figure 1 → {out_path}")


# ── Figure 2 — SLEEP share vs. orexin tonic ───────────────────────────────────

def figure2(nm: pd.DataFrame, actions: pd.DataFrame, out_path: str):
    """SLEEP share vs. orexinTonic over cognitive time (seq-binned)."""

    nm = nm.copy()
    nm["bin"] = (nm["seq"] // TICK_BIN_SIZE) * TICK_BIN_SIZE
    nm_bin = nm.groupby(["creature_key", "bin"], observed=True)["orexin"].mean().reset_index()
    orexin_ts = nm_bin.groupby("bin")["orexin"].mean()

    actions = actions.copy()
    actions["bin"] = (actions["seq"] // TICK_BIN_SIZE) * TICK_BIN_SIZE
    grp = actions.groupby("bin")
    sleep_share = grp.apply(
        lambda g: (g["action"] == "SLEEP").sum() / max(len(g), 1),
        include_groups=False,
    )
    action_count = grp.size()

    common = orexin_ts.index.intersection(sleep_share.index)
    x          = common.values
    orexin_y   = orexin_ts.loc[common].values
    sleep_y    = sleep_share.loc[common].values

    fig, ax1 = plt.subplots(figsize=(13, 5))
    ax1.bar(x, sleep_y, width=TICK_BIN_SIZE * 0.9, color="#3498db", alpha=0.55,
            label="SLEEP share")
    ax1.set_xlabel(f"Cognitive tick (binned, window = {TICK_BIN_SIZE} ticks = 1 circadian period)")
    ax1.set_ylabel("SLEEP action share", color="#2980b9")
    ax1.tick_params(axis="y", labelcolor="#2980b9")
    ax1.set_ylim(0, max(sleep_y.max() * 2.5 if sleep_y.max() > 0 else 0.02, 0.02))

    ax2 = ax1.twinx()
    ax2.plot(x, orexin_y, color="#e67e22", linewidth=2.0, label="Mean orexin tonic")
    ax2.axhline(OREXIN_GATE, color="#e67e22", linestyle="--", alpha=0.6,
                label=f"Orexin gate ({OREXIN_GATE})")
    ax2.set_ylabel("Mean orexin tonic", color="#e67e22")
    ax2.tick_params(axis="y", labelcolor="#e67e22")

    lines1, lab1 = ax1.get_legend_handles_labels()
    lines2, lab2 = ax2.get_legend_handles_labels()
    ax1.legend(lines1 + lines2, lab1 + lab2, loc="upper right", fontsize=9)

    total_sleep = int((actions["action"] == "SLEEP").sum())
    total_acts  = len(actions)
    ax1.set_title(
        f"SLEEP action share vs. orexin tonic  "
        f"(SLEEP: {total_sleep}/{total_acts} = {100*total_sleep/max(total_acts,1):.2f}%  |  "
        f"orexin gate = {OREXIN_GATE}  |  anti-correlation expected)"
    )
    ax1.grid(True, alpha=0.25, axis="y")

    plt.tight_layout()
    plt.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"Figure 2 → {out_path}")


# ── Figure 3 — Decisions per filter & action distribution ──────────────────────

def figure3(actions: pd.DataFrame, out_path: str):
    """Action distribution across filter types and action types."""

    if actions.empty:
        print("  figure3: no action data", file=sys.stderr)
        return

    fig, axes = plt.subplots(1, 2, figsize=(14, 6))
    fig.suptitle("Action Selection Distribution", fontsize=12)

    # ── Panel (a): decisions per action type (overall share) ──────────────────
    action_counts = actions["action"].value_counts()
    colors_a = plt.cm.tab10(np.linspace(0, 1, len(action_counts)))
    axes[0].bar(action_counts.index, action_counts.values, color=colors_a)
    axes[0].set_title("(a) Actions selected — total count per type")
    axes[0].set_ylabel("Count")
    axes[0].tick_params(axis="x", rotation=30)
    for i, (lbl, v) in enumerate(action_counts.items()):
        axes[0].text(i, v + action_counts.max() * 0.01,
                     f"{100*v/len(actions):.1f}%", ha="center", fontsize=8)
    axes[0].grid(True, alpha=0.25, axis="y")

    # ── Panel (b): decisions per filter + action×filter stacked heatmap ─────────
    ft_col = "filter_type" if "filter_type" in actions.columns else "selection_type"
    if ft_col in actions.columns and actions[ft_col].notna().any():
        pivot = actions.groupby(["action", ft_col]).size().unstack(fill_value=0)
        pivot.plot(kind="bar", stacked=True, ax=axes[1], colormap="Set2")
        axes[1].set_title("(b) Decisions per filter (stacked by action type)")
        axes[1].set_ylabel("Count")
        axes[1].tick_params(axis="x", rotation=30)
        axes[1].legend(fontsize=8, title="Filter", loc="upper right")
        axes[1].grid(True, alpha=0.25, axis="y")
    else:
        axes[1].text(0.5, 0.5, "No filter data available",
                     ha="center", va="center", transform=axes[1].transAxes)
        axes[1].set_title("(b) Decisions per filter (no data)")

    plt.tight_layout()
    plt.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"Figure 3 → {out_path}")


# ── Figure 4 — HPA time series over circadian cycles ──────────────────────────

def figure4(nm: pd.DataFrame, endo: pd.DataFrame, out_path: str):
    """Cortisol tonic and STRESS affect over time (in circadian cycles)."""

    if nm.empty or endo.empty:
        print("  figure4: no data", file=sys.stderr)
        return

    # Express time in circadian cycles for readability.
    endo = endo.copy()
    nm   = nm.copy()

    fig, axes = plt.subplots(3, 1, figsize=(13, 9), sharex=True)
    fig.suptitle(
        "HPA axis & orexin over time (binned by circadian period)\n"
        "Each x-tick = one circadian period",
        fontsize=11,
    )

    creatures = nm["creature_key"].unique()
    palette = plt.cm.tab10(np.linspace(0, 1, max(len(creatures), 1)))

    # Aggregate by time bin (one circadian period per bin)
    for idx, ck in enumerate(creatures):
        nm_ck   = nm[nm["creature_key"] == ck].copy()
        endo_ck = endo[endo["creature_key"] == ck].copy()

        nm_ck["cycle"]   = nm_ck["seq"] // TICK_BIN_SIZE
        endo_ck["cycle"] = endo_ck["seq"] // TICK_BIN_SIZE

        orexin_cycle  = nm_ck.groupby("cycle")["orexin"].mean()
        cortisol_cycle = endo_ck.groupby("cycle")["cortisol_tonic"].mean()
        stress_cycle   = endo_ck.groupby("cycle")["stress_level"].mean()

        c = palette[idx]
        axes[0].plot(orexin_cycle.index, orexin_cycle.values,
                     color=c, alpha=0.7, linewidth=1.2, label=f"Creature {ck}")
        axes[1].plot(cortisol_cycle.index, cortisol_cycle.values,
                     color=c, alpha=0.7, linewidth=1.2)
        axes[2].plot(stress_cycle.index, stress_cycle.values,
                     color=c, alpha=0.7, linewidth=1.2)

    # Aggregate across all creatures
    nm["cycle"]   = nm["seq"] // TICK_BIN_SIZE
    endo["cycle"] = endo["seq"] // TICK_BIN_SIZE
    orexin_mean   = nm.groupby("cycle")["orexin"].mean()
    cortisol_mean = endo.groupby("cycle")["cortisol_tonic"].mean()
    stress_mean   = endo.groupby("cycle")["stress_level"].mean()

    axes[0].plot(orexin_mean.index, orexin_mean.values,
                 color="black", linewidth=2.2, linestyle="--", label="Mean")
    axes[0].axhline(OREXIN_GATE, color="#e67e22", linestyle=":", alpha=0.8,
                    label=f"Gate ({OREXIN_GATE})")
    axes[0].set_ylabel("Orexin tonic")
    axes[0].set_title("(a) Orexin tonic")
    axes[0].legend(fontsize=8, loc="lower right")
    axes[0].grid(True, alpha=0.25)

    axes[1].plot(cortisol_mean.index, cortisol_mean.values,
                 color="black", linewidth=2.2, linestyle="--", label="Mean")
    axes[1].axhline(CORTISOL_STRESS_THRESHOLD, color="#c0392b", linestyle=":",
                    alpha=0.8, label=f"Stress threshold ({CORTISOL_STRESS_THRESHOLD})")
    axes[1].set_ylabel("Cortisol tonic")
    axes[1].set_title("(b) Cortisol tonic (v2 — bounded by negative feedback)")
    axes[1].legend(fontsize=8)
    axes[1].grid(True, alpha=0.25)

    axes[2].plot(stress_mean.index, stress_mean.values,
                 color="black", linewidth=2.2, linestyle="--", label="Mean")
    axes[2].set_ylabel("Stress level")
    axes[2].set_title("(c) STRESS affect")
    axes[2].set_xlabel("Circadian cycle number")
    axes[2].legend(fontsize=8)
    axes[2].grid(True, alpha=0.25)

    plt.tight_layout()
    plt.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"Figure 4 → {out_path}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    nm      = load_neuromodulator()
    endo    = load_endocrine()
    actions = load_actions()

    if nm.empty or endo.empty or actions.empty:
        print("ERROR: one or more tables returned no data — is the db container running?",
              file=sys.stderr)
        sys.exit(1)

    figure1(nm, endo, os.path.join(OUT_DIR, "fig1_neuroendocrine_cycle.png"))
    figure2(nm, actions, os.path.join(OUT_DIR, "fig2_sleep_vs_orexin.png"))
    figure3(actions, os.path.join(OUT_DIR, "fig3_decisions_per_filter.png"))
    figure4(nm, endo, os.path.join(OUT_DIR, "fig4_hpa_time_series.png"))

    n_trials = actions["trial"].nunique() if "trial" in actions.columns else 1
    print("\nSummary statistics")
    print(f"  Trials:              {n_trials}")
    print(f"  Creatures (NM log):  {nm['creature_key'].nunique()}")
    print(f"  NM log rows:         {len(nm):,}")
    print(f"  Endocrine log rows:  {len(endo):,}")
    print(f"  Action selections:   {len(actions):,}")
    print(f"  SLEEP selections:    {(actions['action']=='SLEEP').sum():,}  "
          f"({100*(actions['action']=='SLEEP').mean():.3f}%)")
    print(f"  Mean orexin tonic:   {nm['orexin'].mean():.4f}")
    print(f"  Mean cortisol tonic: {endo['cortisol_tonic'].mean():.4f}")
    print(f"  Max cortisol:        {endo['cortisol_tonic'].max():.4f}")
    print(f"  Max stress level:    {endo['stress_level'].max():.4f}")
    print(f"  Mean stress level:   {endo['stress_level'].mean():.4f}")
    ft_col = "filter_type" if "filter_type" in actions.columns else "selection_type"
    if ft_col in actions.columns and actions[ft_col].notna().any():
        print(f"\n  Decisions per filter ({ft_col}):")
        for ftype, cnt in actions[ft_col].value_counts().items():
            print(f"    {str(ftype):<25} {cnt:>8,}  ({100*cnt/len(actions):.1f}%)")
    print(f"\n  Action distribution:")
    for act, cnt in actions["action"].value_counts().items():
        print(f"    {act:<15} {cnt:>8,}  ({100*cnt/len(actions):.1f}%)")


if __name__ == "__main__":
    main()
