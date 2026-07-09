# Sleep Pressure Recovery & Negative RPE Calibration (issue #59 follow-up)

## Context

EXP-P59 v2 confirmed the HPA axis fix but exposed two further behavioural problems:

1. **Creatures almost never sleep (0.023%).** Diagnosed: sleep pressure never rises above 0.39
   (near MIN_AROUSAL_LEVEL = 0.18). The orexin gate cannot open if sleep pressure stays at
   the floor.

2. **Dopamine is flat and high.** Diagnosed: DA only rises (positive RPE from predictable EAT
   rewards in the food-repositioning world); no "missed reward" or "drive-rising-without-relief"
   signal pulls it down.

---

## Root cause 1 — `Body` emits sleep-clearing CholinergicStimulus on every speed=0 action

`Body.java` sends `CholinergicStimulus` (delta = `CHOLINERGIC_DELTA = 0.1`) to
`HomeostaticRegulation` whenever `muscular.speed == 0`. This fires on **EAT, OBSERVE, and SLEEP**
— not just SLEEP. With 12% EAT actions:

```
sleep gain per tick ≈ 1e-3  (AdenosinergicStimulus, circadian)
sleep loss per EAT tick ≈ 0.1 × 12% = 0.012
net per tick ≈ -0.011   → sleep pressure driven to the floor
```

The adenosine signal is overwhelmed by spurious cholinergic clearing from eating and observing.
Sleep pressure never accumulates; the orexin gate never opens; creatures never sleep.

---

## Root cause 2 — Orexin time constant too large for practical gate-opening

Even if sleep pressure were correct, `OREXIN_DECAY = 0.97` gives τ = 33 ticks. At 60% sleep
pressure, it takes ~130 ticks to transition from full-alert orexin to below the gate (15.0).
That is two-thirds of a circadian period — too slow for a creature whose sleep pressure may
only stay high for a brief window.

Analytical steady-state at sleep pressure `s` (release = 1 − s/MAX):

| s | release | orexin_eq (decay=0.97) | orexin_eq (decay=0.90) |
|---|---------|----------------------|----------------------|
| 0%  | 1.0 | 33.3 | 10.0 |
| 50% | 0.5 | 16.7 | 5.0  |
| 60% | 0.4 | 13.3 | 4.0  |
| 80% | 0.2 | 6.7  | 2.0  |

Gate at 15.0 requires sleep pressure > 55% to open (at equilibrium), and takes 130 ticks to
reach from full alert. Gate at **5.0** (= 50% of fixed-point 10 with decay=0.90) opens at 50%
sleep pressure and converges in **~9 ticks** from full alert at 80% sleep pressure.

---

## Root cause 3 — DA has no negative RPE signal

`DopaminergicStimulus` (from `Valuation`) is the only source of DA change (besides the
circadian baseline which averages to zero). In a food-repositioning world EAT always delivers
expected reward, so `rpe ≈ 0` once the expectancy predictor converges. DA accumulates from
early positive-RPE episodes and decays slowly, producing a flat elevated tonic with no phasic
variation.

**Missing signal**: when a drive rises above the equilibrium band **without relief**, that is
a negative outcome relative to the creature's expectation of homeostatic stability. This "drive
deprivation" should suppress DA — the counterpart to the DA burst on drive relief.

Biologically: VTA dopamine neurons are tonically active and are suppressed below baseline when
predicted rewards fail to arrive (Schultz 1997). Currently there is no suppression pathway.

---

## Proposed changes

### Change 1 — `Body.java`: gate CholinergicStimulus to SLEEP only

Remove the `speed == 0` → CholinergicStimulus logic from `Body`. The body position should
update regardless; sleep recovery is a cognitive decision, not a physics consequence.

```java
// REMOVE:
if (muscular.speed == 0) {
    cholinergic = new CholinergicStimulus(id, nextStimulusId());
    creature.homeostatic().tell(cholinergic);
}
```

### Change 2 — `FullAppraisal.java`: emit CholinergicStimulus only on SLEEP action

`FullAppraisal` already tracks `inSleep` and `sleepDwellTicks`. When the selected action is
SLEEP, send the cholinergic recovery stimulus directly to `HomeostaticRegulation`:

```java
if (action.type == ActionType.SLEEP) {
    creature.homeostatic().tell(new CholinergicStimulus(id, nextStimulusId()));
}
```

This preserves the sleep-recovery behaviour while removing the spurious clearing during EAT
and OBSERVE. The `CHOLINERGIC_DELTA = 0.1` magnitude remains correct: at 1e-3/tick
accumulation, one SLEEP tick clears 100 ticks' worth of adenosine — appropriate for a creature
in genuine sleep.

### Change 3 — `Constants.java`: recalibrate orexin constants

```java
// was: 0.97
double OREXIN_DECAY = 0.90;
// was: 15.0
double OREXIN_SLEEP_GATE_THRESHOLD = 5.0;
```

Justification (see table above):
- Fixed point at rest = 1/(1−0.90) = 10.0
- Gate = 5.0 = 50% of fixed point → opens at 50% sleep pressure
- Convergence time from full alert at 80% sleep pressure ≈ 9 ticks
- Gate comment updated to document new steady-state values

### Change 4 — `HomeostaticRegulation.java`: drive-deprivation negative RPE

When a metabolic drift event (AdrenergicStimulus or AdenosinergicStimulus) pushes a drive
**above** `EQUILIBRIUM_BAND_UPPER`, the creature is experiencing an unmet need. This is a
negative outcome (drive went up when homeostatic stability requires it to stay low). Emit an
`EvaluationStimulus` to `Valuation` so the expectancy path computes a negative RPE and
suppresses DA.

The pattern mirrors `handleTedium` and `handleNutritive`:

```java
// In handleAdrenergic, after regulate(HUNGER, s.delta):
if (learningSettings.isExpectancyEnabled()) {
    double hungerLevel = creature.emotions().getLevel(Constants.HUNGER);
    if (hungerLevel > Constants.EQUILIBRIUM_BAND_UPPER) {
        ExpectancyContext ctx = contextFor(Constants.HUNGER);
        Emotion regulated = creature.emotions().snapshot(Constants.HUNGER);
        Stimulus eval = new EvaluationStimulus(id, nextStimulusId(),
                id, Self.get(), ActionType.WANDER, regulated, s.delta, ctx);
        creature.valuation().tell(eval);
    }
}
```

`arousalVariation = +s.delta` (drive went up) → `reward = -s.delta` (negative) → RPE is
negative when expected_reward ≥ 0 (creature expected homeostasis). DA dips.

Apply the same pattern in `handleAdenosinergic` for the SLEEP drive.

**Rate limiting**: the adrenergic tick fires every cycle; emitting an `EvaluationStimulus`
every cycle when hungry would flood `Valuation`. Gate to every `DEPRIVATION_RPE_INTERVAL = 10`
adrenergic ticks per drive. Add `private int hungerDeprivationCycle = 0` and
`private int sleepDeprivationCycle = 0` counters.

New constant:
```java
int DEPRIVATION_RPE_INTERVAL = 10;  // ticks between drive-deprivation RPE events
```

### Change 5 — Tests

**`BodyTest` (new or extended)**: verify that CholinergicStimulus is NOT emitted for EAT
speed (speed=0 from non-SLEEP); verify it IS emitted when the MuscularStimulus represents
SLEEP. But since `Body` no longer has the responsibility, verify `Body` emits no
CholinergicStimulus at all regardless of speed.

**`FullAppraisalTest` (new)**: verify that CholinergicStimulus is sent to homeostatic when
SLEEP is selected, and NOT sent for EAT.

**`HomeostaticRegulationTest` (extended)**: verify `EvaluationStimulus` is sent to Valuation
when hunger drifts above EQUILIBRIUM_BAND_UPPER for DEPRIVATION_RPE_INTERVAL consecutive
adrenergic ticks; verify it is NOT sent below the band; verify it fires at interval, not every
tick.

**`NeuromodulatorSystemTest` (extended)**: verify that a negative-RPE `DopaminergicStimulus`
drives dopamine down; verify dopamine is floored at zero.

---

## Steady-state predictions after fixes

With the three fixes applied (correct sleep accumulation, faster orexin, deprivation RPE):

| Condition | Expected behaviour |
|---|---|
| Well-rested, fed | Orexin high (~10), sleep pressure low, DA near tonic baseline; SLEEP gated out |
| Sleep-deprived (pressure > 50% MAX) | Orexin falls below gate within ~9 ticks; SLEEP allowed in; creature sleeps; sleep pressure cleared |
| Hungry, no food | DA dips every DEPRIVATION_RPE_INTERVAL ticks; expectancy for EAT decreases in unfed state; creature motivated to forage |
| EAT event | Positive RPE → DA spike; sleep drive NOT cleared; hunger drive cleared as before |

---

## Verification experiment

**Calibration run** (3 trials, 3 creatures, 40 min, food repositioning OFF):

Hypotheses:
- **H1**: Sleep pressure reaches 50% of MAX (3.5) within 40 circadian periods (~8,000 ticks).
- **H2**: Orexin drops below gate threshold (5.0) during high-sleep-pressure periods; SLEEP
  share rises to > 5%.
- **H3**: DA shows phasic variation — dips during deprivation, spikes on EAT; coefficient of
  variation of DA tonic > 0.3.
- **H4**: Creatures survive the full run without dying from sleep deprivation (sleep pressure
  is periodically cleared by genuine SLEEP actions).

Food repositioning OFF removes the confound that guaranteed EAT reward; creatures must forage
actively, which naturally produces periods of unmet hunger → negative RPE episodes.

---

## Files changed

| File | Change |
|---|---|
| `creature/components/Body.java` | Remove `speed == 0 → CholinergicStimulus` block |
| `creature/components/FullAppraisal.java` | Emit `CholinergicStimulus` to homeostatic when `action.type == SLEEP` |
| `common/Constants.java` | `OREXIN_DECAY = 0.90`, `OREXIN_SLEEP_GATE_THRESHOLD = 5.0`, `DEPRIVATION_RPE_INTERVAL = 10` |
| `creature/components/HomeostaticRegulation.java` | Drive-deprivation `EvaluationStimulus` emission in `handleAdrenergic` and `handleAdenosinergic`; rate-limiter counters |
| `test/.../BodyTest.java` | New: verify no CholinergicStimulus emitted |
| `test/.../FullAppraisalTest.java` | New/extended: verify CholinergicStimulus on SLEEP, not EAT |
| `test/.../HomeostaticRegulationTest.java` | Extended: drive-deprivation RPE interval gate |
| `test/.../NeuromodulatorSystemTest.java` | Extended: negative RPE drives DA down to floor |

## Out of scope

- Cortisol/HPA interaction with DA (deferred, explicitly noted in HLD).
- STRESS → action coupling (requires predator/injury world, separate sub-issue).
- Sleep-onset JEPA consolidation (issue #58, separate feature).
