package br.cefetmg.lsi.l2l.creature.testing;

import br.cefetmg.lsi.l2l.creature.ComponentRef;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Terminal {@link ComponentRef} for messages leaving the creature: the holder, the
 * memory consolidator, the BD actor. Records messages so tests can assert on what
 * the creature emitted to its external collaborators.
 */
public final class ExternalSink implements ComponentRef {

    private final String name;
    private final List<Object> messages = new ArrayList<>();

    public ExternalSink(String name) {
        this.name = name;
    }

    @Override
    public void tell(Object msg) {
        messages.add(msg);
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
