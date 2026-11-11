# Issue #115 — Calibration, measured only

Status: **plan, 2026-11-11.** Paper §4.4; recipe §4 (rescale **off**), §5.

The SE rescale (RMS of recent z, clipped to [0.5, 3], recomputed in sleep) is **not implemented**: the recipe fixes it off for A2, A3 and
A5 and evaluates it as a separate step after A3/A5. Calibration is measured from data the runs already write:

- **Operant, per update** (recipe §5): `z = (sample - Q) / sqrt(SE^2 + sigma^2)` with the cell's estimate *before* the update (previous row
  of the same (object type, action) in `operant_updates`, or the prior). Statistic per trial: variance of z per cell with >= 30 updates and
  non-constant samples, averaged weighted by update count; constant-outcome cells reported apart with the pooled variance.
- **Episodic, per closed episode**: for each decision whose deciding controller was EPISODIC, `z = (V_realised - Q) / sqrt(SE^2 + sigma^2)`, from
  `arbitration_decisions` (`executed_q/se/sigma`, `realised_value` joined from `episode_return`). Statistic per trial: mean and variance of z.
No Java change is expected beyond #111-#114's columns; the plan verifies the join carries the deciding controller and the realised value.
Deliverable: `analysis/experiments/arbiter_ladder.py` (calibration section for both controllers, with tests in `analysis/diagnostics/test_arbiter_ladder.py`).
