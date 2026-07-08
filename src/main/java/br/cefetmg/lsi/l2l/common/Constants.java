package br.cefetmg.lsi.l2l.common;

/**
 * Created by felipe on 03/01/17.
 */
public interface Constants {

    double DELTA = 1.5e-3;

    double CHOLINERGIC_DELTA = 1e-1;

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

    double TEDIUM_IDLE_RATE     = 2e-2;
    double TEDIUM_OBSERVE_RATE  = 5e-2;
    double TEDIUM_WANDER_RELIEF = 5e-2;

    double PAIN_IMMUNE_THRESHOLD = 0.2;
    double PAIN_IMMUNE_RATE      = 5e-3;

    double MIN_AROUSAL_LEVEL = 0.18;
    double MAX_AROUSAL_LEVEL = 7;

    int COMPLEX_TASK = 2;

    int TRACE_DECAY_HALF_LIFE = 5;

    double MIN_TRACE_ELIGIBILITY = 0.01;

    int CONSOLIDATION_WINDOW = 128;

    int MEMORY_FILTER_WINDOW = 256;

    double MEMORY_CONSOLIDATION_THRESHOLD = 0.1;

    int CONSOLIDATION_BATCH_SIZE = 16;

    int CIRCADIAN_PERIOD_TICKS = 200;

    double BASE_SLEEP_DRIVE = 1e-3;

    double CIRCADIAN_AMPLITUDE = 5e-4;

    int MIN_SLEEP_TICKS = 10;

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
    // Passive boredom accrual per cognitive cycle when no reward arrives.
    double BOREDOM_RISE_RATE = 5e-3;
    // Tedium relief per unit of positive reward-prediction error (a rewarding/novel event).
    double DA_TEDIUM_RELIEF = 1.0;
    // How strongly serotonergic contentment (satiety) slows the passive boredom rise.
    double SEROTONIN_BOREDOM_TOLERANCE = 1.0;
}
