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


def _spec_module(experiment: str) -> str | None:
    """`analysis.module` from experiments/<name>.yml, or None if unset/unreadable.

    Deliberately forgiving: a missing spec, missing key or unparseable YAML just means "fall
    back to the experiment name". The runner should not fail to start over a field that is
    optional by design.
    """
    try:
        import yaml
        spec = _REPO_ROOT / "experiments" / f"{experiment}.yml"
        if not spec.exists():
            return None
        raw = yaml.safe_load(spec.read_text()) or {}
        analysis = raw.get("analysis") or {}
        return analysis.get("module")
    except Exception:
        return None


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Run a DL2L experiment analysis")
    parser.add_argument("--experiment", required=True,
                         help="Experiment name, e.g. rotten_fruit_v1")
    parser.add_argument("--module", default=None,
                         help="analysis.experiments.<module> to run "
                              "(default: same as --experiment)")
    args = parser.parse_args(argv)

    # Precedence: --module, then the spec's `analysis.module`, then the experiment name.
    # Consulting the spec matters because several experiments deliberately share one analysis
    # module — p84_pilot declares `analysis.module: p84_behaviour_parity`, since a sizing pilot
    # and its campaign are the same figures over fewer trials. Deriving the module from the
    # experiment name alone made `-e analyze=true` fail the whole playbook at the very last
    # step, after every trial had already been simulated and extracted.
    module_name = args.module or _spec_module(args.experiment) or args.experiment
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
