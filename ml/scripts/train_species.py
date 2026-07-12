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
    --variant    Architecture to train (default: single):
                   single              — SpeciesModel: world encoder + predictor + critic
                   dual                — DualSpeciesModel: internal state fed to predictor AND critic
                   internal_critic     — InternalCriticModel: predictor is world-only; only critic
                                         sees internal state
                   internal_predictor  — InternalPredictorModel: predictor sees internal state;
                                         critic is world-only
                 Dual variants require train_dual.parquet / val_dual.parquet in --data dir.
"""

import argparse
import copy
import json
import os
import sys
from pathlib import Path

import torch
from torch.utils.data import DataLoader

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jepa.dataset import TrajectoryDataset
from jepa.model   import (SpeciesModel, DualSpeciesModel, InternalCriticModel,
                          InternalPredictorModel, InternalCriticUnifiedModel, IndividualAdapter)
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
    p.add_argument("--device",  default=None)
    p.add_argument("--variant", default="single",
                   choices=["single", "dual", "internal_critic", "internal_predictor",
                            "unified_critic"])
    p.add_argument("--ema", type=float, default=0.996,
                   help="EMA decay for target encoder (0 = disable EMA, use online encoder)")
    p.add_argument("--resume", default=None,
                   help="Path to a .pt checkpoint to load before training")
    p.add_argument("--freeze-encoder", action="store_true",
                   help="Freeze world encoder weights; train predictor+internal encoder only. "
                        "Use with --resume to run phase-2 fine-tuning on a fixed target.")
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
    print(f"Variant: {args.variant}")

    dual_variant = args.variant in ("dual", "internal_critic", "internal_predictor", "unified_critic")

    if dual_variant:
        if "internal_state_dim" not in stats:
            sys.exit(f"--variant {args.variant} requires internal_state_dim in stats.json. "
                     "Run prepare_dataset.py --dual first.")
        train_file = data_dir / "train_dual.parquet"
        val_file   = data_dir / "val_dual.parquet"
        if not train_file.exists():
            sys.exit(f"train_dual.parquet not found at {train_file}. "
                     "Run prepare_dataset.py --dual first.")
    else:
        train_file = data_dir / "train.parquet"
        val_file   = data_dir / "val.parquet"

    train_ds = TrajectoryDataset(str(train_file), str(stats_path), dual=dual_variant)
    val_ds   = TrajectoryDataset(str(val_file),   str(stats_path), dual=dual_variant)
    train_loader = DataLoader(train_ds, batch_size=args.batch, shuffle=False, drop_last=True)
    val_loader   = DataLoader(val_ds,   batch_size=args.batch, shuffle=False, drop_last=False)

    dual_kwargs = dict(
        input_dim           = stats["input_dim"],
        internal_state_dim  = stats["internal_state_dim"],
        action_dim          = stats["action_dim"],
        latent_dim          = stats["latent_dim"],
        internal_latent_dim = stats["internal_latent_dim"],
        emotion_dim         = stats["emotion_dim"],
        min_arousal         = stats["min_arousal"],
        max_arousal         = stats["max_arousal"],
    ) if dual_variant else {}

    if args.variant == "single":
        model = SpeciesModel(
            input_dim   = stats["input_dim"],
            action_dim  = stats["action_dim"],
            latent_dim  = stats["latent_dim"],
            emotion_dim = stats["emotion_dim"],
            min_arousal = stats["min_arousal"],
            max_arousal = stats["max_arousal"],
        ).to(device)
    elif args.variant == "dual":
        model = DualSpeciesModel(**dual_kwargs).to(device)
    elif args.variant == "internal_critic":
        model = InternalCriticModel(**dual_kwargs).to(device)
    elif args.variant == "unified_critic":
        model = InternalCriticUnifiedModel(**dual_kwargs).to(device)
    else:
        model = InternalPredictorModel(**dual_kwargs).to(device)

    if args.resume:
        ckpt = torch.load(args.resume, map_location=device)
        model.load_state_dict(ckpt)
        print(f"Resumed from {args.resume}")

    if args.freeze_encoder:
        for p in model.encoder.parameters():
            p.requires_grad_(False)
        model.encoder.eval()
        print("Encoder frozen — training predictor + internal encoder only")

    # Target encoder for L_pred.
    # - freeze-encoder mode: use the (already frozen) online encoder directly — target is fixed.
    # - normal mode: use an EMA copy that updates slowly each batch.
    if args.freeze_encoder:
        target_encoder = None  # train_one_epoch falls back to model.encoder (frozen = fixed)
        print("Target encoder: frozen online encoder (fixed target)")
    elif args.ema > 0:
        target_encoder = copy.deepcopy(model.encoder).to(device)
        for p in target_encoder.parameters():
            p.requires_grad_(False)
        target_encoder.eval()
        print(f"Target encoder: EMA copy (decay={args.ema})")
    else:
        target_encoder = None
        print("Target encoder: online encoder (moving target — not recommended)")

    # Only optimise trainable parameters (encoder excluded when frozen).
    trainable = [p for p in model.parameters() if p.requires_grad]
    optimizer = torch.optim.Adam(trainable, lr=args.lr)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer, T_max=args.epochs, eta_min=args.lr * 0.01)

    best_val_pred = float("inf")
    print(f"\nTraining for {args.epochs} epochs …\n")
    print(f"{'Epoch':>6}  {'tr_total':>9}  {'tr_pred':>8}  {'tr_vic':>7}  "
          f"{'tr_crit':>8}  {'vl_total':>9}  {'vl_pred':>8}")
    print("-" * 72)

    action_names = stats.get("action_index_order")

    for epoch in range(1, args.epochs + 1):
        tr = train_one_epoch(model, train_loader, optimizer, live_dims,
                             args.sigreg, args.crit, device,
                             action_names=action_names,
                             target_encoder=target_encoder, ema_decay=args.ema)
        vl = evaluate(model, val_loader, live_dims, args.sigreg, args.crit, device,
                      action_names=action_names, target_encoder=target_encoder)

        print(f"{epoch:>6}  {tr['total']:>9.4f}  {tr['pred']:>8.4f}  "
              f"{tr['sigreg']:>7.4f}  {tr['crit']:>8.4f}  "
              f"{vl['total']:>9.4f}  {vl['pred']:>8.4f}")

        scheduler.step()

        if vl["pred"] < best_val_pred:
            best_val_pred = vl["pred"]
            torch.save(model.state_dict(), ckpt_dir / "best.pt")

    torch.save(model.state_dict(), ckpt_dir / "final.pt")

    import shutil
    shutil.copy(stats_path, ckpt_dir / "stats.json")

    print(f"\nBest val L_pred: {best_val_pred:.4f}")
    print(f"Checkpoints saved to {ckpt_dir}/")


if __name__ == "__main__":
    main()
