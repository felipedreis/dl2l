#!/usr/bin/env python3
"""
p59 validation: orexin / cortisol / endocrine circadian analysis.

Figure 1 — Neuroendocrine mean cycle:
    Four panels sharing a circadian-phase x-axis, averaged over all creatures
    and all cycles:
      (a) circadian_phase oscillator
      (b) dopamine + serotonin + orexin tonic
      (c) cortisol_tonic with CORTISOL_STRESS_THRESHOLD reference line
      (d) stress_level

Figure 2 — SLEEP share vs. orexin tonic over cognitive time:
    Dual y-axis, x = cognitive tick (binned), showing anti-correlation between
    high orexinTonic and absence of SLEEP selections.

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

# ── Config ────────────────────────────────────────────────────────────────────
CONTAINER               = "db"
DB_USER                 = "postgres"
DB_NAME                 = "l2l"
OREXIN_GATE             = 15.0
CORTISOL_STRESS_THRESHOLD = 3.0
PHASE_BINS              = 60      # bins across [0, 2π] for Fig 1
TICK_BIN_SIZE           = 200     # ticks per window for Fig 2 (= one circadian period)
OUT_DIR                 = os.path.dirname(os.path.abspath(__file__))

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
    print("  loading chosen_action_state …", file=sys.stderr)
    df = psql("""
        SELECT css.key AS creature_key,
               css.time AS wall_ms,
               cas.action
        FROM   data.chosen_action_state cas
        JOIN   data.change_stimulus_state css ON cas.changestimulusstate_id = css.id
        WHERE  css.key IN (1000, 1001, 1002, 1003, 1004)
        ORDER  BY css.key, css.time
    """)
    # Normalise wall_ms → elapsed_ms per creature, then scale to nm seq units.
    # nm seq ≈ tick count; each creature has ~32K ticks over ~2170 s of sim.
    # Use per-creature min wall_ms as t=0 and scale so the range matches nm seq.
    nm_seq_range = psql("""
        SELECT creature_key, MIN(seq) AS seq_min, MAX(seq) AS seq_max
        FROM   data.neuromodulator_state_log
        WHERE  creature_key IN (1000, 1001, 1002, 1003, 1004)
        GROUP  BY creature_key
    """).set_index("creature_key")
    rows = []
    for ck, grp in df.groupby("creature_key"):
        t_min = grp["wall_ms"].min()
        t_max = grp["wall_ms"].max()
        seq_max = int(nm_seq_range.loc[ck, "seq_max"])
        elapsed = grp["wall_ms"] - t_min
        duration = max(t_max - t_min, 1)
        grp = grp.copy()
        grp["seq"] = (elapsed / duration * seq_max).round().astype(int)
        rows.append(grp)
    df = pd.concat(rows, ignore_index=True)
    print(f"  {len(df):,} rows", file=sys.stderr)
    return df

# ── Figure 1 ──────────────────────────────────────────────────────────────────

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

    # Attach phase to endocrine rows via nearest-seq join per creature.
    nm_phase  = nm[["creature_key", "seq", "circadian_phase"]]
    creatures = endo["creature_key"].unique()
    parts = []
    for ck in creatures:
        e = endo[endo["creature_key"] == ck].sort_values("seq").reset_index(drop=True)
        n = nm_phase[nm_phase["creature_key"] == ck].sort_values("seq").reset_index(drop=True)
        parts.append(pd.merge_asof(e, n, on="seq", direction="nearest",
                                   suffixes=("", "_nm")))
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

    # ── Panel 1: circadian oscillator ─────────────────────────────────────────
    axes[0].plot(x_nm, nm_avg["circadian_phase"].values,
                 color="#9b59b6", linewidth=1.8)
    axes[0].set_ylabel("Phase (rad)")
    axes[0].set_title("(a) Circadian oscillator phase")
    axes[0].grid(True, alpha=0.25)

    # ── Panel 2: DA + 5-HT (left axis) + orexin (right axis, different scale) ─
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

    # ── Panel 3: cortisol ─────────────────────────────────────────────────────
    axes[2].plot(x_endo, endo_avg["cortisol_tonic"].values,
                 color="#8e44ad", linewidth=1.8)
    axes[2].axhline(CORTISOL_STRESS_THRESHOLD, color="#c0392b", linestyle="--",
                    alpha=0.7, label=f"Stress threshold ({CORTISOL_STRESS_THRESHOLD})")
    axes[2].set_ylabel("Cortisol tonic")
    axes[2].set_title("(c) HPA axis: cortisol tonic")
    axes[2].legend(fontsize=8)
    axes[2].grid(True, alpha=0.25)

    # ── Panel 4: stress ───────────────────────────────────────────────────────
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


# ── Figure 2 ──────────────────────────────────────────────────────────────────

def figure2(nm: pd.DataFrame, actions: pd.DataFrame, out_path: str):
    """SLEEP share vs. orexinTonic over cognitive time (seq-binned)."""

    # Bin neuromodulator by seq window and average orexin per creature per bin.
    nm = nm.copy()
    nm["bin"] = (nm["seq"] // TICK_BIN_SIZE) * TICK_BIN_SIZE
    nm_bin = nm.groupby(["creature_key", "bin"], observed=True)["orexin"].mean().reset_index()
    orexin_ts = nm_bin.groupby("bin")["orexin"].mean()

    # Actions: bin by sequential counter per creature using the same window size.
    actions = actions.copy()
    actions["bin"] = (actions["seq"] // TICK_BIN_SIZE) * TICK_BIN_SIZE
    grp = actions.groupby("bin")
    sleep_share = grp.apply(
        lambda g: (g["action"] == "SLEEP").sum() / max(len(g), 1),
        include_groups=False,
    )
    action_count = grp.size()

    # Common bins
    common = orexin_ts.index.intersection(sleep_share.index)
    x          = common.values
    orexin_y   = orexin_ts.loc[common].values
    sleep_y    = sleep_share.loc[common].values
    count_y    = action_count.loc[common].values

    fig, ax1 = plt.subplots(figsize=(13, 5))

    ax1.bar(x, sleep_y, width=TICK_BIN_SIZE * 0.9, color="#3498db", alpha=0.55,
            label="SLEEP share")
    ax1.set_xlabel(f"Cognitive tick (binned, window = {TICK_BIN_SIZE} ticks = 1 circadian period)")
    ax1.set_ylabel("SLEEP action share", color="#2980b9")
    ax1.tick_params(axis="y", labelcolor="#2980b9")
    ax1.set_ylim(0, max(sleep_y.max() * 2.5, 0.02))

    ax2 = ax1.twinx()
    ax2.plot(x, orexin_y, color="#e67e22", linewidth=2.0, label="Mean orexin tonic")
    ax2.axhline(OREXIN_GATE, color="#e67e22", linestyle="--", alpha=0.6,
                label=f"Orexin gate ({OREXIN_GATE})")
    ax2.set_ylabel("Mean orexin tonic", color="#e67e22")
    ax2.tick_params(axis="y", labelcolor="#e67e22")

    lines1, lab1 = ax1.get_legend_handles_labels()
    lines2, lab2 = ax2.get_legend_handles_labels()
    ax1.legend(lines1 + lines2, lab1 + lab2, loc="upper right", fontsize=9)

    # Annotate total SLEEP selections
    total_sleep = int((actions["action"] == "SLEEP").sum())
    total_acts  = len(actions)
    ax1.set_title(
        f"SLEEP action share vs. orexin tonic  "
        f"(SLEEP: {total_sleep}/{total_acts} = {100*total_sleep/total_acts:.2f}%  |  "
        f"orexin gate = {OREXIN_GATE}  |  anti-correlation expected)"
    )
    ax1.grid(True, alpha=0.25, axis="y")

    plt.tight_layout()
    plt.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"Figure 2 → {out_path}")


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

    print("\nSummary statistics")
    print(f"  Creatures:           {nm['creature_key'].nunique()}")
    print(f"  NM log rows:         {len(nm):,}")
    print(f"  Endocrine log rows:  {len(endo):,}")
    print(f"  Action selections:   {len(actions):,}")
    print(f"  SLEEP selections:    {(actions['action']=='SLEEP').sum():,}  "
          f"({100*(actions['action']=='SLEEP').mean():.3f}%)")
    print(f"  Mean orexin tonic:   {nm['orexin'].mean():.3f}")
    print(f"  Mean cortisol tonic: {endo['cortisol_tonic'].mean():.3f}")
    print(f"  Max stress level:    {endo['stress_level'].max():.3f}")


if __name__ == "__main__":
    main()
