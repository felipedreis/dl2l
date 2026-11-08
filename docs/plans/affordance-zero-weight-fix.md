# Plan: the affordance filter's all-zero-weight fallback (bug), its fix, and what it invalidates

Status: **fix implemented 2026-11-08; comparison done: not material** (recipe: `docs/experiments/p107_fix_recipe.md`; results: `docs/known-issues/affordance-zero-weight.md`).

## The bug

`ActionProbabilityFilter` (`AFFORDANCE`, the legacy learned action-probability table of A0 and A1) picks one action per target object type
by sampling the learned weights. When every candidate action of a target has weight 0 the weights sum to 0, the normalisation divides 0
by 0, every weight becomes NaN, `r <= accumulator` is always false, and the loop leaves `chosenAction = 0`: **the first candidate, always**.

When it fires: the table learns weights only for the *reinforced* action, and only EAT is ever reinforced (40,638 updates in the p107 gate-1
dump, nothing else). Final learned weights there: red and green apples EAT = 1, every other action 0; gray apples EAT = 0 and
APPROACH / AVOID / SLEEP about 0.25 each. A red or green apple seen at a distance has candidates APPROACH, AVOID, SLEEP, OBSERVE, WANDER
(`actionsAtDistance`, in that order; EAT only exists at contact), all weight 0, so the filter returns APPROACH, deterministically. This
matches A0 / A1 spending 63-65% of their cycles on APPROACH (A2, which does not use the filter: 27%).

Confirmed by `ActionProbabilityZeroWeightTest` (before the fix: APPROACH chosen 2000/2000 when listed first and 0/2000 when second).
Not confirmed: that this is what makes A0 starve (the hypothesis is dithering between targets in the following `RANDOM` filter).

## The fix (no flag)

When the weights sum to 0 (or are not finite) the filter chooses uniformly among that target's candidates, drawing `nextInt(n)` from its
own seeded stream. The positive-weight path is unchanged. Runs are not comparable with earlier ones draw-by-draw: the fallback draws one
`nextInt` where it used to draw one `nextDouble` and then fall through.

## What it invalidates

Nothing, as it turned out. The first version of this plan treated every A0 / A1 result as invalid until re-run; the comparison (`p107_pilot_fix`) showed the fix does not change A0 / A1 behaviour materially (A0 still starves, APPROACH share unchanged, A1 slightly worse), so the notices were softened and the results stand. See the known-issues page for the numbers.

## Check

`mvn test` (514 tests, includes the regression test). Then `p107_pilot_fix`: A0 and A1 with the fix on the pilot's seeds, compared with the
pilot's A0 / A1 paired by seed.
