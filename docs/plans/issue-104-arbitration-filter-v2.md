# Issue #104 — `ArbitrationFilter`: decisiveness, Thompson sampling with constant τ, random floor

Status: **draft, awaiting approval** (open questions Q1–Q10 below each carry a recommendation).
Decided by the user: **Q1 = explicit focal-drive argument.** The `AtomicReference<String>` shared between `FullAppraisal`
(writer) and the operant estimator facade (reader) is a flaw: actors do not share state. `ValueEstimator.estimate` takes the
focal drive as an argument, and `focalDriveRef` is removed from `CreatureActor`, `FullAppraisal`, `TestingCreature` and
`OperantEstimator`. (`wmFilterRef` is a separate, older bridge and is not touched here.)
Context: arbiter story map (`docs/plans/2026-q4-research-plan.md`), step **A2** ("arbiter with the operant table only,
drive-reduction value V(K|H)"); arbitration paper draft (`docs/bib/uncertainty_arbitration.pdf`) §4.5 (decisiveness,
budget, `decide()` pseudocode pp. 11–12, Thompson sampling, ε floor), §4.4 (what each controller reports), App. A step 8
(this issue) and step 9 (telemetry, which is #105), App. B.1 (risks).
Depends on #99 (focal drive), #102 (`Estimate`, `ValueEstimator`), #103 (`OperantEstimator`), #110 (episode buffer behind
`MemorySystem`, `OperantStore` facade, `DecisionRecord.chosenQ = NaN` placeholder) — all merged.
Unblocks #105 (ArbitrationDecisionState table), #109 (A2 campaign), #114 (budget + novelty), #115 (calibration), #116 (fixed order).

## Scope

**In**

- A pure decision core, `Arbiter` (package `creature.arbiter`, no actor, no I/O): consults an ordered list of controllers
  cheapest first, computes decisiveness `conf = Φ((Q1 − Q2) / √(SE1² + SE2²))`, stops at the first controller with
  `conf ≥ θ` (θ = 0.8), otherwise keeps the most decisive one consulted; then with probability ε = 0.03 picks a candidate
  uniformly, else draws `N(Q, (τ·SE)²)` per candidate (τ = 1, constant) and takes the argmax.
- An immutable `ArbitrationDecision` record describing what happened (consulted controllers and their conf, deciding
  controller, chosen and runner-up estimates, exploration source). Built now so #105 is plumbing only; **not persisted
  as its own table here** (see Q6).
- `ArbitrationFilter implements ActionFilter` (package `creature.actionSelector`): thin adapter that holds the per-cycle
  inputs (h, focal drive), calls the `Arbiter`, returns exactly one action, and exposes `takeLastDecision()` (same
  pattern as `MemoryFilter`).
- `ActionSelectionType.ARBITER` (appended to the enum; the column is written by name, so existing data is unaffected).
- `LearningSettings`: `ARBITER` accepted in `enabledFilters`, with validation (requires `operantEstimatorEnabled`, which
  already requires `focalDriveEnabled`; excludes `MEMORY`, `AFFORDANCE`, `WORLD_MODEL`, `RANDOM`). With it on, the chain
  is **action tendency (own flag) → TARGET_DISTANCE → ARBITER**.
- `FullAppraisal` wiring: construct the filter in `preStart`, feed it `h` and the focal drive each cycle, and fill
  `DecisionRecord.chosenQ` with a real value (no longer `NaN`) when the arbiter is on.
- Read path to the operant table: the focal drive is passed **explicitly** into the estimate call instead of through the
  `AtomicReference<String>` supplier (Q1). This is the "focal-drive ref race" that #110 left out of scope for #104.
- A verification run spec `experiments/p104_a2_check.yml` + `simulations/p104_a2_check.conf`.

**Out (by design)**

- Efficiency budget (`e = E(A)/10`, tiers at 0.3 / 0.7) and novelty bonus `0.1/√(1 + n_eff)`: #114. In R1 there is
  only one controller, so the budget can never gate anything.
- τ coupled to tonic dopamine: no issue in the story map owns it yet (§4.5 `tau(dopamine)`); here τ is a constant
  constructor parameter. See Q7.
- Episodic and planner controllers: #113 and later. The `Arbiter` accepts a list, but production wires one.
- Per-decision telemetry table (`ArbitrationDecisionState`): #105. SE calibration: #115. Fixed-order mode: #116.
- The A2 campaign itself (pre-registered, multi-trial): #107/#109. The run here is a smoke check, not a hypothesis test.
- Changing who owns the operant table (it stays behind the `OperantStore` TypedActor facade from #110; see Q4).
- The legacy chain (`MemoryFilter`, `ActionProbabilityFilter`, `WorldModelFilter`, `RandomFilter`) is untouched. With
  `ARBITER` off, behaviour is bit-for-bit what it is today.

## What exists today (verified in code)

- `ActionSelection.selectOne` runs filters in order and **stops as soon as a filter leaves one candidate**, returning
  `filtered.get(0)`; `getLastUsedFilterType()` is the type persisted in `ChosenActionState.actionselectiontype`.
  So a filter that returns an empty list crashes the cycle, and a filter placed after one that leaves a single
  candidate is never called.
- `FullAppraisal.preStart` builds the chain from `LearningSettings.MASTER_FILTER_ORDER` filtered by `isFilterEnabled`,
  with `ActionTendencyFilter` prepended by its own flag. `MASTER_FILTER_ORDER` is **also the default `enabledFilters`**
  (`new LearningSettings()` and a config with no `enabledFilters`). Each random stream gets
  `new Random(Seeds.derive(rootSeed, "creature", id.key, "<stream>"))` (#95).
- Per-cycle filter inputs are pushed by `FullAppraisal.selectAction` before `selectOne`
  (`worldModelFilter.updateInternalState`, `affordanceFilter.setModulation`, `memoryFilter.setModulation`).
- `emotionToRegulate` (called as the argument to `selectOne`) runs the `FocusTracker`, fills `cycleLevels`
  (drive levels in `FocusTracker.DRIVES` order) and `focalDrive`, and publishes `focalDriveRef`. With no open episode
  it passes the most-aroused emotion to the filters but leaves `focalDrive = null`.
- Cycle order in `onEmotionalStimulus`: `selectAction` → `updateMemory` → `dispatchTediumStimulus` →
  **`enforceHysteresisGate`** (can replace the chosen action with SLEEP while `dwellTicks < MIN_SLEEP_TICKS`) →
  `sendArbiterMessages(action)` (post-gate action; `DecisionRecord(..., chosenQ = Double.NaN)`) → `updateSleepState`
  → effector → `persistDecision` (`ChosenActionState` with the last filter's type).
- `EpisodeBuffer.bootstrapAt(cutCycle)` uses the chosen Q of the decision at a pre-emption cut, treating `NaN` as 0.
  So today every pre-empted close bootstraps with 0; #104 is what makes the bootstrap real.
- `OperantEstimator.estimate(candidates, h)` reads the focal drive from a `Supplier<String>` that in production is
  `focalDriveRef::get`, an `AtomicReference` written by `FullAppraisal` and read on the estimator facade's thread.
  `h` is unused. Unseen cell → `Estimate(0, 1, 1)`. The σ blend keeps SE strictly positive.
- `creature.operantStore()` is a TypedActor proxy; `estimate` returns a value, so it is a **blocking ask** (Akka's
  `akka.actor.typed.timeout`, default 5 s, not overridden in `application.conf`). `record` is `void` (async tell),
  called by `Valuation` when `ClosedEpisodes` arrive. `TestingCreature` uses a plain in-process `OperantEstimator`.
- `ChosenActionState.actionselectiontype` is written as `enum.name()` and extracted as `selection_type` in the
  `actions` Parquet table; nothing iterates `ActionSelectionType.values()`. Adding a constant is safe.
- `commons-math3` is already a dependency (`org.apache.commons.math3.special.Erf`) for Φ.

## Design

### 1. Pure core — `creature/arbiter/Arbiter.java`

```java
public final class Arbiter {
    public enum ControllerType { OPERANT, EPISODIC, PLANNER }          // order = cost; only OPERANT wired in R1
    public record Controller(ControllerType type, ValueEstimator estimator) { }
    public record Params(double theta, double epsilon, double tau) {     // defaults(0.8, 0.03, 1.0)
        // theta in [0.5, 1]; epsilon in [0, 1]; tau finite and >= 0
    }
    public Arbiter(List<Controller> cheapestFirst, Params params, Random random);   // >= 1 controller
    public ArbitrationDecision decide(List<Action> candidates, double[] h, String focalDrive);
    static double decisiveness(List<Estimate> estimates);              // package-visible, pure
}
```

`decide` follows the paper's pseudocode (pp. 11–12), with every edge pinned down:

1. **Consult**, cheapest first. For each controller: `est = estimator.estimate(List.copyOf(candidates), h.clone(), focalDrive)`;
   validate size == candidates and no nulls (else `IllegalStateException`, a bug, not a fallback);
   `conf = decisiveness(est)`; if `conf > bestConf` keep it (strict `>`: on a conf tie the **cheaper** controller wins,
   as in the pseudocode with `best_conf = 0.0`); if `conf >= theta` stop.
2. **Decisiveness** over the deciding candidate list:
   - Q1, Q2 = the two largest Q (by value, not position); SE1, SE2 = the SEs **of those two candidates**. On equal Q the
     earlier candidate is "1" (affects only which SE is SE1; the formula is symmetric).
   - one candidate → `conf = 1` (nothing to tell apart);
   - `Q1 == Q2` → `0.5`;
   - `SE1 = SE2 = 0` and `Q1 > Q2` → `1`; otherwise `Φ(x) = 0.5·erfc(−x/√2)`.
   - Result is always in `[0.5, 1]`.
3. **Floor**: one `random.nextDouble()` **always** drawn; if `< epsilon`, `chosen = candidates.get(random.nextInt(n))`,
   source `EPSILON`.
4. **Thompson**: otherwise one `random.nextGaussian()` per candidate, in candidate order (always drawn, even when SE = 0,
   so the stream's consumption depends only on n): `draw_i = Q_i + tau·SE_i·z_i`; `chosen = argmax`, ties → lowest index.
   Source `GREEDY` if the chosen candidate is also the argmax of Q (ties → lowest index), else `THOMPSON`. (This is the
   "greedy / Thompson / ε" split #105 asks to record; defining it here keeps #105 a pure persistence change.)
5. Estimates of the **deciding** controller are the ones drawn from and reported.

Single candidate: `decide` returns it with source `SINGLE` and consumes **no** random numbers (but still consults, so its
Q is known; see §4).

Empty candidate list: `IllegalArgumentException`. `ActionSelection` would crash on it anyway; the candidate generator
always yields at least WANDER on the self-perception (`PartialAppraisal` falls back to `Self`), so this is unreachable
in production and an explicit error is better than an `IndexOutOfBounds` two frames up.

### 2. `creature/arbiter/ArbitrationDecision.java`

Immutable record: `candidateCount`, `List<Consultation>` (`ControllerType`, `conf`), `decidingController`,
`chosenIndex`, `chosen` (`Action`), `chosenEstimate`, `runnerUpEstimate` (second-highest Q of the deciding controller,
null when n = 1), `ExplorationSource` (`SINGLE | GREEDY | THOMPSON | EPSILON`). Lists copied in the compact constructor.
Not persisted here; `toString` used in a `fine` log line.

### 3. `actionSelector/ArbitrationFilter.java`

```java
public class ArbitrationFilter implements ActionFilter {
    public ArbitrationFilter(Arbiter arbiter);
    public void setCycleInput(double[] h, String focalDrive);   // per cycle, before selectOne (cf. updateInternalState)
    public List<Action> filter(List<Action> actions, Emotion toRegulate);   // List.of(decision.chosen())
    public Optional<ArbitrationDecision> takeLastDecision();    // clears, like MemoryFilter
    public Estimate valueOf(Action executed);                   // one consult of the cheapest controller, for chosenQ
    public ActionSelectionType getFilterType();                 // ARBITER
}
```

`toRegulate` is ignored: the arbiter's "emotion" is the focal drive set by `setCycleInput`, which is what keys the operant
cells. `filter` throws `IllegalStateException` if `setCycleInput` was not called this cycle (h is reset to null after
each `filter`), which catches wiring mistakes in tests instead of silently estimating against stale levels.

### 4. `FullAppraisal` wiring

- `preStart`: if `isFilterEnabled(ARBITER)`, after the `MASTER_FILTER_ORDER` loop append
  `arbitrationFilter = new ArbitrationFilter(new Arbiter(List.of(new Controller(OPERANT, creature.operantStore())),
  Params.defaults(), new Random(Seeds.derive(rootSeed, "creature", id.key, "arbiter"))))`.
  `ARBITER` is **not** added to `MASTER_FILTER_ORDER`, because that list is also the default `enabledFilters` and
  adding it would make `new LearningSettings()` invalid (it would combine ARBITER with every legacy filter and with
  `operantEstimatorEnabled = false`). The arbiter is terminal by construction: appended last.
- `selectAction`: compute `toRegulate = emotionToRegulate(emotional)` **before** pushing inputs (today it is evaluated
  inline as the `selectOne` argument, after the filter inputs are set), then
  `arbitrationFilter.setCycleInput(cycleLevels, focalDrive)`, then `selectOne(possibleActions, toRegulate)`.
- `chosenQ`: after the gate, in `sendArbiterMessages(action)`:
  - if the arbiter decided this cycle and `action` is the decision's chosen candidate (`Action.equals`: type + perception
    id) → `decision.chosenEstimate().q()`;
  - else if the action is among the decision's candidates (gate replaced it with a SLEEP that was a candidate) → that
    candidate's Q from the deciding controller (kept in the decision; one array lookup, no extra call);
  - else (chain ended before the arbiter with one candidate, or the gate's SLEEP was not a candidate) →
    `arbitrationFilter.valueOf(action).q()`: one estimate call for one candidate.
  So `chosenQ` is always "the deciding (or cheapest) controller's Q of the action actually executed", which is what the
  pre-emption bootstrap needs, and the arbiter costs at most one blocking ask per cycle except on gate-overridden
  cycles whose SLEEP was filtered out by the action tendency (two asks).
- `ChosenActionState` keeps the existing semantics: type = last filter that ran. A single-candidate cycle is still
  labelled `ACTION_TENDENCY`/`TARGET_DISTANCE` (truthful: the arbiter did not choose). Gate overrides keep the
  existing mislabel (see Q8).
- Flag off: `arbitrationFilter == null`, nothing above runs, `chosenQ` stays `NaN`. The arbiter's random stream is a
  separate derived seed, so other streams' draws are unchanged even with the flag on.

### 5. Explicit focal drive on the read path (Q1)

`ValueEstimator.estimate(List<Action> candidates, double[] h)` becomes
`estimate(List<Action> candidates, double[] h, String focalDrive)` (null = no focus). `OperantEstimator` drops the
`Supplier<String>` constructor parameter and keys by the argument. `CreatureActor`, `TestingCreature` and `FullAppraisal`
drop `focalDriveRef` (its only reader was the supplier). `OperantEstimatorTest` is updated to pass the drive. Rationale:
the `AtomicReference` is a mutable object shared between two actors (against the project's Akka rule), it makes the
facade's answer depend on a side channel rather than its message, and #113's episodic estimator will need the focal
drive too (§4.2: the focal drive's precision weight is raised in retrieval).

### 6. Settings

`LearningSettings` constructor validation (thrown at parse like the existing `operantEstimatorEnabled` check):
- `ARBITER` ⇒ `operantEstimatorEnabled` (⇒ `focalDriveEnabled`).
- `ARBITER` ⇒ none of `MEMORY`, `AFFORDANCE`, `WORLD_MODEL`, `RANDOM` (the arbiter replaces them; §4 "replaces the
  memory, operant and world-model filters"; RANDOM's role is the ε floor).
- `ARBITER` ⇒ not `neuromodulationEnabled` (τ is constant; see Q7).
No new boolean flag; `Simulation.getLearningSettings` already parses `enabledFilters` by `valueOf`.

### 7. Concurrency and immutability

- `Arbiter`, `ArbitrationFilter` and their `Random` are `FullAppraisal`'s own state, created in `preStart`, never shared.
- What crosses the facade: `List.copyOf(candidates)`, `h.clone()`, a `String`; what comes back: an unmodifiable list of
  immutable `Estimate` records. `Action`/`Perception` have final fields.
- The blocking ask is the same pattern and count the legacy `ActionProbabilityFilter` pays via `getProbabilities`
  (which is excluded when ARBITER is on). A timeout propagates as an exception, exactly as a legacy
  `getProbabilities` timeout does today (Q4).

## Ambiguities and tensions, and how they are resolved

| # | Where | Issue | Resolution |
|---|---|---|---|
| A1 | Issue text, ActionSelection | "Stopping rule" is behaviourally inert in R1: one controller is always consulted and always decides. | `Arbiter` takes a list now; the stopping-rule tests use two real `OperantEstimator`s with different training. Production wires one. |
| A2 | §4.5 formula | Undefined for one candidate, Q1 = Q2, or SE1 = SE2 = 0. | conf = 1, 0.5, and 1 (if Q1 > Q2) respectively; always in [0.5, 1]. |
| A3 | Pseudocode `best_conf = 0.0`, `conf > best_conf` | Tie between controllers' conf. | Strict `>`: cheaper wins. |
| A4 | §4.5 decisiveness | Uses only the two highest Q: a third candidate with a huge SE (possibly the true best) does not lower conf. | Kept as written (paper's definition); Thompson still samples it. Note for #115. |
| A5 | `ActionSelection` | Stops before the arbiter whenever a pre-filter leaves one candidate (frequent: hungry with an empty visual field → WANDER(Self) only), so the arbiter is not called and `chosenQ` would stay NaN. | `chosenQ` comes from `valueOf(executed)` on those cycles; `ChosenActionState` keeps the truthful label. `ActionSelection` unchanged. |
| A6 | Hysteresis gate | The gate can replace the arbiter's choice with SLEEP *after* selection; `DecisionRecord.action` is post-gate. A `chosenQ` of the arbiter's pre-gate choice would mismatch the executed action. | `chosenQ` is the Q of the **executed** action (§4). The gate is untouched. |
| A7 | Candidates are (action, target) pairs | Target-agnostic actions (SLEEP, WANDER, OBSERVE) appear once per visible object type, so the ε floor and Thompson's max over more draws favour them when many types are in view. The legacy `RandomFilter` has the same bias. | Kept (paper §4: candidates are the pairs; #103 keys SLEEP by object type on purpose). `candidateCount` recorded for #105; revisit if A2 shows SLEEP/WANDER inflation (Q5). |
| A8 | Focus vs. operant key | With no open episode, `focalDrive = null` → the `NONE` focal bucket, which is never trained (only closed episodes write the table). Calm-state choice is then Thompson over `N(0, 1)` for every candidate: effectively uniform. Meanwhile the tendency filter still sees the most-aroused emotion. | Accepted for #104 (the paper's idle state has no habit to consult). Measured in the check run (share of decisions with null focus). See Q3. |
| A9 | Determinism | The issue asks for seeded tests. A full run cannot be replayed bit-for-bit anyway: the table the arbiter reads is written asynchronously by `Valuation`. | One derived stream `"arbiter"`; fixed consumption pattern (1 uniform per decision with n ≥ 2, then 1 int or n gaussians). Unit tests are exact; the run is seed-reproducible only up to actor interleaving (same as every other filter since #95). |
| A10 | ε placement | Pseudocode consults controllers before the ε check, so estimates are paid for even on ε cycles. | Kept: the estimates are needed for `chosenQ` and #105 anyway. |
| A11 | `ValueEstimator` contract | No focal-drive argument, yet the operant key includes it; production bridges it through a shared `AtomicReference`. | Explicit argument (§5, Q1). |
| A12 | τ(dopamine) | §4.5 and App. A step 8 route tonic dopamine to τ; the issue says constant τ "because neuromodulation is off". No issue owns the coupling. | τ = 1 constant; ARBITER + neuromodulation rejected at parse (Q7). |
| A13 | Issue DoD "arbiter decisions persisted" | #105 owns the decision table. | DoD met by `chosen_action_state.actionselectiontype = 'ARBITER'` rows (→ `actions.selection_type`) plus non-NaN `chosenQ` reaching `episode_return.bootstrap`. Q6. |

## Tests

All tests use real implementations: real `OperantEstimator` instances trained through `record`, the real `FullAppraisal`
in `TestingHarness`/`TestingCreature`, the real `MemorySystemActor` + `EpisodeBuffer` + `Valuation`. No stub of
`ValueEstimator` or `MemorySystem`.

### `ArbiterTest` (pure core)

Helpers: `trained(Map<ActionType, double[] values>)` builds an `OperantEstimator` (decay 1) and records each value; one
candidate per action type on fixed perceptions.

1. **Decisiveness, hand-computed:** Q = (1, 0), SE = (1, 1) → Φ(1/√2) = 0.76025 (1e-5); three candidates where the
   highest-SE one is third → uses only the top two's SEs; candidate order irrelevant (shuffled list gives the same conf);
   Q1 = Q2 → 0.5; one candidate → 1; SE = 0 both with a gap → 1 (via `Estimate`s built directly — `decisiveness` is
   pure over `List<Estimate>`, no estimator involved).
2. **Stopping rule (two real controllers):** A = estimator with a clear winner (20× EAT = 2.0, 20× AVOID = 0 →
   conf ≈ 1), B = untrained. Order (A, B) → consultations = [A], deciding = A. Order (B, A) → consultations = [B, A],
   deciding = A. Both untrained → consultations = [B, A], deciding = B (conf tie 0.5, cheaper wins). θ boundary: a
   `Params` with θ equal to A's computed conf stops at A (`>=`).
3. **Most decisive when none reaches θ:** A conf ≈ 0.6, B conf ≈ 0.7 (moderate training) with θ = 0.8 → both
   consulted, deciding = B; drawn estimates are B's.
4. **Thompson draw, seeded and exact:** seed s, ε = 0.03: replicate `new Random(s)` in the test (`nextDouble()` then
   `nextGaussian()` per candidate), compute `argmax(Q + τ·SE·z)` by hand from the estimator's own estimates, assert
   the same candidate and source. Repeat for a seed whose first uniform is `< 0.03` (found by scanning seeds in the
   test) → `nextInt(n)` candidate, source EPSILON.
5. **Same seed, same sequence:** two `Arbiter`s with equal seeds over 1,000 decisions → identical choices.
6. **Distribution:** two candidates, Q gap d, equal SE s, ε = 0, τ = 1 → P(first) = Φ(d/(s√2)); 20,000 draws within
   4 binomial SEs. τ = 0 → always the higher Q, source GREEDY. ε = 1 → each of 4 candidates ≈ 1/4 (4 SE bounds).
   Default ε → EPSILON share ≈ 0.03 (4 SE bounds).
7. **Single candidate:** returned, source SINGLE, and the `Random` is untouched (a parallel `Random(s)` produces the
   same next value as the arbiter's after the call — checked by constructing the arbiter with a `Random` the test
   also holds).
8. **Validation:** empty candidates → IAE; `Params` out of range → IAE; zero controllers → IAE.

### `ArbitrationFilterTest`

9. `filter` returns a one-element list with the decision's chosen action; `takeLastDecision` returns it once, then
   empty; `filter` without `setCycleInput` → `IllegalStateException`; the focal drive passed is the cell used (train
   EAT under "hunger" only → under "hunger" EAT wins with conf ≈ 1, under "sleep" conf = 0.5).
10. `valueOf(action)` equals the cheapest controller's estimate for `[action]`.

### `OperantEstimatorTest` (updated for §5)

11. Existing cases pass the drive explicitly; new case: same candidates under two drives read different cells; `h`
    and the candidate list are not mutated.

### `LearningSettingsTest`

12. `ARBITER` without `operantEstimatorEnabled` → IAE; with any of MEMORY / AFFORDANCE / WORLD_MODEL / RANDOM → IAE;
    with `neuromodulationEnabled` → IAE; valid HOCON parses (`enabledFilters = [TARGET_DISTANCE, ARBITER]`);
    `new LearningSettings()` still valid and does not enable ARBITER; `simulations/p104_a2_check.conf` parses to A0 +
    focal + operant + `[TARGET_DISTANCE, ARBITER]` (same shape as the existing A0 conf test).

### `ArbitrationFunctionalTest` (harness, real creature)

13. **Chain and label:** A2 settings, seeded; a hungry creature seeing a red apple at distance (pattern of
    `TestingCreatureTest.see_red_apple_at_distance_triggers_full_chain_and_approach`) → `ChosenActionState` rows with
    `ARBITER` are written to `bdSink`, and the effector receives a cortical stimulus for an action that was a candidate.
14. **Learned habit steers choice:** pre-train the harness's operant store (`creature().operantStore().record(...)`,
    i.e. the same write path `Valuation` uses) so that under "hunger" APPROACH(RED_APPLE, bin) has Q ≫ the rest →
    over 200 seeded cycles the arbiter picks APPROACH in ≥ 90 % (expected ≈ 97 % + ε/n), versus ≈ 1/n untrained.
15. **chosenQ reaches the episode buffer:** drive a pre-emption with the harness (open a hunger episode, then raise
    pain past θ_crit) with a pre-trained cell; the pre-empted `EpisodeReturnState.bootstrap` equals the trained cell's Q
    for the cut decision's executed action (was always 0 before #104).
16. **Single candidate:** a hungry creature with an empty visual field (self-perception only) → label
    `ACTION_TENDENCY`, and the cut-decision bootstrap (as in 15) uses `valueOf`'s Q, not 0.
17. **Flag-off parity:** A0 settings, same seed, before vs after: identical `ChosenActionState` (action, type) sequence
    over 300 cycles (golden comparison against the A0 chain built with this branch's code but ARBITER off; the
    arbiter's derived stream is never created).

## Verification

1. `mvn test` (all green, not just the new classes) and `mvn package` clean.
2. `graphify update .` after the code changes.
3. Smoke run (not a hypothesis test; the A2 question "regulation not worse than A1, calibration in range" is #109):
   `simulations/p104_a2_check.conf` = `include "arbiter_a0.conf"` + `maxRuntimeMinutes = 15`, `focalDriveEnabled`,
   `operantEstimatorEnabled`, `enabledFilters = [TARGET_DISTANCE, ARBITER]`; `experiments/p104_a2_check.yml` (1 trial,
   seed 104, UI on as for every local run, upload prefix `p104_a2_check`).
   `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p104_a2_check`.
   Checks on the extracted Parquet:
   - the run completes and extracts; `actions.selection_type` has `ARBITER` rows; share of ARBITER vs pre-filter-decided
     cycles reported;
   - `episode_returns` exists; pre-empted rows have non-zero `bootstrap` at least sometimes (0 everywhere would mean
     `chosenQ` did not arrive);
   - share of decisions with `focal_drive` null (A8);
   - per-action shares vs the p110 check run (same seed family): sanity, no single action > 90 %;
   - cycle rate (`dl2l_creature_*` cycle metrics / `actions` per creature per second) within 10 % of the p110 run,
     since the arbiter adds a blocking ask only where `ActionProbabilityFilter` used to have one.
4. A short note appended to this plan with the numbers (no separate report: no hypothesis is tested here).

## Files

New: `creature/arbiter/Arbiter.java`, `creature/arbiter/ArbitrationDecision.java`,
`creature/actionSelector/ArbitrationFilter.java`, `simulations/p104_a2_check.conf`, `experiments/p104_a2_check.yml`,
tests `ArbiterTest`, `ArbitrationFilterTest`, `ArbitrationFunctionalTest`.
Changed: `bd/ActionSelectionType.java` (+`ARBITER`), `cluster/settings/LearningSettings.java` (validation, javadoc),
`components/FullAppraisal.java`, `arbiter/ValueEstimator.java`, `arbiter/OperantEstimator.java`, `CreatureActor.java`,
test `TestingCreature.java`, `arbiter/DecisionRecord.java` + `EpisodeBuffer.java` (javadoc: chosenQ no longer "NaN until
#104"), `OperantEstimatorTest`, `LearningSettingsTest`.
Unchanged: `TableSchemas`, `ArrowIpcBackend`, `scripts/dl2l_data/tables.py` (no new table; `selection_type` is a string).

## Risks

- **Blocking ask on the hot path.** One ask per cycle to the operant facade, whose mailbox also receives `record`
  bursts when episodes close. Mitigation: measured in the check run; Q4 has the fallback design if it bites.
- **Calm-state randomness (A8)** could change survival relative to A1 for reasons unrelated to arbitration. Measured
  here, decided in #107's pre-registration.
- **Early-life uniformity.** Every cell starts at `(0, 1, 1)`, so early behaviour is Thompson over equal priors:
  close to uniform. A0/A1's legacy table (`OperantConditioningActor`) is also nudged from per-target priors, but
  by valence per outcome rather than by closed episodes, so A2 learns later and more slowly (only on episode close).
  Not a code risk, an interpretation one for #109: compare learning curves, not only lifetime means.
- **Interface change to `ValueEstimator`** (Q1) touches merged #102/#103 code; one implementation, small blast radius.

## Open questions (each with a recommendation)

- **Q1. Pass the focal drive explicitly (change `ValueEstimator.estimate`) or keep the `AtomicReference` supplier?**
  Recommendation: **explicit argument**, in this issue. The supplier is shared mutable state across actors; it only
  works because the ask is blocking and issued right after the write. #113 needs the drive too.
- **Q2. Where does `ARBITER` live: `enabledFilters` or a new boolean `arbiterEnabled`?**
  Recommendation: **`enabledFilters`**, with the validation in §6, and **not** in `MASTER_FILTER_ORDER` (it is the
  default list). Mirrors App. A step 8 ("a new ActionSelectionType") and needs no new parser line.
- **Q3. Calm state (no open episode): key the operant cell by `NONE` (untrained → uniform) or by the most-aroused
  drive, as the tendency filter does?** Recommendation: **`NONE` for #104** (paper: idle has no focal drive; mixing
  most-aroused into the key would also mix cells across drives the episode never valued); report the null-focus share
  in the check run and let #107 decide.
- **Q4. Blocking facade ask vs. moving the table into `FullAppraisal`** (reader owns it; `Valuation` sends immutable
  `OperantSamples` messages instead of calling `record`). Recommendation: **keep the facade** (#110's decision; same
  cost as the legacy path). Switch only if the check run shows > 10 % cycle-rate loss. No timeout fallback: a 5 s
  stall means the creature is broken; let it fail loudly, as `getProbabilities` does.
- **Q5. Deduplicate target-agnostic candidates (one SLEEP/WANDER/OBSERVE regardless of object) before the arbiter?**
  Recommendation: **no** in #104 (changes the candidate set the paper defines and the cells #103 keys); record
  `candidateCount` and revisit after A2 data.
- **Q6. Persist something new in #104, or leave all decision telemetry to #105?** Recommendation: **leave it to #105**;
  the DoD is met by `selection_type = ARBITER` rows and real `chosenQ` in `episode_return.bootstrap`, and
  `ArbitrationDecision` already holds every #105 field except efficiency/affordable tier (#114) and the episode id
  (available in `FullAppraisal` from the tracker).
- **Q7. ARBITER with `neuromodulationEnabled`: reject, or allow with τ silently constant?** Recommendation:
  **reject at parse** until an issue couples τ to dopamine (and file that issue; the story map has none).
- **Q8. Gate overrides keep the last filter's label in `ChosenActionState`** (pre-existing: a forced SLEEP is labelled
  ARBITER/AFFORDANCE). Recommendation: **leave as is** in #104; #105 can add an `overridden` flag. Also pre-existing
  and out of scope: `updateMemory` and `dispatchTediumStimulus` see the pre-gate action.
- **Q9. A canonical `simulations/arbiter_a2.conf` here or in #107/#109?** Recommendation: **only the check conf here**;
  the A2 arm is part of the pre-registration (#107), where θ, ε and the null-focus question are fixed.
- **Q10. Exploration source "greedy": defined as "Thompson's pick equals argmax Q"?** Recommendation: **yes**, computed
  in `Arbiter` so #105 only stores it.
