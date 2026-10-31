package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.cluster.settings.LearningSettings;
import br.cefetmg.lsi.l2l.common.Constants;
import br.cefetmg.lsi.l2l.common.Point;
import br.cefetmg.lsi.l2l.common.SequentialId;
import br.cefetmg.lsi.l2l.creature.bd.ActionSelectionType;
import br.cefetmg.lsi.l2l.creature.bd.ChosenActionState;
import br.cefetmg.lsi.l2l.creature.conditioning.expectancy.ExpectancyMode;
import br.cefetmg.lsi.l2l.stimuli.LuminousStimulus;
import br.cefetmg.lsi.l2l.world.FruitType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #104, flag-off parity: with {@code actionSelector = FILTER_BASED} (the default) the A0 chain must choose
 * exactly what it chose before the arbiter existed. The expected sequence was recorded by running this same
 * scenario at the plan's base commit (5c59153, before the issue), where the arbiter does not exist at all.
 * The scenario prints its sequence first, so the recording run and the check use the same code path.
 */
class FlagOffParityTest {

    private static final Point CREATURE_AT = new Point(100, 100);
    private static final Point BOUNDS = new Point(1000, 1000);

    /** A0 (arbiter_a0.conf's stack) with focal drive off, as the baseline arm runs it. */
    private static LearningSettings a0() {
        return new LearningSettings(false, false,
                List.of(ActionSelectionType.TARGET_DISTANCE, ActionSelectionType.AFFORDANCE, ActionSelectionType.RANDOM),
                false, ExpectancyMode.DISCRETE, false, true, false, false, true, false);
    }

    /**
     * SHA-256 (hex) of the comma-joined "action/selection_type" sequence of the 300 cycles, recorded at 5c59153.
     * The digest stands in for the 300 entries; the sequence itself is printed so a mismatch can be inspected.
     */
    private static final String GOLDEN_SHA256 = "cb54d44e37a42e6073748f8da327edf0f1c7d37cc00f4203bc6ab4871bc517b0";

    private static String sha256(String text) throws java.security.NoSuchAlgorithmException {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    @Test
    void theA0ChainWithTheArbiterOffChoosesWhatItChoseBeforeTheArbiter() throws Exception {
        TestingHarness h = TestingHarness.builder().learningSettings(a0()).position(CREATURE_AT)
                .worldBoundaries(BOUNDS).seed(104L).build();
        h.creature().emotions().regulate(Constants.HUNGER, 3.0);
        for (int i = 0; i < 300; i++) {
            // An apple in view on two cycles of three, so both perceived and self-only cycles occur.
            if (i % 3 != 2) {
                h.injectLuminous(new LuminousStimulus(new SequentialId(42L), new SequentialId(43L),
                        FruitType.RED_APPLE, new Point(150, 100)));
            }
            h.tick();
        }
        List<String> sequence = h.bdSink().ofType(ChosenActionState.class).stream()
                .map(r -> r.getAction().name() + "/" + r.getActionSelectionType().name())
                .toList();

        String joined = String.join(",", sequence);
        System.out.println("PARITY_COUNT=" + sequence.size());
        System.out.println("PARITY_SHA256=" + sha256(joined));
        System.out.println("PARITY_SEQ=" + joined);
        assertEquals(GOLDEN_SHA256, sha256(joined));
    }
}
