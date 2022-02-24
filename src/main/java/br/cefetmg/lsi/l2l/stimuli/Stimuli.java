package br.cefetmg.lsi.l2l.stimuli;

/**
 * Created by felipe on 04/09/17.
 */
public final class Stimuli {

    public static final String[] internalStimuli = {
            AdrenergicStimulus.class.getSimpleName(),
            CorticalStimulus.class.getSimpleName(),
            EmotionalStimulus.class.getSimpleName(),
            NutritiveStimulus.class.getSimpleName(),
            EvaluationStimulus.class.getSimpleName(),
            FocusStimulus.class.getSimpleName(),
            MuscularStimulus.class.getSimpleName(),
            ProprioceptiveStimulus.class.getSimpleName(),
            SomaticStimulus.class.getSimpleName(),
            VisualStimulus.class.getSimpleName(),
            OlfactoryStimulus.class.getSimpleName(),
            TouchStimulus.class.getSimpleName()
    };

    public static final String[] externalStimuli = {
            DestructiveStimulus.class.getSimpleName(),
            EnergeticStimulus.class.getSimpleName(),
            LuminousStimulus.class.getSimpleName(),
            MechanicalStimulus.class.getSimpleName(),
            TactileStimulus.class.getSimpleName(),
            SmellStimulus.class.getSimpleName(),
    };
}
