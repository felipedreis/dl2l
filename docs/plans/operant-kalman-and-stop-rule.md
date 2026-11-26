# Plan: Kalman operant cell, stop rule, APPROACH credit

Status: **draft for approval, 2026-11-18.** Branch `feature/a3-episodic`. No code until approved.
Spec: `controllers-spec.md` 2.1-2.4, 3.2, 3.3. Design source: `controllers-literature-operant-learner.md` sections 2 and 5.

Three changes, each behind one setting whose default keeps today's behaviour, so the registered A2/A3/A5 arms
(`simulations/arbiter_a*.conf`, `p117_*`) reproduce without edits. All three are cheap branches.

| Setting (`simulation.learningSettings.*`) | Default (today) | New |
|---|---|---|
| `operantEstimator` | `WELFORD` | `KALMAN` |
| `arbiterStopRule` | `TOP_TWO` | `WHOLE_DRAW` |
| `timeoutZeroesApproach` | `true` | `false` |

Parsed in `Simulation.java` next to the other keys (L152-168), stored in `LearningSettings` (main constructor L363-384).

## 1. Kalman operant cell

**What it is.** A Kalman filter here is a running average whose step size depends on how unsure it is, and whose
uncertainty creeps back up when nothing is observed (the world may have changed). One cell per (object type, action
type), scalar, as decided. State: `m` (expected drive reduction), `P` (variance of `m`, so SE = sqrt(P)), `s2`
(spread of single outcomes, so sigma = sqrt(s2)).

On an outcome `v` for cell (o, a):
```
P += tau2;  k = P / (P + s2);  m += k (v - m);  P *= (1 - k);  update s2 with v
every other existing cell (o, a') on the same object:  P += tau2
```
- **tau2 clock (recommendation):** per outcome on that object by any action, as the review proposes. It needs no
  decision-cycle counter and is the least arbitrary option.
- **s2:** the existing `WelfordTable` (decay 0.995) for the sample spread, blended with a prior `s2_0` worth one
  observation exactly as `OperantEstimator.blendedSigma` does now (`OperantEstimator.java:140-143`), floored at `s2_min`.
- **Scale (same reasoning as the episodic sigma0 = 0.1 note):** values are drive reductions of about 0.15-0.30 per bite,
  so `s2_0 = 0.1^2`, `s2_min = 0.05^2`. Steady-state gain satisfies `tau2 = k*^2 s2 / (1 - k*)`; for k* = 0.25 at
  s2 = 0.01 this gives **tau2 = 8e-4** (k* = 0.43 at the floor; steady SE about 0.05). Fixed before the mini-experiment,
  then checked by calibration (z-variance above 1 means tau2 too small).
- **Untried cell, pooled prior:** if action `a` has cells on other objects, start from `m0` = mean of their `m`,
  `P0 = max(between-object variance of those m, s2_0) + tau2`, `s2 = s2_0`. **It counts as evidence** under spec rule 2
  (it is what the creature knows about that action), and every estimate is tagged so calibration is checked on
  pooled-prior cells separately.
- **Abstention:** if `a` was never tried on anything, the cell abstains. A first outcome on an abstaining cell is then
  `m = v, P = s2` (the limit of the update with infinite P).
- **No APPROACH rule.** Option (a) follows from the learning signal: no evaluation carries APPROACH (the emitters are
  `HomeostaticRegulation.java:173, 189, 204, 216, 226, 246`; to be confirmed for the two that pass `s.action`), so APPROACH,
  and the `NONE` bucket (WANDER, SLEEP on the creature, never recorded, `Valuation.java:178-180`), abstain by construction.
  A test pins this; the code does not special-case it.

**The sample: common unit.** Today `Valuation.recordOperant` records `+1 / -1` (`Valuation.java:198`) while the
estimator's javadoc claims `-arousalVariation` (`OperantEstimator.java:16-17`). Under `KALMAN` the sample becomes the
drive reduction. Two ways, decision needed (see the end):
- (R, recommended) `V(K | H_before)` from `HomeostaticValue.defaults()`, the function episodic memory uses
  (`HomeostaticValue.java`, `V = d(H) - d(H + K)`). `HomeostaticRegulation` has `before` in hand (`L60-67`); the value is
  computed there and carried as one new field on `EvaluationStimulus` (immutable message).
- (C, cheaper) `-arousalVariation`. Equal to (R) when only the regulated drive is above its set point and the bite does not
  cross it; differs otherwise (the deficit is an L2 norm over drives, capped at the set point).

Under `WELFORD` the sample stays `+1 / -1`. The legacy `OperantConditioning` table is **not** affected either way:
`recordOperant` feeds only `operantStore`; the legacy table is reinforced separately at `Valuation.java:217` and `:249`.
The javadoc at `Valuation.java:183-189` and `OperantEstimator.java:16-17` will be corrected.

**Abstention in `Estimate` (least invasive).** Add one component, `Basis basis` = `OBSERVED | POOLED_PRIOR | ABSTAIN`;
the 3- and 4-argument constructors keep working with `OBSERVED`. `Estimate.abstain()` gives `q = NaN, se = +Inf,
sigma = NaN, ABSTAIN`; validation (`Estimate.java:34-47`) relaxes only for that basis; `withSeScaled` returns it unchanged.
Consumers and what changes:
- `Arbiter`: max-Q, tie count, draw and decisiveness skip abstaining candidates (section 2).
- `ArbitrationDecision`: `bestEstimate` is the best non-abstaining one; `runnerUpEstimate` may already be null.
- `ExecutedEstimate.java:44-68`: unchanged (copies estimates).
- `FullAppraisal.java:375` passes the executed Q to the episode buffer; NaN is already read as 0 (`EpisodeBuffer.java:317-319`).
- `ArbitrationDecisionState` writes q/se/sigma (`L105-128`); the table maps NaN to null (`L21`). Extend it to map +Inf
  to null and add a `basis` column per controller (`TableSchemas.java:386`, `scripts/dl2l_data/tables.py`).
- `OperantUpdateState` (`L10-40`): keep the columns; `sample` is in the estimator's unit; add `q_prior`, `se_prior`
  (the predictive estimate before the update) and `basis`, so calibration needs no reconstruction of the drift.
- Analysis: `analysis/experiments/arbiter_ladder.py:119-121` rebuilds z from shifted rows and a fixed prior SE; under
  `KALMAN` it reads `q_prior`/`se_prior` instead.

**Code.** New `KalmanOperantEstimator implements OperantStore` next to `OperantEstimator` (left untouched). Chosen in
`CreatureActor.java:166-169` and `TestingCreature.java:97` by the setting.

## 2. Stop rule: decisiveness = confidence of the actual choice

Today decisiveness is `Phi((Q1 - Q2) / sqrt(SE1^2 + SE2^2))` over the top two by Q (`Arbiter.java:321-341`), with no tau
or novelty, while the draw is `q + novelty + tau * se * N(0,1)` over all candidates (`Arbiter.java:262-269`).
Under `WHOLE_DRAW`, decisiveness is the probability that the candidate with the highest mean draw (`q + novelty`) wins
the actual draw, under the same distributions:
`conf = integral of N(x; mu_b, s_b) * prod_{j != b} Phi((x - mu_j) / s_j) dx`, with `s_j = tau * se_j`, computed by
fixed Gauss-Hermite quadrature (deterministic; consumes no random numbers, so the stream contract `Arbiter.java:39-42`
holds). Zero-SE cases become step functions. Epsilon is left out (it scales every controller alike).

Consequences: conf can now be below 0.5 (many close candidates); the `[0.5, 1]` note on `Consultation.conf`
(`ArbitrationDecision.java:54`) changes; theta stays 0.8. `FIXED_ORDER` does not use conf to decide, only records it.

**Abstaining candidates in the draw** (needed once the operant abstains). Recommendation: the rule already simulated as
"option 2" in `analysis/diagnostics/arbiter_options_sim.py`: all abstaining candidates enter the draw and the decisiveness
as one pseudo-candidate "something else" at Q = 0 (no drive reduction claimed) with the SE of the best non-abstaining
candidate; if it wins, one abstaining candidate is picked uniformly (one `nextInt`, new source `ABSTAIN_UNIFORM`). No
abstainer gets its own ticket. If every candidate abstains, conf = 0, so any other consulted controller decides; alone
(A2), the pick is uniform.

**Competition at the choice (decided).** This is the same fix, no new mechanism: the Thompson draw already turns a
confident high value into a larger share of choices at the expense of the alternatives on that object (probability
matching). What was wrong is that the stop rule did not measure that share; after this change it does.

## 3. APPROACH credit

`EpisodeBuffer.java:301-303` zeroes `g` and `v` for APPROACH steps of a TIMED_OUT episode. Under
`timeoutZeroesApproach = false` every step keeps its Monte Carlo return, whatever its action type. The flag reaches the
buffer through its constructor (`EpisodeBuffer.java:110`, built at `MemorySystemActor.java:141`).

## 4. Verification

**Order of work (revised 2026-11-24).** Three commits, each checked alone: (1) settings plus the APPROACH credit flag, (2) Kalman cell with `basis` and abstention, (3) `WHOLE_DRAW` stop rule.

**Theta, checked on the real p117_factorial data (2026-11-21).** Today (`TOP_TWO`) the operant reaches decisiveness 0.8 in only 7% of decisions in `a3_L0T0` (90% sit at exactly 0.5, untried ties; mean 10.3 candidates), so `WHOLE_DRAW` does not take away confidence the operant has now. What decides it is the new, smaller candidate set (trained cells plus the pseudo-candidate), which needs Kalman SEs that do not exist yet; the stand-in cannot give them (its candidate counts, 2.4, are far below the real 10.3). So theta stays 0.8, as a setting (`arbiterTheta`), and is read off the replay after step 2.

**Unit tests (deterministic only; no seeds that must be lucky).**
- `KalmanOperantEstimatorTest` (new): update equations against hand values; k converges to k* for constant input;
  P grows by tau2 on other cells of the same object only; pooled prior mean/variance; abstention and first outcome;
  APPROACH and the NONE bucket abstain with no special case; finite-value checks.
- `EstimateTest`: `abstain()` and the basis validation.
- `ArbiterTest`: `WHOLE_DRAW` conf equals a closed form for two candidates (it must reduce to today's Phi when tau = 1,
  no novelty) and matches the empirical win rate of the actual draw over a fixed seed within a tolerance stated from n;
  the pseudo-candidate rule; all-abstain gives conf 0. `TOP_TWO` tests stay as they are.
- `ArbiterActionSelectorTest`, `ExecutedEstimateTest`: abstaining estimates pass through.
- `EpisodeBufferTest`: keep `timedOutEpisodeZeroesOnlyApproachSteps` (L223) for the default; add
  `timedOutEpisodeKeepsEveryReturnWhenZeroingOff`.
- `ValuationOperantTest`: under `KALMAN` the recorded sample is the drive reduction, under `WELFORD` still `+1 / -1`.
- `ArbitrationDecisionRoundTripTest`, `OperantUpdateRoundTripTest`, `TableSchemasTest`, `ArrowExtractGoldenTest`: new
  columns, Inf and NaN to null. `LearningSettingsTest`, `LadderConfigsTest`: new keys, defaults unchanged for every
  existing config. Python: `analysis/diagnostics/test_arbiter_ladder.py` for the z change.
- `mvn test` and `mvn package` clean.

**Offline, before any cluster time.**
1. *Replay on real data.* Feasible (checked 2026-11-18 on `ml/data_p117_factorial/a3_L0T0/trial_5`). Rebuild each creature's per-outcome
   stream: `operant_updates` rows (EAT only, sample +-1) joined to `drive_outcomes` on creature, object type and action, with time within
   100 ms. The join matched 98-100% of apple rows, 375/514 for aloe and 369/369 for cactus. Gray apples never match, and that is correct:
   eating one changes no drive, so there is no `drive_outcomes` row and the drive reduction is 0 (today's valence scores it -1). Unmatched
   rows are therefore zeros, not missing. The replay builds the drive-reduction sample from `drive_outcomes` (summed reductions per row,
   i.e. option C); it cannot build `V(K | H_before)` (option R) from that table alone, because `drive_outcomes` has no H-before column.
   Run the Kalman cell over it. Report k
   realised, SE over life, and one-step-ahead z per cell kind (observed, pooled prior). Pass: mean z within +-0.1 and
   var z in [0.8, 1.25]. If not, adjust tau2 once here, then freeze it.
2. *Stand-in world* (`arbiter_options_sim.py`, which already has `mc_fix` and option 2). Add the Kalman cell and the
   `WHOLE_DRAW` rule as options. Gate: the stand-in must first reproduce A5 > A3 (fewer deaths, more food per 1k
   decisions) with today's code, untuned on A5; if it does not, no option is read. Pass for the new arm (A3 with all
   three changes): deaths no more than A5-today, and P(eat | food contact) above A3-today. Fail: no gain over A3-today,
   in which case no cluster time is spent.

**Calibration (spec 2.4)** per controller from the decision table: `z = (V_realised - Q) / sqrt(SE^2 + sigma^2)`,
mean about 0, variance about 1; operant pooled-prior cells reported separately.

## 4b. `FIXED_ORDER` (A5) under `KALMAN`

A5 consults the operant first, and episodic memory overrides it only when every candidate has `n_eff >= 3` (`Arbiter.java:218-228`,
`hasEvidence` at L304-311). `hasEvidence` reads `nEff`, which is NaN for the operant, so the operant always counts as having evidence;
that is harmless today. Under `KALMAN` the operant abstains on every APPROACH, WANDER and SLEEP candidate, so when episodic memory lacks
evidence the operant becomes the fallback and the pseudo-candidate rule (section 2) decides: a uniform pick among the abstainers. This is
intended, and only applies when `operantEstimator = KALMAN` (the default is unchanged). `hasEvidence` must skip abstaining candidates
(they need no neighbours). Test: `ArbiterTest` pins that an abstaining estimate does not make `hasEvidence` false, and that A5 with
the operant abstaining on everything falls to the uniform pick.

## 5. After this plan (out of scope)

The mini-experiment gets its own recipe at `docs/experiments/<name>_recipe.md` and spec under `experiments/`, written
before anything runs. Hypothesis in one line: with the three changes, arbitration by confidence (A3) survives at least
as well as the fixed order (A5) in the plants world, and the operant decides most contact decisions with food and eats
there (today 7.6% of its decisive decisions). It is falsified if A3 still dies more than A5, or if the operant's z-variance
is far from 1 (then its SE is not honest and the arbiter's choice of driver means nothing).

## Decisions needed from the researcher

1. Sample unit: (R) `V(K | H_before)` (recommended, exact common unit, one new message field) or (C) `-arousalVariation`.
2. Abstaining candidates in the draw: the option-2 pseudo-candidate (recommended). This also defines A2 with the Kalman
   cell, where the operant alone abstains on every APPROACH, WANDER and SLEEP candidate.
3. Confirm the three settings with today's behaviour as default.


## Results of the offline replay (2026-11-25)

`analysis/diagnostics/kalman_operant_replay.py` over the 12 `a3_L0T0` trials of p117_factorial (36,931 one-step predictions on varying cells; cells whose outcomes are all equal, e.g. gray apples at 0, left out as in the ladder analysis):

| setting | mean z | var z | band [0.8, 1.25] |
|---|---|---|---|
| tau2 = 8e-4 (plan) | +0.003 | 0.712 | below: SE too wide |
| tau2 = 3e-4 | +0.007 | 0.761 | below |
| tau2 = 1.5e-4 | +0.012 | 0.793 | just below |
| **tau2 = 1e-4 (frozen)** | +0.015 | 0.810 | **pass** |
| tau2 = 1e-5 | +0.037 | 0.876 | pass |

Two changes to the plan, both from this replay and both frozen: **tau2 = 1e-4** (not 8e-4; steady-state gain about 0.1, not 0.25: the calibration criterion picks the slower cell, and these data are stationary, so it is an upper bound on how slow the cell should be), and a **floor of 16 prior variances (0.16, SE 0.4) under the pooled prior's variance** (not 1): with the floor at one variance the first outcome on a new object scored z variance 8.0 (mean -0.68; EAT is +0.3 on an apple and -0.7 on a cactus, and the few cells seen so far understate that); at 16 it is 1.3 (mean -0.18, n = 240). The same on `a5_L0T0` (tau2 2e-4: 0.81; pooled 1.27). The per-decision candidate counts of the new stop rule cannot be measured from this data (only the best and the runner-up are stored); theta stays 0.8 as `arbiterTheta`.

## Result of the local pilot (2026-11-25)

`experiments/p117_kalman_local.yml` (4 arms x 6 trials, 20-minute cap): deaths 7 (control), 3 (+ APPROACH credit), 2 (+ Kalman operant), 2 (+ whole-draw rule) of 30 creatures; EATs per minute 17.2, 18.2, 24.6, 27.0. The Kalman cell is the change that moves feeding; the whole-draw stop rule adds nothing measurable. Details and caveats: `docs/experiments/p117_kalman_recipe.md`, section 7.

## Result of the cluster campaign (2026-11-25)

`experiments/p117_kalman.yml` (CCAD, 4 arms x 12 trials, 60-minute cap, 60 creatures per arm): deaths 20 (control), 13 (+ APPROACH credit + Kalman operant), 28 (+ whole-draw stop rule), 16 (A5 control). The Kalman cell brings A3 to the level of A5 and feeds clearly more; **the whole-draw stop rule is worse (28 against 13 deaths, p = 0.004) and should not be adopted**; the survival gain of the Kalman cell over the control is not statistically established and the control itself moved by 9 deaths against the factorial. Details: `docs/experiments/p117_kalman_recipe.md`, section 9.

