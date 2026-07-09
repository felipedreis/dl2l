# Implementation Plan: Orexin / Cortisol / Endocrine System

**HLD:** `docs/hld/orexin_cortisol_stress_wakefulness.md`
**Target issue:** post-#57 milestone (orexin + cortisol/stress)

---

## Overview

Three components to implement in one coherent unit:

1. **Orexin** — per-cycle release from `PartialAppraisal` → `NeuromodulatorSystem` → `orexinTonic` cached in `FullAppraisal` → SLEEP gate
2. **Cortisol / EndocrineSystem** — slow integrator; two cortisol sources → activates STRESS affect:
   - *Circadian morning pulse*: `PartialAppraisal` fires once per period (phase wrap)
   - *Chronic stressors*: `HomeostaticRegulation` fires whenever a drive arousal exceeds threshold
3. **Feature flags** — `orexinEnabled`, `endocrineEnabled` in `LearningSettings` (default-off)

---

## New Files

### `stimuli/OrexinergicStimulus.java`
Same structure as `AdenosinergicStimulus`. Single field: `double release` (∈ [0, 1]).

```java
public class OrexinergicStimulus extends Stimulus {
    public final double release;
    public OrexinergicStimulus(SequentialId origin, SequentialId stimulusId, double release) {
        super(origin, stimulusId);
        this.release = release;
    }
}
```

### `stimuli/CortisolStimulus.java`
Single field: `double magnitude`.

```java
public class CortisolStimulus extends Stimulus {
    public final double magnitude;
    public CortisolStimulus(SequentialId origin, SequentialId stimulusId, double magnitude) {
        super(origin, stimulusId);
        this.magnitude = magnitude;
    }
}
```

### `stimuli/EndocrineState.java`
Published by `EndocrineSystem` after each update.

```java
public class EndocrineState extends Stimulus {
    public final double cortisolTonic;
    public final double stressLevel;
    ...
}
```

### `creature/components/EndocrineSystem.java`
Untyped `CreatureComponent`. Leaky integrator for cortisol; converts accumulation above threshold into STRESS affect; persists `EndocrineStateLog`.

```
onReceive(List<Stimulus>):
  for each CortisolStimulus c:
    cortisol = cortisol * CORTISOL_DECAY + c.magnitude
    stressLevel = max(0, (cortisol - CORTISOL_STRESS_THRESHOLD) * CORTISOL_STRESS_GAIN)
    double stressDelta = stressLevel - creature.emotions().getLevel(STRESS)
    creature.emotions().regulate(STRESS, stressDelta)
  publishState() → creature.fullAppraisal().tell(EndocrineState)
  persist(EndocrineStateLog)
```

### `creature/bd/EndocrineStateLog.java`
JPA entity: `(id PK, creature_key, seq, cortisol_tonic, stress_level)`.
Table: `data.endocrine_state_log`.
Pattern identical to `NeuromodulatorStateLog`.

### `creature/bd/NeuromodulatorStateLog.java` (extend existing)
Add `circadian_phase` column (`double`). This makes the neuromodulator log the single source of truth for the oscillator phase — both dopamine/serotonin/orexin and the circadian signal that drives them are in one table, so the analysis script can produce the overlay plot without joining multiple tables.

Update the named native query and the constructor accordingly.

---

## Modified Files

### `stimuli/NeuromodulatorState.java`
Add `orexinTonic` field to the readout. Add new 4-arg constructor; keep old 3-arg constructor calling the new one with `orexinTonic=0.0` for backward compatibility (used in tests).

```java
public final double orexinTonic;  // new field
```

### `creature/components/NeuromodulatorSystem.java`
- Add `private double orexin = 0.0;` integrator
- Add `private double lastCircadianPhase = 0.0;` field — updated in `onTick()` from the tick's phase value so `publishState()` can persist it
- Add `case OrexinergicStimulus ox -> onOrexin(ox);` in the switch
- `onOrexin`: `orexin = clampFloor(orexin * OREXIN_DECAY + s.release);`
- `onTick`: also store `lastCircadianPhase = tick.circadianPhase`
- `publishState()`: include `orexin` in `NeuromodulatorState(... orexin)`; pass `lastCircadianPhase` to `NeuromodulatorStateLog`
- Add `double orexin()` package-private accessor for tests

### `creature/components/PartialAppraisal.java`
Two additions, both gated behind their respective feature flags:

**Orexin emission** (each cycle, when `learningSettings.isOrexinEnabled()`):
```java
double sleepPressure = creature.emotions().getLevel(Constants.SLEEP);
double orexinRelease = Math.max(0, 1.0 - sleepPressure / Constants.MAX_AROUSAL_LEVEL);
creature.neuromodulators().tell(new OrexinergicStimulus(id, nextStimulusId(), orexinRelease));
```

**Morning cortisol pulse** (once per circadian cycle, when `learningSettings.isEndocrineEnabled()`):
Track `previousPhase` field. Detect wrap: `circadian.phase() < previousPhase` → day start.
```java
if (learningSettings.isEndocrineEnabled()) {
    double currentPhase = circadian.phase();
    if (currentPhase < previousPhase) {  // circadian wrap = new day
        creature.endocrine().tell(new CortisolStimulus(id, nextStimulusId(), CORTISOL_MORNING_PULSE));
    }
    previousPhase = currentPhase;
}
```

### `creature/components/HomeostaticRegulation.java`
After each handler that raises drive arousal (adrenergic, adenosinergic, nociceptive), emit a `CortisolStimulus` if the resulting arousal exceeds `STRESS_ACTIVATION_THRESHOLD`. Gate on `learningSettings.isEndocrineEnabled()`.

Add helper `emitCortisolIfStressed(double arousalLevel)`:
```java
private void emitCortisolIfStressed(double arousalLevel) {
    if (!learningSettings.isEndocrineEnabled()) return;
    double stressorMagnitude = Math.max(0, arousalLevel - Constants.STRESS_ACTIVATION_THRESHOLD);
    if (stressorMagnitude > 0) {
        creature.endocrine().tell(
            new CortisolStimulus(id, nextStimulusId(), stressorMagnitude * CORTISOL_STRESSOR_GAIN));
    }
}
```

Call it at the end of:
- `handleAdrenergic` — use the resulting HUNGER level (or SLEEP when circadian disabled)
- `handleAdenosinergic` — use resulting SLEEP level
- `handleNociceptive` — use resulting PAIN level

### `creature/components/FullAppraisal.java`
- Add `private double orexinTonic = 0.0;` field
- Add `private double endocrineStress = 0.0;` field (cached from EndocrineState for logging)
- In `onReceive`, add branch for `EndocrineState`:
  ```java
  if (stimulus instanceof EndocrineState es) { endocrineStress = es.stressLevel; continue; }
  ```
- In `NeuromodulatorState` handler, extract `orexinTonic`:
  ```java
  orexinTonic = nm.orexinTonic;
  ```
- In `definePossibleActions`, add orexin gate after building the action list:
  ```java
  if (learningSettings.isOrexinEnabled() && orexinTonic >= Constants.OREXIN_SLEEP_GATE_THRESHOLD) {
      actions.removeIf(a -> a.type == ActionType.SLEEP);
  }
  ```

### `creature/Creature.java`
Add:
```java
ComponentRef endocrine();
```

### `creature/CreatureActor.java`
- In `componentFactories(...)`: add
  ```java
  factories.put(EndocrineSystem.class, EndocrineSystem::new);
  ```
- Add:
  ```java
  public ComponentRef endocrine() { return refOf(EndocrineSystem.class); }
  ```

### `creature/testing/TestingCreature.java`
- Add `register(cid = cid.next(), new EndocrineSystem(cid));` after `NeuromodulatorSystem`
- Add accessor:
  ```java
  @Override public ComponentRef endocrine() { return refs.get(EndocrineSystem.class); }
  ```
- Add convenience method in `TestingHarness`:
  ```java
  public RecordingComponentRef endocrineRecorder() { return recorderOf(EndocrineSystem.class); }
  ```

### `common/Constants.java`
Add:
```java
// --- Orexin (wakefulness stabiliser) ---
double OREXIN_DECAY                = 0.97;
double OREXIN_SLEEP_GATE_THRESHOLD = 0.4;

// --- Cortisol / HPA axis ---
double CORTISOL_DECAY              = 0.9995;
double CORTISOL_MORNING_PULSE      = 0.5;
double CORTISOL_STRESSOR_GAIN      = 0.3;
double STRESS_ACTIVATION_THRESHOLD = 4.0;
double CORTISOL_STRESS_THRESHOLD   = 3.0;
double CORTISOL_STRESS_GAIN        = 0.5;
```

### `cluster/settings/LearningSettings.java`
- Add fields `private final boolean orexinEnabled;` and `private final boolean endocrineEnabled;`
- Extend the most-complete constructor with these two new params (default `false`)
- Add a new 9-arg constructor that is the canonical one; existing constructors delegate with `false, false`
- Add accessors `isOrexinEnabled()`, `isEndocrineEnabled()`

### `resources/META-INF/persistence.xml`
Add:
```xml
<class>br.cefetmg.lsi.l2l.creature.bd.EndocrineStateLog</class>
```

---

## Tests to Write

### `NeuromodulatorSystemTest` additions (unit — inject directly into component)
- `orexin_accumulates_from_release()`: inject `OrexinergicStimulus(release=0.5)` → published `NeuromodulatorState.orexinTonic == 0.5`
- `orexin_decays_to_zero_without_input()`: inject `release=1.0` once, then 300 `NeuromodulatorTick`s → `orexinTonic` approaches 0
- `high_orexin_converges_to_fixed_point()`: inject `release=1.0` before each tick for 400 ticks → converges to `1.0 / (1 - OREXIN_DECAY)`

### New `EndocrineSystemTest` (unit — inject directly into component)
- `cortisol_accumulates_from_stimulus()`: inject `CortisolStimulus(magnitude=1.0)` → published `EndocrineState.cortisolTonic == 1.0`
- `cortisol_decays_slowly()`: inject magnitude=5.0 once, then send 300 zero-input batches → verify cortisol still > 4 (slow decay)
- `stress_activates_above_threshold()`: inject enough cortisol to exceed `CORTISOL_STRESS_THRESHOLD` → `creature.emotions().getLevel(STRESS) > 0`
- `stress_zero_below_cortisol_threshold()`: inject small cortisol (below threshold) → STRESS stays at `MIN_AROUSAL_LEVEL`

### New `OrexinFunctionalTest` (in `creature/testing/` — full pipeline via `TestingHarness`)

**Message delivery:**
- `orexin_stimulus_delivered_to_neuromodulator_pool()`: create `TestingHarness` with `orexinEnabled=true`; run 5 ticks; assert `neuromodulatorRecorder().hasAny(OrexinergicStimulus.class)` — verifies `PartialAppraisal` emits orexin each cycle
- `no_orexin_when_disabled()`: same harness with default settings (`orexinEnabled=false`); run 5 ticks; assert no `OrexinergicStimulus` in neuromodulator recorder

**Behavioural gate (statistical, N decisions):**
- `orexin_gates_sleep_out_when_rested()`: build N harnesses with `orexinEnabled=true`, sleep pressure at `MIN_AROUSAL_LEVEL`; prime high orexin by running several ticks; inject a fruit at distance; assert SLEEP share of first decisions is near 0 (< 5%)
- `orexin_allows_sleep_when_exhausted()`: same setup but set sleep pressure to `MAX_AROUSAL_LEVEL - 0.1` (near max); assert SLEEP appears as a candidate (orexinTonic should have fallen below gate threshold)

### New `CortisolFunctionalTest` (in `creature/testing/` — full pipeline via `TestingHarness`)

**Message delivery — circadian morning pulse:**
- `morning_cortisol_pulse_delivered_after_circadian_wrap()`: create harness with `endocrineEnabled=true`; tick exactly `CIRCADIAN_PERIOD_TICKS + 1` times (one full cycle + 1 to trigger the wrap); assert `endocrineRecorder().hasAny(CortisolStimulus.class)` — verifies phase-wrap pulse reaches `EndocrineSystem`
- `no_cortisol_before_first_circadian_wrap()`: tick only `CIRCADIAN_PERIOD_TICKS / 2` times; assert no `CortisolStimulus` in endocrine recorder (no wrap yet)

**Message delivery — wake-up spike (CAR):**
- `cortisol_spike_on_wakeup()`: create harness with `endocrineEnabled=true`; force creature to sleep (push sleep pressure to MAX, run ticks until `inSleep`); then clear sleep pressure (hunger-style direct injection) and run one more tick; assert a new `CortisolStimulus` arrives at `EndocrineSystem` after the SLEEP→WAKE transition

**Message delivery — stressor pathway:**
- `stressor_cortisol_emitted_when_hunger_exceeds_threshold()`: create harness with `endocrineEnabled=true`; set hunger to `STRESS_ACTIVATION_THRESHOLD + 1`; run one `AdrenergicStimulus` tick; assert `endocrineRecorder().hasAny(CortisolStimulus.class)`

**No cortisol when disabled:**
- `no_cortisol_messages_when_endocrine_disabled()`: default settings (`endocrineEnabled=false`); run `CIRCADIAN_PERIOD_TICKS + 5` ticks; assert no `CortisolStimulus` in endocrine recorder

---

## Simulation Config

Create `simulations/orexin_endocrine.conf` (extends `basic.conf`):
```hocon
simulation.learningSettings {
  circadianEnabled      = true
  orexinEnabled         = true
  endocrineEnabled      = true
  neuromodulationEnabled = true   # keep DA/5-HT running so we can see all four signals
  expectancyEnabled     = true
  actionTendencyEnabled = false   # OFF: validate that orexin alone gates SLEEP, not the tendency filter
}
```

The `ActionTendencyFilter` is intentionally disabled so the experiment isolates the orexin mechanism. If SLEEP is suppressed during the active phase, it must be orexin doing it.

Run duration: at least 10 circadian cycles per creature (10 × 200 = 2000 ticks minimum) so the periodic pattern is clearly visible.

---

## Validation Experiment

### Goal
Confirm that the orexin/cortisol/adenosine signals form a coherent daily cycle aligned with the circadian oscillator — i.e., the implementation actually produces the loop described in the HLD integration section.

### Plots to produce (Python analysis script `analysis/p{issue}/circadian_neuroendocrine.py`)

**Figure 1 — Neuroendocrine cycle overlay:**
Four panels, all sharing a common x-axis (cognitive cycle), averaged across all creatures:
1. `circadian_phase` (from `neuromodulator_state_log`) — the base sinusoidal oscillator
2. `dopamine` + `serotonin` + `orexin` (from `neuromodulator_state_log`) — three lines, one panel
3. `cortisol_tonic` (from `endocrine_state_log`) — shown with horizontal `CORTISOL_STRESS_THRESHOLD` reference line
4. `stress_level` (from `endocrine_state_log`) — expected to stay near zero unless drives are chronically elevated

**Expected pattern to confirm:**
- Orexin tracks inverse of sleep pressure: high during low-sleep-pressure phase, drops as sleep accumulates
- Cortisol spikes once per circadian period (morning pulse) then slowly decays
- Dopamine rises during active (low-sleep-pressure) phase when foraging occurs
- Serotonin rises after successful eating events

**Figure 2 — SLEEP action share vs. orexin tonic:**
X-axis: cognitive cycle (binned), Y-axis: fraction of creatures selecting SLEEP, second Y-axis: mean orexinTonic. Expect anti-correlation: when orexinTonic is above gate threshold, SLEEP share drops.

Data source: join `chosen_action_state` (action column) with `neuromodulator_state_log` (orexin column) on `creature_key` + `seq`.

---

## Implementation Order

1. Constants (no deps)
2. New stimulus classes: `OrexinergicStimulus`, `CortisolStimulus`, `EndocrineState`
3. `NeuromodulatorState` — add `orexinTonic` field
4. `NeuromodulatorStateLog` — add `circadian_phase` column
5. `NeuromodulatorSystem` — add orexin integrator + persist phase
6. `EndocrineStateLog` JPA entity + `persistence.xml`
7. `EndocrineSystem` component
8. `LearningSettings` — add flags
9. `Creature` interface — add `endocrine()`
10. `CreatureActor` + `TestingCreature` — register EndocrineSystem
11. `PartialAppraisal` — emit OrexinergicStimulus + morning CortisolStimulus
12. `HomeostaticRegulation` — emit stressor CortisolStimulus
13. `FullAppraisal` — cache orexinTonic, gate SLEEP, handle EndocrineState
14. `TestingHarness` — add `endocrineRecorder()`
15. Tests: `NeuromodulatorSystemTest` additions, `EndocrineSystemTest`, `OrexinFunctionalTest`, `CortisolFunctionalTest`
16. `mvn package` — must compile clean
17. `mvn test` — all tests must pass
18. Simulation run + extractor + analysis script

---

## Invariants to Verify

- When sleep pressure = MAX_AROUSAL_LEVEL: orexinRelease = 0 → orexinTonic falls below OREXIN_SLEEP_GATE_THRESHOLD → SLEEP is allowed
- When sleep pressure = MIN_AROUSAL_LEVEL: orexinRelease ≈ 1 → orexinTonic stays high → SLEEP is gated out
- Orexin gate is additive to `ActionTendencyFilter` (either can suppress SLEEP independently)
- STRESS is not lethal (`getMaxDriveArousal()` must not include STRESS — it's an affect, not a drive)
- CortisolStimulus sent to `creature.endocrine()` only when `endocrineEnabled` flag is true

---

## Open Questions / Risks

1. **STRESS as affect vs. drive in `EmotionalSystemActor`**: verify that STRESS is registered as a non-drive affect so it doesn't trigger death at MAX_AROUSAL_LEVEL. The `getMaxDriveArousal()` query must exclude STRESS.
2. **`NeuromodulatorState` 3-arg → 4-arg migration**: the old 3-arg constructor in tests still compiles. The `NeuromodulatorSystem.publishState()` call must use the new 4-arg form.
3. **`morning pulse` once per period**: phase wraps once per 200 ticks — exactly right for a daily cortisol pulse.
4. **`circadian_phase` column in `NeuromodulatorStateLog`**: this is a schema change — `drop-and-create-tables` in `persistence.xml` means it's fine for a new simulation run, but any existing data in the dev DB will be wiped on next startup (expected behaviour).
