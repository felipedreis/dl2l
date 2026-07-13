"""CLI entry point: python3 -m dl2l_analysis --experiment <name>

Loads the experiment's ExperimentAnalysis config (from experiments/<name>.yml
once Phase 3 lands; falls back to letting the experiment module build its own
config if no spec file exists yet), imports analysis.experiments.<module>,
and calls its run(cfg) function.
"""

from __future__ import annotations

import argparse
import importlib
import sys
from pathlib import Path

from . import config as config_mod

# `analysis.experiments.<module>` requires the repo root (parent of
# analysis/) on sys.path. Runner is documented as `python3 -m dl2l_analysis`
# with PYTHONPATH=analysis, which does NOT put the repo root on sys.path —
# so make sure it's there regardless of how this was invoked.
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Run a DL2L experiment analysis")
    parser.add_argument("--experiment", required=True,
                         help="Experiment name, e.g. rotten_fruit_v1")
    parser.add_argument("--module", default=None,
                         help="analysis.experiments.<module> to run "
                              "(default: same as --experiment)")
    args = parser.parse_args(argv)

    module_name = args.module or args.experiment
    full_module = f"analysis.experiments.{module_name}"
    try:
        mod = importlib.import_module(full_module)
    except ModuleNotFoundError as e:
        print(
            f"error: could not import {full_module} "
            f"(analysis/experiments/{module_name}.py) — {e}",
            file=sys.stderr,
        )
        return 1

    if not hasattr(mod, "run"):
        print(f"error: {full_module} has no run(cfg) function", file=sys.stderr)
        return 1

    try:
        cfg = config_mod.from_spec(args.experiment)
    except FileNotFoundError:
        # No experiments/<name>.yml yet (pre-Phase-3) — the experiment module
        # is expected to build its own ExperimentAnalysis internally and
        # ignore the cfg argument in that case.
        cfg = None

    mod.run(cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
