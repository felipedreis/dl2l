# HPA Axis Biological Fix — Negative Feedback, Circadian Baseline & Sustained-Stressor Gate (issue #59 follow-up)

## Context

The p59 validation run (see `docs/reports/EXP_P59_OREXIN_ENDOCRINE.md`) confirmed the **orexin gate** works (mean orexin 32.4 ≈ fixed point; SLEEP suppressed to 0.019%) but showed the **cortisol/HPA axis is pathologically miscalibrated**: mean cortisol 40.2 (13× the 3.0 stress threshold) and STRESS pinned at MAX (7.0) from early in the run.

Root causes, confirmed in code:

1. **No negative feedback.** `EndocrineSystem.onCortisol` does `cortisol = cortisol·DECAY + magnitude` — passive linear leak, no self-suppression. With input arriving nearly every tick the fixed point is `magnitude/(1−DECAY) ≈ 500·magnitude` → runaway. Real cortisol suppresses its own synthesis via glucocorticoid receptors on the hypothalamus/pituitary; this brake is the single most important missing piece.
2. **Routine hunger treated as an acute stressor.** `HomeostaticRegulation.emitCortisolIfStressed` fires from `handleAdrenergic` (every metabolic tick) whenever hunger > `STRESS_ACTIVATION_THRESHOLD` (4.0) — a normal foraging state. Biologically, cortisol answers *threat* and *sustained/unrelieved* deprivation, not ordinary appetite.
3. **Decay tied to stimulus arrival, not to the cycle.** There is no `EndocrineTick`. Decay only happens inside `onCortisol`, so with no stressor a batch never decays, and with several stressors it decays several times. Contrast `NeuromodulatorSystem.onTick(NeuromodulatorTick)` which owns per-cycle reuptake + circadian synthesis.

Intended outcome: a bounded, self-limiting cortisol tonic that follows a smooth circadian rhythm (morning peak, night trough) in a well-fed creature and only rises into the STRESS-activating range under genuine sustained deprivation.

**Design decisions confirmed with the user:**
- Circadian rhythm: **smooth phase-modulated baseline** (replaces the discrete morning pulse).
- Stressor pathway: **sustained deprivation** — only drives held above threshold for N consecutive cycles emit cortisol.

---

## Biological model

Leaky integrator with **saturating (self-limiting) synthesis** — the standard receptor-saturation form of glucocorticoid negative feedback:

```
each cycle (EndocrineTick, phase φ):
    cortisol *= CORTISOL_DECAY                              // passive adrenal clearance
    synth = CORTISOL_CIRCADIAN_BASELINE
          + CORTISOL_CIRCADIAN_AMPLITUDE * max(0, sin(φ - MORNING_OFFSET))
    cortisol += synth / (1 + CORTISOL_FEEDBACK_GAIN * cortisol)

on stressor (CortisolStimulus, sustained deprivation only):
    cortisol += magnitude / (1 + CORTISOL_FEEDBACK_GAIN * cortisol)

STRESS affect (recomputed every update):
    stressLevel = max(0, (cortisol - CORTISOL_STRESS_THRESHOLD) * CORTISOL_STRESS_GAIN)
    creature.emotions().regulate(STRESS, stressLevel - currentStress)
```

The `input/(1 + k·cortisol)` term throttles new synthesis toward zero as cortisol rises. Combined with per-cycle decay, the equilibrium under constant input rate `r` solves `r/(1+k·c) = c·(1−DECAY)` → a **bounded** `c`. Choose `CORTISOL_FEEDBACK_GAIN` so that under normal circadian baseline alone the tonic settles *below* `CORTISOL_STRESS_THRESHOLD`, and only sustained stressor input pushes it above.

---

## Changes

### 1. New message: `EndocrineTick` — `src/main/java/br/cefetmg/lsi/l2l/stimuli/EndocrineTick.java`
Mirror `NeuromodulatorTick`: carries `double circadianPhase`. Immutable `Stimulus` subclass. This is the per-cycle pacemaker for decay + circadian synthesis (follows the established "molecule *is* the message" untyped-actor pattern — see the `feedback_neuromodulator_message_passing` convention).

### 2. `EndocrineSystem` — `src/main/java/br/cefetmg/lsi/l2l/creature/components/EndocrineSystem.java`
- Add `onTick(EndocrineTick)`: passive decay, then circadian saturating synthesis, then STRESS recompute.
- Change `onCortisol(CortisolStimulus)`: apply the same saturating feedback term to `magnitude`; recompute STRESS. Remove the passive-decay line (decay now lives in `onTick`).
- Dispatch both message types in `onReceive`.
- Extract the STRESS recompute + `publishState` into a shared helper so both handlers converge on it.

### 3. `HomeostaticRegulation` — `src/main/java/br/cefetmg/lsi/l2l/creature/components/HomeostaticRegulation.java`
Replace `emitCortisolIfStressed(double)` (lines ~178–185) with a **sustained-deprivation gate**:
- Add a per-drive streak map (`Map<String,Integer>` keyed by drive name: HUNGER, SLEEP, PAIN).
- On each drive sample: `streak++` if `level > STRESS_ACTIVATION_THRESHOLD`, else reset to 0.
- Emit `CortisolStimulus(excess · CORTISOL_STRESSOR_GAIN)` **only** when `streak >= CORTISOL_STRESSOR_SUSTAIN_TICKS`, where `excess = level − STRESS_ACTIVATION_THRESHOLD`.
- Keep the existing call sites (`handleAdrenergic` for hunger, `handleAdenosinergic`/metabolic for sleep, `handleNociceptive` for pain), now routed through the gated method.

### 4. `PartialAppraisal` — `src/main/java/br/cefetmg/lsi/l2l/creature/components/PartialAppraisal.java`
- Replace the discrete morning-pulse block (lines ~88–96) with: when `endocrineEnabled`, send `EndocrineTick(circadian.phase())` to `creature.endocrine()` each cycle.
- Remove the `previousCircadianPhase` phase-wrap logic used only for the pulse (circadian synthesis now happens continuously in `EndocrineSystem.onTick`).

### 5. `Constants` — `src/main/java/br/cefetmg/lsi/l2l/common/Constants.java`
- **Add:** `CORTISOL_FEEDBACK_GAIN`, `CORTISOL_CIRCADIAN_BASELINE`, `CORTISOL_CIRCADIAN_AMPLITUDE`, `CORTISOL_MORNING_OFFSET`, `CORTISOL_STRESSOR_SUSTAIN_TICKS`.
- **Retire:** `CORTISOL_MORNING_PULSE` (replaced by circadian baseline+amplitude).
- **Keep/retune:** `CORTISOL_DECAY`, `STRESS_ACTIVATION_THRESHOLD`, `CORTISOL_STRESS_THRESHOLD`, `CORTISOL_STRESS_GAIN`, `CORTISOL_STRESSOR_GAIN`.
- Document the equilibrium math (as with the orexin constants) so the bounded steady state is auditable. Target: baseline-only tonic settles ~1–2 (below the 3.0 threshold); sustained deprivation pushes it into 3–6.

### 6. Tests — `src/test/java/br/cefetmg/lsi/l2l/creature/components/EndocrineSystemTest.java`
Existing tests assume decay-in-`onCortisol` and no feedback; they must be reworked:
- Route decay through `EndocrineTick` (inject a tick between cortisol stimuli).
- Update expected values for the saturating feedback term (`cortisol_accumulates_from_stimulus` still yields 1.0 from an empty pool since `1/(1+0)=1`; the additive test changes).
- **New:** bounded-steady-state test — drive constant input for many cycles, assert cortisol converges below a finite ceiling (never runs away).
- **New:** sustained-gate test — a drive above threshold for `< SUSTAIN_TICKS` emits no cortisol; held for `>= SUSTAIN_TICKS` it does.
- Check `TestingHarness` routes `EndocrineTick` to `EndocrineSystem` (add if the harness whitelists message types).

### 7. HLD — `docs/hld/orexin_cortisol_stress_wakefulness.md`
Rewrite **Component 2 — Cortisol**: document negative-feedback synthesis, smooth circadian baseline (replacing the discrete pulse), sustained-deprivation stressor gate, and the `EndocrineTick` pacemaker. Update the constants table. Add a short note citing the p59 finding as the empirical motivation for the redesign.

---

## Verification

1. **Build:** `mvn package` — must compile clean and pass all tests (196 + new endocrine tests).
2. **Unit-level:** the two new `EndocrineSystemTest` cases (bounded steady state; sustained gate) are the fast proof the runaway is fixed.
3. **Simulation:** rebuild image, run p59 via `docker compose -f docker/docker-compose-p59.yml up -d` (already wired to `exp_p59_orexin_endocrine.conf`, 20-min `maxRuntimeMinutes`, UI on 8080).
4. **Analysis:** `python3 analysis/p59/circadian_neuroendocrine.py` — expected outcomes:
   - Figure 1(c): cortisol shows a **smooth circadian curve** that stays mostly **below** the 3.0 threshold line in a well-fed creature (contrast the current flat-line-far-above).
   - Figure 1(d): STRESS is near floor except during genuine deprivation episodes, not pinned at MAX.
   - Summary: mean cortisol in single digits; max stress < MAX.
   - Orexin/SLEEP results should be unchanged (H1/H2 still confirmed).
5. **Report:** append a "v2 (HPA recalibration)" section to `docs/reports/EXP_P59_OREXIN_ENDOCRINE.md` with the before/after cortisol figures.
6. **HuggingFace:** upload the new run under `p59/` (append; never discard the prior run's data).

## Out of scope
- Orexin gate (validated, production-ready — do not touch).
- Cortisol→dopamine suppression interaction (explicitly deferred in the HLD).
- Stress→{ESCAPE,WANDER} action coupling (needs a world with chronic stressors; separate sub-issue).
