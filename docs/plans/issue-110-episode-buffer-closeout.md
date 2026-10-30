# Issue #110 — Episode buffer and close-out: discounted returns G

Status: **implemented 2026-10-26** (approved with all recommendations, Q1–Q6; deviations and open items in the last section).
Context: arbiter story map (`docs/plans/2026-q4-research-plan.md`, A2); arbitration paper draft
(`docs/bib/uncertainty_arbitration.pdf`) §4.2 "How an episode closes, and what each step learns", Fig. 2, App. A steps 4–6.
Depends on #98 (`FocusTracker`/`FocusEvent`), #100 (K), and — through the sink — #101 (`HomeostaticValue`), #102/#103
(`ValueLearner`, `OperantEstimator`). Feeds #104 (arbiter reads what is learned), #105 (realised V per decision),
#111 (engram gains G), #113 (episodic estimator), #115 (calibration needs the realised value at close).

## Scope

In: while an episode is open its steps wait in a buffer (never queried for decisions). When it closes, each step gets a
return `G_t = Σ_j γ^j K_{t+j}` (γ = 2^(−1/5) ≈ 0.87, the eligibility-trace half-life of 5 cycles already in use) and is
written onward, by closing type:

| Closing | Source | Return | Written value |
|---|---|---|---|
| **Regulated** | tracker `Closed(REGULATED)` | Monte Carlo to the close cycle (backward pass `G_t = K_t + γ·G_{t+1}`), averaged downstream, not max as in MFEC | `V(G_t \| H_t)` |
| **Pre-empted** | tracker `Suspended` | cut at the cycle focus moved; episode suspended, may resume | `V(G_t^cut \| H_t) + γ^{T−t}·B`, `B` = the controllers' estimate at the cut state (bootstrapped N-step return) |
| **Timed out** | tracker `Closed(TIMED_OUT)` | APPROACH steps: `G = 0`; every other step keeps its Monte Carlo return | `V(G_t \| H_t)` (0 for APPROACH) |
| **Death** | creature dies | closes with the lethal deviation as the last outcome | `V(G_t \| H_t)` |

Out: the engram fields and persistence of G on engrams (#111), the episodic and planner estimators (#113, #114), SE
calibration (#115), the arbiter and its decision table (#104, #105), and any change to selection behaviour. With the
flag off nothing changes.

## What exists today (verified in code)

- `FocusTracker.update` returns `List<FocusEvent>` (`Opened/Closed(outcome)/Suspended/Resumed`, each `(cycle, drive)`),
  but `FullAppraisal.emotionToRegulate` (`:280`) **ignores the return value**. `FocusTracker.onDeath(cycle)` exists and has
  **no caller**. Events carry no episode id; one episode per drive at a time, so `drive` identifies it.
- `HomeostaticRegulation` computes K per stimulus (`persistDriveOutcome`, #100: realised after − before, all-zero
  skipped, `BASELINE` for unattributed drift) and only **persists** it. Its `EvaluationStimulus` to `Valuation` carries a
  single scalar `arousalVariation`, not the four-vector K.
- `Valuation` already is where outcomes are valued and memory is consolidated (`reinforceWarmTraces`, operant nudge) —
  the Mapa design, and the place you pointed to for close-out.
- Components are separate actors. Cross-actor shared state is solved here by **TypedActor facades** (`MemorySystem`,
  `OperantConditioning`, `ExpectancyPredictor`) created in `CreatureActor`; where a read needs a value across the
  boundary there is also the `AtomicReference` bridge (`wmFilterRef`). `memorySystem.currentDecisionCycle()` is the shared
  cycle epoch (ticked by `FullAppraisal`; same increment as `cognitiveCycle`).
- No message currently tells `Valuation` what was decided, or what the tracker did.

## Design

### 1. Pure core — `EpisodeBuffer` (package `creature.arbiter`, no actor, no I/O)

Single-writer, not thread-safe, owned by `Valuation`. Input events, all stamped with the decision cycle:

- `onDecision(cycle, Action, double[] h, String focalDrive, double chosenQ)` — one **step**: the action (type, object
  type, distance at decision time), `H_t` at decision, the focal drive, and the arbiter's estimate of the chosen
  candidate (`NaN` until #104 exists). A step belongs to the open segment of its focal drive; no focus → not buffered.
- `onOutcome(cycle, double[] k)` — realised K of one act that arrived in `cycle`. Attributed K only (see Q3).
- `onFocus(FocusEvent)` — `Opened` starts a segment with a new episode id; `Suspended` flushes it; `Resumed` starts a
  new segment of the **same** episode id; `Closed` flushes the open segment (a `Closed` of an already-flushed suspended
  episode only drops its bookkeeping).
- `onDeath(cycle, double[] lethalDeviation)`.

Output: `ClosedStep(episodeId, drive, closeType, cycle, Action, h, double[] g, double bootstrap, double value)` delivered to
registered `ReturnSink`s. `value` is `V(G_t|H_t)` (+ bootstrap term) already computed with the injected `HomeostaticValue`.
`g` is the 4-vector (what #111 will store); `bootstrap` is `NaN` unless pre-empted.

Return arithmetic: with `K_c` = sum of K arrived in cycle `c`, `G_t = K_t + γ·G_{t+1}` computed backward over the flushed
cycles `t … T`; every step that shares a cycle shares that tail. γ is a constructor argument, default `2^(−1/5)`.

### 2. Sinks

- `ReturnSink` interface (`void onClosed(List<ClosedStep>)`), so #111 (engram writer) and #105 (realised V) attach later
  without touching the buffer.
- First sink: `OperantReturnSink` → `ValueLearner.record(drive, action, value)` for each step (the operant table of #103).
- Second sink: persistence (Q4).

### 3. Message plumbing (the part that is not obvious)

Three producers, one consumer, no shared ordering. New immutable messages into `Valuation`:

| From | Message | When |
|---|---|---|
| `FullAppraisal` | `DecisionRecord(cycle, action, h, focalDrive, chosenQ)` | every cycle (after selection) |
| `FullAppraisal` | `FocusEvents(cycle, List<FocusEvent>)` | every cycle the tracker returns any (`update` result stops being dropped) |
| `HomeostaticRegulation` | `DriveOutcome(cycle?, double[] k)` | per stimulus with attributed non-zero K — the same K it already persists |
| `CreatureActor` | `CreatureDied(cycle, double[] lethalDeviation)` | on `setAlive(false)` |

`DecisionRecord` and `FocusEvents` are FIFO between the same pair of actors. `DriveOutcome` is a different channel, so a
close may overtake the last K that caused it. **Rule:** `Valuation` stamps each K with `memorySystem.currentDecisionCycle()`
on arrival (as it already does for warm traces) and **applies a close one cycle later** (`SETTLE_CYCLES = 1`, constructor
argument): the close is held until a decision record with `cycle > closeCycle` arrives, or the creature dies. A K stamped
≤ the close cycle still counts. Tested with a reordered-arrival case.

Sending is gated by the same flag that creates the tracker (`focalDriveEnabled`, #99) so with it off no message is sent.
Baseline drift (adrenergic/adenosinergic, per tick) is **not** sent — it would dominate message volume.

### 4. Where the learned table lives

`Valuation` writes (single writer), `FullAppraisal` will read in #104 — different actors. Follow the codebase's own pattern:
`OperantEstimator` is created in `CreatureActor` as a **TypedActor facade** behind a combined interface
`OperantStore extends ValueEstimator, ValueLearner` (a new tiny interface; `OperantEstimator implements OperantStore`), gated by a new
`LearningSettings.operantEstimatorEnabled` (default off; requires `focalDriveEnabled`, validated at parse). Its focal-drive
supplier reads an `AtomicReference<String>` that `FullAppraisal` sets each cycle (same bridge as `wmFilterRef`). Reads are a
blocking ask, the same cost the legacy `getProbabilities` already pays per cycle — measured in the verification run (Q2).

### 5. Persistence (Q4)

New `EpisodeReturnState` → table `episode_return` → Parquet `episode_returns`, one row per closed step: creature, episode
id, drive, close type, step cycle, action, object type, distance bin source value, `h`(4), `g`(4), `bootstrap`, `value`,
steps-to-close. Same shape as `drive_outcome` (#100: `TableSchemas`, `tables.py`, round-trip test).

## Tests (`EpisodeBufferTest`, plus `ValuationEpisodeTest`)

Pure buffer, one test per closing type as the issue requires:
1. **Regulated:** a hand-built episode (steps at known cycles, K at known cycles) → `G` equals the hand-computed
   discounted sum for every step; the value equals `HomeostaticValue.value(g, h_t)` (reproduces paper §4.1's 1.95 example
   shape); a step before the eat gets the discounted credit, the eat step the largest.
2. **Pre-empted:** G cut at the cut cycle, `value = V(G^cut|H_t) + γ^{T−t}·B` with the chosenQ of the decision at the cut cycle;
   `bootstrap` exposed; the same drive resuming within the window opens a new segment of the **same** episode id, whose later
   steps are not mixed with the flushed ones; a suspended episode that then times out writes nothing twice.
3. **Timed out:** APPROACH steps → `g = 0`, `value = 0`; a consummatory step in the same episode keeps its Monte Carlo
   return.
4. **Death:** the lethal deviation is the last outcome; all buffered steps get a strongly negative value; `onDeath` closes
   suspended episodes too (matches `FocusTracker.onDeath`).
5. Not queried while open: the buffer exposes nothing about open steps (no getter that a decision could read).
6. Settle rule: K arriving after the close event but stamped ≤ the close cycle is included; K stamped later is not.
7. No-focus steps and baseline K are never buffered.
8. `γ` and `SETTLE_CYCLES` are constructor arguments; invalid values rejected.
`ValuationEpisodeTest` (existing component-test harness): the three messages in adversarial order produce the same
`ClosedStep`s as in order, and `OperantReturnSink` makes `OperantEstimator.estimate` move for the recorded cell.

## Proposals needing a decision

- **Q1 — Which steps are "approach" at timeout.** Literal (`ActionType.APPROACH` only; recommended — it is the paper's word,
  and AVOID/ESCAPE relieve pain, so they did lead somewhere) vs. every non-consummatory action.
- **Q2 — Estimator ownership.** TypedActor facade created in `CreatureActor` (recommended — the codebase's established
  pattern, precedent for blocking reads) vs. `Valuation` owning it and pushing cell snapshots to `FullAppraisal`
  (message-passed read model; staler, more code, no precedent).
- **Q3 — Baseline drift in G.** Exclude (recommended; the paper defines K as the change *an action caused*, and it keeps
  messages cheap) vs. include as a built-in time cost on long episodes. A constructor switch is **not** added until someone
  needs it (#100 already stores `BASELINE` rows).
- **Q4 — Persist closed steps now.** Yes (recommended: real-data check like #100's identity check, and #105/#115 need the
  realised value at close) vs. defer persistence to #105/#111 and verify only by unit tests and logs. Cost: a table, an
  extraction query and a round-trip test; volume ≈ one row per focused decision.
- **Q5 — Create the operant estimator in this issue.** Yes (recommended: "written to the long-term store" has no meaning
  otherwise, and ownership was left to this issue) vs. stop at `ReturnSink` and let #104 create it.
- **Q6 — Death wiring.** Wire `CreatureDied` from `setAlive(false)` best-effort (recommended) vs. test the buffer's death
  path only. The per-creature table dies with the creature, so death only matters for telemetry (`episode_returns`); the
  risk is the message being lost to actor stop — checked in the verification run.

## Risks

- **Ordering across three producers** (above) — settle rule + adversarial-order test.
- **Message volume.** One `DecisionRecord` per cycle plus one `DriveOutcome` per attributed outcome; baseline excluded. Compared
  against `chosen_action_state` rows (one per cycle already persisted).
- **`FocusEvent` carries no episode id.** Safe because there is one episode per drive; the buffer assigns ids. If #98 ever
  allows two episodes per drive, events must grow an id — noted, not built.
- **Bootstrap value before #104.** `chosenQ = NaN` ⇒ `B = 0` (the prior `Q0`); pre-empted values are then the truncated
  return only. Documented; becomes real in #104.
- **Facade blocking latency** for `estimate` in #104 — measured here with a synthetic read loop.

## Verification

`mvn test -Dtest='EpisodeBufferTest,ValuationEpisodeTest'`, full `mvn test`, `mvn package`, `graphify update .`.
Then a short A0 run (`simulations/arbiter_a0.conf` with `focalDriveEnabled` and `operantEstimatorEnabled`, UI on per the
local-experiment rule) and an extraction. Checks on real data: every closing type occurs; Σ K attributed to closed steps ≤
Σ K in `drive_outcome`; per-step `value` re-derives from the persisted `h` and `g`; the operant table's cells fill
(an `estimate` call from a test harness shows `n > 0`); no deadlock or slowdown from the facade (cycle rate unchanged within
noise). Per CLAUDE.md step 5, a hypothesis experiment needs a reader of the learned values — arrives with #104/#109; this issue's
evidence is the identity and invariant checks above, recorded in the diary.

## Implementation notes and deviations (2026-10-26)

- **Death is wired from `CreatureActor.kill()`, not `setAlive(false)`.** Nothing in the codebase calls `setAlive`;
  death goes through `kill()` from `PartialAppraisal.checkDeath`, and `setAlive(false)` calls `kill()`. `CreatureDied`
  is sent best-effort from `kill()` immediately before the components are stopped, so it is likely dropped (the
  `Valuation` mailbox is stopped with the rest). **Not verified.** Until it is, death closes are absent from the data.
- **Lethal deviation (not specified in the plan):** the dominant basic drive is pushed to `Constants.MAX_AROUSAL_LEVEL`
  (the others are zero). A zero K would leave death steps with no negative credit, contrary to test 4's intent.
- **`DriveOutcomeMessage` carries K only, no cycle.** `Valuation` stamps each K on arrival (the plan's rule); a
  sender-side cycle would need a blocking memory call per outcome and would not be authoritative.
- **`ClosedStep` carries `closeCycle`** in addition to the plan's fields, so steps-to-close can be stored.
- **The settle rule lives in `EpisodeBuffer`** (held pending closes flushed when a decision with cycle
  `>= closeCycle + settleCycles` arrives, or at death), not in `Valuation`. `Valuation` owns the buffer.
- **Pre-empted bootstrap** is the chosen Q of the decision *at the cut cycle*, looked up when the held close is written
  (that decision arrives before the write). `B = 0` while the Q is NaN.
- **Persistence naming:** table `episode_return` (as the brief asked, not `*_state`). Columns follow `drive_outcome`:
  `seq` and `time_ms` are included, `steps_to_close` is stored, `bootstrap` is a null column unless pre-empted.
- **Facade wiring landed in the stage 3 commit**, not stage 4: the `Creature.operantStore()` accessor it needs is
  part of the plumbing. The `LearningSettings` validation (`operantEstimatorEnabled` requires `focalDriveEnabled`)
  sits in its constructor, which the parser calls.
- **Test harness:** `TestingCreature` uses an in-process `OperantEstimator` (no facade, no Akka).
  `ValuationEpisodeTest` drives `Valuation` with messages directly, not through `FullAppraisal`/`HomeostaticRegulation`.

### Open concerns

- **Boundary double count.** A K stamped at a cut or close cycle `T` counts in the closing segment (`<= T`). If the next
  segment opens at `T` and has a step there, the same K also counts in its return. Inherent to the stated rule, not
  resolved here.
- **Baseline exclusion** is by construction only (`HomeostaticRegulation` sends attributed K), with no test that
  asserts a baseline message is never sent.
- **Arrival stamping** makes a K's discount depend on its delivery delay (a K delayed past a cycle is discounted one
  cycle more). The settle rule keeps the *membership* stable; the *weight* still follows arrival.

### Verification not run (per the brief)

- A0 run (`simulations/arbiter_a0.conf`, `focalDriveEnabled` + `operantEstimatorEnabled`, UI on) and extraction on real data.
- Checks on real data: every closing type occurs; `Σ K` on closed steps `<= Σ K` in `drive_outcome`; per-step `value`
  re-derived from persisted `h` and `g` (checked only on a synthetic fixture); operant cells fill from a real run.
- Facade latency and cycle-rate (no deadlock or slowdown) measurement.
- Death delivery: whether `CreatureDied` survives the `kill()` stop sequence.


## Code-review follow-ups (fixed on this branch)

1. **Death delivery.** `CreatureActor.kill()` stopped `Valuation` straight after telling it `CreatureDied`, so the
   message was dropped. `Valuation` now gets a `PoisonPill` instead: the component mailbox is FIFO and handles a
   `PoisonPill` after the messages ahead of it. The lethal-deviation read is wrapped so a failure only costs the
   death close-out and teardown still completes (finding 5).
2. **Isolation.** `Valuation` catches `RuntimeException` from the episode path, logs it and replaces the buffer with a
   fresh one, so a broken tracker invariant cannot take down the reward valuation. `EpisodeBuffer.emit` isolates each
   sink, and `OperantReturnSink` skips a non-finite value, since `record` would throw inside the TypedActor and
   restart it with an empty table.
3. **Null perception** in `persistEpisodeReturns` gives a null target and a NaN distance.
4. **Pre-`Opened` outcomes.** `onOutcome` keeps a pre-roll of `settleCycles + 1` cycles when no segment is live, so the K
   of an episode's first act is counted when it beats the `Opened` event.

Deferred, with the reason: `focalDriveRef` visibility across the facade (matters when #104 reads it); the per-cycle
`DecisionRecord` allocation (measure in the A0 run first); the true return of a pre-empted step (bootstrapped by
design until #104 provides a real Q); delayed-K discounting (arrival stamping, known approximation).

## Revision after PR review: the buffer lives behind the memory system (proposed, not implemented)

Review comment (PR #124, `FullAppraisal.java:332`): the `EpisodeBuffer` is a write-only working memory, so it belongs
behind `MemorySystem`; `FullAppraisal` and `Valuation` should reach it through the memory system, as the buffer
between decision/focus and experience valuation.

**Shape**
- `MemorySystem` (TypedActor, already per creature and already owner of the decision-cycle epoch) gains the episode
  methods; `MemorySystemActor` owns the one `EpisodeBuffer` instance. No other component holds it.
- Write side, all `void` (asynchronous tells on the TypedActor, FIFO per caller):
  `FullAppraisal` -> `recordDecision(DecisionRecord)` and `recordFocusEvents(List<FocusEvent>)`;
  `HomeostaticRegulation` -> `recordOutcome(double[] k)`. The `DecisionRecord` / `FocusEvents` / `DriveOutcomeMessage`
  messages to `Valuation` go away.
- Cycle stamping moves inside the memory actor: an outcome is stamped with the actor's own decision cycle when it is
  processed, so the blocking `currentDecisionCycle()` call per outcome disappears (review finding 4's arrival-stamp
  concern is then one clock, not two).
- Read side (valuation): `Valuation` calls `memory.drainClosedEpisodes()` and valuates what comes back: it writes the
  operant store and persists `episode_return`. Sinks stay in `Valuation` (it owns persistence and the learner), the
  buffer no longer needs sinks; `ReturnSink` is replaced by the returned `List<ClosedStep>`.
- Death: `kill()` still tells `Valuation` `CreatureDied` followed by a `PoisonPill`. `Valuation` calls
  `memory.closeOnDeath(cycle, lethalDeviation)` and valuates the returned steps. Memory is stopped after Valuation.
- Per-cycle `DecisionRecord` allocation (deferred finding) is untouched.

**Open questions**
1. *Pull or push to valuation.* Pull (above): `Valuation` drains when it next runs, so closed episodes are valuated one
   Valuation message late. Push: the memory actor tells `Valuation` a `ClosedEpisodes` message as soon as it settles; no
   blocking call and no lateness, but memory then holds a `Valuation` ref. Recommendation: **push**, since the memory
   system already gets refs from `CreatureActor` and the flow stays message-driven.
2. *Where the buffer's tests live.* `EpisodeBuffer` itself is unchanged and keeps `EpisodeBufferTest`;
   `ValuationEpisodeTest` is reworked to go through the memory facade.

**Out of scope here:** #104's read path, the focal-drive ref race.
