# Issue #111 — Episodic engrams: h, G, key k, episode id, steps to outcome

Status: **plan, 2026-11-11** (branch `feature/a3-episodic`). Paper §4.2 (two stores), §4.3. Depends on #110 (`EpisodeBuffer`, `ClosedStep`).
Consumed by #113 (kNN estimator).

## What exists
`EpisodeBuffer` closes episodes into `ClosedStep(episodeId, drive, closeType, cycle, closeCycle, action, h, g, bootstrap, value)`
and delivers them to `ReturnSink`s. Since the review (operant per outcome) no sink feeds the operant table, so the
sink slot is free. `Valuation` receives the `ClosedEpisodes` (and the death close-out) and writes `episode_return`, which
already holds episode id, drive, steps_to_close, h, g, bootstrap, value, object type and distance per closed step.

## Decisions
1. **A new record `EpisodicEngram`, not new fields on `memory.Engram`.** The issue says "extend `Engram`". The existing
   `Engram` is the legacy reinforced *trace* (`emotionDelta`, `eligibility`, laid at reinforcement time, read by
   `MemoryFilter` and the consolidators). An episodic engram is a different object: it is born when an episode closes, carries
   the return vector G and a key. Adding nullable fields to the trace would make every legacy reader check which kind it holds.
   Deviation noted for the PR.
2. `EpisodicEngram(action, objectType, key k, h, g, bootstrapTerm, episodeId, drive, closeType, stepCycle, stepsToOutcome)`.
   `bootstrapTerm = gamma^(T-t) * B` for a pre-empted step, 0 otherwise, so `V(G | H_now) = value(g, H_now) + bootstrapTerm`
   at retrieval (a bootstrap is already a value, not a drive vector). A timed-out APPROACH has `g = 0`, as the buffer wrote it.
3. **Store `EpisodicEngramStore`**: one bounded FIFO per action type (paper §4.2: the per-action store `M_a`), cap 2000 per action
   (constant, recorded in the recipe note). Pure, no actor.
4. **Writer `EngramWriter implements ReturnSink`**: turns `ClosedStep`s into engrams with the `EngramKeyEncoder` (#112) and adds
   them to the store. Invoked by `Valuation` for the `ClosedEpisodes` and the death close-out, beside the `episode_return` write.
5. **Sharing**: the store is read by `FullAppraisal` (decisions) and written by `Valuation`: a TypedActor facade `EpisodicStore`
   (like `OperantStore`), created in `CreatureActor` when `episodicEstimatorEnabled`.
6. **Persistence**: new table `episodic_engram` (`EpisodicEngramState`): creature_key, seq, time_ms, episode_id, drive, close_type,
   step_cycle, steps_to_outcome, action, object_type, h_*, g_*, bootstrap_term, k_00..k_14. `episode_return` is untouched. Extracted by
   `scripts/dl2l_data/tables.py` into `episodic_engrams`. Tests: `TableSchemasTest`, round trip, extraction golden.

## Tests
Store caps per action and keeps order; writer maps a REGULATED, a PRE-EMPTED (bootstrap term) and a TIMED_OUT-APPROACH step;
round trip and golden extraction of the new table.
