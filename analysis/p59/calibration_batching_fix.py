"""
Calibration analysis for the homeostatic batching fix (issue #59).

Validates that sleep pressure stays bounded after the AdenosinergicStimulus
backlog fix (HOMEO_BATCH_SIZE=20 batching in PartialAppraisal + FullAppraisal).

Produces three figures saved to analysis/p59/figures/:
  Fig1 – Sleep arousal over simulation time for all creatures
          (must stay below MAX_AROUSAL_LEVEL=7.0, ideally below 5.0)
  Fig2 – Action type distribution across all creatures
  Fig3 – Sleep episode duration histogram (in cognitive cycles)

Usage:
  python3 analysis/p59/calibration_batching_fix.py
"""

import os
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

DATA_DIR = "ml/data_p59_calibration"
OUT_DIR = "analysis/p59/figures"
os.makedirs(OUT_DIR, exist_ok=True)

CREATURE_IDS = ["1000:0", "1001:0", "1002:0"]
COLORS = ["#2196F3", "#FF5722", "#4CAF50"]
MAX_AROUSAL = 7.0

# ---------------------------------------------------------------------------
# Load data
# ---------------------------------------------------------------------------
arousal_dfs = {}
action_dfs  = {}

for cid in CREATURE_IDS:
    adf = pd.read_csv(os.path.join(DATA_DIR, cid, "arousal_history.csv"))
    # time column is in minutes
    adf["time_s"] = adf["time"] * 60.0
    arousal_dfs[cid] = adf

    tdf = pd.read_csv(os.path.join(DATA_DIR, cid, "trajectory_actions.csv"))
    # Normalise action_time to seconds from start
    t0 = tdf["action_time"].min()
    tdf["time_s"] = (tdf["action_time"] - t0) / 1000.0
    action_dfs[cid] = tdf

all_actions = pd.concat(action_dfs.values(), ignore_index=True)

# ---------------------------------------------------------------------------
# Fig 1 – Sleep arousal over time
# ---------------------------------------------------------------------------
fig, axes = plt.subplots(3, 1, figsize=(12, 9), sharex=False)
fig.suptitle("Sleep Arousal Over Time — Batching Fix Validation\n"
             "(sleep must stay < MAX_AROUSAL=7.0; previously rose monotonically to death)",
             fontsize=12, fontweight="bold")

for ax, cid, color in zip(axes, CREATURE_IDS, COLORS):
    df = arousal_dfs[cid]
    ax.plot(df["time_s"], df["sleep"], color=color, linewidth=0.8, label="sleep")
    ax.plot(df["time_s"], df["hunger"], color="orange", linewidth=0.8,
            alpha=0.6, linestyle="--", label="hunger")
    ax.axhline(MAX_AROUSAL, color="red", linewidth=1.0, linestyle=":",
               label=f"MAX_AROUSAL={MAX_AROUSAL}")
    ax.axhline(5.0, color="purple", linewidth=0.8, linestyle=":",
               alpha=0.6, label="OREXIN_GATE=5.0")
    ax.set_ylabel("Arousal", fontsize=9)
    ax.set_ylim(0, MAX_AROUSAL + 0.3)
    ax.set_title(f"Creature {cid}  |  sleep_max={df['sleep'].max():.3f}  "
                 f"hunger_max={df['hunger'].max():.3f}", fontsize=9)
    ax.legend(fontsize=7, loc="upper left", ncol=2)
    ax.grid(alpha=0.3)
    ax.set_xlabel("Simulation time (s)", fontsize=8)

plt.tight_layout()
fig.savefig(os.path.join(OUT_DIR, "fig1_sleep_arousal_over_time.png"), dpi=150)
plt.close()
print("Saved fig1_sleep_arousal_over_time.png")

# ---------------------------------------------------------------------------
# Fig 2 – Action distribution
# ---------------------------------------------------------------------------
action_counts = all_actions["action_type"].value_counts()
colors_pie = ["#2196F3", "#FF5722", "#4CAF50", "#FFC107", "#9C27B0",
              "#607D8B", "#F44336"]

fig, ax = plt.subplots(figsize=(7, 7))
wedges, texts, autotexts = ax.pie(
    action_counts.values,
    labels=action_counts.index,
    autopct="%1.1f%%",
    colors=colors_pie[:len(action_counts)],
    startangle=140,
    pctdistance=0.82
)
for at in autotexts:
    at.set_fontsize(10)
ax.set_title(
    f"Action Distribution — All Creatures (n={len(all_actions):,})\n"
    f"SLEEP rate={action_counts.get('SLEEP',0)/len(all_actions)*100:.1f}%  "
    f"(prior broken run: 44.2%)",
    fontsize=11, fontweight="bold"
)
plt.tight_layout()
fig.savefig(os.path.join(OUT_DIR, "fig2_action_distribution.png"), dpi=150)
plt.close()
print("Saved fig2_action_distribution.png")

# ---------------------------------------------------------------------------
# Fig 3 – Sleep episode duration histogram
# ---------------------------------------------------------------------------
episode_durations = []
for cid, tdf in action_dfs.items():
    actions = tdf["action_type"].tolist()
    in_sleep = False
    count = 0
    for a in actions:
        if a == "SLEEP":
            count += 1
            in_sleep = True
        else:
            if in_sleep and count > 0:
                episode_durations.append(count)
            in_sleep = False
            count = 0
    if in_sleep and count > 0:
        episode_durations.append(count)

fig, ax = plt.subplots(figsize=(9, 5))
if episode_durations:
    bins = range(1, max(episode_durations) + 2)
    ax.hist(episode_durations, bins=bins, color="#2196F3", edgecolor="white",
            alpha=0.85)
    ax.axvline(10, color="red", linewidth=1.5, linestyle="--",
               label=f"MIN_SLEEP_TICKS=10")
    ax.set_xlabel("Sleep episode duration (cognitive cycles)", fontsize=11)
    ax.set_ylabel("Count", fontsize=11)
    ax.set_title(
        f"Sleep Episode Duration Histogram\n"
        f"n={len(episode_durations)} episodes  |  "
        f"mean={np.mean(episode_durations):.1f}  median={np.median(episode_durations):.0f}",
        fontsize=11, fontweight="bold"
    )
    ax.legend(fontsize=9)
    ax.grid(axis="y", alpha=0.3)
else:
    ax.text(0.5, 0.5, "No sleep episodes found", ha="center", va="center",
            transform=ax.transAxes, fontsize=14)
plt.tight_layout()
fig.savefig(os.path.join(OUT_DIR, "fig3_sleep_episode_durations.png"), dpi=150)
plt.close()
print("Saved fig3_sleep_episode_durations.png")

# ---------------------------------------------------------------------------
# Summary stats
# ---------------------------------------------------------------------------
print("\n=== Summary ===")
for cid in CREATURE_IDS:
    df = arousal_dfs[cid]
    tdf = action_dfs[cid]
    sleep_pct = (tdf["action_type"] == "SLEEP").mean() * 100
    lifetime = df["time_s"].max()
    print(f"Creature {cid}: lifetime={lifetime:.1f}s  sleep_pct={sleep_pct:.1f}%  "
          f"sleep_max={df['sleep'].max():.3f}  sleep_mean={df['sleep'].mean():.3f}  "
          f"sleep>5: {(df['sleep']>5.0).sum()}")

print(f"\nTotal episodes: {len(episode_durations)}")
if episode_durations:
    print(f"Episode duration: mean={np.mean(episode_durations):.1f}  "
          f"median={np.median(episode_durations):.0f}  "
          f"min={min(episode_durations)}  max={max(episode_durations)}")
