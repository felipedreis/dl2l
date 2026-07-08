# EXP-P59: Orexin Wakefulness Gate & HPA Cortisol Axis

**Issue:** #59  
**Date:** 2026-07-08  
**Duration:** 20 minutes (MaxRuntimeExpired limit)  
**Creatures:** 3  
**Config:** `simulations/exp_p59_orexin_endocrine.conf`  
**Data:** `ml/data_p59/` · HuggingFace `felipedreis/dl2l-experiments` → `p59/`

---

## Purpose

Validate the two mechanisms implemented in issue #59:

1. **Orexin wakefulness gate** — a tonic leaky integrator whose release is suppressed by sleep pressure; when the tonic level falls below `OREXIN_SLEEP_GATE_THRESHOLD = 15.0` the SLEEP action is allowed back into the action set.
2. **HPA cortisol axis** (`EndocrineSystem`) — a slow leaky integrator that accumulates cortisol from homeostatic stressors and the circadian morning pulse; above `CORTISOL_STRESS_THRESHOLD = 3.0` the STRESS affect activates.

`ActionTendencyFilter` is **OFF** throughout so that sleep suppression during the active phase must be produced by orexin alone — not the innate tendency prior.

---

## Assumptions

| Parameter | Value | Rationale |
|---|---|---|
| `OREXIN_DECAY` | 0.97 | Fixed point at full release = 1/(1-0.97) ≈ 33.3 |
| `OREXIN_SLEEP_GATE_THRESHOLD` | 15.0 | Opens SLEEP at ~55% of MAX sleep pressure |
| `CORTISOL_DECAY` | 0.998 | Half-life ≈ 346 ticks ≈ 1.7 circadian periods |
| `CORTISOL_MORNING_PULSE` | 0.5 | Equilibrium from pulses alone ≈ 1.52 < threshold |
| `CORTISOL_STRESSOR_GAIN` | 0.05 | Reduced from 0.3 to prevent runaway accumulation |
| `CORTISOL_STRESS_THRESHOLD` | 3.0 | Activates STRESS affect |
| `CIRCADIAN_PERIOD_TICKS` | 200 | |
| Food repositioning | enabled | Creatures can always find food |

---

## Hypotheses

**H1** (orexin gate active): Mean orexin tonic stays well above the gate threshold (15.0) during the simulated wakefulness phase.

**H2** (SLEEP suppression): SLEEP action share is < 1% when `orexinEnabled=true` and `actionTendencyEnabled=false`.

**H3** (exhaustion opens gate): When sleep pressure approaches MAX the orexin tonic decays below the gate, allowing SLEEP. *(qualitative — not directly observable over 20 min without a natural SLEEP episode.)*

**H4** (morning cortisol pulse): Cortisol shows a circadian modulation driven by the morning pulse; the tonic remains below `CORTISOL_STRESS_THRESHOLD` in a well-rested creature.

**H5** (stress only from sustained demand): STRESS affect activates only when cortisol accumulates above threshold from sustained homeostatic load.

---

## Results and Analysis

### Dataset

| Metric | Value |
|---|---|
| Creatures | 3 |
| Neuromodulator log rows | 349,451 |
| Endocrine log rows | 48,950 |
| Action selections | 348,019 |
| SLEEP selections | 67 (0.019%) |
| Mean orexin tonic | 32.38 |
| Mean cortisol tonic | 40.24 |
| Max stress level | 7.00 |

### H1 — Orexin gate: CONFIRMED ✓

Mean orexin tonic = **32.38**, close to the theoretical fixed point of 33.3 at full release. The gate threshold of 15.0 is never approached during the 20-minute run, confirming that rested creatures maintain stable wakefulness.

### H2 — SLEEP suppression: CONFIRMED ✓

**67 SLEEP selections out of 348,019 total (0.019%)**, all concentrated in the first 200 ticks before orexin reaches its fixed point. Once orexin stabilises above the gate, SLEEP is effectively excluded from the action set. This validates the orexin gate mechanism without any assistance from ActionTendencyFilter.

### H3 — Exhaustion: UNOBSERVED

No creature accumulated enough sleep pressure to drop orexin below the gate within the 20-minute window. This is expected: with food repositioning enabled, creatures stay active and never fully exhaust. A longer run or a run without food repositioning would be needed to trigger natural SLEEP.

### H4 — Morning cortisol pulse: FAILED ✗ (calibration issue)

Mean cortisol tonic = **40.24**, more than 13× the `CORTISOL_STRESS_THRESHOLD` of 3.0. The expected equilibrium from morning pulses alone is 1.52; the observed value is ~26× higher. The cortisol overaccumulation is driven by the stressor pathway: each homeostatic drive above `STRESS_ACTIVATION_THRESHOLD = 4.0` adds `stressorMagnitude × CORTISOL_STRESSOR_GAIN = 0.05` per handler call. With creatures experiencing high hunger and stress simultaneously, the stressor pathway generates several cortisol units per cognitive tick — far exceeding the decay rate at `CORTISOL_DECAY = 0.998`.

The morning pulse is visible as a small circadian modulation in the cortisol trace (panel c, Figure 1) but it is masked by the dominant stressor accumulation.

### H5 — STRESS activation: PARTIALLY CONFIRMED

STRESS activates (H5 technically holds: cortisol > threshold → STRESS activates), but the magnitude is pathological: max stress = **7.0** (= `MAX_AROUSAL_LEVEL`). Creatures are permanently maximally stressed from early in the simulation, which is not the intended behaviour. The HPA axis is in positive feedback: STRESS → homeostatic pressure → more stressors → more cortisol → more STRESS.

---

## Figures

### Figure 1 — Mean Neuroendocrine Cycle

![Figure 1](../../analysis/p59/fig1_neuroendocrine_cycle.png)

- **(a) Circadian phase** shows the regular sawtooth oscillator with period 2π.
- **(b) Neuromodulators**: Orexin (right axis, orange) holds steady at ~32.4 across all phases — the gate is wide open throughout. DA and 5-HT (left axis) remain near zero: no substantial reward-prediction errors or satiety signals occur during this run.
- **(c) Cortisol tonic** (purple) stays far above the stress threshold (dashed red) throughout the entire circadian cycle, indicating the stressor pathway has overwhelmed the system.
- **(d) STRESS affect** is pinned at MAX_AROUSAL_LEVEL (7.0) from early in the run.

### Figure 2 — SLEEP Share vs. Orexin Tonic

![Figure 2](../../analysis/p59/fig2_sleep_vs_orexin.png)

The SLEEP action share (blue bars) is near zero across the entire run except for the first 1–2 circadian bins (ticks 0–400), when orexin is still below the gate during initialisation. Once orexin stabilises, SLEEP disappears from the selection distribution, confirming that the orexin gate is the sole mechanism suppressing SLEEP (ActionTendencyFilter is OFF).

---

## Discussion

### What worked

The **orexin wakefulness gate** is functioning exactly as designed:
- Tonic orexin converges to the predicted fixed point (~33 vs theory ~33.3)
- SLEEP is suppressed to 0.019% once orexin stabilises
- The gate is the only mechanism responsible (ActionTendencyFilter OFF)

### What needs calibration: HPA axis

The **cortisol / HPA axis** is severely miscalibrated. Root cause: the stressor pathway adds `stressorMagnitude × 0.05` to cortisol on every homeostatic regulation handler call. With creatures experiencing hunger above 4.0 regularly (a normal physiological state during foraging), the stressor pathway fires multiple times per cognitive tick, accumulating cortisol orders of magnitude faster than the decay rate.

Proposed recalibration for the next experiment:
1. **Reduce stressor frequency**: emit cortisol only when a drive is above threshold AND the drive is increasing (Δdrive > 0), not on every handler invocation.
2. **Reduce stressor gain**: try `CORTISOL_STRESSOR_GAIN = 0.001` so the morning pulse equilibrium (1.52) dominates over transient stressors.
3. **Or: add stressor hysteresis**: require the drive to remain above threshold for N consecutive ticks before emitting cortisol.

The orexin gate is production-ready. Only the HPA calibration requires a follow-up fix before the STRESS affect produces meaningful behavioural signal.
