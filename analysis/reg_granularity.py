"""
analysis/reg_granularity.py

Aggregates per-creature regulation-batch CSVs and prints the granularity
decision for Phase 1 of the JEPA integration (HLD §6 #2).

Decision rule:
  p_collision = fraction of regulating batches with sameDriveCollision
  < COLLISION_THRESHOLD  → PURE PER-BATCH reinforcement
  >= COLLISION_THRESHOLD → FROZEN-BASELINE PER-STIMULUS reinforcement

Set `wd` to the simulation results directory before running.
"""

import os
import glob
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

wd = "/path/to/simulation/results"
COLLISION_THRESHOLD = 0.01

hist_files = glob.glob(os.path.join(wd, "**", "*reg_hist.csv"), recursive=True)
if not hist_files:
    raise FileNotFoundError("No *reg_hist.csv files found under: " + wd)

hist = pd.concat([pd.read_csv(f) for f in hist_files], ignore_index=True)
agg = hist.groupby("regulatingCount")["batches"].sum().sort_index()

regulating = int(agg[agg.index >= 1].sum())
multi      = int(agg[agg.index >= 2].sum())
p_multi    = float(multi) / regulating if regulating else 0.0

coll_files = glob.glob(os.path.join(wd, "**", "*reg_collisions.csv"), recursive=True)
total_coll = 0
if coll_files:
    total_coll = int(
        pd.concat([pd.read_csv(f) for f in coll_files])["sameDriveCollisions"].sum()
    )
p_coll = float(total_coll) / regulating if regulating else 0.0

print(
    "Regulating batches: %d | multi(>=2): %d (p=%.4f) | same-drive collisions: %d (p=%.4f)"
    % (regulating, multi, p_multi, total_coll, p_coll)
)
print("DECISION:", "PURE PER-BATCH" if p_coll < COLLISION_THRESHOLD else "FROZEN-BASELINE PER-STIMULUS")

plt.figure()
plt.bar(agg.index.astype(int), agg.values)
plt.xlabel("regulating stimuli per batch")
plt.ylabel("batches")
plt.title("Regulating-stimulus count per onReceive batch")
plt.tight_layout()
plt.savefig(os.path.join(wd, "reg_granularity_hist.png"), dpi=120, bbox_inches="tight")
print("Histogram saved to", os.path.join(wd, "reg_granularity_hist.png"))
