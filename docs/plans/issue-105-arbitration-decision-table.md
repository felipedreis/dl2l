# Issue #105 — `ArbitrationDecisionState`: one row per arbiter decision, and its extraction

Status: **approved by the user with the reviewer's recommendations** (2026-11-01): (1) a join mismatch in the extraction
warns and nulls the joined columns, it does not abort the extraction; (2) the `episodic_*` and `planner_*` column blocks are
declared now; (3) `ExecutedEstimate` and the `executedQ` refactor stay in this issue. No code has been changed yet.
Context: arbiter story map (`docs/plans/2026-q4-research-plan.md`); arbitration paper draft
(`docs/bib/uncertainty_arbitration.pdf`) App. A step 9 "Telemetry" (p. 22), §4.4 "What each controller reports" and its
Calibration paragraph (pp. 10–11), §4.5 decisiveness, budget, exploration (pp. 11–12), §7.1 (controller shares and
non-greedy share over life, pp. 16–17), §8 validation plan (pp. 18–19), App. B.1 risks (p. 22).
Depends on #104 (merged, PR #126: `Arbiter`, `ArbitrationDecision`, `ArbiterActionSelector.takeLastDecision()`) and on #110
(merged: `EpisodeBuffer`, `episode_return` table, the persisted-table pattern this issue copies).
Unblocks #115 (SE calibration), #109 (A2 campaign analysis), #114 (budget: the row already has the efficiency column),
#113 (episodic controller: the row already has its columns).

## The issue (verbatim)

> Draft Appendix A step 9. One row per arbiter decision: creature, time, focal drive, efficiency, most expensive affordable
> controller, each consulted controller's conf, deciding controller, Q/SE/sigma of chosen and runner-up, exploration source
> (greedy / Thompson / epsilon), episode id. Realised value is joined when the episode closes (needed for calibration and
> analysis). Add to `TableSchemas`, `ArrowIpcBackend` output and `scripts/dl2l_data/tables.py`. Done when a schema test
> passes and the table extracts to Parquet from a short run.

## Scope

**In**

- `creature/bd/ArbitrationDecisionState` (a `PersistenceState`), registered in `TableSchemas` as raw table
  `arbitration_decision_state` (written by `ArrowIpcBackend` with no backend change: the backend iterates `TableSchemas.ALL`).
- `FullAppraisal` writes one row per arbiter decision (every cycle in `actionSelector = ARBITER`, single-candidate cycles
  included), in the same `persist(...)` call as that cycle's `ChosenActionState`.
- Small, behaviour-neutral additions to `ArbitrationDecision`/`Arbiter` so the row can be filled without recomputation:
  the highest-Q estimate, the number of candidates tied at the highest Q, each consultation's own estimates, the most
  expensive affordable controller; and **a fix of the GREEDY/THOMPSON label on ties** (code review of #104).
- A pure helper that resolves the **executed** action's estimate (after the hysteresis gate) from the decision, replacing
  the private `FullAppraisal.executedQ` logic, so the row and `DecisionRecord.chosenQ` read the same number.
- Extraction: `TABLES["arbitration_decisions"]` in `scripts/dl2l_data/tables.py`, which LEFT JOINs `episode_return` on
  `(creature_key, cycle = step_cycle)` to attach the episode id, close type and realised value; `extract.py` skips the table
  (and `episode_returns`) when a pre-#105 (pre-#110) raw dump has no such file.
- Tests (schema, round trip, core, functional harness, cross-language golden) and a short check run on the p104 stress
  worlds, whose creatures open and close episodes.

**Out (proposed for confirmation; owning issue named)**

- SE calibration (z, the RMS rescale during sleep, `[0.5, 3]` clip): **#115**. This issue only makes z computable.
  z itself is not computed in the extraction SQL (Q4).
- Efficiency budget (tiers 0 / 0.3 / 0.7) and novelty bonus: **#114**. The row persists the efficiency the arbiter was
  given and a "most expensive affordable controller" that today is defined without a budget (A2 below).
- Episodic and planner controllers: **#113** and later. Their columns are declared now and are NULL (Q2).
- Consulting every affordable controller even after a decisive one (App. B.1 "a controller never calibrates"): a
  behaviour and cost change, belongs to #113/#115. The row records what was consulted.
- Any change to the hysteresis gate, the filter-based selector or the `actions` table (`chosen_action_state`).
  The pre-existing `ChosenActionState` label on gate overrides (#104 Q8) is not touched: the new row carries the
  override explicitly.
- The calibration and controller-share analyses themselves (`analysis/experiments/...`): #109 / #115.

## What exists today (verified in code and on the p104 data)

- `ArbitrationDecision` (record): `candidateCount`, `candidates`, `estimates` (deciding controller, all candidates),
  `consultations` (`ControllerType`, `conf`), `decidingController`, `chosenIndex`, `chosen`, `chosenEstimate`,
  `runnerUpEstimate` (**second-highest Q overall**, `null` when n = 1), `source` ∈ {SINGLE, GREEDY, THOMPSON, EPSILON}.
  Nothing persists it.
- `Arbiter.decide`: consultations keep only `conf`, not the estimates of non-deciding controllers. GREEDY iff
  `chosenIndex == argmaxQ(deciding)`, with `argmaxQ` breaking ties to the earliest index. With all Q equal (every
  untrained cell is `Estimate(0, 1, 1)`), about (n−1)/n of the Thompson picks are labelled THOMPSON though every candidate
  is equally greedy. The label does not influence any choice and is not persisted yet.
- `runnerUpEstimate` is the second-best by Q, **not** the best among the non-chosen. When Thompson picks a candidate that
  is not the argmax, the decision has the chosen and the second-best but **not the best** candidate's estimate, so neither
  the decisiveness nor "was this greedy" can be recomputed from the record.
- `FullAppraisal.onEmotionalStimulus`: `cognitiveCycle++` → `memorySystem.tickDecisionCycle()` (async tell) → `selectAction`
  (stores `cycleContext`, `cycleDecision = takeLastDecision()`) → `updateMemory` → `dispatchTediumStimulus` →
  `enforceHysteresisGate` (may replace the pick with SLEEP while `dwellTicks < MIN_SLEEP_TICKS`) → `sendArbiterMessages`
  (blocking ask `currentDecisionCycle()`, then `DecisionRecord(cycle, executedAction, h, focalDrive, executedQ)`) →
  `updateSleepState` → effector → `persistDecision` (`ChangeStimulusState` + `ChosenActionState` in one `persist` call).
- `executedQ(executed)`: the decision's estimate when the executed action is a candidate (pick, or the gate's SLEEP when
  SLEEP was a candidate), else `arbiterSelection.valueOf(executed, ctx)` (one extra estimate call, cheapest controller).
  Returns only `q`; the SE and σ of the executed action are thrown away.
- `SelectionContext.efficiency` = `EmotionalStimulus.behaviouralEfficiency` =
  `PartialAppraisal.normalizedBehaviouralEfficiency`: `1 − exp(−0.4·a)` when fewer than `COMPLEX_TASK` perceptions,
  else `a·(280/49 − 40/49·a)/10` (the Campos inverted U, peak 1 at a = 3.5, **negative above a = 7**). So it is already
  on the paper's `e = E/10` scale for complex tasks, but the simple-task branch is a different (monotone) curve. The
  `Arbiter` does not receive it.
- **Clocks.** Only `FullAppraisal` ticks the memory system's `decisionCycle`, once per cycle, right after its own
  `cognitiveCycle++`; the tell and the ask go from the same sender to the same TypedActor, so they are ordered and
  `DecisionRecord.cycle == cognitiveCycle`. `FocusTracker` is fed `(int) cognitiveCycle`. `EpisodeBuffer` stamps steps with
  `DecisionRecord.cycle` → `episode_return.step_cycle`. The `actions` Parquet (`chosen_action_state`) has a wall-clock
  `time` but **no cycle**.
- `EpisodeBuffer`: a decision becomes a step iff `focalDrive != null` and the open segment is for that drive; one decision
  is in at most one segment, so `(creature_key, step_cycle)` is unique. Episode ids come from `nextEpisodeId++` on
  `Opened`; a resumed episode keeps its id. Segments open at trial end (creature still alive) are never written.
- **Real data** (`ml/data_p104_a2_stress`, both conditions, every creature died): per creature, the number of decisions
  with non-null `focal_drive` equals the number of `episode_returns` rows **exactly** (4360/4360, 6884/6884, ... 3244/3244),
  `step_cycle` is unique per creature, and `max(step_cycle)` equals the creature's decision count. So the join on
  `(creature_key, cycle)` is complete for creatures that died. Close types: TIMED_OUT 26,532 / PREEMPTED 476 / DEATH 463
  (scarce_regrow), 17,085 / 227 / 179 (finite_food).
- Volume: 133,205 decisions in 15 min for 5 creatures (`p104_a2_check`), ~29.7 decisions per creature per second.
  Raw dump of that run: 782 MB uncompressed (`DL2L_ARROW_COMPRESSION` unset = none); `chosen_action_state.arrow` 17 MB,
  `engram_state.arrow` 366 MB.
- Persistence pattern: `TableSchemas.ColType` is scalar only (`STRING, INT32, INT64, DOUBLE, BOOLEAN`); repeated
  structures are **flattened into fixed columns** (`episode_return.h_hunger … g_tedium`). Every column is nullable;
  `NaN` is mapped to null explicitly where meaningful (`episode_return.bootstrap`). `CreatureComponent.persist` is
  producer-side buffered.
- `ArrowIpcBackend` opens one writer per `TableSchemas.ALL` entry at start and writes the schema immediately, so every
  table has a `.arrow` file even when it has no rows. `extract.py` registers every file as a view; a zero-row result is
  **not** written to Parquet (`save()` prints "(empty)"), which is why `p104_a2_check` has no `episode_returns.parquet`.
  A raw dump from before a table existed has no file, hence no view, and its SQL fails, except `run_info`, which
  `extract.py` skips explicitly. (`episode_returns` has no such guard today: re-extracting a pre-#110 dump fails.)

## Ambiguities and tensions, and how they are resolved

| # | Where | Issue | Resolution |
|---|---|---|---|
| T1 | Issue "time" | Wall clock, cognitive cycle, or decision cycle? | Both `cycle` (the memory system's decision cycle, the one in `DecisionRecord` and `episode_return.step_cycle`; join key) and `time_ms` (`System.currentTimeMillis()`, as `episode_return`/`memory_decision_state`). Plus a per-creature `seq`. |
| T2 | "most expensive affordable controller" | The budget does not exist until #114, so "affordable" has no rule. | `Arbiter` computes it: the most expensive **wired** controller allowed by the budget; with no budget, the most expensive wired one (OPERANT in R1, constant). Never derived from paper tiers at persist time: a tier that did not gate anything would be a false record. #114 changes the rule, not the column. |
| T3 | "efficiency" | Which number and scale? The code's value is two curves (simple vs complex task), can be negative; the paper's is `E/10` in [0, 1]. | Persist exactly what the arbiter was given (`SelectionContext.efficiency`), column `efficiency`, documented as "PartialAppraisal's normalised behavioural efficiency, ≤ 1, may be < 0 above arousal 7". Whether #114 budgets on it unchanged is #114's question (Q9). |
| T4 | "Q/SE/σ of chosen and runner-up" | `runnerUpEstimate` is the second-best by Q, not the best non-chosen; when Thompson picks a non-argmax the best is lost. | Persist three estimates: **chosen**, **best** (highest Q, the "1" of decisiveness) and **runner-up** (second-highest Q, the "2"). Decisiveness and greediness become recomputable. `ArbitrationDecision` gains `bestEstimate`. |
| T5 | GREEDY vs THOMPSON | Tie at max Q labels equally-greedy picks THOMPSON; the exploration share would be inflated early in life (all cells untrained). | Fix in `Arbiter` (label only, no draw changes): GREEDY iff `Q[chosen] == max Q`. Persist `n_tied_at_max` so analysis can separate "greedy among ties" (uninformed) from "greedy, unique best". Q1. |
| T6 | Single-candidate cycles | Are they "arbiter decisions"? | Yes, a row each, `source = SINGLE`, runner-up NULL, conf of the consulted controller = 1. Their executed action has a Q and, inside an episode, a realised value, so they belong to calibration; analyses of exploration filter them out by `source`. |
| T7 | Pre-gate vs executed | The arbiter's pick can be replaced by the gate's SLEEP; `episode_return.action` is the executed action. Calibration needs Q of the **executed** action. | The row has both: `chosen_*` (the pick, pre-gate) and `executed_*` (action, object type, Q, SE, σ of the executed action, deciding controller's when the executed action is a candidate) plus `executed_source` ∈ {`CHOSEN`, `OTHER_CANDIDATE`, `VALUE_OF`}. `executed_source != CHOSEN` ⇔ the gate overrode (the gate only fires on a non-SLEEP pick). |
| T8 | "episode id" + "realised value joined when the episode closes" | `FullAppraisal` does not know the id (assigned inside `EpisodeBuffer`, another actor); Arrow tables are append-only, so a row cannot be updated at close. | **Join at extraction** on `(creature_key, cycle = step_cycle)` (Q3). The raw row has no episode id; the extracted Parquet has `episode_id`, `episode_drive`, `close_type`, `realised_value`, `bootstrap`, `steps_to_close` from `episode_return`. |
| T9 | Calibration on joined rows | Most steps in the stress worlds are TIMED_OUT, and the buffer zeroes G and value for TIMED_OUT **APPROACH** steps by rule; PREEMPTED values include `γ^(T−t)·B`, with B a Q of the arbiter itself. Neither is a pure Monte Carlo realised value. | Not resolved here (#115's definition). The extracted row carries `close_type`, `bootstrap` and the executed action so #115 can exclude or treat them. Flagged in Risks. |
| T10 | NaN vs NULL | Absent runner-up (n = 1), unconsulted controllers, executed estimates of controllers that never saw the executed action. | NULL (Arrow validity bit), as `episode_return.bootstrap`. No NaN is ever written: `Estimate` rejects non-finite values, and absent ones are null references. |
| T11 | "each consulted controller's conf" with 1 → 3 controllers | List column vs fixed columns. `ColType` has no list type; DuckDB/Arrow support lists but no table here uses one, and the extraction SQL and pandas analyses all assume flat columns. | Fixed columns per `ControllerType`, generated from `ControllerType.values()` (`operant_*`, `episodic_*`, `planner_*`), NULL when not consulted. Declared now for all three, so #113 adds no column (Q2). |
| T12 | Volume | ~30 rows/s/creature. | ~45 columns, ~36 fixed-width 8-byte + 7 short strings ≈ 350–400 B/row uncompressed → ≈ 50 MB per 15-min 5-creature run (+6% on a 782 MB raw dump). No extra ask or estimate call per cycle: everything comes from the decision already in hand. One more object per cycle into the producer-side persist buffer. Measured in the check run. |
| T13 | Empty table rule | A FILTER_BASED run writes zero rows; an old dump has no file. | Keep the generic rule (zero rows → no Parquet, "(empty)" logged); `actions.selection_type` says whether the arbiter ran. Missing file → skipped with a note, like `run_info`; same guard added for `episode_returns` (Q6). |
| T14 | B.1 "log every affordable controller's estimate for the chosen action" | Requires consulting controllers the stopping rule skipped. | Out of scope (behaviour). The columns exist; they are filled for every **consulted** controller (its estimate of the executed action when it is a candidate). |

## Design

### 1. Core additions — `creature/arbiter/ArbitrationDecision.java`, `Arbiter.java` (behaviour-neutral)

```java
public record ArbitrationDecision(int candidateCount, List<Action> candidates, List<Estimate> estimates,
                                  List<Consultation> consultations, Arbiter.ControllerType decidingController,
                                  Arbiter.ControllerType mostExpensiveAffordable,          // new (T2)
                                  int chosenIndex, Action chosen, Estimate chosenEstimate,
                                  Estimate bestEstimate,                                   // new (T4): highest Q, ties → earliest
                                  Estimate runnerUpEstimate,                               // unchanged meaning; null when n = 1
                                  int nTiedAtMax,                                          // new (T5): # candidates with Q == max Q
                                  ExplorationSource source) {
    public record Consultation(Arbiter.ControllerType controller, double conf, List<Estimate> estimates) { } // + estimates, copied
}
```

- `Consultation.estimates`: that controller's estimates of all candidates (the list the `Arbiter` already has in hand;
  `List.copyOf`, elements immutable). `ArbitrationDecision.estimates` stays (= the deciding consultation's list) so
  #104's callers are untouched.
- `mostExpensiveAffordable`: `Arbiter` gets it as the last controller in its cheapest-first list (no budget yet). A
  comment points at #114. With one wired controller it is `OPERANT` on every row.
- `bestEstimate = deciding.get(argmaxQ(deciding))`; with n = 1 it equals `chosenEstimate`.
- `nTiedAtMax` = count of `i` with `deciding.get(i).q() == maxQ` (exact `==`, as `argmaxQ` already compares; Q values
  of untrained cells are exactly 0.0).
- **Label fix:** `source = deciding.get(chosenIndex).q() == maxQ ? GREEDY : THOMPSON` (non-ε, n ≥ 2). EPSILON and SINGLE
  unchanged. No random number is drawn differently, so every choice sequence is unchanged (the #104 flag-off digest and
  `ArbitrationFunctionalTest` seeds keep passing; only label assertions with tied Q would change, and the existing
  `thompsonDrawMatchesTheHandComputedArgmaxForTheSeed` fixture has distinct Q).
- Javadoc of `ExplorationSource` updated: "GREEDY: the Thompson draw picked a candidate whose Q equals the highest Q
  (possibly one of several tied)".

### 2. Executed estimate — `creature/arbiter/ExecutedEstimate.java` (new, pure)

```java
public record ExecutedEstimate(Action action, Source source, Estimate deciding,
                               Map<Arbiter.ControllerType, Estimate> byController) {
    public enum Source { CHOSEN, OTHER_CANDIDATE, VALUE_OF }
    /** Resolves the executed action against the decision; valueOf is called only for VALUE_OF. */
    public static ExecutedEstimate of(ArbitrationDecision d, Action executed, Function<Action, Estimate> valueOf);
}
```

- `CHOSEN`: `executed.equals(d.chosen())` → deciding estimate = `chosenEstimate`; `byController` = each consultation's
  estimate at `chosenIndex`.
- `OTHER_CANDIDATE`: first index `i` with `candidates.get(i).equals(executed)` → `d.estimates().get(i)`; `byController`
  from each consultation at `i`.
- `VALUE_OF`: not a candidate → `valueOf.apply(executed)` (today `arbiterSelection.valueOf(executed, cycleContext)`, the
  cheapest controller); `byController` = `{cheapest: that estimate}`. Note: with more than one controller this estimate is
  the cheapest controller's, not necessarily the deciding one's; recorded by `executed_source = VALUE_OF`.
- `FullAppraisal.executedQ` becomes `executed = ExecutedEstimate.of(cycleDecision, action, a -> arbiterSelection.valueOf(a,
  cycleContext))` stored in a cycle field, and `DecisionRecord.chosenQ = executed.deciding().q()`. Same number as today,
  same number of calls (the `VALUE_OF` ask only on the rare branch it already had).

### 3. `creature/bd/ArbitrationDecisionState.java` (new) and the `TableSchemas` entry

One immutable-after-construction `PersistenceState` (same shape as `EpisodeReturnState`: `UUID id`, final-ish fields,
getters, no-arg constructor), built by a static factory
`ArbitrationDecisionState.of(long creatureKey, long seq, long timeMs, long cycle, String focalDrive, double efficiency,
ArbitrationDecision d, ExecutedEstimate x)` so the mapping is tested once, without an actor.

Raw table `arbitration_decision_state`, appended at the end of `TableSchemas.ALL` (order there has no effect on the
output; the `TableSchemasTest` ported-block check covers only the first 22 tables). Columns (`id` first, added by `table(...)`):

| Column | Type | Null when | Source |
|---|---|---|---|
| `creature_key` | INT64 | never | `id.key` (as every per-creature table) |
| `seq` | INT64 | never | per-`FullAppraisal` counter |
| `time_ms` | INT64 | never | wall clock at persist |
| `cycle` | INT64 | never | decision cycle (= `DecisionRecord.cycle`; join key) |
| `focal_drive` | STRING | no open episode | `focalDrive` |
| `efficiency` | DOUBLE | never | `cycleContext.efficiency()` (T3) |
| `affordable_controller` | STRING | never | `d.mostExpensiveAffordable()` |
| `candidate_count` | INT32 | never | `d.candidateCount()` |
| `deciding_controller` | STRING | never | `d.decidingController()` |
| `n_consulted` | INT32 | never | `d.consultations().size()` |
| `source` | STRING | never | `SINGLE` / `GREEDY` / `THOMPSON` / `EPSILON` |
| `n_tied_at_max` | INT32 | never | `d.nTiedAtMax()` |
| `chosen_action`, `chosen_object_type` | STRING | object type: when the perception has none (see *) | `d.chosen()` (pre-gate pick) |
| `chosen_q`, `chosen_se`, `chosen_sigma` | DOUBLE | never | `d.chosenEstimate()` |
| `best_q`, `best_se`, `best_sigma` | DOUBLE | never | `d.bestEstimate()` |
| `runnerup_q`, `runnerup_se`, `runnerup_sigma` | DOUBLE | n = 1 | `d.runnerUpEstimate()` |
| `executed_action`, `executed_object_type` | STRING | as chosen | post-gate action |
| `executed_source` | STRING | never | `CHOSEN` / `OTHER_CANDIDATE` / `VALUE_OF` |
| `executed_q`, `executed_se`, `executed_sigma` | DOUBLE | never | `x.deciding()` (the calibration columns) |
| `{c}_conf` for c ∈ operant, episodic, planner | DOUBLE | c not consulted | consultation of c |
| `{c}_q`, `{c}_se`, `{c}_sigma` | DOUBLE | c not consulted, or `VALUE_OF` and c not the cheapest | `x.byController().get(c)` |

\* Object type uses the same expression as `Valuation.persistEpisodeReturns` (`perception.objectType.getOrElse(null)`
→ `name()`, null when absent), so the two tables agree on the self-perception. The `{c}_*` block is generated by a loop
over `Arbiter.ControllerType.values()` in `TableSchemas`, lower-cased names. This makes `creature.bd` import an enum from
`creature.arbiter` (new dependency direction; no `bd` class does so today). The state class stores the per-controller
values in arrays indexed by `ControllerType.ordinal()`, so `TableSchemas` needs only the enum.

No `changestimulusstate_id` link to `chosen_action_state` (Q7): the row carries the executed action itself, and the
`actions` table has no cycle to join on anyway.

### 4. `FullAppraisal` wiring

- New fields: `long arbitrationDecisionSeq`, `ExecutedEstimate cycleExecuted`, `long cycleDecisionCycle`.
- `sendArbiterMessages(action)`: already asks `currentDecisionCycle()`; store it in `cycleDecisionCycle`; compute
  `cycleExecuted` when `cycleDecision != null`; `DecisionRecord.chosenQ = cycleExecuted != null ?
  cycleExecuted.deciding().q() : NaN`.
- `persistDecision`: when `cycleDecision != null`, add `ArbitrationDecisionState.of(id.key, arbitrationDecisionSeq++,
  System.currentTimeMillis(), cycleDecisionCycle, focalDrive, cycleContext.efficiency(), cycleDecision, cycleExecuted)` to
  the **same** `persist(change, chosenActionState, decisionRow)` call (one batch, the #84 atomicity convention); then
  clear `cycleDecision`/`cycleExecuted`.
- FILTER_BASED: `arbiterSelection == null` → `cycleDecision == null` → nothing written, no new call, no new field read.
  `ARBITER` requires `focalDriveEnabled` (settings validation from #104), so `sendArbiterMessages` always runs before
  `persistDecision` in that mode.

### 5. Extraction — `scripts/dl2l_data/tables.py`, `extract.py`

```sql
-- Issue #105: one row per arbiter decision (actionSelector = ARBITER), with the closed-episode step it became, if any.
SELECT d.creature_key, d.seq, d.time_ms, d.cycle, d.focal_drive, d.efficiency, d.affordable_controller,
       d.candidate_count, d.deciding_controller, d.n_consulted, d.source, d.n_tied_at_max,
       d.chosen_action, d.chosen_object_type, d.chosen_q, d.chosen_se, d.chosen_sigma,
       d.best_q, d.best_se, d.best_sigma, d.runnerup_q, d.runnerup_se, d.runnerup_sigma,
       d.executed_action, d.executed_object_type, d.executed_source, d.executed_q, d.executed_se, d.executed_sigma,
       d.operant_conf, d.operant_q, d.operant_se, d.operant_sigma,
       d.episodic_conf, d.episodic_q, d.episodic_se, d.episodic_sigma,
       d.planner_conf, d.planner_q, d.planner_se, d.planner_sigma,
       er.episode_id, er.drive AS episode_drive, er.close_type, er.steps_to_close,
       er.bootstrap, er.value AS realised_value,
       er.action AS episode_action
FROM data.arbitration_decision_state d
LEFT JOIN data.episode_return er ON er.creature_key = d.creature_key AND er.step_cycle = d.cycle
ORDER BY d.creature_key, d.seq
```

- Post-process (Python, same file), **warn, never abort** (an exception here would skip every later table; the extraction
  must keep writing): (a) if the join changed the row count (a duplicate `(creature_key, step_cycle)` in `episode_return`
  would mean an invariant broke), print a warning naming the creatures and keep the un-joined decision rows (de-duplicate
  `episode_return` to its first row per key before joining); (b) wherever the joined `episode_return.action` differs from
  `executed_action` (the cycle clocks diverged; T1), print a warning naming creature and cycle count and set the
  `episode_*`, `close_type`, `steps_to_close`, `bootstrap` and `realised_value` columns to NULL on those rows, so
  calibration never reads a mismatched value; then drop `episode_action`. The functional test 10 (join invariant) is what
  fails the build when the invariant breaks; the extraction itself degrades gracefully.
- `TABLE_ORDER` gets `"arbitration_decisions"` after `"episode_returns"`.
- `extract.py`: replace the `run_info` special case with `OPTIONAL_RAW = {"run_info": "run_info", "episode_returns":
  "episode_return", "arbitration_decisions": "arbitration_decision_state"}`; a table whose raw view is missing is skipped
  with a note naming the issue that introduced it. The zero-row → no-Parquet rule is unchanged (T13).
- z is **not** computed here (Q4).

### 6. Concurrency and immutability

All new state is `FullAppraisal`'s own (cycle fields, a counter). `ArbitrationDecision`/`Consultation`/`ExecutedEstimate`
are immutable (lists copied, `Map.copyOf`). The persisted state object is handed to `BDActor` by the existing
`persist` path and never touched again by `FullAppraisal`. No new message type, no new actor, nothing shared between
actors; the episode id is obtained without any runtime coupling to `MemorySystemActor` (Q3).

## Tests

All against real implementations: real `Arbiter` with real `OperantEstimator`s trained through `record`, real
`ArrowIpcBackend` writing to a temp dir, the real `FullAppraisal`/`MemorySystemActor`/`EpisodeBuffer`/`Valuation` in
`TestingHarness`/`TestingCreature`, the real `python3 -m dl2l_data.extract`. No stub of `ValueEstimator`, `MemorySystem`
or the backend.

### `ArbiterTest` (additions)

1. **Label on ties:** an untrained estimator (all cells `(0, 1, 1)`), 4 candidates, ε = 0, 2,000 seeded decisions →
   every source is GREEDY and `nTiedAtMax == 4`; a trained estimator with a unique best → THOMPSON exactly when
   `chosenEstimate.q() < bestEstimate.q()`, GREEDY otherwise, and the THOMPSON share matches the closed form
   `1 − P(best wins)` within 4 binomial SEs (two candidates).
2. **Same choices as before the label fix:** the #104 seeded sequence test (1,000 decisions) produces the same
   `chosenIndex` sequence (the fix is label-only).
3. **best / runner-up:** three candidates with Q = (0.2, 1.0, 0.5) → `bestEstimate` is index 1, `runnerUpEstimate` index 2,
   and for a seed whose Thompson pick is index 0, `chosenEstimate` is index 0: all three distinct. n = 1 → best = chosen,
   runner-up null, `nTiedAtMax == 1`.
4. **Consultations carry estimates:** two controllers (A decisive, B untrained), order (B, A) → two consultations, each
   with its own list, equal to `X.estimate(candidates, h, drive)` for that controller.
5. **mostExpensiveAffordable:** one controller → OPERANT; two controllers, order (A, B), A decisive (stops at A) →
   still the type of B (affordable ≠ consulted).

### `ExecutedEstimateTest` (new, real `Arbiter` decisions)

6. Executed = pick → CHOSEN, deciding estimate = `chosenEstimate`; executed = another candidate (SLEEP among them) →
   OTHER_CANDIDATE with that candidate's estimate per consultation; executed not a candidate → VALUE_OF, the function is
   called exactly once (a counting wrapper around the real `ArbiterActionSelector.valueOf`, not a stub) and only the
   cheapest controller is in `byController`; CHOSEN/OTHER_CANDIDATE never call it.

### `ArbitrationDecisionRoundTripTest` (new, pattern of `EpisodeReturnRoundTripTest`)

7. Three states through a real `ArrowIpcBackend`: a two-candidate THOMPSON decision executed as chosen; a SINGLE
   decision (runner-up null); a gate override (`executed_source = OTHER_CANDIDATE`, executed ≠ chosen). Read back every
   column: values equal; `runnerup_*` null on the SINGLE row; `episodic_*`, `planner_*` null on all rows; `focal_drive`
   null where set null; no NaN anywhere.

### `TableSchemasTest` (updated)

8. Golden column list for `arbitration_decision_state` (exact names and order), the class mapping, `forState` resolves the
   new class, and every getter is total on a default-constructed row (the existing per-table checks loop over `ALL`).

### `ArbitrationFunctionalTest` (harness, additions)

9. **One row per cycle:** A2 settings, seeded, 300 cycles → exactly 300 `ArbitrationDecisionState` in `bdSink`, `cycle`
   = 1..300 contiguous, `seq` 0..299, `executed_action` equals the `ChosenActionState.action` written in the same
   batch, and `chosen_*` equal the decision returned to the selector (checked through the label/`source` and Q of a
   pre-trained cell).
10. **Join invariant (the extraction's key):** drive an episode to close in the harness (pattern of #104 test 15: open
    hunger, pre-empt with pain; and a regulated close) → for every `EpisodeReturnState` there is exactly one
    `ArbitrationDecisionState` with the same `creature_key` and `cycle == step_cycle`, its `executed_action` equals the
    step's `action`, and its `executed_q` equals the `chosenQ` the buffer used (for the cut decision, `bootstrap ==
    executed_q`).
11. **Gate override:** start a SLEEP episode (pre-train SLEEP so it is picked), then pre-train APPROACH so the arbiter picks
    it while `dwellTicks < MIN_SLEEP_TICKS` → the row has `chosen_action = APPROACH`, `executed_action = SLEEP`,
    `executed_source ∈ {OTHER_CANDIDATE, VALUE_OF}`, and `executed_q` = the Q the `DecisionRecord` carried. If the harness
    cannot hold the creature asleep deterministically, test 6 covers the mapping and this test is dropped (recorded as a
    deviation).
12. **Flag off:** A0 / FILTER_BASED settings → zero `ArbitrationDecisionState` rows; `FlagOffParityTest`'s digest unchanged.

### `ArrowExtractGoldenTest` (cross-language, updated)

13. Write arbitration rows for cycles 1–4 and `episode_return` rows for cycles 2–3 of the same creature (plus one other
    creature with the same cycle numbers, to prove `creature_key` is in the key) → `arbitration_decisions.parquet` has 4
    + n rows, `episode_id`/`realised_value` non-null exactly on the matching cycles, NULL elsewhere, and no
    `episode_action` column.
14. Old-dump tolerance: delete `arbitration_decision_state.arrow` and `episode_return.arrow` from the raw dir → extraction
    completes, those two Parquet files are absent, the note is printed.
15. Mismatch guard: an `episode_return` row whose `action` differs from the decision's `executed_action` → extraction
    still completes, prints a warning naming the creature and the number of cycles, and the joined columns of those rows
    are NULL (the decision columns are intact). A duplicate `(creature_key, step_cycle)` in `episode_return` → warning,
    and the row count of `arbitration_decisions` equals the number of decision rows.

## Verification

1. `mvn test` (all green, not only the new classes) and `mvn package` clean.
2. `graphify update .` after the code changes.
3. **Check run** (not a hypothesis test, so no report in `docs/reports`; a results note appended to this plan and a
   research-diary entry; per CLAUDE.md step 5 the mini-experiment has no reader beyond verifying the DoD and sizing the
   table for #109/#115). Spec `experiments/p105_decision_check.yml`, two conditions reusing the p104 stress confs
   unchanged (`simulations/p104_a2_scarce_regrow.conf`, `simulations/p104_a2_finite_food.conf`: every creature opens and
   closes episodes and dies in 3–5.5 min, so the run ends early and every episode is written), 1 trial, seed 104, UI on,
   `upload.prefix: p105_decision_check`, data dir `ml/data_p105_decision_check`. Run:
   `cd ansible && ansible-playbook -i inventories/local run-experiment.yml -e experiment=p105_decision_check`.
   Checks on the Parquet (a short script in the plan note, not a module):
   - `arbitration_decisions.parquet` exists in both conditions (the DoD); rows per creature == `actions` rows per
     creature; `cycle` contiguous 1..N per creature; no NaN in non-null columns;
   - **join coverage:** every row with non-null `focal_drive` has non-null `episode_id` (expected 100%: all creatures die,
     as on the p104 data), every null-focus row has null `episode_id`; the mismatch guard did not fire;
   - shares of `source` (SINGLE / GREEDY / THOMPSON / EPSILON; EPSILON ≈ 3% of n ≥ 2 rows), of `n_tied_at_max == candidate_count`
     (uninformed decisions), and of `executed_source != CHOSEN` (gate overrides), each over life (first vs last third);
   - descriptive z = (realised_value − executed_q) / √(executed_se² + executed_sigma²) by `close_type`: mean and variance
     only, for #115 to read; no claim;
   - raw size of `arbitration_decision_state.arrow` vs the dump total, and decisions per creature per second against
     the p104 stress run (29.7/s; within 10%).
4. Results note appended here; diary entry under 11-xx.

## Files

New: `creature/arbiter/ExecutedEstimate.java`; `creature/bd/ArbitrationDecisionState.java`;
tests `ExecutedEstimateTest`, `ArbitrationDecisionRoundTripTest`; `experiments/p105_decision_check.yml`.
Changed: `creature/arbiter/ArbitrationDecision.java` (fields, javadoc), `creature/arbiter/Arbiter.java` (best, ties,
consultation estimates, affordable, label fix), `creature/components/FullAppraisal.java` (executed estimate, row persist),
`creature/bd/TableSchemas.java` (new table), `scripts/dl2l_data/tables.py` (query, post-process, `TABLE_ORDER`),
`scripts/dl2l_data/extract.py` (optional raw tables), tests `ArbiterTest`, `ArbiterActionSelectorTest` (constructor arity
if it builds decisions), `ArbitrationFunctionalTest`, `TableSchemasTest`, `ArrowExtractGoldenTest`;
`CLAUDE.md` "Persistence"/"Extraction" paragraphs only if they list tables (they do not today: no change expected).
Unchanged: `ArrowIpcBackend` (iterates `TableSchemas.ALL`), `EpisodeBuffer`, `FocusTracker`, `FocusEvent`,
`MemorySystem(Actor)`, `DecisionRecord`, `ChosenActionState`, the gate, `LearningSettings`.

Commit order:
1. Core: `ArbitrationDecision`/`Arbiter` additions and the GREEDY label fix, with `ArbiterTest` 1–5 (choices unchanged;
   all existing tests green).
2. `ExecutedEstimate` + `FullAppraisal.executedQ` refactor onto it, `ExecutedEstimateTest` (no persisted change).
3. `ArbitrationDecisionState` + `TableSchemas` + `TableSchemasTest` + round-trip test (schema DoD).
4. `FullAppraisal` writes the row; `ArbitrationFunctionalTest` 9–12.
5. `tables.py`/`extract.py` + `ArrowExtractGoldenTest` 13–15.
6. Check-run spec, results note, diary entry.

## Risks

- **Calibration data dominated by rule-made values (T9).** In the stress worlds 96–97% of joined steps are TIMED_OUT, and
  the APPROACH ones have value 0 by rule. A naive z over all joined rows would mostly measure the timeout rule. Mitigated by
  carrying `close_type`/`bootstrap`/action; the choice is #115's.
- **Join depends on a clock invariant**, not on an id carried in the row: one ticker for the decision cycle, ordered
  tell/ask. A restart of `FullAppraisal` (supervision) would desynchronise `cognitiveCycle` but not the row's `cycle`
  (taken from the memory system, the same value the buffer uses), so the join survives it. Guarded by the post-process
  mismatch warning (joined columns nulled) and functional test 10.
- **Unclosed episodes at trial end** (creatures alive at `maxRuntimeMinutes`) get NULL `episode_id` although they were
  in an episode. Their realised value does not exist anyway; analyses must not read NULL `episode_id` as "no focus"
  (use `focal_drive` for that). Q3's alternative would fix the id but not the value.
- **Width (T12)** adds ~6% to raw dumps; if CCAD quotas bite, `DL2L_ARROW_COMPRESSION` already exists.
- **Schema reliance on `ControllerType`**: renaming or reordering the enum renames columns. Golden test 8 catches it.
- **Persist batch grows by one object per cycle**: same order as `ChosenActionState`; the producer-side buffer threshold
  is in states, so it flushes ~1.5× as often. Cycle rate is checked in the run.

## Open questions (each with a recommendation)

- **Q1. Fix the GREEDY/THOMPSON label in `Arbiter` now, or store the raw label plus a tie count and fix later?**
  Recommendation: **fix now** (label-only, no draw changes, nothing persisted yet so no data is reinterpreted) **and**
  persist `n_tied_at_max` plus `best_q`, so "greedy among ties" (uninformed, early life) is separable from "greedy, unique
  best", which is the paper's §7.1 non-greedy share.
- **Q2. Declare `episodic_*` and `planner_*` columns now (NULL), or add them in #113?** Recommendation: **declare now**,
  generated from `ControllerType.values()`. Cost ≈ 8 null doubles per row (~8 MB per 15-min run uncompressed); benefit:
  one extraction query for every arm (A2–A6) and no old-dump column handling. The per-controller estimate of the executed
  action is what App. B.1 asks to log for per-controller calibration.
- **Q3. Episode id: extraction-time join, tracker-assigned id, or memory-system stamping?** Recommendation: **extraction
  join on `(creature_key, cycle = step_cycle)`.** Zero runtime cost, no new messages, no coupling between `FullAppraisal`
  and `MemorySystemActor`, and the realised value has to be joined that way regardless (append-only tables). Verified
  complete on the p104 stress data (decisions with focus == episode steps, per creature, exactly). Alternatives:
  (b) `FocusTracker` numbers episodes and `FocusEvent.Opened` carries the id, which `EpisodeBuffer` adopts instead of
  `nextEpisodeId++`: gives an id also for episodes still open at trial end, costs a change to the #99/#110 records and
  their tests, and duplicates nothing at runtime; worth it only if an analysis needs ids of unclosed episodes.
  (c) The memory system writes the decision row: ships the whole `ArbitrationDecision` through the TypedActor every
  cycle and splits arbiter telemetry across two components; not recommended.
- **Q4. Compute z in the extraction SQL?** Recommendation: **no.** Its definition (which close types, bootstrap handling,
  SE before or after the #115 rescale) is #115's; the extraction provides `realised_value`, `executed_q/se/sigma`,
  `close_type`, `bootstrap`. The check run prints a descriptive z only.
- **Q5. Persist the pre-#115 raw SE separately from a rescaled SE?** Recommendation: **not now.** The row stores the
  estimate the arbiter used. #115 adds an `se_scale` column (additive; all #115 data postdates it).
- **Q6. Empty-table rule: write an empty Parquet when the arbiter is off, or keep "no rows → no file"?** Recommendation:
  **keep the rule** (one rule for every table; `actions.selection_type` tells whether the arbiter ran), and add the
  missing-file skip for `arbitration_decisions` **and** `episode_returns` (the latter fixes a latent failure on pre-#110
  dumps).
- **Q7. Link the row to `chosen_action_state` (store `changestimulusstate_id`)?** Recommendation: **no**; a 36-char UUID
  per row (~5 MB per run) for a link whose content (executed action, target type) the row already holds. If an analysis
  needs the wall-clock `time` of `actions`, add a `cycle` column to `chosen_action_state` in a separate issue.
- **Q8. Row for single-candidate cycles?** Recommendation: **yes** (T6). They are a large share when the visual field is
  empty, and excluding them would bias calibration toward multi-candidate situations.
- **Q9. Persist `efficiency` as the code computes it (two curves, may be negative) or clamp/convert to the paper's
  `E/10`?** Recommendation: **as computed**, documented. Converting would invent a number the arbiter never saw; whether
  the budget should use the complex-task curve only is for #114 to decide (to be noted on #114 once this plan is approved).
- **Q10. "Most expensive affordable" with no budget: most expensive wired controller, or NULL?** Recommendation:
  **most expensive wired** (OPERANT in R1), computed by `Arbiter` so #114 changes one method, not the schema. NULL would
  be indistinguishable from "nothing affordable", which cannot happen (the operant table is always affordable).
- **Q11. Gate overrides: should the row of an overridden cycle be flagged in `chosen_action_state` too (#104 Q8)?**
  Recommendation: **no**, the new row's `executed_source` is the record; `actions` stays untouched.
