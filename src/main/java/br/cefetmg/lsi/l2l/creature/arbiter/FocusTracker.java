package br.cefetmg.lsi.l2l.creature.arbiter;

import br.cefetmg.lsi.l2l.common.Constants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks which drive holds attentional focus and turns that into episode events
 * (open / close / suspend / resume), with hysteresis so focus does not flicker between
 * drives of similar urgency.
 *
 * <p>Urgency is {@code u_i = max(0, H_i - H*) / scale}. Thresholds are in the same units
 * (raw arousal above baseline by default; see {@link Params#defaults()}). Rules, evaluated
 * once per cognitive cycle by {@link #update}:
 * <ol>
 *   <li>focal {@code u <= thetaOff}: episode closes as REGULATED;</li>
 *   <li>focal active for more than {@code tMax} cycles: closes as TIMED_OUT (no re-open that cycle);</li>
 *   <li>while focal {@code u < thetaCrit}, any drive at {@code u >= thetaCrit} takes focus at once;</li>
 *   <li>otherwise a drive takes focus only if its urgency exceeds the focal one by more than {@code delta};</li>
 *   <li>with no focus, the most urgent drive at {@code u >= thetaOn} opens an episode;</li>
 *   <li>a pre-empted episode is suspended for {@code resumeWindow} cycles and resumes if
 *       focus returns to it in time, keeping its elapsed active cycles.</li>
 * </ol>
 * Ties are broken by the fixed order of {@link #DRIVES}, so seeded runs stay deterministic.
 * Not thread-safe; meant to be owned by a single actor.
 */
public class FocusTracker {

    /** Tie-break order, also the set of drives that can hold focus. */
    public static final List<String> DRIVES =
            List.of(Constants.HUNGER, Constants.SLEEP, Constants.PAIN, Constants.TEDIUM);

    /**
     * @param tMax placeholder until calibrated as 3 x median steps-to-outcome from p84 data (#107)
     */
    public record Params(double thetaOn, double thetaOff, double delta, double thetaCrit,
                         int tMax, int resumeWindow, double hStar, double scale) {

        public static Params defaults() {
            return new Params(2.0, 0.5, 0.3, 4.0, 200, Constants.MIN_SLEEP_TICKS,
                    Constants.MIN_AROUSAL_LEVEL, 1.0);
        }

        public Params withTMax(int value) {
            return new Params(thetaOn, thetaOff, delta, thetaCrit, value, resumeWindow, hStar, scale);
        }

        public Params withResumeWindow(int value) {
            return new Params(thetaOn, thetaOff, delta, thetaCrit, tMax, value, hStar, scale);
        }
    }

    private static final class Episode {
        final int openCycle;
        int activeCycles;
        int deadline;

        Episode(int openCycle) {
            this.openCycle = openCycle;
        }
    }

    private final Params params;
    private String focal;
    private Episode focalEpisode;
    private final Map<String, Episode> suspended = new LinkedHashMap<>();

    public FocusTracker(Params params) {
        this.params = params;
    }

    public Optional<String> focalDrive() {
        return Optional.ofNullable(focal);
    }

    public boolean isSuspended(String drive) {
        return suspended.containsKey(drive);
    }

    public List<FocusEvent> update(int cycle, Map<String, Double> levels) {
        List<FocusEvent> events = new ArrayList<>();
        Map<String, Double> u = urgencies(levels);

        expireSuspended(cycle, u, events);

        if (focal != null) {
            focalEpisode.activeCycles++;
            double fu = u.get(focal);
            if (fu <= params.thetaOff()) {
                close(cycle, FocusEvent.Outcome.REGULATED, events);
            } else if (focalEpisode.activeCycles > params.tMax()) {
                close(cycle, FocusEvent.Outcome.TIMED_OUT, events);
                return events;
            } else {
                String challenger = challenger(u, fu);
                if (challenger != null) {
                    preempt(cycle, challenger, events);
                }
                return events;
            }
        }

        String best = null;
        for (String d : DRIVES) {
            if (u.get(d) >= params.thetaOn() && (best == null || u.get(d) > u.get(best))) {
                best = d;
            }
        }
        if (best != null) {
            focus(cycle, best, events);
        }
        return events;
    }

    /** Closes every open or suspended episode with outcome DEATH. */
    public List<FocusEvent> onDeath(int cycle) {
        List<FocusEvent> events = new ArrayList<>();
        if (focal != null) {
            close(cycle, FocusEvent.Outcome.DEATH, events);
        }
        suspended.keySet().forEach(d -> events.add(new FocusEvent.Closed(cycle, d, FocusEvent.Outcome.DEATH)));
        suspended.clear();
        return events;
    }

    private Map<String, Double> urgencies(Map<String, Double> levels) {
        Map<String, Double> u = new LinkedHashMap<>();
        for (String d : DRIVES) {
            double h = levels.getOrDefault(d, params.hStar());
            u.put(d, Math.max(0, h - params.hStar()) / params.scale());
        }
        return u;
    }

    private void expireSuspended(int cycle, Map<String, Double> u, List<FocusEvent> events) {
        var it = suspended.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (u.get(e.getKey()) <= params.thetaOff()) {
                events.add(new FocusEvent.Closed(cycle, e.getKey(), FocusEvent.Outcome.REGULATED));
                it.remove();
            } else if (cycle > e.getValue().deadline) {
                events.add(new FocusEvent.Closed(cycle, e.getKey(), FocusEvent.Outcome.TIMED_OUT));
                it.remove();
            }
        }
    }

    /** The drive that should take focus from the incumbent, or null. */
    private String challenger(Map<String, Double> u, double focalUrgency) {
        boolean emergency = focalUrgency < params.thetaCrit();
        String best = null;
        for (String d : DRIVES) {
            if (d.equals(focal)) {
                continue;
            }
            double ud = u.get(d);
            boolean qualifies = emergency && ud >= params.thetaCrit()
                    || ud > focalUrgency + params.delta();
            if (qualifies && (best == null || ud > u.get(best))) {
                best = d;
            }
        }
        return best;
    }

    private void preempt(int cycle, String challenger, List<FocusEvent> events) {
        focalEpisode.deadline = cycle + params.resumeWindow();
        suspended.put(focal, focalEpisode);
        events.add(new FocusEvent.Suspended(cycle, focal, challenger));
        focus(cycle, challenger, events);
    }

    private void focus(int cycle, String drive, List<FocusEvent> events) {
        Episode resumed = suspended.remove(drive);
        focal = drive;
        if (resumed != null) {
            focalEpisode = resumed;
            events.add(new FocusEvent.Resumed(cycle, drive));
        } else {
            focalEpisode = new Episode(cycle);
            events.add(new FocusEvent.Opened(cycle, drive));
        }
    }

    private void close(int cycle, FocusEvent.Outcome outcome, List<FocusEvent> events) {
        events.add(new FocusEvent.Closed(cycle, focal, outcome));
        focal = null;
        focalEpisode = null;
    }
}
