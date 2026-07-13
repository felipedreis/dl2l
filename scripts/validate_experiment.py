#!/usr/bin/env python3
"""
Validate an experiments/<name>.yml spec against the schema documented in
experiments/README.md.

Usage:
    python3 scripts/validate_experiment.py experiments/rotten_fruit_v1.yml

Exits non-zero and prints every problem found (not just the first) if the
spec is invalid. Prints "OK" and exits 0 if it passes.
"""

import argparse
import re
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
HEX_COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")

try:
    from dl2l_data.tables import TABLES
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from dl2l_data.tables import TABLES


def validate(spec: dict) -> list:
    """Return a list of human-readable error strings; empty list = valid."""
    errors = []

    for key in ("name", "trials", "conditions", "data_dir"):
        if key not in spec:
            errors.append(f"missing required top-level key: '{key}'")

    if "trials" in spec:
        trials = spec["trials"]
        if not isinstance(trials, int) or isinstance(trials, bool) or trials < 1:
            errors.append(f"'trials' must be a positive int, got {trials!r}")

    conditions = spec.get("conditions")
    if conditions is not None:
        if not isinstance(conditions, list) or len(conditions) == 0:
            errors.append("'conditions' must be a non-empty list")
        else:
            seen_keys = set()
            for i, cond in enumerate(conditions):
                if not isinstance(cond, dict):
                    errors.append(f"conditions[{i}] must be a mapping, got {cond!r}")
                    continue
                for field in ("key", "simulation", "label", "color"):
                    if field not in cond:
                        errors.append(f"conditions[{i}] missing required field '{field}'")

                key = cond.get("key")
                if key is not None:
                    if key in seen_keys:
                        errors.append(f"duplicate condition key: '{key}'")
                    seen_keys.add(key)

                sim = cond.get("simulation")
                if sim is not None:
                    sim_path = REPO_ROOT / sim
                    if not sim_path.exists():
                        errors.append(
                            f"conditions[{i}] ('{key}'): simulation path does not exist: {sim}"
                        )

                color = cond.get("color")
                if color is not None and not HEX_COLOR_RE.match(str(color)):
                    errors.append(
                        f"conditions[{i}] ('{key}'): color '{color}' is not a valid "
                        f"'#RRGGBB' hex string"
                    )

    analysis = spec.get("analysis")
    if analysis is not None:
        if not isinstance(analysis, dict):
            errors.append("'analysis' must be a mapping")
        else:
            module = analysis.get("module")
            if module:
                module_path = REPO_ROOT / "analysis" / "experiments" / f"{module}.py"
                if not module_path.exists():
                    errors.append(
                        f"analysis.module '{module}' has no file at "
                        f"analysis/experiments/{module}.py"
                    )

    extract = spec.get("extract")
    if extract is not None:
        if not isinstance(extract, dict):
            errors.append("'extract' must be a mapping")
        else:
            tables = extract.get("tables")
            if tables is not None and tables != "all":
                if not isinstance(tables, list):
                    errors.append("'extract.tables' must be the string 'all' or a list of table names")
                else:
                    for t in tables:
                        if t not in TABLES:
                            errors.append(
                                f"'extract.tables' entry '{t}' is not a known table "
                                f"(valid: {sorted(TABLES)})"
                            )

    cond_dir = spec.get("cond_dir")
    if cond_dir is not None:
        if not isinstance(cond_dir, dict):
            errors.append("'cond_dir' must be a mapping of condition key -> path")
        else:
            cond_keys = {c.get("key") for c in conditions or [] if isinstance(c, dict)}
            for key in cond_dir:
                if key not in cond_keys:
                    errors.append(
                        f"'cond_dir' references unknown condition key '{key}'"
                    )

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("spec_path", help="Path to an experiments/<name>.yml file")
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
