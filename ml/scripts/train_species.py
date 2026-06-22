"""
Task 2.3 — Offline species base training.

Usage:
    cd ml
    python -m scripts.train_species [options]

Options:
    --data       Path to directory containing train.parquet, val.parquet, stats.json
                 (default: ml/data)
    --ckpt       Checkpoint output directory (default: ml/checkpoints)
    --epochs     Number of training epochs (default: 100)
    --batch      Batch size (default: 256)
    --lr         Learning rate (default: 1e-3)
    --sigreg     λ_sigreg weight (default: 0.1)
    --crit       λ_crit weight (default: 1.0)
    --device     cpu | cuda | mps (default: auto-detect)
"""

import argparse
import json
import os
import sys
from pathlib import Path

import torch
from torch.utils.data import DataLoader

# Allow running as `python -m scripts.train_species` from the ml/ directory.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jepa.dataset import TrajectoryDataset
from jepa.model   import SpeciesModel, IndividualAdapter
from jepa.train   import train_one_epoch, evaluate


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--data",   default="data")
    p.add_argument("--ckpt",   default="checkpoints")
    p.add_argument("--epochs", type=int,   default=100)
    p.add_argument("--batch",  type=int,   default=256)
    p.add_argument("--lr",     type=float, default=1e-3)
    p.add_argument("--sigreg", type=float, default=0.1)
    p.add_argument("--crit",   type=float, default=1.0)
    p.add_argument("--device", default=None)
    return p.parse_args()


def main():
    args = parse_args()

    data_dir = Path(args.data)
    ckpt_dir = Path(args.ckpt)
    ckpt_dir.mkdir(parents=True, exist_ok=True)

    stats_path = data_dir / "stats.json"
    if not stats_path.exists():
        sys.exit(f"stats.json not found at {stats_path} — run prepare_dataset.py first.")

    stats = json.loads(stats_path.read_text())
    live_dims = stats["live_emotion_dims"]
    print(f"Live emotion dims: {live_dims} "
          f"({[stats['emotion_index_order'][i] for i in live_dims]})")

    if args.device:
        device = torch.device(args.device)
    elif torch.cuda.is_available():
        device = torch.device("cuda")
    elif torch.backends.mps.is_available():
        device = torch.device("mps")
    else:
        device = torch.device("cpu")
    print(f"Device: {device}")

    train_ds = TrajectoryDataset(str(data_dir / "train.parquet"), str(stats_path))
    val_ds   = TrajectoryDataset(str(data_dir / "val.parquet"),   str(stats_path))
    # shuffle=False to preserve temporal order within each creature's segment
    # (needed for consecutive-pair next-state computation in train.py)
    train_loader = DataLoader(train_ds, batch_size=args.batch, shuffle=False, drop_last=True)
    val_loader   = DataLoader(val_ds,   batch_size=args.batch, shuffle=False, drop_last=False)

    model = SpeciesModel(
        input_dim  = stats["input_dim"],
        action_dim = stats["action_dim"],
        latent_dim = stats["latent_dim"],
        emotion_dim= stats["emotion_dim"],
        min_arousal= stats["min_arousal"],
        max_arousal= stats["max_arousal"],
    ).to(device)

    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)

    best_val_pred = float("inf")
    print(f"\nTraining for {args.epochs} epochs …\n")
    print(f"{'Epoch':>6}  {'tr_total':>9}  {'tr_pred':>8}  {'tr_sig':>7}  "
          f"{'tr_crit':>8}  {'vl_total':>9}  {'vl_pred':>8}")
    print("-" * 72)

    for epoch in range(1, args.epochs + 1):
        tr = train_one_epoch(model, train_loader, optimizer, live_dims,
                             args.sigreg, args.crit, device)
        vl = evaluate(model, val_loader, live_dims, args.sigreg, args.crit, device)

        print(f"{epoch:>6}  {tr['total']:>9.4f}  {tr['pred']:>8.4f}  "
              f"{tr['sigreg']:>7.4f}  {tr['crit']:>8.4f}  "
              f"{vl['total']:>9.4f}  {vl['pred']:>8.4f}")

        if vl["pred"] < best_val_pred:
            best_val_pred = vl["pred"]
            torch.save(model.state_dict(), ckpt_dir / "best.pt")

    torch.save(model.state_dict(), ckpt_dir / "final.pt")

    # Save stats alongside checkpoints so export_model.py can find them.
    import shutil
    shutil.copy(stats_path, ckpt_dir / "stats.json")

    print(f"\nBest val L_pred: {best_val_pred:.4f}")
    print(f"Checkpoints saved to {ckpt_dir}/")


if __name__ == "__main__":
    main()
