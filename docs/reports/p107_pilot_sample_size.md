# Sample size for the A1 / A2 campaigns (#107), from `p107_pilot`

> **Note — affordance zero-weight bug (fixed 2026-11-08).** This result predates the fix of a filter that returned the first candidate when every weight was 0. Measured on the arbiter-ladder arms (`p107_pilot_fix`) the fix did **not** change A0 / A1 behaviour materially (A0 still starves, the APPROACH share is unchanged; A1 d(H) +0.39), so the result is not considered invalid, but it has not been re-run. See [`docs/known-issues/affordance-zero-weight.md`](../known-issues/affordance-zero-weight.md).

Paired t-test on trial-level differences (unit = seed), alpha 0.05, power 0.8, non-central t. `n` = paired trials per arm. SD has 2 degrees of freedom, so each row is sized on the point SD and on the one-sided 80% upper bound (2.12 x SD). A1 pilot mean d(H) over life = 1.241.

## Superiority (two-sided), true difference = the pilot's observed mean

| contrast | measure | per-seed differences | mean | SD | n (point SD) | n (upper-bound SD) |
|---|---|---|---|---|---|---|
| A1 - A0 | d(H), first 180 s | [-1.423, -1.409, -0.344] | -1.059 | 0.619 | 5 | 15 |
| A1 - A0 | d(H) over life | [-2.41, -2.597, -2.257] | -2.421 | 0.170 | 2 | 3 |
| A1 - A0 | RED+GREEN EATs/min (lower bound) | [3.281, 4.372, 4.13] | 3.927 | 0.573 | 2 | 4 |
| A1 - A0 | D (life deciles with an EAT) | [3.4, 4.0, 3.8] | 3.733 | 0.306 | 2 | 2 |
| A2 - A1 | d(H), first 180 s | [-0.622, -0.55, -0.534] | -0.569 | 0.047 | 2 | 2 |
| A2 - A1 | d(H) over life | [-1.133, -1.031, -1.07] | -1.078 | 0.051 | 2 | 3 |
| A2 - A1 | RED+GREEN EATs/min (lower bound) | [14.099, 13.566, 13.949] | 13.871 | 0.275 | 2 | 2 |
| A2 - A1 | D (life deciles with an EAT) | [0.0, 0.0, 0.0] | 0.000 | 0.000 | n/a (SD or mean is 0) | n/a (SD or mean is 0) |
| A2 - A0 | d(H), first 180 s | [-2.044, -1.959, -0.878] | -1.627 | 0.650 | 4 | 8 |
| A2 - A0 | d(H) over life | [-3.543, -3.628, -3.327] | -3.499 | 0.155 | 2 | 3 |
| A2 - A0 | RED+GREEN EATs/min (lower bound) | [17.38, 17.938, 18.079] | 17.799 | 0.369 | 2 | 2 |
| A2 - A0 | D (life deciles with an EAT) | [3.4, 4.0, 3.8] | 3.733 | 0.306 | 2 | 2 |

## Non-inferiority of A2 against A1 on d(H) over life (one-sided; lower is better)

Margins are a decision for #107; shown: 0.06 (draft section 7.1), and 10% / 20% of the A1 pilot mean. 'True diff = 0' is the conservative design assumption; 'true diff = observed' uses the pilot's mean difference.

| margin M | true diff | n (point SD) | n (upper-bound SD) |
|---|---|---|---|
| 0.060 | 0 | 7 | 22 |
| 0.060 | -1.078 (observed) | 2 | 2 |
| 0.124 | 0 | 3 | 7 |
| 0.124 | -1.078 (observed) | 2 | 2 |
| 0.248 | 0 | 3 | 4 |
| 0.248 | -1.078 (observed) | 2 | 2 |

## Creature-level cross-check (what more creatures per trial would buy)

ICC(1) of d(H) over life across trials within an arm, and the per-group sample size at Cohen's d for A2 vs A1 with the design effect for 5 creatures per trial (`dl2l_analysis.stats.required_n`).

- A0: ICC(1) 0.000
- A1: ICC(1) 0.395
- A2: ICC(1) 0.000

A2 vs A1: Cohen's d -23.0 (A2's within-arm spread is tiny), ICC(A1) 0.395, design effect 2.58; the formula gives 0.1 creatures per group, i.e. one trial of 5 already has far more than 80% power. Not informative at this effect size; the paired-trial table above is the one to use.

## Reading

- The pilot effects are large against the between-seed SD, so the paired-trial counts are small; the binding questions are the choice of margin, what A1 vs A2 is meant to show, and the untrained-table control (a result on its own, not a sizing input).
- A0's focus-switch statistic cannot be sized: it is an approximation (the regulated emotion is not persisted). Survival is censored at the cap in A1 and A2 and is better treated as a guard ("not lower") than a powered outcome.
- With 3 seeds the SD estimate is crude; if the campaign's first trials show a larger SD, resize.
