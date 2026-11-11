# Known issue: the affordance filter returned the first candidate when every weight was 0 — fixed, measured, not material

**Status:** fixed on `fix/affordance-zero-weight` (2026-11-08), no flag. **The fix was tested and did not change the behaviour of A0 / A1 materially**, so
the results that depend on those arms are *not* invalidated. (An earlier version of this page withdrew them; that was premature and is reversed here.)
Plan: [`../plans/affordance-zero-weight-fix.md`](../plans/affordance-zero-weight-fix.md); recipe: [`../experiments/p107_fix_recipe.md`](../experiments/p107_fix_recipe.md);
data: HF `p107_pilot_fix/`.

## The defect

`ActionProbabilityFilter` (`AFFORDANCE`, the legacy learned action-probability table) samples one action per target from learned weights. Only EAT is ever
reinforced, so for a red or green apple seen at a distance (candidates APPROACH, AVOID, SLEEP, OBSERVE, WANDER; no EAT) every weight is 0. The normalisation then
gave NaN and the filter returned the first candidate, APPROACH, deterministically. Confirmed by `ActionProbabilityZeroWeightTest` (APPROACH 2000/2000 when listed first,
0/2000 when second). It now chooses uniformly in that case.

## What the fix changed (3 seeds x 5 creatures, seeds 10701-10703, same confs; pilot arms vs fixed)

| | A0 | A0 fixed | A1 | A1 fixed |
|---|---|---|---|---|
| creatures dead | 15 of 15 | 15 of 15 | 1 of 15 | 4 of 15 |
| d(H) over life | 3.66 | 4.27 (+0.43 to +0.78 per seed) | 1.24 | 1.63 (+0.33 to +0.47 per seed) |
| APPROACH share of cycles | 65-66% | 64-67% | 63% | 65-66% |
| EATs per minute | 14.0 | 13.4 | 13.0 | 10.4 |

Pre-registered readings (all negative): the fix does **not** lower A0's d(H) by 0.5 (it raises it), A0 still dies (15 of 15, not at most 5), and A1 fixed is further from A2 (d(H) 1.63 against 0.16),
not within 0.06. So the bug **does not explain why A0 starves**, and it does not explain the gap between A1 and A2.

## What this leaves open

- The mechanism I proposed (the zero-weight path produces the APPROACH dominance) is **not supported**: the APPROACH share is unchanged by the fix. Why A0 / A1 spend about 65% of cycles on APPROACH and eat only 0.9% of the time is unexplained; the candidate lists would have to be logged to find out.
- The fix makes A0 and A1 somewhat *worse* (it removes a deterministic fallback that happened to help), so the "classic" baseline now differs from the pre-fix one; campaigns run on the fixed code. For #108, A1 on the fixed code (4 of 15 died) is a different arm from the pilot's.
- The tracker-hysteresis explanation of A0 -> A1 and the "A1 -> A2 comes from the selector, not learning" reading both stand as they were.

## Other results

The other 27 experiment specs with an arm using the filter predate the fix and were not re-run; given the measured effect on A0 / A1 they are not considered invalid, but absolute levels may shift by about the amounts above.
