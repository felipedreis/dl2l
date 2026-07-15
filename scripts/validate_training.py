#!/usr/bin/env python3
"""
Validate a training/<name>.yml spec against the schema documented in
training/README.md.

Usage:
    python3 scripts/validate_training.py training/p9_variants.yml

Exits non-zero and prints every problem found (not just the first) if the
spec is invalid. Prints "OK" and exits 0 if it passes.
"""

import argparse
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent

VALID_VARIANTS = {"single", "dual", "internal_critic", "internal_predictor", "unified_critic"}
VALID_DEVICES = {"cpu", "cuda", "mps"}
VALID_HYPERPARAM_KEYS = {"batch", "lr", "sigreg", "crit", "ema", "freeze_encoder"}


def validate(spec: dict) -> list:
    """Return a list of human-readable error strings; empty list = valid."""
    errors = []

    for key in ("name", "source_experiment"):
        if key not in spec:
            errors.append(f"missing required top-level key: '{key}'")

    source_experiment = spec.get("source_experiment")
    if source_experiment is not None:
        if not isinstance(source_experiment, str):
            errors.append("'source_experiment' must be a string")
        else:
            exp_path = REPO_ROOT / "experiments" / f"{source_experiment}.yml"
            if not exp_path.exists():
                errors.append(
                    f"source_experiment '{source_experiment}' has no file at "
                    f"experiments/{source_experiment}.yml"
                )

    variants = spec.get("variants")
    if variants is not None:
        if not isinstance(variants, list) or len(variants) == 0:
            errors.append("'variants' must be a non-empty list")
        else:
            for v in variants:
                if v not in VALID_VARIANTS:
                    errors.append(
                        f"'variants' entry '{v}' is not a known variant "
                        f"(valid: {sorted(VALID_VARIANTS)})"
                    )

    if "epochs" in spec:
        epochs = spec["epochs"]
        if not isinstance(epochs, int) or isinstance(epochs, bool) or epochs < 1:
            errors.append(f"'epochs' must be a positive int, got {epochs!r}")

    device = spec.get("device")
    if device is not None and device not in VALID_DEVICES:
        errors.append(f"'device' must be one of {sorted(VALID_DEVICES)}, got {device!r}")

    hyperparams = spec.get("hyperparams")
    if hyperparams is not None:
        if not isinstance(hyperparams, dict):
            errors.append("'hyperparams' must be a mapping")
        else:
            for k, v in hyperparams.items():
                if k not in VALID_HYPERPARAM_KEYS:
                    errors.append(
                        f"'hyperparams' entry '{k}' is not a known key "
                        f"(valid: {sorted(VALID_HYPERPARAM_KEYS)})"
                    )
                elif k == "freeze_encoder":
                    if not isinstance(v, bool):
                        errors.append("'hyperparams.freeze_encoder' must be a bool")
                elif k == "batch":
                    if isinstance(v, bool) or not isinstance(v, int):
                        errors.append(f"'hyperparams.{k}' must be an int")
                elif k in ("lr", "sigreg", "crit", "ema"):
                    if isinstance(v, bool) or not isinstance(v, (int, float)):
                        errors.append(f"'hyperparams.{k}' must be a number")

    for key in ("prepared_dir", "ckpt_dir"):
        if key in spec and not isinstance(spec[key], str):
            errors.append(f"'{key}' must be a string path")

    upload = spec.get("upload")
    if upload is not None:
        if not isinstance(upload, dict):
            errors.append("'upload' must be a mapping")
        else:
            if "enabled" in upload and not isinstance(upload["enabled"], bool):
                errors.append("'upload.enabled' must be a bool")
            for k in ("repo", "data_repo", "data_prefix"):
                if k in upload and not isinstance(upload[k], str):
                    errors.append(f"'upload.{k}' must be a string")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("spec_path", help="Path to a training/<name>.yml file")
    args = parser.parse_args()

    spec_path = Path(args.spec_path)
    if not spec_path.exists():
        print(f"FAIL: spec file not found: {spec_path}", file=sys.stderr)
        return 1

    with open(spec_path) as f:
        spec = yaml.safe_load(f)

    if not isinstance(spec, dict):
        print(f"FAIL: {spec_path} did not parse to a mapping", file=sys.stderr)
        return 1

    errors = validate(spec)

    if errors:
        print(f"FAIL: {spec_path} — {len(errors)} problem(s):", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print(f"OK: {spec_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
