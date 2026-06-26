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

    double PAIN_DECAY_RATE = 5e-3;

    double MIN_AROUSAL_LEVEL = 0.18;
    double MAX_AROUSAL_LEVEL = 7;

    int COMPLEX_TASK = 2;

    int TRACE_DECAY_HALF_LIFE = 5;

    double MIN_TRACE_ELIGIBILITY = 0.01;

    int CONSOLIDATION_WINDOW = 128;

    int CONSOLIDATION_BATCH_SIZE = 16;

    int CIRCADIAN_PERIOD_TICKS = 200;

    double BASE_SLEEP_DRIVE = 1e-3;

    double CIRCADIAN_AMPLITUDE = 5e-4;

    int MIN_SLEEP_TICKS = 10;
}
