package br.cefetmg.lsi.l2l.creature.components;

import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.SequentialId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PartialAppraisalTest {

    private final PartialAppraisal pa = new PartialAppraisal(new SequentialId(), null);

    // --- Simple task (0–1 objects) → monotonic increasing (Mapa Eq 4.1) ---

    @Test
    void simple_task_efficiency_is_monotonically_increasing() {
        double[] arousalLevels = {
            Constants.MIN_AROUSAL_LEVEL, 0.5, 1.0, 2.0, 3.5, 5.0, Constants.MAX_AROUSAL_LEVEL
        };
        for (int perceptions : new int[]{0, 1}) {
            double prev = pa.normalizedBehaviouralEfficiency(arousalLevels[0], perceptions);
            for (int i = 1; i < arousalLevels.length; i++) {
                double curr = pa.normalizedBehaviouralEfficiency(arousalLevels[i], perceptions);
                assertTrue(curr > prev,
                    String.format("Simple task (perceptions=%d): not monotonically increasing at arousal=%.2f",
                        perceptions, arousalLevels[i]));
                prev = curr;
            }
        }
    }

    // --- Complex task (≥2 objects) → inverted-U with interior maximum (Mapa Eq 4.2) ---

    @Test
    void complex_task_efficiency_has_interior_maximum() {
        // Eq 4.2 peak at A* = 280/(2×40) = 3.5
        double eLow  = pa.normalizedBehaviouralEfficiency(Constants.MIN_AROUSAL_LEVEL, 2);
        double ePeak = pa.normalizedBehaviouralEfficiency(3.5, 2);
        double eHigh = pa.normalizedBehaviouralEfficiency(Constants.MAX_AROUSAL_LEVEL, 2);

        assertTrue(ePeak > eLow,
            "Complex task: peak efficiency must exceed efficiency at MIN_AROUSAL_LEVEL");
        assertTrue(ePeak > eHigh,
            "Complex task: peak efficiency must exceed efficiency at MAX_AROUSAL_LEVEL");
    }

    // --- Both curves in [0, 1] ---

    @Test
    void simple_task_efficiency_in_unit_range() {
        for (double a : new double[]{Constants.MIN_AROUSAL_LEVEL, 1.0, 3.5, Constants.MAX_AROUSAL_LEVEL}) {
            double e = pa.normalizedBehaviouralEfficiency(a, 0);
            assertTrue(e >= 0 && e <= 1,
                String.format("Simple task efficiency out of [0,1] at arousal=%.2f: %.4f", a, e));
        }
    }

    @Test
    void complex_task_efficiency_in_unit_range() {
        for (double a : new double[]{Constants.MIN_AROUSAL_LEVEL, 1.0, 3.5, Constants.MAX_AROUSAL_LEVEL}) {
            double e = pa.normalizedBehaviouralEfficiency(a, 2);
            assertTrue(e >= 0 && e <= 1,
                String.format("Complex task efficiency out of [0,1] at arousal=%.2f: %.4f", a, e));
        }
    }
}
