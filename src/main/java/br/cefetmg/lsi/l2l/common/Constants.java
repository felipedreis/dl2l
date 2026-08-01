package br.cefetmg.lsi.l2l.common;

/**
 * Created by felipe on 03/01/17.
 */
public interface Constants {

    // Issue #79: metabolic/circadian/sleep rates below are tied to cognitive-cycle count,
    // not wall-clock time. #76 (async persistence) + #78 (bounded BDActor batch), then the
    // full JPA/EclipseLink removal from the write path (docs/plans/remove-jpa-persistence-layer.md),
    // removed the blocking/contending persistence that used to throttle the cognitive loop,
    // so cycle throughput rose in wall-clock terms (measured via
    // dl2l_creature_cognitive_cycles_total, pre-#76 baseline 155.33 Hz
    // [docs/reports/p59_batching_fix_report.md data]). First rescale (S=4.286) was
    // calibrated against 665.75 Hz, measured *before* the JPA removal; that removal freed
    // CPU/dispatcher capacity JPA overhead used to contend for, so the cognitive loop itself
    // sped up further too (measured 909.9 Hz pooled across the p79_metabolic_rescale
    // validation run) - recalibrated here to S=5.858. The per-cycle accrual/clearing rates
    // below are divided by S so lifespan and rhythm cadence are restored to their pre-#76
    // wall-clock values; this is Phase A (cheap rescale) of #79's fix, not Phase B (true
    // wall-clock coupling) - it re-breaks if cycle throughput ever shifts again.
    double DELTA = 2.56067e-4;

    double CHOLINERGIC_DELTA = 1.70711e-2;

    double MAX_VISION_FIELD_OPENING = 150;
    double MIN_VISION_FIELD_OPENING = 50;

    double MAX_OLFACTORY_FIELD_RADIUS = 50;
    double MIN_OLFACTORY_FIELD_RADIUS = 65;


    double MAX_ROTATE_ANGLE = 30;

    double MAX_STEP = 10;
    double MIN_STEP = 3;

    double DEFAULT_BODY_RADIUS = 10;
    double DEFAULT_VISION_FIELD_RADIUS = 150;
    double DEFAULT_MOUTH_RADIUS = 10;
    double DEFAULT_MOUTH_OPENING = 45;
    double FRUIT_RADIUS = 8;


    String HUNGER   = "hunger";
    String SLEEP    = "sleep";
    String APATHY   = "apathy";
    String STRESS   = "stress";
    String PAIN     = "pain";
    String TEDIUM   = "tedium";
    String FEAR     = "fear";
    String CURIOSITY = "curiosity";
    String FERTILITY = "fertility";

    // Issue #79: rescaled /= S (see DELTA's comment above).
    double TEDIUM_IDLE_RATE     = 3.41422e-3;
    double TEDIUM_OBSERVE_RATE  = 8.53555e-3;
    double TEDIUM_WANDER_RELIEF = 8.53555e-3;

    double PAIN_IMMUNE_THRESHOLD = 0.2;
    // Issue #79: rescaled /= S (see DELTA's comment above).
    double PAIN_IMMUNE_RATE      = 8.53555e-4;

    double MIN_AROUSAL_LEVEL = 0.18;
    double MAX_AROUSAL_LEVEL = 7;

    int COMPLEX_TASK = 2;

    int TRACE_DECAY_HALF_LIFE = 5;

    double MIN_TRACE_ELIGIBILITY = 0.01;

    int CONSOLIDATION_WINDOW = 128;

    int MEMORY_FILTER_WINDOW = 256;

    double MEMORY_CONSOLIDATION_THRESHOLD = 0.1;

    int CONSOLIDATION_BATCH_SIZE = 16;

    // Issue #79: rescaled *= S (see DELTA's comment above) so the circadian day stays
    // ~constant in wall-clock seconds despite the higher cycle throughput.
    int CIRCADIAN_PERIOD_TICKS = 1172;

    // Issue #79: rescaled /= S (see DELTA's comment above).
    double BASE_SLEEP_DRIVE = 1.70711e-4;

    // Issue #79: rescaled /= S (see DELTA's comment above).
    double CIRCADIAN_AMPLITUDE = 8.53555e-5;

    // Issue #79: rescaled *= S (see DELTA's comment above) so the anti-micro-nap floor
    // stays ~constant in wall-clock seconds despite the higher cycle throughput.
    int MIN_SLEEP_TICKS = 59;

    // --- Expectancy predictor (symbolic reward-prediction) ---
    // Rescorla-Wagner learning rate for the running-mean expected-reward update.
    double EXPECTANCY_ALPHA = 0.2;
    // Number of buckets the dominant-drive arousal level is discretised into for the
    // CONTINUOUS expectancy variant, spanning [MIN_AROUSAL_LEVEL, MAX_AROUSAL_LEVEL].
    int EXPECTANCY_LEVEL_BUCKETS = 8;
    // Prior expected reward for a never-seen key (neutral).
    double EXPECTANCY_NEUTRAL_PRIOR = 0.0;

    // --- Neuromodulator pools (dopamine / serotonin leaky integrators) ---
    // Per-tick multiplicative decay (reuptake) of the tonic concentration.
    double DOPAMINE_DECAY  = 0.95;
    double SEROTONIN_DECAY = 0.95;
    // Baseline synthesis added each tick (circadian term is layered on top of this).
    double DOPAMINE_BASELINE  = 0.0;
    double SEROTONIN_BASELINE = 0.0;
    // Circadian modulation amplitude of neuromodulator baseline synthesis.
    double NEUROMODULATOR_CIRCADIAN_AMPLITUDE = 0.05;
    // Upper bound of Mapa's homeostatic equilibrium band [MIN_AROUSAL_LEVEL, 2.0];
    // drives inside the band contribute to serotonergic satiety.
    double EQUILIBRIUM_BAND_UPPER = 2.0;

    // --- Neuromodulator behavioural gains (applied in ActionProbabilityFilter) ---
    // Tonic dopamine raises the softmax temperature (flatter → more exploration): T = 1 + gain·tanh(daTonic).
    double DA_EXPLORATION_GAIN = 2.0;
    // Tonic serotonin up-weights quieting actions (SLEEP/OBSERVE/WANDER): factor = 1 + gain·satiety.
    double SEROTONIN_REST_GAIN = 1.0;

    // --- Tedium as a reward-absence affect (regulated by the neuromodulator system) ---
    // Passive boredom accrual per cognitive cycle when no reward arrives. Kept below the metabolic
    // hunger drift (DELTA) so hunger dominates and drives foraging; boredom is a gentle background
    // pressure that surfaces only when basic needs are met and no reward is arriving.
    // Issue #79: rescaled /= S (see DELTA's comment above) - preserves the < DELTA ratio.
    double BOREDOM_RISE_RATE = 1.36569e-4;
    // Tedium relief per unit of positive reward-prediction error (a rewarding/novel event).
    double DA_TEDIUM_RELIEF = 1.0;
    // How strongly serotonergic contentment (satiety) slows the passive boredom rise.
    double SEROTONIN_BOREDOM_TOLERANCE = 1.0;

    // --- Orexin (wakefulness stabiliser) ---
    // Per-tick multiplicative decay of orexin tonic level.
    // τ = 1/(1-OREXIN_DECAY). At 0.90 → τ = 10 ticks; gate opens within ~9 ticks at 80% sleep
    // pressure, giving a responsive but not flickering gate. Previous value 0.97 (τ=33) was too
    // sluggish: even at 80% sleep pressure the gate took ~38 ticks to open from full-alert.
    double OREXIN_DECAY                = 0.90;
    // Below this tonic orexin level SLEEP is allowed back into the action set.
    // Fixed point at full release (sleep=0) = 1/(1-0.90) = 10.0.
    // At 50% sleep pressure: release=0.5 → fixed point = 5.0 (= gate) → gate opens at 50%.
    // At 60% sleep pressure: fixed point = 4.0 < gate → gate open; convergence ~9 ticks.
    double OREXIN_SLEEP_GATE_THRESHOLD = 5.0;

    // --- Drive-deprivation RPE rate limiting ---
    // Negative EvaluationStimulus to Valuation is emitted at most once per N adrenergic ticks
    // when the corresponding drive is above EQUILIBRIUM_BAND_UPPER. Prevents flooding Valuation
    // with one RPE event per cognitive cycle during sustained deprivation.
    int DEPRIVATION_RPE_INTERVAL = 10;

    // --- Homeostatic message batching ---
    // PartialAppraisal fired at ~134 Hz (eye-driven) when this was written. Sending one
    // AdrenergicStimulus and one AdenosinergicStimulus per cycle floods HomeostaticRegulation
    // (processes ~25/s due to TypedActor overhead), creating a backlog of stale metabolic
    // stimuli that push sleep to MAX before CholinergicStimuli from actual sleep episodes can
    // clear it.
    // Fix: accumulate deltas and send ONE batched message every HOMEO_BATCH_SIZE cycles.
    // Rate dropped to 134/20 × 2 ≈ 13/s (well below the 25/s processing capacity). The
    // total biological effect (sum of deltas) is unchanged.
    // Issue #79: post-#76/#78/JPA-removal the measured rate is ~910 Hz (see the DELTA
    // rescale comment near the top of this file), so the batched rate is now ~910/20×2≈91/s -
    // above the 25/s figure above; whether this reproduces the original stale-backlog symptom
    // is unverified. Left unscaled in Phase A along with the other decay/batch-cadence
    // constants - watch for it in the Phase A mini-experiment's sleep-arousal results.
    int HOMEO_BATCH_SIZE = 20;

    // --- Cortisol / HPA axis ---
    // Per-cycle multiplicative decay (adrenal clearance). Half-life ≈ 346 ticks ≈ 1.7 periods.
    double CORTISOL_DECAY                  = 0.998;
    // Circadian baseline synthesis added each tick regardless of phase.
    // Resting equilibrium (baseline only, k=1): solve 0.003/(1+c) = 0.002*c → c ≈ 0.82 (< 3.0). ✓
    double CORTISOL_CIRCADIAN_BASELINE     = 0.003;
    // Circadian amplitude; synthesis peaks at phase = π/2. Peak equilibrium ≈ 2.1 (< 3.0). ✓
    double CORTISOL_CIRCADIAN_AMPLITUDE    = 0.01;
    // Phase offset so that peak synthesis occurs at phase = π/2 (morning of the circadian day).
    double CORTISOL_MORNING_OFFSET         = 0.0;
    // Glucocorticoid negative-feedback gain k. Synthesis = input / (1 + k * cortisol).
    // At k=1 the synthesis rate halves when cortisol = 1.0 and approaches zero as cortisol grows.
    double CORTISOL_FEEDBACK_GAIN          = 1.0;
    // Per-handler stressor contribution: cortisol added = excess * GAIN / (1 + k * cortisol).
    double CORTISOL_STRESSOR_GAIN          = 0.05;
    // Number of consecutive above-threshold ticks before HomeostaticRegulation emits cortisol.
    // Prevents routine foraging hunger (transient, ~1 period) from triggering HPA activation.
    int    CORTISOL_STRESSOR_SUSTAIN_TICKS = 10;
    // Drive/affect arousal level above which a HomeostaticRegulation handler increments the streak.
    double STRESS_ACTIVATION_THRESHOLD     = 4.0;
    // Cortisol accumulation level above which the STRESS affect activates.
    double CORTISOL_STRESS_THRESHOLD       = 3.0;
    // Conversion factor: cortisol excess → stress arousal delta.
    double CORTISOL_STRESS_GAIN            = 0.5;
}
