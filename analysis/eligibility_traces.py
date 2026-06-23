"""
analysis/eligibility_traces.py

Analyses the engram_state table produced by Phase 4 (eligibility-trace credit
assignment). Generates all figures for the EXP_P4_1 report.

Set `wd` to the simulation results directory (CSV output of extractor) before
running. All CSVs must be named  <creature_key>/engrams.csv  and
<creature_key>/trajectory_emotions.csv under `wd`.

Alternatively, set `pg_dsn` to a PostgreSQL DSN and the script will query
directly instead of reading CSVs.
"""

import os, sys, glob
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker

# --- configuration -----------------------------------------------------------
wd  = os.environ.get("WD", "/path/to/results")        # override via env var
out = os.environ.get("OUT", wd)                        # figure output dir
pg_dsn = os.environ.get("PG_DSN", "")                 # set to skip CSV read

HALF_LIFE    = 5                                        # cycles (from Constants)
LAMBDA       = np.log(2) / HALF_LIFE
MIN_ELIG     = 0.01
MAX_GAP_PLOT = 60                                       # x-axis cap for decay plots
# -----------------------------------------------------------------------------


def load_from_postgres(dsn: str) -> pd.DataFrame:
    import psycopg2
    conn = psycopg2.connect(dsn)
    # EclipseLink maps camelCase without @Column(name=) as lowercase (no underscore)
    df = pd.read_sql_query(
        "SELECT creaturekey AS creature_key, action_type, lay_cycle, reinforced_cycle, "
        "cycle_gap, eligibility, emotion_delta FROM data.engram_state ORDER BY creaturekey, reinforced_cycle",
        conn)
    conn.close()
    return df


def load_from_csv(directory: str) -> pd.DataFrame:
    files = glob.glob(os.path.join(directory, "**", "*engrams.csv"), recursive=True)
    if not files:
        raise FileNotFoundError(f"No *engrams.csv found under {directory}")
    return pd.concat([pd.read_csv(f) for f in files], ignore_index=True)


def load_emotions(directory: str) -> pd.DataFrame:
    files = glob.glob(os.path.join(directory, "**", "*trajectory_emotions.csv"), recursive=True)
    if not files:
        return pd.DataFrame()
    return pd.concat([pd.read_csv(f) for f in files], ignore_index=True)


# --- load data ---------------------------------------------------------------
if pg_dsn:
    dsn = pg_dsn
else:
    dsn = "host=localhost port=5432 dbname=l2l user=postgres password=postgres"

try:
    df = load_from_postgres(dsn)
    print(f"Loaded {len(df):,} engrams from PostgreSQL")
except Exception as e:
    print(f"PostgreSQL unavailable ({e}), falling back to CSV")
    df = load_from_csv(wd)
    print(f"Loaded {len(df):,} engrams from CSV")

if df.empty:
    print("ERROR: no data found"); sys.exit(1)

os.makedirs(out, exist_ok=True)

creatures = sorted(df["creature_key"].unique())
print(f"Creatures: {creatures}")
print(df.groupby("action_type")[["cycle_gap","eligibility","emotion_delta"]].describe())


# =============================================================================
# Figure 1 — Engram count per action type (stacked bar per creature)
# =============================================================================
fig1, ax1 = plt.subplots(figsize=(8, 4))
action_counts = (df.groupby(["creature_key", "action_type"])
                   .size().unstack(fill_value=0))
action_counts.plot(kind="bar", stacked=True, ax=ax1, colormap="tab10")
ax1.set_xlabel("Creature key")
ax1.set_ylabel("Engram count")
ax1.set_title("Engram count by action type per creature")
ax1.legend(title="Action type", bbox_to_anchor=(1.01, 1), loc="upper left", fontsize=8)
ax1.tick_params(axis="x", rotation=0)
plt.tight_layout()
fig1.savefig(os.path.join(out, "fig1_engram_count_by_action.png"), dpi=120, bbox_inches="tight")
plt.close(fig1)
print("Fig 1 saved")


# =============================================================================
# Figure 2 — Eligibility vs cycle_gap: empirical vs theoretical decay curve
# =============================================================================
fig2, ax2 = plt.subplots(figsize=(7, 4))
gap_vals = np.linspace(0, MAX_GAP_PLOT, 300)
theoretical = np.exp(-LAMBDA * gap_vals)

ax2.scatter(df["cycle_gap"], df["eligibility"], s=2, alpha=0.3, color="steelblue",
            label="Engrams")
ax2.plot(gap_vals, theoretical, "r-", linewidth=2,
         label=f"Theoretical exp(−ln2/{HALF_LIFE}·gap)")
ax2.axhline(MIN_ELIG, color="grey", linestyle="--", linewidth=1,
            label=f"MIN_ELIGIBILITY = {MIN_ELIG}")
ax2.set_xlim(0, MAX_GAP_PLOT)
ax2.set_ylim(0, 1.05)
ax2.set_xlabel("Cycle gap (reinforcedCycle − layCycle)")
ax2.set_ylabel("Eligibility")
ax2.set_title("Eligibility decay: empirical vs theoretical")
ax2.legend(fontsize=8)
plt.tight_layout()
fig2.savefig(os.path.join(out, "fig2_eligibility_decay.png"), dpi=120, bbox_inches="tight")
plt.close(fig2)
print("Fig 2 saved")


# =============================================================================
# Figure 3 — Cycle gap histogram
# =============================================================================
fig3, ax3 = plt.subplots(figsize=(7, 4))
max_gap = int(df["cycle_gap"].max())
bins = range(0, min(max_gap + 2, MAX_GAP_PLOT + 2))
ax3.hist(df["cycle_gap"], bins=bins, color="steelblue", edgecolor="white",
         density=True, label="Observed gaps")
ax3.plot(gap_vals, LAMBDA * np.exp(-LAMBDA * gap_vals), "r-", linewidth=2,
         label=f"Theoretical Exp(λ={LAMBDA:.3f})")
ax3.set_xlabel("Cycle gap")
ax3.set_ylabel("Density")
ax3.set_title("Distribution of cycle gaps (decision → reinforcement)")
ax3.legend(fontsize=8)
ax3.set_xlim(0, MAX_GAP_PLOT)
plt.tight_layout()
fig3.savefig(os.path.join(out, "fig3_cycle_gap_histogram.png"), dpi=120, bbox_inches="tight")
plt.close(fig3)
print("Fig 3 saved")


# =============================================================================
# Figure 4 — Emotion delta distribution (positive vs negative valence)
# =============================================================================
fig4, axes4 = plt.subplots(1, 2, figsize=(10, 4))

# 4a — overall histogram
ax4a = axes4[0]
ax4a.hist(df["emotion_delta"], bins=80, color="steelblue", edgecolor="white")
ax4a.axvline(0, color="red", linestyle="--", linewidth=1)
ax4a.set_xlabel("Emotion delta (effective credit)")
ax4a.set_ylabel("Engram count")
ax4a.set_title("Distribution of emotion delta (all engrams)")

# 4b — valence breakdown by action type
ax4b = axes4[1]
valence = df.copy()
valence["valence"] = valence["emotion_delta"].apply(lambda x: "negative (good)" if x < 0 else "positive (bad)")
vc = valence.groupby(["action_type", "valence"]).size().unstack(fill_value=0)
vc.plot(kind="bar", ax=ax4b, colormap="RdYlGn", edgecolor="white")
ax4b.set_xlabel("Action type")
ax4b.set_ylabel("Engram count")
ax4b.set_title("Valence by action type")
ax4b.tick_params(axis="x", rotation=30)
ax4b.legend(title="Valence", fontsize=8)
plt.tight_layout()
fig4.savefig(os.path.join(out, "fig4_emotion_delta.png"), dpi=120, bbox_inches="tight")
plt.close(fig4)
print("Fig 4 saved")


# =============================================================================
# Figure 5 — Cumulative engram production over cognitive cycles per creature
# =============================================================================
fig5, ax5 = plt.subplots(figsize=(8, 4))
colors = plt.cm.tab10.colors
for i, ck in enumerate(creatures):
    sub = df[df["creature_key"] == ck].sort_values("reinforced_cycle")
    sub = sub.assign(cumulative=range(1, len(sub) + 1))
    ax5.plot(sub["reinforced_cycle"], sub["cumulative"],
             label=f"Creature {ck}", color=colors[i % len(colors)])
ax5.set_xlabel("Decision cycle (reinforced_cycle)")
ax5.set_ylabel("Cumulative engrams")
ax5.set_title("Engram accumulation over cognitive cycles")
ax5.legend(fontsize=8)
plt.tight_layout()
fig5.savefig(os.path.join(out, "fig5_engram_accumulation.png"), dpi=120, bbox_inches="tight")
plt.close(fig5)
print("Fig 5 saved")


# =============================================================================
# Figure 6 — Avg eligibility × avg |emotion_delta| per action type
#            (credit score = how much signal does each action type receive?)
# =============================================================================
fig6, axes6 = plt.subplots(1, 2, figsize=(10, 4))
agg = df.groupby("action_type").agg(
    avg_eligibility=("eligibility", "mean"),
    avg_abs_delta=("emotion_delta", lambda x: np.abs(x).mean()),
    count=("eligibility", "count")).reset_index()

colors_act = plt.cm.tab10.colors[:len(agg)]
axes6[0].bar(agg["action_type"], agg["avg_eligibility"], color=colors_act)
axes6[0].set_xlabel("Action type"); axes6[0].set_ylabel("Mean eligibility")
axes6[0].set_title("Mean eligibility per action type")
axes6[0].tick_params(axis="x", rotation=30)

axes6[1].bar(agg["action_type"], agg["avg_abs_delta"], color=colors_act)
axes6[1].set_xlabel("Action type"); axes6[1].set_ylabel("Mean |emotion_delta|")
axes6[1].set_title("Mean |effective credit| per action type")
axes6[1].tick_params(axis="x", rotation=30)
plt.tight_layout()
fig6.savefig(os.path.join(out, "fig6_credit_per_action.png"), dpi=120, bbox_inches="tight")
plt.close(fig6)
print("Fig 6 saved")


# =============================================================================
# Summary stats
# =============================================================================
total = len(df)
neg = (df["emotion_delta"] < 0).sum()
print(f"\n=== Summary ===")
print(f"Total engrams  : {total:,}")
print(f"Negative delta (beneficial) : {neg:,} ({100*neg/total:.1f}%)")
print(f"Positive delta (adverse)    : {total-neg:,} ({100*(total-neg)/total:.1f}%)")
print(f"Unique creatures            : {len(creatures)}")
print(f"\nPer action type:")
print(df.groupby("action_type").agg(
    count=("eligibility","count"),
    avg_gap=("cycle_gap","mean"),
    avg_elig=("eligibility","mean"),
    avg_delta=("emotion_delta","mean"),
    pct_neg=("emotion_delta", lambda x: 100*(x<0).mean())
).round(4).to_string())
print(f"\nFigures written to: {out}")
