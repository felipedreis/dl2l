package br.cefetmg.lsi.l2l.creature.conditioning.expectancy;

import java.io.Serializable;

/**
 * Immutable snapshot of the internal state an {@link ExpectancyPredictor} conditions its
 * prediction on, captured at the moment of a world interaction (decision time).
 *
 * <p>Carries the dominant regulated drive's name and its arousal level. The DISCRETE variant keys
 * only on the name; the CONTINUOUS variant additionally discretises the level, so it <em>can</em>
 * represent a dependence of reward on the drive magnitude. Whether that extra granularity helps
 * depends on the environment actually producing level-dependent reward (see the realized-reward
 * change in {@code HomeostaticRegulation} and the issue-57 report).
 */
public record ExpectancyContext(String dominantDriveName, double dominantDriveLevel)
        implements Serializable {
}
