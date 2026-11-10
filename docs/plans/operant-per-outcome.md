# Plan: the A2 operant table learns per outcome

Status: implementing, 2026-11-10. Design decided in `docs/plans/arbiter-proposal-review.md` (sections 2, 3, 4, 7; D1-D5).
Branch `feature/operant-per-outcome`. Java change: goes through a PR (not opened by this work).

## What changes

The A2 arm's operant estimator (`OperantEstimator`, behind the `OperantStore` TypedActor facade) stops being an episode learner
(`OperantReturnSink`, #110) and becomes a basic per-outcome learner like the legacy `OperantConditioning` table, while it still answers
the arbiter with `Estimate(Q, SE, sigma)`.

- **Key (D1):** `(object type, action type)`. The focal-drive and distance-bin dimensions are removed. An action with no object uses a
  single `NONE` bucket (never trained, see D2). `estimate(candidates, h, focalDrive)` keeps its signature but ignores `h` and `focalDrive`.
- **Feeding (D2):** `Valuation.recordOperant`, called once per `EvaluationStimulus` after `evaluateLegacy` / `evaluateWithExpectancy`, so one
  place serves both paths and the legacy `varyProbability` calls are untouched. Same filter as `OperantConditioningActor`: the target is a
  `FruitType` or `PlantType`. Outcomes on the creature itself (`Self`: cholinergic/SLEEP, tedium WANDER/OBSERVE, deprivation RPE) are left out.
- **Sample (D3):** the magnitude `-arousalVariation` (positive = the drive went down), on both paths.
- **Statistics (D4):** unchanged `WelfordTable` form and constants (`Q0 = 0`, `kappa = 1`, prior sigma 1 until n >= 3 via the blend, decay 0.995).
- **Legacy (D5):** `OperantConditioning` and `ActionProbabilityFilter` unchanged (A0/A1 parity).
- **Episodes:** `FocusTracker`, `EpisodeBuffer`, `ReturnSink` and `episode_returns` persistence stay (for episodic memory, #111/#113). The operant
  table is detached from the close-out. `OperantReturnSink` and its test are deleted: nothing else used it, and `ReturnSink` stays as the
  extension point for the engram writer.
- **Settings:** `LearningSettings` no longer requires `focalDriveEnabled` for `operantEstimatorEnabled` (its only reason, "fed by episodes", is
  gone). `ARBITER` still requires the estimator. No simulation conf or experiment spec is changed.
- **Interfaces:** `ValueLearner.record(WorldObjectType target, ActionType action, double value)` now returns an `OperantUpdate(n, Estimate)`
  (the cell after the update; a non-void TypedActor call, blocking like `estimate`), so `Valuation` can log the learning without a second call.
  `OperantEstimator` loses its distance-edge constructor argument.

## Telemetry

New Arrow table `operant_update_state` (`OperantUpdateState`, registered last in `TableSchemas`), extracted as `operant_updates`
(`scripts/dl2l_data/tables.py`, optional raw view in `extract.py` so older dumps still extract). Columns: `creature_key, seq, time_ms, cycle,
object_type, action, sample, n, q, se, sigma` (the cell after the update; `cycle` is the memory system's decision cycle).
One row per recorded outcome, persisted from `Valuation` with its other states.

## Tests (deterministic)

- `OperantEstimatorTest`: Welford/decay tests kept; the key has no drive and no distance (same cell across distances and focal drives); record by
  `(target, action)`; distance-edge tests removed.
- `ValuationOperantTest` (new): a nutritive EAT outcome on a fruit records `-arousalVariation` into the `(fruit, EAT)` cell on the legacy and the
  expectancy path, and writes an `operant_update_state` row; an outcome whose target is `Self` is ignored; a plant target is kept.
- `ValuationEpisodeTest`: closed episodes still write `episode_return` rows but no longer move the operant table.
- `LearningSettingsTest`: `operantEstimatorEnabled` is accepted without `focalDriveEnabled`.
- `TableSchemasTest`, `OperantUpdateRoundTripTest` and `ArrowExtractGoldenTest`: the new table.

## Out of scope

Paper edits; re-piloting A2/A2u (CCAD); the `realised_value` of the decision table (still joined from episodes); revisiting the focus
thresholds 0.4 / 0.15; running any simulation.
