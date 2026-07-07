# Plan: Issue #55 — Scope metabolism, arousal & death to the four active emotions

Closes findings A and E from `docs/roadmap/Campos2015_Model_Parity.md`.

## Context

`EmotionalSystemActor.regulateAll(∆)` raises **all nine** emotions every metabolic cycle. The five
disabled emotions (`stress`, `apathy`, `fear`, `curiosity`, `fertility`) have no parasympathetic
decrease path, so they accumulate monotonically at `∆ = 1.5e-3`/cycle and cross `MAX_AROUSAL_LEVEL
= 7` at ≈ 4547 cycles — an undocumented hard lifetime ceiling that confounds every longevity
measurement (including JEPA experiments). Additionally, when the circadian clock is on, `sleep` is
raised by both the metabolic `regulateAll` and the `AdenosinergicStimulus`, double-incrementing it.

## Changes

### `EmotionalSystemActor.java`
- Add `ACTIVE = Set.of(HUNGER, SLEEP, PAIN, TEDIUM)`.
- `regulateAll(delta)`: iterate only over `ACTIVE`.
- `getMaxArousal()`: max over `ACTIVE` only.
- Remove `getMaxComplexArousal()` (never called; complex emotion election deferred — roadmap §8).

### `EmotionalSystem.java`
- Remove `getMaxComplexArousal()` declaration.

### `HomeostaticRegulation.java`
- Add `LearningSettings` constructor parameter.
- `handleAdrenergic`: when circadian enabled, regulate `{HUNGER, PAIN, TEDIUM}` individually
  (circadian owns sleep pressure); when disabled, call `regulateAll` (SLEEP included).

### `CreatureActor.java`
- Pass `effective` to `HomeostaticRegulation` in `componentFactories`.

### `TestingCreature.java`
- Pass `learningSettings` to `HomeostaticRegulation` constructor.

### `EmotionalSystemActorTest.java` / `HomeostaticRegulationTest.java`
New tests per acceptance criteria (see issue #55).

## JEPA vector
The 9-slot `simpleEmotions` list order is preserved. Disabled emotions stay pinned at
`MIN_AROUSAL_LEVEL`; their columns remain present but constant.
