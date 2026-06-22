"""
Task 0.1 — Perception coverage probe.

Set `wd` to the results directory produced by the data extractor
(the one containing perceptionCoverage.csv files), then run:

    python3 analysis/coverage_probe.py

Outputs: perception_ranges_report.txt, distance_hist.png,
         angle_hist.png, pca_scree.png — all written to `wd`.
"""

import os
import sys
import glob
import pandas as pd
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

wd = "/path/to/simulation/results"   # <-- set this

COVERAGE_THRESHOLD = 0.01   # object types below this fraction flag sparse coverage


# ── helpers ──────────────────────────────────────────────────────────────────

def find_csvs(base, name):
    """Recursively find all <name>.csv files under base (matches util.py pattern)."""
    found = []
    target = name + ".csv"
    for dirpath, _, files in os.walk(base):
        if target in files:
            found.append(os.path.join(dirpath, target))
    return found


def load_df(base, name):
    files = find_csvs(base, name)
    if not files:
        print(f"ERROR: no {name}.csv found under {base}", file=sys.stderr)
        sys.exit(1)
    frames = [pd.read_csv(f) for f in files]
    return pd.concat(frames, ignore_index=True)


# ── load ─────────────────────────────────────────────────────────────────────

df = load_df(wd, "perceptionCoverage")
df = df.dropna(subset=["distance", "angle"])
print(f"Loaded {len(df)} perception events from {df['creatureKey'].nunique()} creature(s).")

# ── per-dimension stats ───────────────────────────────────────────────────────

lines = []
for col in ["distance", "angle"]:
    s = df[col]
    lines.append(f"\n{col}:")
    lines.append(f"  min={s.min():.4f}  max={s.max():.4f}  mean={s.mean():.4f}  std={s.std():.4f}")
    lines.append(f"  p5={s.quantile(0.05):.4f}  p50={s.quantile(0.50):.4f}  p95={s.quantile(0.95):.4f}")

total = len(df)
lines.append("\nobjectType breakdown:")
type_counts = df["objectType"].value_counts()
sparse = []
for otype, cnt in type_counts.items():
    frac = cnt / total
    lines.append(f"  {otype}: {cnt} ({frac*100:.2f}%)")
    if frac < COVERAGE_THRESHOLD:
        sparse.append(otype)

# ── PCA ──────────────────────────────────────────────────────────────────────

try:
    from sklearn.decomposition import PCA
    from sklearn.preprocessing import StandardScaler

    ohe = pd.get_dummies(df["objectType"], prefix="type")
    numeric = pd.concat([df[["distance", "angle"]].reset_index(drop=True),
                         ohe.reset_index(drop=True)], axis=1).fillna(0)
    X = StandardScaler().fit_transform(numeric.values)

    pca = PCA()
    pca.fit(X)
    evr = pca.explained_variance_ratio_
    cumvar = np.cumsum(evr)
    n95 = int(np.searchsorted(cumvar, 0.95)) + 1

    lines.append(f"\nPCA on {X.shape[1]} features ({X.shape[0]} samples):")
    for i, v in enumerate(evr[:10]):
        lines.append(f"  PC{i+1}: {v*100:.2f}%  (cumulative {cumvar[i]*100:.2f}%)")
    lines.append(f"  Components needed for 95% variance: {n95}")

    fig, ax = plt.subplots()
    ax.bar(range(1, len(evr) + 1), evr * 100)
    ax.set_xlabel("Principal component")
    ax.set_ylabel("Explained variance (%)")
    ax.set_title("Perception vector PCA scree")
    fig.savefig(os.path.join(wd, "pca_scree.png"), dpi=120, bbox_inches="tight")
    plt.close(fig)

except ImportError:
    lines.append("\n[sklearn not available — PCA skipped]")
    n95 = None

# ── histograms ───────────────────────────────────────────────────────────────

for col, fname in [("distance", "distance_hist.png"), ("angle", "angle_hist.png")]:
    fig, ax = plt.subplots()
    ax.hist(df[col], bins=50)
    ax.set_xlabel(col)
    ax.set_ylabel("count")
    ax.set_title(f"Perception {col} distribution")
    fig.savefig(os.path.join(wd, fname), dpi=120, bbox_inches="tight")
    plt.close(fig)

# ── decision ─────────────────────────────────────────────────────────────────

lines.append("\n=== DECISION ===")
if sparse:
    lines.append(f"Sparse object types (< {COVERAGE_THRESHOLD*100:.0f}%): {sparse}")
    lines.append("RECOMMENDATION: add random-policy episodes to training data (Task 2.1).")
else:
    lines.append("All object types adequately covered.")
    lines.append("RECOMMENDATION: no random-policy episodes required.")

report = "\n".join(lines)
print(report)

report_path = os.path.join(wd, "perception_ranges_report.txt")
with open(report_path, "w") as f:
    f.write(report)
print(f"\nReport written to {report_path}")
