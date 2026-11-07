"""Analysis: p107_pilot_ctl — the pilot's A0 / A1 / A2 plus the untrained-table control (A2u), paired by seed.

Reuses arbiter_ladder with a fourth arm whose data lives in ml/data_p107_pilot_ctl; the other three come from ml/data_p107_pilot.

    PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p107_pilot_ctl
"""
from __future__ import annotations

from analysis.dl2l_analysis.config import REPO_ROOT, Condition, ExperimentAnalysis
from analysis.experiments import arbiter_ladder as al


def run(cfg) -> None:
    pilot = REPO_ROOT / "ml" / "data_p107_pilot"
    ctl = REPO_ROOT / "ml" / "data_p107_pilot_ctl"
    conds = [Condition("a0", "A0 baseline", "#333333"), Condition("a1", "A1 focus tracker", "#3b7dd8"),
             Condition("a2", "A2 operant arbiter", "#d8733b"), Condition("a2u", "A2 untrained table (control)", "#9467bd")]
    merged = ExperimentAnalysis(name="p107_pilot_ctl", conditions=conds, trials=3, data_dir=pilot,
                                cond_dir={"a0": pilot, "a1": pilot, "a2": pilot, "a2u": ctl})
    al.ARMS = ["a0", "a1", "a2", "a2u"]
    al.PAIRS = [("a1", "a0"), ("a2", "a1"), ("a2", "a2u"), ("a2u", "a1"), ("a2", "a0")]
    al.run(merged)
