# Issue #100 — Attribute the per-drive outcome vector K to the act that caused it

Status: **draft, awaiting approval.** Revised after reading the arbitration paper draft
(`docs/bib/uncertainty_arbitration.pdf`, §4.1, §4.2, §4.4, App. A step 2).
Context: arbiter story map, `docs/plans/2026-q4-research-plan.md` (A2: value V(K|H) is read from K at decision
time). Supersedes #91 by construction: K is recorded independent of need; need is applied when K is read.

## Scope

In:
1. `HomeostaticRegulation` computes, per regulation event, the **realized** change of each of the four drives
   K = (Δhunger, Δsleep, Δpain, Δtedium) and tags it with the originating action.
2. K persisted (new table `drive_outcome`, extracted as a Parquet table of the same name).
3. Test: summed K over a run equals the drives' total change from regulation.

Out: reading K (estimators, engrams, V(K|H) — A2), changing any selection behaviour, #105/#110 tables.
No flag: this adds a persisted row and touches no decision path.

## What the paper requires of K

- §4.1: K is "the change in drive levels that an action caused", over H = (hunger, sleep, pain, tedium) — exactly
  the four drives here. Engrams store K, not a scalar; V(K|H) = d(H) − d(H+K) is applied at retrieval. K must
  therefore be a plain signed level change, independent of need, with no deficit/set-point/weights baked in.
- §4.2: an engram later stores G_t = Σ γ^j K_{t+j} until its episode closes. So per-step K must be **additive**
  (summing rows over a window gives the window's change) — which is also this issue's "done when" test.
- App. A step 2: "HomeostaticRegulation attributes K to the stimulus the act produced" — matches the design
  below. Joining K to engrams/episodes (key, episode id, steps-to-outcome) is the *rest* of step 2 and belongs to
  the engram/estimator issues, not here; this issue must persist enough to join later (see Q5).
- The paper is silent on background drift (adrenergic/adenosinergic, immune decay). It matters: G summed over an
  episode will either include drift (a built-in time cost on long episodes) or not. Keeping it as a separate
  `BASELINE` row lets the arbiter issues choose without re-collecting data.

## What exists today

- `HomeostaticRegulation.onReceive` already takes `emotionalSnapshot()` before/after each stimulus and persists
  `InternalDynamicState(before, after, change)` — but it holds all 9 emotions as two separate rows plus a link row,
  carries no action, and there is no clean delta to sum.
- Each handler knows the originating action: `handleNutritive` → EAT, `handleCholinergic` → SLEEP (hard-coded);
  `Nociceptive`/`Analgesic`/`Tedium` carry `s.action`. `Adrenergic`/`Adenosinergic`/immune-decay carry none
  (background metabolic drift).
- Drives are also mutated outside the component: `NeuromodulatorSystem` (TEDIUM, off in A0) and `EndocrineSystem`
  (STRESS, not part of K).

## Design

**1. What K is.** `DriveOutcome(action, objectType?, dHunger, dSleep, dPain, dTedium)` from `after − before` of the
four `Constants` drives around each stimulus, i.e. *realized* (post-clamp) change — same notion as
`realizedDelta`, so a clamped drive contributes 0. Taken from the existing snapshots, so no handler is
changed and K cannot disagree with the levels.

**2. Attribution.** Action comes from the stimulus: EAT (nutritive), SLEEP (cholinergic), `s.action` for
nociceptive/analgesic/tedium. No action (null `s.action`, adrenergic, adenosinergic, immune decay) →
**`BASELINE`** pseudo-action. Without that bucket the sum-matches test cannot hold, and baseline drift is a
real quantity the arbiter value should be able to subtract. Stored as a string column, `BASELINE` not an
`ActionType` member, so nothing that switches on `ActionType` is affected.

**3. Persistence.** New `DriveOutcomeState` (+ `TableSchemas` entry `drive_outcome`, + `tables.py` query →
`drive_outcomes` Parquet): creature key, `cycle`/tick, `action`, `object_type`, four deltas, plus
`changestimulusstate_id` like its siblings so it joins to the stimulus. One row per stimulus whose K is not
all-zero (idle ticks would otherwise dominate row count; zero rows contribute nothing to the sum).

**4. Test.** `HomeostaticRegulationTest` (existing harness): drive a mixed script — nutritive, cholinergic, adrenergic
drift, nociceptive with and without action, analgesic, tedium, plus saturation at floor and ceiling — then assert
(a) per-action rows carry the expected K, (b) Σ K per drive == final level − initial level, with no other writer
active. Plus a schema/round-trip check that the table is registered, in `BdSinkFunctionalTest`'s style.

## Proposals needing a decision

- **Q1 — Where K is computed.** (a) from the existing before/after snapshots (recommended: one source of truth, zero
  handler edits); (b) each handler returns its own delta (more invasive, can drift from the real level).
- **Q2 — Out-of-component writers.** `NeuromodulatorSystem` tedium changes bypass K. Neuromodulation is off from A0,
  so (a) leave and document that the sum identity holds *from regulation* as the issue says (recommended); or
  (b) route them through `HomeostaticRegulation` — scope creep, defer unless neuromod returns (A7).
- **Q3 — Zero rows.** Skip all-zero K (recommended) or write every stimulus.
- **Q4 — Row granularity.** Per stimulus (recommended; the arbiter can aggregate per decision) vs. per batch.
- **Q5 — Join key for the later engram/episode link.** Rows carry creature, cycle, action, object type + target key
  (the object the act was aimed at, as `EvaluationStimulus` already does) and the stimulus-state id. Is that enough
  for how you plan to join K to a decision (#105) — or should the decision's id be threaded to the regulation
  stimulus now? Threading it touches Mouth/Body stimuli (more invasive); I recommend not, until #105 shows it's needed.

## Verification

`mvn test -Dtest=HomeostaticRegulationTest` then full `mvn test`, `mvn package`. Then a short A0 run
(`simulations/arbiter_a0.conf`, UI on per local-experiment rule) and an extraction, checking in the real data that Σ K per
creature ≈ Δ levels. Per CLAUDE.md step 5, a mini-experiment is only meaningful if K is *read*; here nothing reads it,
so this issue's evidence is the identity check on real data, recorded in a short note in the diary — no hypothesis
experiment/report until A2.
