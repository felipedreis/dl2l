package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.cluster.settings.Simulation;
import br.cefetmg.lsi.l2l.common.Constants;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sleep hysteresis switch (docs/plans/sleep-hysteresis-switch.md): the gate's condition and the setting. */
class SleepHysteresisSwitchTest {

    @Test
    void theGateHoldsASleepingCreatureUntilMinSleepTicksAndOnlyWhenOn() {
        int min = Constants.MIN_SLEEP_TICKS;
        assertTrue(FullAppraisal.holdsSleep(true, true, 0));
        assertTrue(FullAppraisal.holdsSleep(true, true, min - 1));
        assertFalse(FullAppraisal.holdsSleep(true, true, min), "at MIN_SLEEP_TICKS the creature may wake");
        assertFalse(FullAppraisal.holdsSleep(true, false, 0), "no active sleep episode, nothing to hold");
        assertFalse(FullAppraisal.holdsSleep(false, true, 0), "switch off: never held");
        assertFalse(FullAppraisal.holdsSleep(false, true, min - 1));
    }

    @Test
    void theSwitchIsOnByDefaultInEveryOlderConstructorAndConfig() {
        assertTrue(new LearningSettings().isSleepHysteresisEnabled());
        assertTrue(load("arbiter_a3.conf").isSleepHysteresisEnabled());
        assertTrue(load("arbiter_a0.conf").isSleepHysteresisEnabled());
    }

    @Test
    void theSwitchIsReadFromTheSimulationConfig() {
        Config off = ConfigFactory.parseString("simulation.learningSettings.sleepHysteresisEnabled = false")
                .withFallback(ConfigFactory.parseFile(new File("simulations/arbiter_a3.conf"))).resolve();
        assertFalse(new Simulation(off).getLearningSettings().isSleepHysteresisEnabled());
    }

    private static LearningSettings load(String name) {
        return new Simulation(ConfigFactory.parseFile(new File("simulations/" + name)).resolve()).getLearningSettings();
    }
}
