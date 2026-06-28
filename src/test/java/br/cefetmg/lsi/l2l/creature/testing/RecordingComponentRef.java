package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.creature.ComponentRef;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Forwards every {@link #tell(Object)} to a downstream {@link ComponentRef} and records
 * the message. Tests inspect the recorded list to verify the stimulus chain through
 * the creature.
 */
public final class RecordingComponentRef implements ComponentRef {

    private final String name;
    private final ComponentRef downstream;
    private final List<Object> messages = new ArrayList<>();

    public RecordingComponentRef(String name, ComponentRef downstream) {
        this.name = name;
        this.downstream = downstream;
    }

    @Override
    public void tell(Object msg) {
        messages.add(msg);
        downstream.tell(msg);
    }

    public String name() {
        return name;
    }

    public List<Object> messages() {
        return List.copyOf(messages);
    }

    public int size() {
        return messages.size();
    }

    public void clear() {
        messages.clear();
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> ofType(Class<T> type) {
        return messages.stream()
                .filter(type::isInstance)
                .map(m -> (T) m)
                .collect(Collectors.toList());
    }

    public <T> T lastOf(Class<T> type) {
        List<T> matches = ofType(type);
        return matches.isEmpty() ? null : matches.get(matches.size() - 1);
    }

    public boolean hasAny(Class<?> type) {
        return messages.stream().anyMatch(type::isInstance);
    }
}
