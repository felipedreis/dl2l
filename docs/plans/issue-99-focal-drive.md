# Issue #99 — Focal drive replaces the per-cycle most-aroused emotion (flagged), plus the A0 baseline config

Status: **approved 2026-10-21 (all five proposals taken); implemented on `feature/issue-99-focal-drive`. Simulation-run verification pending — see Verification.**
Context: A1 of the arbiter ladder (`docs/plans/2026-q4-research-plan.md`). Depends on #98 (`FocusTracker`,
merged). #105 (decision-state table) and #108 (A1 campaign) build on it.

## Scope

In:
1. A `LearningSettings` flag `focalDriveEnabled` (default **off**). On, the emotion handed to the selection
   filters is the `FocusTracker`'s focal drive; off, nothing changes.
2. The focal drive persisted on every decision.
3. `simulations/arbiter_a0.conf` — the A0 baseline every arbiter arm derives from.

Out: persisting `FocusEvent`s / episode buffer (#110), decision-state table (#105), calibrating `Params` (#107),
the arbiter itself.

## What exists today

- `PartialAppraisal.buildEmotionalStimulus` (`:260`) picks `emotionalSystem.getMaxArousal()` every cycle and
  ships only that `Emotion` in `EmotionalStimulus` (`maxEmotion`). It also feeds that level into
  `normalizedBehaviouralEfficiency`.
- `FullAppraisal.selectAction` (`:251`) passes `emotional.getMaxEmotion()` to `actionSelection.selectOne`.
  Of the filters, `ActionTendencyFilter` is the one that reads it (`toRegulate.getName()`); `null` is
  already handled there (pass-through).
- `FullAppraisal.updateMemory` (`:264`) stores `emotional.maxEmotion` in `ShortTermMemory`.
- `FullAppraisal.persistDecision` (`:338`) writes `ChosenActionState` → `chosen_action_state` table
  (`TableSchemas:193`) → `actions` Parquet table (`scripts/dl2l_data/tables.py:48`).
- `FullAppraisal` owns `cognitiveCycle` and the SLEEP anti-flicker gate (`enforceHysteresisGate`,
  `MIN_SLEEP_TICKS`). `FocusTracker` is unwired.
- Flags are parsed in `Simulation.java:~125` (`ls.hasPath(..) && ls.getBoolean(..)`) and carried by
  `LearningSettings`, whose telescoping constructors end at a 10-arg one.

## Design

**1. Per-drive levels reach FullAppraisal.** `EmotionalStimulus` only carries the winner, but the tracker needs
all four drives. Add an immutable `Map<String, Double> driveLevels` (HUNGER, SLEEP, PAIN, TEDIUM — the
`EmotionalSystemActor.ACTIVE` set, i.e. `FocusTracker.DRIVES`) to `EmotionalStimulus`, filled in
`buildEmotionalStimulus` from `emotionalSystem.getLevel(name)`. Existing 5-arg constructor kept (empty map) so
tests and `TestingCreature` don't change. `maxEmotion` stays, so the per-cycle max is still computed and
`behaviouralEfficiency` is untouched.

**2. Flag.** `LearningSettings.focalDriveEnabled`, default false, parsed in `Simulation` like the other
default-off flags, one more constructor overload (11-arg) delegating from the 10-arg one.

**3. Wiring in `FullAppraisal.onEmotionalStimulus`.** If the flag is on, create a `FocusTracker(Params.defaults())`
in `preStart`; each cycle, before `selectAction`, call `tracker.update(cognitiveCycle, emotional.driveLevels)`.
The emotion for selection becomes the focal drive's `Emotion`; with the flag off the code path is byte-for-byte
the current one (no tracker is created, no extra RNG draw — the tracker has none anyway).
Resolving the focal name to an `Emotion` object: `EmotionalStimulus` also carries the `Emotion` objects for the
four drives (instead of just levels) — see Q2; that avoids reaching into `PartialAppraisal`'s mutable
`EmotionalSystem` from another actor.

**4. Persistence.** New nullable column `focal_drive` (STRING) on `chosen_action_state`:
`ChosenActionState.focalDrive` + `TableSchemas` + the `actions` query in `tables.py`
(`cas.focal_drive AS focal_drive`). Null when the flag is off or no episode is open. Per-decision, as the issue
asks; episode events are #110's.

**5. `simulations/arbiter_a0.conf`.** Copy of `p84_legacy_nomem.conf` (action tendency on; neuromodulation,
expectancy, orexin, endocrine off; `reposition = true`, `repositionDelaySeconds = 0`; filters
`TARGET_DISTANCE, AFFORDANCE, RANDOM`), header comment rewritten to say what it is and that every arbiter arm
derives from it (`include` + override `learningSettings.focalDriveEnabled`, so arms differ by one line).
No `seed` in the file: seeds come from the experiment spec's `seeds:` list (#95).

## Tests

- `EmotionalStimulus` carries the four levels; unmodifiable.
- `Simulation` parses `focalDriveEnabled` (absent ⇒ false) — extend `SimulationSettingsExtensionTest`.
- `FocalDriveSelectionTest` (via `TestingCreature`/`TestingHarness`): flag on, HUNGER focal but SLEEP momentarily
  higher within Δ ⇒ `ActionTendencyFilter` is given HUNGER; flag off ⇒ given the per-cycle max, as today.
- Persistence: `chosen_action_state` row carries `focal_drive` when on, null when off
  (pattern of `BdSinkFunctionalTest`).
- **Flag-off identity** — see Verification.

## Verification

1. `mvn test` (all green) and `mvn package` (clean).
2. *(Revised during implementation.)* The p97 seed-replay check established that same-seed runs share the
   initial world but **not** trajectories, so a whole-run comparison against `main` cannot show identity.
   Done instead: by construction (no tracker is created with the flag off, `selectAction` receives
   `getMaxEmotion()` as before) plus `FocalDriveSelectionTest.calmCreature_choosesTheSameActionsWithTheFlagOnAndOff`
   (seeded harness, 40 ticks, identical actions on/off). The original text follows.
   **"Flag off ⇒ seeded run identical to A0"** (the issue's done-when): run `arbiter_a0.conf` twice with the
   same seed on this branch, and once on `main` with `p84_legacy_nomem.conf` — same seed — then compare the
   `actions` tables (action_type, target_key, selection_type per creature per decision). Same-seed identity of
   two runs was the #95 criterion; here the extra claim is that this branch with the flag off equals `main`.
   Wall-clock jitter may break exact identity (cognitive cycles are tick-driven, #85) — if it does, the check
   falls back to the per-creature decision *sequence* up to first divergence and I report where it diverges
   rather than claiming identity.
3. **Flag on, short run** (UI on, per the local-experiments rule): `focal_drive` non-null on decisions while a
   drive is above θ_on, null otherwise.
4. Per CLAUDE.md step 5 this is a plumbing change, not a behavioural claim, so no hypothesis experiment here —
   the behavioural check ("fewer focus switches, survival not lower") is the A1 campaign, #108. Say if you want a
   mini-experiment anyway.

After code lands: `graphify update .`.

## Open questions

1. **When no episode is open** (every drive under θ_on, i.e. the creature is in its equilibrium band) what does
   the filter get? **Proposal:** fall back to the per-cycle most-aroused emotion, and persist `focal_drive = null`.
   That keeps calm-state behaviour identical to A0 (WANDER/OBSERVE selection unchanged) so the flag only changes
   behaviour where an episode exists. The alternative — pass `null` (tendency filter becomes a pass-through) —
   changes calm-state behaviour too and confounds the A1 contrast.
2. **Carrying the Emotion.** Proposal: `EmotionalStimulus` gets `Map<String, Emotion> drives` (snapshots), so
   `FullAppraisal` can look up the focal `Emotion` and read levels from it. Fine, or do you prefer levels-only
   plus a name-based `Emotion` lookup?
3. **SLEEP gate.** The issue says the tracker "generalises" `MIN_SLEEP_TICKS`. **Proposal:** keep the gate
   untouched in this issue (flag on or off); whether the tracker's hysteresis makes it redundant is something
   A1 should measure, not assume.
4. **ShortTermMemory.** Keep storing the per-cycle max emotion (proposal; keeps memory/consolidation behaviour
   out of this change) or store the focal drive?
5. **A0 world.** `p84_legacy_nomem` is the *mixed* world (100 red / 100 green / 300 gray, 5 creatures, 120 min,
   where hunger binds per the research plan). The `_simple` variant has no gray apples and runs 30 min.
   **Proposal:** mixed, as in the research plan's finding. And the p97 diary says collapse is gray-world learning
   dynamics — say if A0 should be the gray-free world instead.

## Rollback

Flag defaults off; the additions are one nullable column, one `EmotionalStimulus` field, one conf file.
Reverting the commit restores the previous behaviour. Caveat found while implementing: the `actions` query now
selects `cas.focal_drive`, so re-extracting a raw Arrow dump written *before* this change with the new
`tables.py` fails on the missing column; use the older revision for those.
