package br.cefetmg.lsi.l2l.creature.common;

import java.util.EnumSet;
import java.util.Set;

/**
 * Created by felipe on 22/03/17.
 */
public enum ActionType {
    EAT, APPROACH, AVOID, ESCAPE, TURN, SLEEP, WANDER, TOUCH, PLAY, OBSERVE;

    /**
     * Acts that consume or manipulate an object and so produce an outcome attributable to
     * <em>that</em> object — Mapa's (2009) {@code comer, tocar, brincar}, the only acts her
     * long-term memory stores.
     *
     * <p>The distinction is not stylistic. An APPROACH's outcome depends on what the creature
     * does next, so eligibility traces credit every recent approach when a drive later falls,
     * regardless of which object was approached. Measured over the p84 pilot, EAT engrams
     * discriminate GREEN/RED/GRAY apples by caloric value at 6.3x while APPROACH engrams manage
     * 1.09x — approach credit is very nearly object-blind.
     */
    public static final Set<ActionType> CONSUMMATORY = EnumSet.of(EAT, TOUCH, PLAY);

    public boolean isConsummatory() {
        return CONSUMMATORY.contains(this);
    }
}