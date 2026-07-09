# Orexin / Cortisol / Stress — Wakefulness & Arousal Stabilisation — HLD

**Status:** Design only. Deferred from issue #57; target milestone: post-#57.

---

## Motivation

Issue #57 introduced a dopamine/serotonin neuromodulatory loop and the `ActionTendencyFilter`
to fix over-sleeping. The `ActionTendencyFilter` works as a pragmatic gate (sleep tendency is
active only when the creature is actually sleepy), but the *reason* a creature without it
defaults to ~84% SLEEP is architectural: nothing actively stabilises wakefulness during the day.
Biological wakefulness is not the mere absence of sleep pressure — it is enforced by an
excitatory neuropeptide system (orexin/hypocretin) that is itself modulated by a slow
endocrine hormone (cortisol) and eventually generates a valenced affect (stress) when over-driven.

This document captures the full design agreed in the #57 debrief, so it can be implemented as a
coherent unit in a future milestone.

---

## Buck's Taxonomy Recap

| Class | Instances in DL2L | Lethal? | Regulated by |
|---|---|---|---|
| **Drive** | hunger, sleep | yes (→ death at MAX) | metabolic drift + consummatory action |
| **Affect** | pain, tedium, **stress** | no | nociception / reward-absence / HPA axis |

Cortisol activates **stress** — a new affect (no metabolic drift; regulated by the endocrine loop).

---

## Component 1 — Orexin (`OrexinergicStimulus`, `NeuromodulatorSystem`)

### What it is biologically

Orexin (also called hypocretin) is a neuropeptide produced in the lateral hypothalamus.
Its sole known function is to *stabilise* waking: it is not what causes waking, but it
prevents inappropriate intrusions of sleep during the active phase. Orexin neurons fire
tonically during wakefulness, suppress REM atonia, and are inhibited by adenosine (sleep
pressure). Narcolepsy is caused by orexin cell loss.

### Architecture

```
PartialAppraisal (each cognitive cycle)
  compute sleepPressure = creature.emotions().getLevel(SLEEP)
  compute orexinRelease = max(0, 1 - sleepPressure / MAX_AROUSAL_LEVEL)   // low when sleepy
  send OrexinergicStimulus(origin, stimulusId, orexinRelease)
       ──► NeuromodulatorSystem

NeuromodulatorSystem (new handler)
  onOrexin(OrexinergicStimulus s):
    orexin = orexin * OREXIN_DECAY + s.release               // leaky integrator, same pattern as DA
    publish NeuromodulatorState (extended with orexinTonic)

FullAppraisal (action gating)
  when neuromodulationEnabled:
    cache orexinTonic from NeuromodulatorState
    at action selection: SLEEP eligibility gate
      if orexinTonic >= OREXIN_SLEEP_GATE_THRESHOLD:
          remove ActionType.SLEEP from candidate list entirely
```

### Key constants (to tune)

| Constant | Initial value | Role |
|---|---|---|
| `OREXIN_DECAY` | 0.97 | Slow decay so tonic level is stable across many cycles |
| `OREXIN_SLEEP_GATE_THRESHOLD` | 0.4 | Below this orexin, SLEEP is allowed back in |

### Invariants

- When `sleepPressure = MAX_AROUSAL_LEVEL` (creature is genuinely exhausted), `orexinRelease = 0`
  → `orexinTonic` falls below threshold → SLEEP is gated *in*, not out. The creature is allowed
  to sleep when it is actually sleepy.
- When `sleepPressure = MIN_AROUSAL_LEVEL` (just woke, fully rested), `orexinRelease = 1.0`
  → `orexinTonic` stays high → SLEEP is gated *out*. The creature cannot immediately
  re-enter sleep.
- Orexin gate is **additive** to `ActionTendencyFilter` (they can both be active). Orexin
  provides the biologically principled gate; `ActionTendencyFilter` provides the softer
  innate-tendency prior. Either alone already works; together they are redundant for SLEEP
  suppression (acceptable — belt-and-suspenders for robustness).

---

## Component 2 — Cortisol (`EndocrineTick`, `CortisolStimulus`, `EndocrineSystem`)

### What it is biologically

Cortisol is a glucocorticoid hormone secreted by the adrenal cortex (HPA axis: hypothalamus →
pituitary → adrenal). It operates on a *much slower* timescale than neuropeptides (minutes to
hours vs. milliseconds). Its biological functions:

1. **Smooth circadian baseline** — a daily tonic that peaks in the early morning (awakening
   cortisol response) and declines through the day. This is not an all-or-nothing pulse but a
   continuously phase-modulated synthesis rate.
2. **Stress response** — the HPA activates only under *sustained, unrelieved* stressors (chronic
   hunger, persistent pain). A single foraging episode above threshold is not a biological
   stressor — sustained deprivation is.
3. **Negative feedback** — glucocorticoid receptors in the hypothalamus and pituitary inhibit
   further CRH/ACTH release. This self-limiting mechanism prevents runaway accumulation. The
   standard receptor-saturation model is `synth_effective = synth / (1 + k·cortisol)`.

*Empirical motivation: the v1 implementation (flat morning pulse + per-tick stressor pathway)
caused pathological accumulation — mean cortisol 40× above the stress threshold, STRESS pinned
at MAX for the entire run (EXP-P59). The redesign below fixes all three root causes identified.*

### Architecture — separate `EndocrineSystem` component

```
EndocrineSystem (untyped CreatureComponent)
  state: cortisol  (slow leaky integrator)

  ── Tick pacemaker ───────────────────────────────────────────────────────────────────
  PartialAppraisal (each cognitive cycle, endocrineEnabled):
    send EndocrineTick(circadian.phase())  ──► EndocrineSystem

  onTick(EndocrineTick et):
    cortisol *= CORTISOL_DECAY                    // passive adrenal clearance
    synth = CORTISOL_CIRCADIAN_BASELINE
          + CORTISOL_CIRCADIAN_AMPLITUDE * max(0, sin(phase - CORTISOL_MORNING_OFFSET))
    cortisol += synth / (1 + CORTISOL_FEEDBACK_GAIN * cortisol)  // saturating negative feedback

  ── Sustained-stressor pathway ────────────────────────────────────────────────────────
  HomeostaticRegulation (per drive: HUNGER, SLEEP, PAIN):
    streak[drive]++ if drive > STRESS_ACTIVATION_THRESHOLD, else reset streak[drive] = 0
    if streak[drive] >= CORTISOL_STRESSOR_SUSTAIN_TICKS:
      excess = drive - STRESS_ACTIVATION_THRESHOLD
      send CortisolStimulus(excess * CORTISOL_STRESSOR_GAIN)  ──► EndocrineSystem

  onCortisol(CortisolStimulus cs):
    cortisol += cs.magnitude / (1 + CORTISOL_FEEDBACK_GAIN * cortisol)  // negative feedback

  ── Shared update after all stimuli processed ─────────────────────────────────────────
  stressLevel = max(0, (cortisol - CORTISOL_STRESS_THRESHOLD) * CORTISOL_STRESS_GAIN)
  creature.emotions().regulate(STRESS, stressLevel - currentStress)
  publish EndocrineState(cortisolTonic, stressLevel) → FullAppraisal / logging
```

### Steady-state analysis

The `input / (1 + k·c)` feedback term bounds cortisol at a finite equilibrium. At constant
synthesis rate `r`, the fixed point solves `r / (1+k·c) = (1−DECAY) · c`:

| Condition | Total rate `r` | Equilibrium `c*` | Below threshold? |
|---|---|---|---|
| Baseline only (night) | 0.003 | ≈ 0.82 | ✓ (< 3.0) |
| Baseline + morning peak | 0.013 | ≈ 2.1 | ✓ (< 3.0) |
| Sustained stressor (excess=1) | 0.003 + 0.05 = 0.053 | ≈ 4.7 | ✗ → STRESS ≈ 0.85 |

### Stress affect

`STRESS` is registered as an inactive emotion in `EmotionalSystemActor`. Cortisol activates it:

- **Valence:** negative (arousal → aversion — same polarity as pain/tedium).
- **Not lethal** — stress cannot trigger death (only drives can).
- **Action coupling** (deferred): `ActionTendencyFilter` can map `stress → {ESCAPE, WANDER}` to
  model fight-or-flight; needs a world with chronic stressors (predator, injury zones).
- **Persistence:** `EndocrineStateLog` JPA entity (same pattern as `NeuromodulatorStateLog`).

### Key constants

| Constant | Value | Role |
|---|---|---|
| `CORTISOL_DECAY` | 0.998 | Passive adrenal clearance; half-life ≈ 346 cycles ≈ 1.7 periods |
| `CORTISOL_CIRCADIAN_BASELINE` | 0.003 | Trough synthesis (night); baseline equilibrium ≈ 0.82 |
| `CORTISOL_CIRCADIAN_AMPLITUDE` | 0.01 | Morning peak increment; peak equilibrium ≈ 2.1 |
| `CORTISOL_MORNING_OFFSET` | 0.0 | Phase offset; peak at circadian phase = π/2 |
| `CORTISOL_FEEDBACK_GAIN` | 1.0 | Negative-feedback strength `k` in `synth/(1+k·c)` |
| `CORTISOL_STRESSOR_GAIN` | 0.05 | Per-handler stressor magnitude multiplier |
| `CORTISOL_STRESSOR_SUSTAIN_TICKS` | 10 | Consecutive above-threshold ticks before HPA fires |
| `STRESS_ACTIVATION_THRESHOLD` | 4.0 | Drive arousal level that increments the streak counter |
| `CORTISOL_STRESS_THRESHOLD` | 3.0 | Cortisol level above which STRESS affect activates |
| `CORTISOL_STRESS_GAIN` | 0.5 | Conversion factor: cortisol excess → stress arousal delta |

---

## Component 3 — Integration

```
Creature lifecycle (with all three):

  Waking hour:
    circadian rises → PartialAppraisal fires morning cortisol pulse
    orexin = high (sleep pressure low) → SLEEP gated out
    creature forages (hunger → ActionTendency → APPROACH/EAT)
    successful eating → DA phasic → RPE → reinforcement
    serotonin from satiety → quieting actions rewarded

  Chronic hunger / injury:
    each HomeostaticRegulation tick emits CortisolStimulus
    cortisol accumulates → stress affect activates
    stress can add ESCAPE/WANDER tendency → creature leaves dangerous area
    if hunger still unrelieved → cortisol stays high → sustained stress

  Sleep onset (circadian + adenosine):
    sleep pressure rises (AdenosinergicStimulus → HomestatickRegulation)
    orexin release drops → orexinTonic falls below gate → SLEEP allowed back in
    ActionTendency maps sleep → {SLEEP, WANDER} (passivity increasing)
    creature sleeps → adenosine cleared → circadian resets → cycle repeats
```

---

## Message inventory

| Stimulus | From | To | When |
|---|---|---|---|
| `OrexinergicStimulus` | `PartialAppraisal` | `NeuromodulatorSystem` | Every cognitive cycle |
| `EndocrineTick` | `PartialAppraisal` | `EndocrineSystem` | Every cognitive cycle (decay + circadian synthesis) |
| `CortisolStimulus` | `HomeostaticRegulation` (sustained stressor only) | `EndocrineSystem` | After streak ≥ CORTISOL_STRESSOR_SUSTAIN_TICKS |
| `EndocrineState` | `EndocrineSystem` | `FullAppraisal`, logged | After each cortisol update |

---

## New files / changes (for implementor)

| File | Change |
|---|---|
| `stimuli/OrexinergicStimulus.java` | New — same structure as `AdenosinergicStimulus` |
| `stimuli/CortisolStimulus.java` | New — carries `magnitude` float |
| `stimuli/EndocrineState.java` | New — readout (`cortisolTonic`, `stressLevel`) |
| `creature/components/EndocrineSystem.java` | New untyped `CreatureComponent`; slow-integrator cortisol; emit `EndocrineState` |
| `creature/components/NeuromodulatorSystem.java` | Add `orexin` integrator + `onOrexin` handler; extend `NeuromodulatorState` with `orexinTonic` |
| `creature/components/PartialAppraisal.java` | Emit `OrexinergicStimulus` and `CortisolStimulus` (morning) each cycle |
| `creature/components/HomeostaticRegulation.java` | Emit `CortisolStimulus` from stressor handlers |
| `creature/components/FullAppraisal.java` | Cache `orexinTonic` from `NeuromodulatorState`; gate SLEEP out when `orexinTonic >= OREXIN_SLEEP_GATE_THRESHOLD` |
| `creature/CreatureActor.java` | Register `EndocrineSystem` in `componentFactories`; wire `creature.endocrine()` |
| `creature/Creature.java` | Add `endocrine()` returning the `EndocrineSystem` `ComponentRef` |
| `creature/bd/EndocrineStateLog.java` | New JPA entity (creature_key, cycle, cortisol_tonic, stress_level) |
| `common/Constants.java` | New constants above |
| `cluster/settings/LearningSettings.java` | Add `orexinEnabled`, `endocrineEnabled` flags (default-off) |
| `resources/META-INF/persistence.xml` | Register `EndocrineStateLog` |

---

## Experiment design (future)

The headline hypothesis for this milestone is:

> **H** — With orexin gating (no `ActionTendencyFilter`), a creature with full sleep pressure
> sleeps appropriately, and a rested creature does not sleep even though SLEEP appears in the
> action set.

Ablation: `{orexin_off, tendency_off}` vs `{orexin_on, tendency_off}` vs `{orexin_on, tendency_on}`.
Measure: SLEEP%, hunger drift, lifetime, action entropy.

The cortisol/stress hypothesis requires a world with chronic stressors (persistent hunger, injury
zones) and is best left to a separate sub-issue.

---

## Relationship to existing features

- **ActionTendencyFilter (#57):** Orexin provides the mechanistic gate; `ActionTendencyFilter`
  provides the innate prior. They are independent and additive. Neither makes the other
  redundant for a different reason: orexin encodes *real-time sleep pressure* while tendency
  encodes *drive-specific action priors*. A creature with high hunger and low sleep pressure
  benefits from both.
- **Circadian / adenosine (#55):** Adenosine drives sleep pressure; orexin opposes it during the
  active phase. They are antagonists — the classic two-process sleep model of Borbély. The
  circadian already provides the phase signal; orexin only needs `sleepPressure` from
  `EmotionalSystem`.
- **Dopamine / serotonin (#57):** Cortisol interacts with DA (chronic stress suppresses phasic
  DA) — this interaction is not modelled in this HLD and is deferred as a further refinement.
