"""Analysis: p107_pilot_fix — A0 / A1 with the affordance zero-weight fix against the pilot's A0 / A1 / A2 (and the A2 control), paired by seed.

    PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p107_pilot_fix
"""
from __future__ import annotations

from analysis.dl2l_analysis.config import REPO_ROOT, Condition, ExperimentAnalysis
from analysis.experiments import arbiter_ladder as al


def run(cfg) -> None:
    pilot, ctl, fix = (REPO_ROOT / "ml" / d for d in ("data_p107_pilot", "data_p107_pilot_ctl", "data_p107_pilot_fix"))
    conds = [Condition("a0", "A0 (old filter)", "#333333"), Condition("a0f", "A0 fixed", "#7f7f7f"),
             Condition("a1", "A1 (old filter)", "#3b7dd8"), Condition("a1f", "A1 fixed", "#17becf"),
             Condition("a2", "A2 operant arbiter", "#d8733b"), Condition("a2u", "A2 untrained (control)", "#9467bd")]
    merged = ExperimentAnalysis(name="p107_pilot_fix", conditions=conds, trials=3, data_dir=pilot,
                                cond_dir={"a0": pilot, "a1": pilot, "a2": pilot, "a2u": ctl, "a0f": fix, "a1f": fix})
    al.ARMS = ["a0", "a0f", "a1", "a1f", "a2", "a2u"]
    al.PAIRS = [("a0f", "a0"), ("a1f", "a1"), ("a1f", "a0f"), ("a2", "a1f"), ("a2", "a0f")]
    al.run(merged)
