# Issue #98 — FocusTracker: episodes as attentional focus on one drive, with hysteresis

Status: **approved 2026-10-19; implemented on `feature/issue-98-focus-tracker`.** Open questions were resolved by taking each stated proposal (raw arousal units with H* = 0.18 and scale 1; tMax = 200 placeholder and R = 10; corridor test written from the rule as stated, to be checked against draft §7.3; package `creature/arbiter`).
Context: A1 of the arbiter ladder (`docs/plans/2026-q4-research-plan.md`, milestone "Arbiter R1 — Walking
skeleton"). First unblocked story on the critical path: #99 (focal drive replaces per-cycle max emotion)
depends on it; #110 (episode buffer), #105 (decision-state table) and #108 (A1 campaign) build on top.

## Scope

In: a `FocusTracker` class, pure Java, no Akka and no I/O, with unit tests for every transition.
Out (deferred to #99): wiring into `FullAppraisal`, the `LearningSettings` flag, persistence, the A0 config.
This keeps #98 behaviour-neutral by construction — nothing in the running simulation changes.

## What exists today

- `PartialAppraisal.buildEmotionalStimulus` picks `emotionalSystem.getMaxArousal()` every cycle (the
  per-cycle most-aroused of HUNGER, SLEEP, PAIN, TEDIUM) and ships only that `Emotion` in `EmotionalStimulus`.
  Per-drive levels are available via `EmotionalSystem.getLevel(name)`.
- The only existing anti-flicker mechanism is the SLEEP gate (`FullAppraisal.enforceHysteresisGate`,
  `MIN_SLEEP_TICKS = 10`); #99 generalises it.
- Arousal is clamped to `[MIN_AROUSAL_LEVEL = 0.18, MAX_AROUSAL_LEVEL = 7]`; `EQUILIBRIUM_BAND_UPPER = 2.0`.

## Design

`creature/arbiter/FocusTracker.java` (package choice: open question 4), constructed from an immutable
`FocusTracker.Params` record (θ_on, θ_off, Δ, θ_crit, T_max, R, H*, scale) so the pre-registration (#107) can
cite one object and tests can build tight variants.

```java
List<FocusEvent> update(int cycle, Map<String, Double> levels)   // once per cognitive cycle
List<FocusEvent> onDeath(int cycle)                               // closes the open episode, outcome DEATH
Optional<String> focalDrive()                                     // null when no episode is open
```

`FocusEvent` = sealed record: `Opened(cycle, drive)`, `Closed(cycle, drive, outcome)` with
`outcome ∈ {REGULATED, TIMED_OUT, DEATH}`, `Suspended(cycle, drive, by)`, `Resumed(cycle, drive)`.
Events are returned, not pushed to listeners, so #110 can consume them and tests can assert on them.

Urgency: `u_i = w_i · max(0, H_i − H*_i) / scale`, w = 1 (see open question 1 for `scale`).

Transition rules, evaluated in this order each cycle:

1. **Close (regulated):** focal `u ≤ θ_off` → `Closed(REGULATED)`; focus is empty.
2. **Close (timed out):** cycles since open `> T_max` → `Closed(TIMED_OUT)`; the drive may re-open next cycle
   if still above θ_on (a fresh episode, not a resume).
3. **Emergency override:** if the focal drive's `u < θ_crit` and another drive's `u ≥ θ_crit` → that drive
   takes focus immediately, ignoring Δ; the old episode is suspended. If the focal drive is itself at or above
   θ_crit it keeps focus (the override applies "only while the focal drive is below θ_crit").
4. **Pre-emption with hysteresis:** another drive takes focus only if `u_other > u_focal + Δ`; ties and
   near-ties keep the incumbent. Among challengers the highest `u` wins, ties broken by fixed drive order
   (HUNGER, SLEEP, PAIN, TEDIUM) so seeded runs stay deterministic.
5. **Open:** with no focus, the highest `u ≥ θ_on` opens an episode (a suspended one for that drive resumes
   instead, see 6).
6. **Suspend / resume:** a pre-empted episode is suspended with a deadline `cycle + R`. If focus returns to
   that drive before the deadline and its `u > θ_off`, it emits `Resumed` and continues with its original
   open cycle (the T_max clock counts only active cycles). Past the deadline it is dropped with
   `Closed(TIMED_OUT)`. If the suspended drive falls to `≤ θ_off` meanwhile it closes as `REGULATED`.
   At most one suspended episode per drive.

State is a small mutable object (focal drive, open cycle, active-cycle count, map of suspended episodes);
not thread-safe, owned by `FullAppraisal`'s single-threaded actor.

## Tests (`FocusTrackerTest`, JUnit 5)

One test per transition named in the issue, plus the edges:
`opensAtThetaOn`, `staysClosedBelowThetaOn`, `closesRegulatedAtThetaOff`, `hysteresisBandBetweenOnAndOff`,
`timesOutAfterTMax`, `closesOnDeath`, `preemptedOnlyAboveDelta`, `noSwitchWithinDelta`, `emergencyOverride`,
`noOverrideWhenFocalAboveCrit`, `suspendedResumesWithinR`, `suspendedDroppedAfterR`,
`suspendedClosesRegulatedWhenDriveFalls`, `deterministicTieBreak`, `tMaxCountsActiveCyclesOnly`.
Plus a **corridor** property test reproducing draft §7.3 (see open question 3).

## Verification

`mvn test -Dtest=FocusTrackerTest`, then full `mvn test` and `mvn package` (must stay clean). No simulation
run: the class is unwired, so a run could not differ. The behavioural check belongs to #99
("flag off ⇒ seeded run identical to A0") and the A1 campaign (#108).

After code lands: `graphify update .`.

## Open questions (need an answer before implementing)

1. **Units of the thresholds.** The issue gives `u_i = w·max(0, H−H*)/(L−H*)`, which lies in [0, 1], yet
   θ_on = 2.0 and θ_crit = 4.0 can never be reached by a quantity bounded by 1. The numbers only make sense
   in raw arousal units (θ_on = 2.0 coincides with `EQUILIBRIUM_BAND_UPPER`; θ_crit = 4.0 sits inside
   `[0.18, 7]`). **Proposal:** `scale = 1` and `H* = MIN_AROUSAL_LEVEL = 0.18`, i.e. thresholds are in arousal
   units above baseline, with `scale` kept in `Params` so the normalised reading is one constant away.
   Is that what the draft meant, or is H* something else (e.g. 0, or the 2.0 band edge)?
2. **T_max and R.** T_max = 3 × median steps-to-outcome needs data; R is unspecified. **Proposal:** T_max as a
   `Params` field defaulting to a placeholder, calibrated by a small script over existing p84 `legacy_nomem`
   Parquet (cycles from a drive crossing θ_on to returning below θ_off) before #107 pre-registers it; R
   defaults to `MIN_SLEEP_TICKS = 10` cycles. Both are recorded in the recipe, not tuned.
3. **Draft §7.3 corridor rules.** The arbitration draft PDF is "not in the repo" and no copy was found under
   `docs/`. I cannot reproduce "no switching with Δ ≥ 0.15" without the exact scenario. **Proposal:** until the
   draft is available, the corridor test asserts the property as stated — two drives whose urgencies stay
   within Δ of each other never trade focus — and I fix it against §7.3 once you drop the PDF in
   `docs/roadmap/`.
4. **Package.** `creature/components` (beside the SLEEP episode logic's owner) vs a new `creature/arbiter`
   package that #102–#104 will also need. I'd start in `creature/arbiter`, since six more arbiter classes
   follow; say if you prefer otherwise.

## Rollback

Additive: one new class, one test, one doc. Deleting them reverts the change.
