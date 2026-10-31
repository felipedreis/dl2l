# Issue #104 — `ArbitrationFilter`: decisiveness, Thompson sampling with constant τ, random floor

Status: **proposed, awaiting approval.**
Context: arbiter story map (`docs/plans/2026-q4-research-plan.md`, step A2: "arbiter with the operant table only");
arbitration paper draft (`docs/bib/uncertainty_arbitration.pdf`) §4.5 (decisiveness, budget, the `decide` pseudocode,
Thompson exploration), §4.4 (what each controller reports), Fig. 1, App. A steps 8–9 (filter, telemetry), App. B.1.
Depends on #99 (focal drive), #102 (`Estimate`, `ValueEstimator`), #103 (`OperantEstimator`), and on #110 (the
operant table is only filled by closed episodes — merged, PR #124).

## Scope

In: `ArbitrationFilter implements ActionFilter` and `ActionSelectionType.ARBITER`. With the filter enabled the chain is
action tendency → target distance → arbiter. Per cycle, for the candidates the pre-filters leave:

1. **Consult controllers cheapest first** (paper §4.5): for each controller affordable at the current efficiency
   (`e ≥ controller.minEfficiency`), `estimate(candidates, H)`; `conf = Φ((Q1 − Q2)/√(SE1² + SE2²))` over the two highest `Q`;
   stop at `conf ≥ θ = 0.8`; otherwise keep the **most decisive controller consulted**.
2. **Choose**: with probability `ε = 0.03` uniformly among the candidates (random floor); otherwise draw
   `N(Q_a, (τ·SE_a)²)` per candidate from the deciding controller and take the argmax. `τ = 1`, constant.
3. **Persist** one `arbitration_decision` row per consultation (Done when: "arbiter decisions persisted").
4. **Feed the episode bootstrap**: the chosen candidate's `Q` goes into `DecisionRecord.chosenQ` (NaN until now), so a
   pre-empted step's bootstrap `B` is no longer always 0 (A0 run of #110: 94% of steps were PREEMPTED with `B = 0`).

Out (by design, per the issue): the novelty bonus (needs episodic `n_eff`, #111+), the efficiency budget beyond the
loop's structure (there is one controller in R1), σ-based SE calibration (#115), neuromodulation (τ constant, a later
ladder step), episodic and planner controllers, realised-value join at episode close.

## Design

Package `br.cefetmg.lsi.l2l.creature.arbiter` for the logic; the filter itself in `creature.actionSelector` next to the others.

- **`ArbiterController(String name, double minEfficiency, ValueEstimator estimator)`** (record). The controller list is
  owned by the arbiter, not by the `ValueEstimator` interface (decision Q4 of #102). R1: one entry,
  `("operant", 0.0, operantStore)`.
- **`Decisiveness`** (static, pure): `conf(Estimate best, Estimate second)` = `Φ((Q1−Q2)/√(SE1²+SE2²))`, with Φ from
  `commons-math3` `NormalDistribution` (already a dependency). Edge cases: fewer than two candidates → `conf = 1` (nothing
  to beat); both `SE = 0` → `1` if `Q1 > Q2` else `0.5` (cannot occur with `OperantEstimator`, whose SE is > 0, but the rule
  must not divide by zero).
- **`ArbitrationFilter`**: constructed with the controller list, a seeded `Random` (stream
  `Seeds.derive(rootSeed, "creature", id.key, "arbiter")`, as the other filters), `θ`, `ε`, `τ` (defaults 0.8, 0.03, 1.0).
  Per-cycle inputs the `ActionFilter` signature does not carry (drive vector H, efficiency) are set before selection
  with `updateCycle(double[] h, double efficiency)`, the same pattern as `WorldModelFilter.updateInternalState` and
  `ActionProbabilityFilter.setModulation`. After selection `takeLastDecision()` returns the record that is persisted
  (the same pattern as `MemoryFilter.takeLastDecision`).
- **Always returns exactly one action**, so `RANDOM` is never reached when the arbiter is in the chain. With one
  candidate it is not consulted at all (`ActionSelection` stops at one candidate), so no row is written for that cycle.
- **Focal drive for the operant cell**: read by the estimator's `Supplier` from `focalDriveRef`, which `FullAppraisal`
  sets in `emotionToRegulate` *before* `selectOne` runs, in the same thread that then makes the blocking facade call, so
  the read is ordered (the deferred race of #110 is not a problem on this path).
- **Settings**: `ARBITER` joins `ActionSelectionType` and `MASTER_FILTER_ORDER` (after `TARGET_DISTANCE`); the A1/A2 run
  uses `enabledFilters = [TARGET_DISTANCE, ARBITER]`. No new flag: the existing `enabledFilters` mechanism is the switch.
  `LearningSettings` rejects `ARBITER` without `operantEstimatorEnabled` (as `operantEstimatorEnabled` already requires
  `focalDriveEnabled`).
- **`FullAppraisal`** builds the filter in `preStart` from `creature.operantStore()`, calls `updateCycle(cycleLevels,
  emotional.behaviouralEfficiency)` before `selectOne`, and puts the chosen `Q` into the `DecisionRecord`. If the
  hysteresis gate then overrides the action, the chosen `Q` no longer describes the executed action, so `chosenQ = NaN`.
- **Persistence**: `ArbitrationDecisionState` → table `arbitration_decision` → Parquet `arbitration_decisions` (same
  four-file pattern as `episode_return` in #110: state class, `TableSchemas`, `scripts/dl2l_data/tables.py`, round-trip
  test). Columns: `creature_key, seq, time_ms, cycle, focal_drive, efficiency, n_candidates, n_consulted,
  deciding_controller, conf, exploration (THOMPSON | RANDOM_FLOOR), chosen_action, chosen_object_type, chosen_q, chosen_se,
  chosen_sigma, runner_up_action, runner_up_q, runner_up_se` (NaN/null when absent). `chosen_*` are the arbiter's pick
  (pre hysteresis gate), joined to `ChosenActionState` by cycle.

## Tests (real implementations, seeded, no stubs)

`Decisiveness` and `ArbitrationFilter` use real `OperantEstimator`s, fed with `record(...)` to put hand-chosen
statistics in the cells.
1. **Decisiveness maths**: gap of one combined SE gives `Φ(1) = 0.8413`; equal Q gives `0.5`; one candidate gives `1`;
   `SE = 0` edge cases.
2. **Stopping rule**: with two controllers (two real estimators, different `minEfficiency`), the first decisive one stops
   the loop (the second is not consulted, `n_consulted = 1`); when the first is indecisive the second is consulted and the
   more decisive of the two decides; a controller whose `minEfficiency` exceeds the efficiency is skipped.
3. **Thompson draw**: seeded reproducibility (same seed, same choices); over many draws with two candidates of known
   `(Q, SE)` the empirical pick frequency matches the analytic `P(draw_a > draw_b)` within a binomial bound; unseen cells
   (all `(0, 1, 1)`) pick near-uniformly; a cell with strong evidence is picked almost always.
4. **Random floor**: with `ε = 1` choices are uniform and labelled `RANDOM_FLOOR`; with `ε = 0` never; at 0.03 the
   frequency is within a binomial bound.
5. **Exploration by uncertainty (the #88 property at the arbiter)**: repeating the better action narrows its SE and its
   pick frequency rises, while a rarely tried action is still sampled.
6. **Settings**: `ARBITER` without `operantEstimatorEnabled` is rejected; the chain built by `FullAppraisal` for
   `[TARGET_DISTANCE, ARBITER]` is action tendency → target distance → arbiter.
7. **Component level (`TestingCreature`)**: with the filter on, a cycle with several candidates writes one
   `ArbitrationDecisionState`, `ChosenActionState` carries `ARBITER`, and `DecisionRecord.chosenQ` is finite.
8. **Persistence**: `ArbitrationDecisionState` round trip and `TableSchemasTest` for the new table.

## Verification

`mvn test`, `mvn package`, `graphify update .`. Then, per CLAUDE.md step 5, a short A0+arbiter run
(`experiments/p104_a2_check.yml`, one condition, 15 min, UI on, same seed 110 as the #110 check):
- arbiter decisions persisted, `exploration` split near the expected 3% floor, `conf` distribution, cells filling;
- **cycle rate against the #110 run** (21.6k closed steps in 15 min with the buffer, 28.5k before the memory-system
  refactor): the blocking `estimate` call per cycle is on the hot path, which #110 never measured;
- pre-empted `bootstrap` no longer all 0.
Not a hypothesis test: A2's survival and regulation claims belong to the campaign (#109).

## Questions (recommendation first)

1. **Controller list with the budget loop now?** Recommend yes: the stopping rule is the issue's main logic and can only be
   tested with more than one controller; the loop is a few lines and #111/#113 add controllers to the same list. The
   alternative (single controller, no loop) leaves the rule untested.
2. **Per-cycle inputs by `updateCycle(...)`** rather than widening `ActionFilter.filter` for every filter. Recommend setter.
3. **Chosen Q into the episode bootstrap now.** Recommend yes (item 4 above); pre-gate Q, NaN when the hysteresis gate
   overrides.
4. **No new flag**: `ARBITER` in `enabledFilters`, requiring `operantEstimatorEnabled`. Recommend.
5. **Is `behaviouralEfficiency` already in [0, 1]?** The paper uses `e = E(A)/10`. `PartialAppraisal` names it
   `normalizedBehaviouralEfficiency`; I will confirm the scale in code before using it as `e`. It does not affect R1
   (operant `minEfficiency = 0`) but it is persisted and used by later controllers.
