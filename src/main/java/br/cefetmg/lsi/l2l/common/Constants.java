package br.cefetmg.lsi.l2l.common;

/**
 * Created by felipe on 03/01/17.
 */
public interface Constants {

    // Issue #79 Phase B: DELTA is dt-weighted now (PartialAppraisal.tickMetabolicPacemaker -
    // see cycleEquivalent there), so its effective real-time rate is DELTA * TARGET_CYCLE_HZ
    // per real second, independent of how many onReceive calls happen in that second. This
    // is Phase A's S-rescale (S=5.858, see git history/docs/plans/issue-79-decouple-biological-clock.md
    // for the derivation) fully unwound back to its pre-#76 original value - confirmed via
    // DELTA_original = DELTA_phaseA * S = 2.56067e-4 * 5.858 = 1.50004e-3, suspiciously close
    // to a clean 1.5e-3 (same clean-round-number pattern holds for every other constant this
    // section unwinds, strong corroborating evidence these are the genuine originals).
    // Calibrated so a creature reaches MAX_AROUSAL_LEVEL from ~0 in ~L_target=150s at
    // TARGET_CYCLE_HZ=30 (the pre-#76 p59 baseline: ~150s lifespan / ~4600 cycles) -
    // dt-weighting means this now holds regardless of actual cycle throughput, unlike Phase
    // A's flat per-cycle version which re-broke if cycle rate ever shifted again.
    double DELTA = 1.5e-3;

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

    // Issue #79: rescaled /= S in Phase A (S=5.858, see DELTA's comment above), still at
    // that Phase A value - NOT unwound like DELTA/circadian below. KNOWN GAP: these accrue
    // per action-selection event (HomeostaticRegulation.handleTedium), not per dt-weighted
    // pacemaker cycle, so they remain call-rate-sensitive rather than truly wall-clock-
    // coupled - same "re-breaks if cycle throughput shifts" caveat Phase A always had.
    // Left as a follow-up (see docs/plans/issue-79-decouple-biological-clock.md's Phase B
    // section) rather than guessed at without a dt-weighting mechanism to make the value
    // meaningful.
    double TEDIUM_IDLE_RATE     = 3.41422e-3;
    double TEDIUM_OBSERVE_RATE  = 8.53555e-3;
    double TEDIUM_WANDER_RELIEF = 8.53555e-3;

    double PAIN_IMMUNE_THRESHOLD = 0.2;
    // Issue #79: rescaled /= S in Phase A - same KNOWN GAP as TEDIUM_* above (event-driven,
    // not dt-weighted).
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

    // Issue #79 Phase B: ActiveCircadianClock.tick(dt) is dt-weighted now, so this - as a
    // cycleEquivalent count - unwinds Phase A's *= S rescale back to its pre-#76 original:
    // 1172 / 5.858 = 200.07, clean round 200. The circadian day is now correctly
    // ~constant in wall-clock seconds (200/TARGET_CYCLE_HZ ≈ 6.7s) regardless of actual
    // cycle throughput, unlike Phase A's version which only held at the one throughput it
    // was measured against.
    int CIRCADIAN_PERIOD_TICKS = 200;

    // Issue #79 Phase B: dt-weighted via ActiveCircadianClock.driveRate() * cycleEquivalent
    // (PartialAppraisal.tickMetabolicPacemaker) - unwound like DELTA: 1.70711e-4 * 5.858 =
    // 1.00003e-3, clean round 1.0e-3.
    double BASE_SLEEP_DRIVE = 1.0e-3;

    // Issue #79 Phase B: same dt-weighting path as BASE_SLEEP_DRIVE - unwound:
    // 8.53555e-5 * 5.858 = 5.00013e-4, clean round 5.0e-4.
    double CIRCADIAN_AMPLITUDE = 5.0e-4;

    // Issue #79 Phase B: NOT dt-weighted (no mechanism dt-weights a tick *count* threshold
    // the way it does an accrual rate) but its meaning - "how many cycles is a micro-nap
    // floor" - is now naturally expressed in TARGET_CYCLE_HZ terms since cycle rate is
    // bounded. Unwound like CIRCADIAN_PERIOD_TICKS: 59 / 5.858 = 10.07, clean round 10
    // (≈10/TARGET_CYCLE_HZ ≈ 0.33s floor - same wall-clock intent as the original pre-#76
    // value).
    int MIN_SLEEP_TICKS = 10;

    // Issue #79 Phase B: wall-clock rate cap. CreatureActor's own scheduler (see
    // CreatureActor.java's `clock` field) fires at this rate; each tick guarantees AT LEAST
    // one cognitive cycle (a direct heartbeat to PartialAppraisal) and broadcasts this
    // tick's position for perception, which can independently produce further cycles when
    // something is actually nearby (see CreatureActor.tick()'s javadoc for the full
    // breakdown - NOT "exactly one cycle per tick"). This replaces the previous unbounded
    // self-perpetuating cascade (every movement/perceptual-field change re-triggered
    // perception immediately, with nothing to stop it running as fast as the dispatcher
    // allowed - see docs/plans/issue-79-decouple-biological-clock.md's Phase B section for
    // the full trace). 30 Hz matches the pre-#76 effective rate (p59 baseline, ~150s
    // lifespan / ~4600 cycles), already shown sufficient for behaviour emergence and
    // empirically survivable. CAVEAT (measured 2026-08-02 on p79_single_creature_diag.conf,
    // an atypically dense 1000-food-object world): actual cognitive-cycle rate can run much
    // higher than 30Hz (~300Hz observed) when many objects are in sensory range every tick -
    // still *bounded* (not runaway/recursive, since setters no longer re-trigger a
    // broadcast - see CreatureActor.java), just not tightly equal to TARGET_CYCLE_HZ. DELTA
    // and the circadian constants below are dt-weighted specifically so their real-world
    // effect stays correct regardless of this - see DELTA's comment.
    // docs/plans/arrow-ipc-write-path.md, W5: env-overridable so a mini-experiment/rehearsal
    // can dial cognitive-cycle throughput without a rebuild - e.g. the constrained-rehearsal
    // step of PR 1's verification plan. Unset (the default everywhere else) keeps 30 unchanged.
    int TARGET_CYCLE_HZ = Integer.parseInt(System.getenv().getOrDefault("TARGET_CYCLE_HZ", "30"));

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
