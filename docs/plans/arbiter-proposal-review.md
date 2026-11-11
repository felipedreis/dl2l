# Review: operant conditioning in the arbitration proposal

Status: **draft for discussion, 2026-11-10.** Scope: the uncertainty-arbitration proposal (`docs/bib/uncertainty_arbitration.pdf`, draft of 2026-10-16),
its expected outcomes for A2, the implementation of the operant estimator (#102, #103, #110) and the experiments built on it (#107, #109).
Triggered by the p107 pilot (`docs/reports/20261110_p107_pilot_report.md`).

## 1. The problem

The proposal turns the operant table into a second episodic learner. Two places do it:

- **Section 4.4** keys the operant cells by (focal drive, object type, distance bin). Its own comparison table lists "keyed by object type only" as a
  limitation of the legacy table that the proposal fixes.
- **Appendix A, step 5** updates the cells "from closed episodes", with the episode return `V(G_t | H_t)` as the value (implemented as `OperantReturnSink`,
  #110).

Operant conditioning is a rapid, basic learning mechanism on small, immediate rewards. Learning from a whole episode is what episodic memory is for (section 4.2,
MFEC and NEC, Mapa's complete experiences). Mixing the two makes the operant controller depend on the focus tracker and on episode boundaries, and in the pilot it
barely learned:

- In A2 (pilot trial 1), 97% of the multi-candidate decisions (845,226 of 868,495) were unfocused and read cells with Q = 0 and SE = 1: decisiveness 0.5, all
  candidates tied, a uniform random pick.
- In the 23,269 focused ones, mean decisiveness was 0.59; 6.2% reached theta = 0.8; the gap between the two best Q was 0.013 against an SE of about 0.063.
- An A2 whose table never trained (`focusThetaOn = 99`) was indistinguishable from the trained one (d(H) over life 0.160 against 0.163, per seed +0.002 to +0.006).
- With the default thresholds the table never trained at all (gate 1), which is why the focus thresholds were tuned down to 0.4 / 0.15.

The pilot therefore says nothing about whether a working operant controller helps. It says this one learns too little to matter.

## 2. Principles (decided)

1. **Operant conditioning keeps its meaning and its training.** It is a basic learner: the value of an action on an object type, nudged after each outcome. It is
   **not conditioned on the drives**: no focal drive, no internal state in the key.
2. **What changes is only the output.** The table reports `(Q, SE, sigma)` per candidate, so the arbiter can weigh it against the other controllers.
3. **Episode returns belong to episodic memory only** (A3): the episode buffer, the four closing types and `V(G_t | H_t)` stay, and feed the engrams, not the operant table.

## 3. Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Key of an operant cell | **(object type, action).** No distance bin (decided), no focal drive (principle 1). Actions without an object (WANDER, SLEEP, OBSERVE on the creature itself) share one `self` object type |
| D2 | Which outcomes reinforce it | **The same as the legacy `OperantConditioning` table** (decided). Listed below. **Confirmed 2026-11-10: the `self` outcomes stay out** (no `self` cell, as in the legacy table) |
| D3 | The value of one sample | **Decided 2026-11-10, revised the same day: (a), the valence**, +1 if the drive went down and -1 otherwise, including no change, as the legacy table (`valence = arousalVariation < 0`) and Mapa (2009: `BBCSValuation`, valence false when arousal did not fall; `ProbabilityMemory.varyProbability(target, action, 2, valence)`, fixed step). Q is then 2p - 1. Mapa puts the size (`reward - expectedReward`) into the memory experience only, never into the operant table. The first choice, (b) the magnitude `-arousalVariation`, was dropped after the scarce-world run of `p107_operant`: a gray apple (caloric value 0) gave a sample of 0, equal to the prior of untried actions, so the table could not learn to avoid it (gray share of EATs 93% with and without learning, decisive in under 0.2% of decisions). |
| D4 | Prior and fading | Keep the proposal's Welford form: `Q = (n v + kappa Q0) / (n + kappa)`, `Q0 = 0`, `kappa = 1`, prior sigma 1 until n >= 3, count fading 0.995 per update |
| D5 | Legacy table | Stays for the parity arms (A0, A1) unchanged |

**D2 in detail.** The legacy table is reinforced in `Valuation` (`evaluateLegacy`, or `evaluateWithExpectancy` when expectancy is on) by every `EvaluationStimulus`
that `HomeostaticRegulation` emits, and it keeps only those whose target is a fruit or plant type (`OperantConditioningActor` holds no cell for the creature itself):

| Outcome (`HomeostaticRegulation`) | Action reinforced | Kept by the legacy table |
|---|---|---|
| Nutritive (food eaten) | EAT on the object type | yes |
| Nociceptive (pain caused by an action) | that action on the object type | yes |
| Analgesic (pain relieved by an action) | that action on the object type | yes |
| Cholinergic (sleep recovered) | SLEEP on the creature | **no** (no `self` cell) |
| Tedium (wandering or observing) | WANDER / OBSERVE on the creature | **no** |
| Deprivation RPE (expectancy only) | WANDER on the creature | **no** |

Taking D2 literally, the new table learns only from outcomes on objects. In the current world that is in practice EAT (all 40,638 legacy updates in the p107 gate-1
dump were EAT). SLEEP, WANDER and OBSERVE stay at the prior. Confirmed: the `self` outcomes are left out.

## 4. What this means for the arbiter (to state in the proposal)

- The operant controller only has evidence for actions that produce an immediate outcome. At contact it can be decisive (EAT on a red apple against EAT on a gray one).
  At a distance every candidate (APPROACH, AVOID, OBSERVE, WANDER) is at the prior, decisiveness is 0.5 and the decision passes to the next controller. That is the
  intended division of labour: habit settles consummatory choices fast; valuing the steps that lead to an outcome is the episodic controller's job (A3).
- In A2, where the operant table is the only controller, the arbiter falls back to its Thompson draw whenever the table has no evidence, as it does now. A2 can be
  expected to change *which* consummatory actions are taken, not how well the creature forages.

## 5. Proposed edits to the paper

| Section | Now | Proposed |
|---|---|---|
| 3.1 | habit "responds fast but adapts slowly" | Keep. Add that in dl2l the habit is the legacy operant table: per object type and action, updated after each outcome |
| 4.2, "How an episode closes, and what each step learns" | the return G is written for every step | Say that this is what episodic memory learns. The operant table does not use episodes |
| 4.4, Operant table (habit) | cells keyed by (focal drive, object type, distance bin), values V experienced | Cells keyed by (object type, action), updated after each outcome that reinforced the legacy table, with the outcome's valence (+1 / -1, D3) as the sample; Welford, prior and fading unchanged |
| Figure 1 | "Welford cells over V" | "Welford cells per (object, action), updated per outcome" |
| Comparison table (Campos / Mapa / this proposal) | | Add a row "Operant table": Campos and Mapa nudge action probabilities per object type after each outcome; this proposal keeps that, with (Q, SE, sigma) as output |
| Open questions | "How fine should the operant cells be?" | Answered: as coarse as the legacy table, by design. Replace with "Does a per-outcome habit settle consummatory choices without hurting foraging?" |
| Appendix A, step 5 | "updated from closed episodes" | "updated after each outcome, from the same events as the legacy table. The legacy `OperantConditioning` remains for the parity arms" |
| Appendix B (risks) | | Add: the operant controller has no evidence for approach steps, so A2 alone cannot improve foraging; that is A3's role |
| Appendix C | habit prior E(pi) from the operant table | Keep; the per-outcome table is closer to a habit prior than the episode-trained one |
| Calibration (4.4) | z computed when the episode closes | For the operant controller, z is computed at each outcome, against the estimate of the action that produced it |

## 6. Expected outcomes for A2 (to restate in #107 and #109)

What A2 (per-outcome operant + arbiter) is compared with: A1, and A2 with an untrained table (the control, now meaningful).

- **Should change:** the share of EATs on objects with no food value (gray apples) falls once the table has evidence; at contact the table is decisive (decisiveness
  >= theta in most contact decisions with evidence); calibration z of the operant controller has mean about 0 and variance about 1.
- **Should not change much, in this world:** d(H) and survival against the untrained control. The pilot showed that the untrained arbiter already regulates near the
  floor (d(H) 0.16), so A2 versus A2u on d(H) is a non-inferiority question, not a superiority one.
- **Needs a harder world to show anything on regulation:** a world where drives bind and a random forager fails (the p104 / p105 scarce and stress worlds), run as A2
  versus A2u there as well.

Adoption criteria and margins belong to the #107 recipe.

## 7. Implementation (outline; its own plan per the development cycle)

1. `OperantEstimator`: key `(objectType, actionType)`; drop the focal-drive and distance-bin dimensions. `estimate(candidates, h, focalDrive)` ignores `h` and
   `focalDrive`, so the arbiter interface stays.
2. Feed it from `Valuation`'s per-outcome path, next to the legacy `varyProbability` call, with the same filter (object targets only, D2) and the sample of D3.
3. Remove `OperantReturnSink` from the operant table. The episode buffer stays and will feed the engrams of #111 / #113.
4. A2 no longer needs the focus tracker to learn. Keep the tracker for A1 and for episodes (A3); revisit the tuned thresholds 0.4 / 0.15, whose only reason was to
   make the old operant table train.
5. Telemetry: the decision table's `realised_value` is joined from closed episodes; for the operant controller it should come from the outcome of the action. Add an
   operant-update table (cell, sample, n, Q, SE) so learning can be seen directly.
6. Tests: a cell learns from a nutritive outcome; a `self` outcome is ignored; the key has no drive; deterministic Welford updates.

## 8. Experiments: what stands and what has to be redone

| Item | Status |
|---|---|
| A0, A1 pilot results, sizing method, `arbiter_ladder.py`, CCAD tooling | **Stand** |
| Affordance-filter fix (#130) | Stands, unrelated |
| A2 and A2u pilot data | Describe the episode-trained operant table. Keep, labelled superseded; not inputs to #109 |
| Focus-threshold tuning (0.4 / 0.15) | Its purpose (make the old table train) is gone. Re-decide for A1 under the equal tuning budget of #107 |
| Gate 1 / gate 2 criteria for A2 (episodes must open) | Obsolete for A2. A new A2 gate: the table has evidence for consummatory actions within the run, and z is computed |
| #109 (A2 campaign) | Restate hypotheses (section 6) and re-pilot A2 / A2u (3 seeds, as before) once implemented |
| #107 recipe | Write with the new A2 definition; A2's sample size from the new pilot |
| #110 (episode close-out) | Its operant sink is removed; the rest stays for A3 |
| #115 (calibration) | Per outcome for the operant controller, per episode for the episodic one |

## 9. Order of work

1. ~~You confirm D3 (value of a sample) and that `self` outcomes stay out (D2).~~ Done 2026-11-10; implementation plan in `docs/plans/operant-per-outcome.md`.
2. Paper edits of section 5 (in the paper source; the PDF here cannot be edited).
3. Implementation plan and code (section 7), with tests.
4. Re-pilot A2 / A2u on CCAD (3 seeds), in this world and in one harder world.
5. #107 recipe with the restated A2 hypotheses and the new sample sizes; then #108 / #109.

## 10. Outcome and settled configuration (2026-11-11)

Experiments `p107_operant` and `p107_tendency` (results in their recipes; diagnosis `analysis/diagnostics/p107_exposure_diagnosis.py`):
the per-outcome operant table learns the right values (gray EAT about -0.95, food about +0.85) and at food eats at least as readily as a frozen
table, but learners spend half as long at food and two to three times as long next to gray apples. The table has no value for APPROACH
(approaching has no immediate outcome, D2), so the creature approaches gray apples as readily as food, refuses them, and moves on to the next
gray apple; a frozen table eats them, which clears them away. Mapa's tendency lists or no filter did not help: SLEEP and the 10-cycle sleep lock
made every arm worse.

**Settled:** A0's classic configuration (CAMPOS tendency lists) for every arm; the operant controller as implemented (per-outcome valence,
(object, action) key). A2 is not tuned further. Its limitation (a habit cannot learn "don't go there") is reported as a finding and is the
contrast A3 (episodic credit to APPROACH from what follows) is expected to address. The pre-registration is `docs/experiments/arbiter_ladder_recipe.md`.

