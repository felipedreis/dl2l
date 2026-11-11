package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.world.WorldObjectType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The {@link ReturnSink} that turns the steps an {@link EpisodeBuffer} closes into {@link EpisodicEngram}s (issue
 * #111): the sink the operant table no longer uses. Encodes each step's key from its percept and its decision-time
 * drive levels, and hands the engrams, in step order, to its consumer (the {@link EpisodicStore}, and persistence).
 *
 * <p>The bootstrap term of a pre-empted step is recovered from the buffer's own value, {@code value - V(G | H)},
 * so the two can never disagree; every other close type has none.
 *
 * <p>Pure apart from the consumer. Not thread-safe.
 */
public final class EngramWriter implements ReturnSink {

    private final EngramKeyEncoder encoder;
    private final HomeostaticValue value;
    private final Consumer<List<EpisodicEngram>> out;

    /**
     * @param encoder the key encoder
     * @param value   the value function the buffer used to value its steps
     * @param out     receives the engrams of each closed segment, never empty
     * @throws IllegalArgumentException if an argument is null
     */
    public EngramWriter(EngramKeyEncoder encoder, HomeostaticValue value, Consumer<List<EpisodicEngram>> out) {
        if (encoder == null || value == null || out == null) {
            throw new IllegalArgumentException("encoder, value and out must not be null");
        }
        this.encoder = encoder;
        this.value = value;
        this.out = out;
    }

    @Override
    public void onClosed(List<ClosedStep> steps) {
        List<EpisodicEngram> engrams = new ArrayList<>(steps.size());
        for (ClosedStep s : steps) {
            double[] h = s.h();
            double[] g = s.g();
            double bootstrapTerm = s.closeType() == ClosedStep.CloseType.PREEMPTED ? s.value() - value.value(g, h) : 0;
            WorldObjectType target = s.action().perception.objectType.getOrElse(null);
            engrams.add(new EpisodicEngram(s.action().type, target == null ? null : target.name(),
                    encoder.encode(s.action().perception, h), h, g, bootstrapTerm, s.episodeId(), s.drive(),
                    s.closeType(), s.cycle(), s.closeCycle() - s.cycle()));
        }
        if (!engrams.isEmpty()) {
            out.accept(List.copyOf(engrams));
        }
    }
}
