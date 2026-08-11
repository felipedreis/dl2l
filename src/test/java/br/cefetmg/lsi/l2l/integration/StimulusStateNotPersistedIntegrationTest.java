package br.cefetmg.lsi.l2l.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code stimulus_state} must not be written.
 *
 * <p>It was the single most expensive table in the dump and no consumer read it: measured on a
 * p84 {@code current_nomem} trial, {@code stimulus_state.arrow} was 3.2 GB of a 5.6 GB raw dump —
 * 60% of the output — while {@code scripts/dl2l_data/tables.py} references it in none of its 20
 * queries. It is what exhausted the CCAD disk quota and cost four of six arms of the campaign,
 * so this is pinned rather than left to be re-added by someone restoring "missing" data.
 *
 * <p>Asserted against the real {@code .arrow} artifact rather than a sink, because the omission
 * lives in {@code BDActor.expand} — components still build {@code StimulusState} objects and hand
 * them to the actor inside a {@code ChangeStimulusState}'s object graph, and the actor simply
 * stops walking into them. A component-level sink sees the parent and would pass either way.
 */
public class StimulusStateNotPersistedIntegrationTest {

    @Test
    void the_dump_contains_no_stimulus_state_rows(@TempDir Path saveDir) throws Exception {
        try (SimulationIntegrationHarness h = SimulationIntegrationHarness.boot(
                "simulations/integration_single_creature.conf", "", saveDir)) {

            // Let a creature actually cognize, so the stimulus traffic that used to be written
            // really did happen — otherwise this passes for the wrong reason.
            h.awaitCond(() -> true, Duration.ofSeconds(2), "warmup");
            Thread.sleep(3000);

            // The parent table must be populated: that is what proves stimuli were flowing.
            Map<String, List<Object>> change = h.finalizeAndRead("change_stimulus_state");
            int changeRows = change.isEmpty() ? 0 : change.values().iterator().next().size();
            assertTrue(changeRows > 0,
                    "no change_stimulus_state rows — the creature never cognized, so this test "
                            + "would pass vacuously");

            Path stimulus = saveDir.resolve("raw").resolve("stimulus_state.arrow");
            if (Files.exists(stimulus)) {
                Map<String, List<Object>> rows = h.finalizeAndRead("stimulus_state");
                int n = rows.isEmpty() ? 0 : rows.values().iterator().next().size();
                assertEquals(0, n, "stimulus_state must receive no rows (got " + n
                        + " alongside " + changeRows + " change_stimulus_state rows)");
            }
        }
    }
}
