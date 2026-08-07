# MemoryFilter: rank actions by mean engram value, not sum

Issue: [#88](https://github.com/felipedreis/dl2l/issues/88)
Found by: the issue #84 behaviour-parity rerun (48 trials, 240 creatures)

## Context

`MemoryFilter` scores each candidate action by accumulating `-emotionDelta × eligibility`
over the engrams matching its `(ActionType, WorldObjectType)` key, and picks the argmax:

```java
double contribution = -e.emotionDelta() * e.eligibility();
scores.merge(key, contribution, Double::sum);   // frequency, not quality
```

Because the accumulator is a **sum**, the score grows with *how many times an action has
been taken*, not with *how good it was*. And since taking an action lays more engrams for
it, the filter is self-reinforcing: an action chosen early accumulates a larger sum, which
gets it chosen again, without regard to outcome.

Measured over one trial's 252,188 engrams:

| action | engrams | **total** score | **mean** score |
|---|---|---|---|
| APPROACH | 172,088 (68.2%) | **895.9** | 0.0052 |
| WANDER | 74,411 (29.5%) | 572.7 | 0.0077 |
| SLEEP | 4,673 (1.9%) | 47.3 | 0.0101 |
| EAT | 1,016 (0.4%) | 11.5 | **0.0113** |

EAT is the **most valuable action per occurrence and the least valuable by sum**. The filter
therefore prefers APPROACH — creatures approach food continuously and rarely eat it.

Behavioural consequence across all three arm pairs of the p84 rerun (40 creatures per arm):

| pair | no-memory | memory |
|---|---|---|
| legacy | 246 s, 47 EAT/creature, 40/40 died | 181 s, **13** EAT/creature, 40/40 died |
| current | **0/40 died**, 6,864 EAT/creature | 597 s, **365** EAT/creature, 40/40 died |
| simple world | **0/40 died**, 3,709 EAT/creature | 402 s, **55** EAT/creature, 40/40 died |

In two of three pairs, mortality goes from 0% without memory to 100% with it. MEMORY won
81–94% of decisions in the memory arms, so this is the dominant behaviour, not an edge case.

## Decision

Rank by the **mean** contribution per engram rather than the sum. Chosen by the user from the
three candidates in #88 (mean / recency weighting / explicit frequency normalisation).

Mean is the minimal change that removes the frequency term, and on the data above it flips the
ranking to EAT > SLEEP > WANDER > APPROACH — the intended ordering. Recency weighting and
explicit normalisation both introduce a constant that Mapa does not specify;
`docs/plans/tedium-saturation.md` is a cautionary record of two such guessed constants being
reverted, so they are not pursued here.

**Deliberately not included:** a minimum-engram-count guard. With a mean, a single
high-value engram can outrank a well-established pattern, which is a real risk — but the
threshold would be another unspecified constant, and the engram window is already bounded to
the most recent `Constants.MEMORY_FILTER_WINDOW`. Left for the data to settle: if thrashing
shows up in the rerun, that is evidence for a guard, and evidence is a better basis for the
constant than a guess.

## Change

`src/main/java/br/cefetmg/lsi/l2l/creature/actionSelector/MemoryFilter.java` — accumulate
`(sum, count)` per key and divide, instead of summing. No signature or call-site changes; the
filter's contract, gates and pass-through behaviour are untouched.

## Tests

`MemoryFilterTest.multiple_engrams_for_same_key_are_summed` currently **encodes the bug as
intended behaviour**:

```
// RED: two engrams, +0.8 and +0.5 = total +1.3
// GREEN: one engram, +1.2
// GREEN total 1.2 < RED total 1.3 → RED wins
```

RED wins purely by having more engrams although each is worth less than GREEN's single one —
a miniature of the exact failure. It is renamed and its assertion inverted, becoming the
regression test: under the mean, RED scores 0.65 and GREEN 1.2, so GREEN wins.

Added alongside it, from #88's acceptance criteria: a rare high-value action must beat a
frequent low-value one at a ratio (100 engrams vs 1) that unambiguously separates the two
rules.

Every other existing test uses one engram per key, where mean and sum coincide, so they pin
the unchanged behaviour and must keep passing untouched.

## Verification

1. `mvn test` — all pass, with the two tests above specifically covering the change.
2. Replay the real engram distribution above through both rules and confirm the ranking
   flips to EAT-first. This uses recorded campaign data rather than a synthetic fixture, so
   it validates against the distribution that actually produced the failure.
3. Behavioural confirmation requires rerunning issue #84's campaign, which is a separate
   step — the acceptance criteria there (memory no longer reduces feeding or shortens life)
   cannot be checked from a unit test.

## Consequences

- Changes behaviour for **every** experiment enabling the MEMORY filter. Prior datasets that
  used it are not comparable to post-fix ones.
- Issue #84's P1/P4/P5/D2 must be re-derived after this lands; its P2/P3, D1 and the
  no-memory arms are unaffected.
- P4 additionally needs a longer runtime cap: two `*_nomem` arms had zero deaths, so the
  survival ratio has no denominator regardless of this fix.
