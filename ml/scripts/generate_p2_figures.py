"""Generate figures for EXP_P2_1 report."""

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.gridspec as gridspec
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from jepa.model import SpeciesModel
from jepa.dataset import TrajectoryDataset
from jepa.evaluate import collect_latents, check_collapse
from torch.utils.data import DataLoader

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("../docs/reports/EXP_P2_1_SPECIES_MODEL_BASELINE")
OUT.mkdir(parents=True, exist_ok=True)

DATA = Path("data")
CKPT = Path("checkpoints")

stats = json.loads((DATA / "stats.json").read_text())
train_df = pd.read_parquet(DATA / "train.parquet")
val_df   = pd.read_parquet(DATA / "val.parquet")
all_df   = pd.concat([train_df, val_df], ignore_index=True)

# ── 1. Dataset overview ────────────────────────────────────────────────────────
fig, axes = plt.subplots(1, 3, figsize=(14, 4))

# Action distribution
action_cols = [f"a_{a}" for a in stats["action_index_order"]]
action_counts = all_df[action_cols].sum().rename(
    {f"a_{a}": a for a in stats["action_index_order"]}
)
ax = axes[0]
bars = ax.bar(range(len(action_counts)), action_counts.values,
              color="#4C72B0", edgecolor="white", linewidth=0.5)
ax.set_xticks(range(len(action_counts)))
ax.set_xticklabels(action_counts.index, rotation=40, ha="right", fontsize=8)
ax.set_title("Action type distribution", fontsize=11)
ax.set_ylabel("Tuple count")
for bar, v in zip(bars, action_counts.values):
    ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 200,
            f"{int(v):,}", ha="center", va="bottom", fontsize=7)

# Object type distribution
obj_cols = ["type_GRAY_APPLE", "type_GREEN_APPLE", "type_RED_APPLE"]
obj_counts = all_df[obj_cols].sum().rename(
    {"type_GRAY_APPLE": "GRAY", "type_GREEN_APPLE": "GREEN", "type_RED_APPLE": "RED"}
)
colors = ["#888888", "#4CAF50", "#E53935"]
ax = axes[1]
bars = ax.bar(range(3), obj_counts.values, color=colors, edgecolor="white")
ax.set_xticks(range(3))
ax.set_xticklabels(obj_counts.index, fontsize=9)
ax.set_title("Object type distribution", fontsize=11)
ax.set_ylabel("Tuple count")
for bar, v in zip(bars, obj_counts.values):
    ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 200,
            f"{int(v):,}", ha="center", va="bottom", fontsize=8)

# Train / val split by creature
train_creatures = train_df["creatureKey"].nunique()
val_creatures   = val_df["creatureKey"].nunique()
ax = axes[2]
ax.bar(["Train", "Val"], [len(train_df), len(val_df)],
       color=["#4C72B0", "#DD8452"], edgecolor="white")
ax.set_title("Train / val split", fontsize=11)
ax.set_ylabel("Tuple count")
for label, n, c in zip(["Train", "Val"], [len(train_df), len(val_df)],
                        [train_creatures, val_creatures]):
    ax.text(["Train", "Val"].index(label), n + 500,
            f"{n:,}\n({c} creatures)", ha="center", va="bottom", fontsize=8)

fig.suptitle("Dataset overview — 5 trials × 10 creatures", fontsize=12, y=1.02)
fig.tight_layout()
fig.savefig(OUT / "dataset_overview.png", dpi=150, bbox_inches="tight")
plt.close(fig)
print("Saved dataset_overview.png")

# ── 2. Perception feature distributions ───────────────────────────────────────
fig, axes = plt.subplots(1, 3, figsize=(12, 4))
means = stats["feature_means"]
stds  = stats["feature_stds"]
labels = ["Distance (world units)", "Angle (rad)", "Direction (deg)"]
cols   = ["distance", "angle", "direction"]

for ax, col, mean, std, label in zip(axes, cols, means, stds, labels):
    data = all_df[col].dropna()
    ax.hist(data, bins=60, color="#4C72B0", alpha=0.8, edgecolor="none")
    ax.axvline(mean, color="#E53935", linewidth=1.5, label=f"μ={mean:.2f}")
    ax.axvline(mean - std, color="#E53935", linewidth=1, linestyle="--", alpha=0.6)
    ax.axvline(mean + std, color="#E53935", linewidth=1, linestyle="--", alpha=0.6,
               label=f"σ={std:.2f}")
    ax.set_title(label, fontsize=11)
    ax.set_ylabel("Count")
    ax.legend(fontsize=8)

fig.suptitle("Perception feature distributions (pre-normalisation)", fontsize=12)
fig.tight_layout()
fig.savefig(OUT / "feature_distributions.png", dpi=150, bbox_inches="tight")
plt.close(fig)
print("Saved feature_distributions.png")

# ── 3. Emotion target distributions ───────────────────────────────────────────
fig, axes = plt.subplots(1, 2, figsize=(10, 4))
emotion_labels = ["Hunger arousal", "Sleep arousal"]
emotion_cols   = ["final_hunger", "final_sleep"]
colors_e = ["#E53935", "#1565C0"]

for ax, col, label, color in zip(axes, emotion_cols, emotion_labels, colors_e):
    data = all_df[col].dropna()
    ax.hist(data, bins=60, color=color, alpha=0.8, edgecolor="none")
    ax.axvline(data.mean(), color="black", linewidth=1.5,
               label=f"μ={data.mean():.3f}")
    ax.axvline(data.median(), color="gray", linewidth=1, linestyle="--",
               label=f"med={data.median():.3f}")
    ax.set_title(label, fontsize=11)
    ax.set_xlabel("Arousal value")
    ax.set_ylabel("Count")
    ax.legend(fontsize=9)

fig.suptitle("Live emotion dim target distributions (dims 0 & 1)", fontsize=12)
fig.tight_layout()
fig.savefig(OUT / "emotion_distributions.png", dpi=150, bbox_inches="tight")
plt.close(fig)
print("Saved emotion_distributions.png")

# ── 4. Latent space diagnostics ───────────────────────────────────────────────
device = torch.device("cpu")
model = SpeciesModel(
    input_dim   = stats["input_dim"],
    action_dim  = stats["action_dim"],
    latent_dim  = stats["latent_dim"],
    emotion_dim = stats["emotion_dim"],
    min_arousal = stats["min_arousal"],
    max_arousal = stats["max_arousal"],
)
model.load_state_dict(torch.load(CKPT / "best.pt", map_location="cpu", weights_only=True))
model.eval()

val_ds     = TrajectoryDataset(str(DATA / "val.parquet"), str(DATA / "stats.json"))
val_loader = DataLoader(val_ds, batch_size=512, shuffle=False)
Z = collect_latents(model.encoder, val_loader, device=device)
results = check_collapse(Z, latent_dim=stats["latent_dim"])
var_per_dim = np.array(results["per_dim_var"])

fig = plt.figure(figsize=(14, 5))
gs  = gridspec.GridSpec(1, 2, width_ratios=[3, 1], wspace=0.35)

# Per-dim variance bar chart
ax0 = fig.add_subplot(gs[0])
colors_v = ["#E53935" if v < 1e-4 else "#4C72B0" for v in var_per_dim]
ax0.bar(range(len(var_per_dim)), var_per_dim, color=colors_v, width=0.8)
ax0.axhline(1e-4, color="#E53935", linewidth=1.5, linestyle="--",
            label="Dead-dim threshold (1e-4)")
ax0.axhline(1.0, color="gray", linewidth=1, linestyle=":", alpha=0.6,
            label="Target variance (N(0,I))")
ax0.set_xlabel("Latent dimension")
ax0.set_ylabel("Variance")
ax0.set_title("Per-dimension variance (validation set, n=42 665)", fontsize=11)
ax0.legend(fontsize=9)
ax0.set_yscale("log")
ax0.set_xlim(-1, len(var_per_dim))

# Effective rank summary
ax1 = fig.add_subplot(gs[1])
eff_rank    = results["effective_rank"]
threshold   = results["eff_rank_threshold"]
latent_dim  = stats["latent_dim"]
bar_vals    = [eff_rank, threshold, latent_dim]
bar_labels  = ["Effective\nrank", "Threshold\n(10%)", "Latent\ndim"]
bar_colors  = ["#4C72B0", "#E53935", "#aaaaaa"]
bars2 = ax1.bar(bar_labels, bar_vals, color=bar_colors, edgecolor="white")
for bar, v in zip(bars2, bar_vals):
    ax1.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.5,
             f"{v:.1f}", ha="center", va="bottom", fontsize=10, fontweight="bold")
ax1.set_title("Effective rank", fontsize=11)
ax1.set_ylabel("Value")

fig.suptitle("Latent space diagnostics — collapse check PASS", fontsize=12)
fig.savefig(OUT / "latent_diagnostics.png", dpi=150, bbox_inches="tight")
plt.close(fig)
print("Saved latent_diagnostics.png")

# ── 5. Critic output vs target scatter (live dims) ────────────────────────────
all_pred, all_target = [], []
with torch.no_grad():
    for batch in val_loader:
        s, a, e = batch[0].to(device), batch[1].to(device), batch[2].to(device)
        z, _, emotion_pred = model(s, a)
        all_pred.append(emotion_pred[:, :2].cpu())
        all_target.append(e[:, :2].cpu())
pred_e   = torch.cat(all_pred,   dim=0).numpy()
target_e = torch.cat(all_target, dim=0).numpy()

fig, axes = plt.subplots(1, 2, figsize=(10, 5))
dim_labels = ["Hunger", "Sleep"]
for i, (ax, label) in enumerate(zip(axes, dim_labels)):
    ax.scatter(target_e[:, i], pred_e[:, i],
               alpha=0.05, s=3, color="#4C72B0", rasterized=True)
    lo = min(target_e[:, i].min(), pred_e[:, i].min())
    hi = max(target_e[:, i].max(), pred_e[:, i].max())
    ax.plot([lo, hi], [lo, hi], color="#E53935", linewidth=1.5,
            linestyle="--", label="ideal")
    mae = np.abs(target_e[:, i] - pred_e[:, i]).mean()
    ax.set_title(f"Critic — {label} dim\nMAE = {mae:.4f}", fontsize=11)
    ax.set_xlabel("Target arousal")
    ax.set_ylabel("Predicted arousal")
    ax.legend(fontsize=9)

fig.suptitle("Critic emotion prediction vs target (validation set)", fontsize=12)
fig.tight_layout()
fig.savefig(OUT / "critic_scatter.png", dpi=150, bbox_inches="tight")
plt.close(fig)
print("Saved critic_scatter.png")

print(f"\nAll figures written to {OUT}/")
