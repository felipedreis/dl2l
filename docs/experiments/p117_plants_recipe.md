# Experiment recipe — A2, A3, A5 in a harder world with plants, sleep hysteresis and action tendency off

Plans: [`sleep-hysteresis-switch.md`](../plans/sleep-hysteresis-switch.md) · Pilot it follows: [`p117_pilot_recipe.md`](p117_pilot_recipe.md) (section 7) · Ladder: [`arbiter_ladder_recipe.md`](arbiter_ladder_recipe.md)
Spec: `experiments/p117_plants.yml` · Data: `ml/data_p117_plants`, HF `p117_plants/` · Code: `feature/a3-episodic` (image from `preview/a3-episodic`, pinned by `sha-` tag).
Written before the run. A changed step is changed here first, dated.

## 1. Question

The pilot world is too easy: hunger drift is 2.70 per creature-minute in every arm, A1-A5 feed at or above that and 30 of 30 creatures survive, so the contrasts the ladder asks for
(A3 vs A2, A3 vs A5) have a ceiling. Does a world where the pain drive and its objects matter (CACTUS hurts, ALOE heals) separate A2, A3 and A5, and does the episodic controller get used
when arousal is higher? Also: two hand-built priors under the arbiter are off in these arms: the sleep hysteresis gate (an unconditional 10-cycle lock) and the innate action tendency lists (hunger keeps only EAT / APPROACH / WANDER), so the controllers choose among every affordable action.

## 2. Design

Three arms, 3 trials each, seeds 10701-10703 (the pilot's), 5 creatures, CCAD, 120 minutes, **all in one window** (this also gives the same-window A2 the pilot lacked):
- `a2`, `a3`, `a5`: the same configs as the pilot's arms (`p107_gate2_a2.conf`, `arbiter_a3.conf`, `arbiter_a5.conf`: CAMPOS tendency lists, thresholds 0.4 / 0.15, theta 0.8 / eps 0.03 / tau 1)
  with `p117_plants_overrides.conf` on top: the pilot world plus **140 CACTUS and 140 ALOE** (the memory-vs-WM campaign's density of 50 + 100 on 1200 x 900, scaled to this map's area, rounded), and
  `sleepHysteresisEnabled = false` and `actionTendencyEnabled = false`. A unit test pins that the arms differ from the pilot's only in these three things (the world, the lock, the tendency lists).

Deliberate limits: no control with the priors on, so the effects of the two switches are **not isolated** from the effect of the world nor from each other (chosen by the researcher: all arms with both off); the plants'
density is a first guess, not calibrated: the difficulty is whatever it turns out to be, and section 4 says how that is read.

## 3. Readings (fixed now; per seed, no p-values at 3 pairs)

- **G0 (machinery):** all trials complete; creatures contact plants (`mouth_interactions` / `chosen_object_type` with CACTUS and ALOE); pain is a focal drive in some episodes (`focal_drive`).
- **G1 (is the world informative?):** deaths per arm, and d(H) over life. Informative if neither "all alive with d(H) at the floor" nor "all dead in minutes". Outside that range the contrasts below are reported but not read.
- **C1 (what episodic memory adds):** a3 - a2 on d(H) over life, survival, EATs per minute, pain deficit (mean of the pain drive's deviation above the set point), per seed, same window.
- **C2 (the main contrast):** a3 - a5 on the same measures.
- **B (budget):** share of decisions where the episodic controller was affordable (e >= 0.3) and decided, against 7.1% affordable / 4.5-6.7% deciding in the pilot world.
- **Cal:** operant statistic per trial (recipe 5) and episodic z (mean, variance).
- **T (tendency off):** action mix (the arbiter now sees AVOID, SLEEP, OBSERVE, ESCAPE as candidates), candidates per decision, share of life in SLEEP and in APPROACH.
- **H (hysteresis off):** executed action differs from the chosen one in far fewer decisions than the pilot's 4.1% (the gate was the main source of that); share of life spent in SLEEP.

## 4. Predictions (before data)

**Risk to state first:** the earlier tendency experiment (`p107_tendency`, no filter, pilot world) starved every learner (30 of 30 within 3-10 minutes against 3 and 9 of 15 frozen), and an untrained arbiter choosing uniformly among all five candidates at a seen object leaves EAT only at contact. With the tendency lists off and a harder world, **deaths are likely**, in A2 as much as in A3 and A5; G1 reads that honestly. The run is descriptive: it asks whether the episodic controller helps when nothing hand-built is steering the choice.

G0 holds. G1: unknown, which is the point; cactus contact costs pain and ALOE heals, so pain episodes should open (the pilot's 12% focused share rises). If every creature still survives with d(H) near the floor, the
dial needs another turn (more plants, less food) and this run is a descriptive baseline. C1 and C2: no prediction on direction; the pilot's a3 = a5 holds if the budget still blocks the episodic controller (B), and
the question is whether pain arousal lifts efficiency above 0.3. H: the executed-differs share falls to about 0.

## 5. Procedure

As the pilot: pin the image (`sha-` tag of the commit), submit with the playbook, progress with `ansible/ccad-status.yml`, collect with `-e rescue=true`, upload by hand (`dl2l_data.upload`), analyse with python3.13.

## 6. Results

Run 2026-11-12 on CCAD, image `ghcr.io/felipedreis/dl2l:sha-c72e6d3`, SLURM jobs 2955 (a2), 2958 (a3), 2961 (a5), all 9 trials `COMPLETED` exit 0, seeds 10701-10703 (`run_info`), one window, 5 creatures per trial. Data `ml/data_p117_plants`, HF `p117_plants/` (87 files for a3 and a5). Report `docs/reports/p117_plants_report.md`. n = 3 trials per arm: descriptive, no p-value is a hypothesis test.

| Reading | Result |
|---|---|
| **G0 (machinery)** | Holds. Plants are contacted (about 35-36% of chosen objects are CACTUS or ALOE in every arm) and pain-driven episodes occur. Episodic engrams in a3 and a5; both switches really are off: **executed action differs from the chosen one in 0.0000 of decisions** in all arms (4.1% with the lock on). |
| **G1 (is the world informative?)** | **Yes, strongly.** Deaths: a2 15/15 (all by 433 s, mean 282 s), a3 13/15 (mean 2984 s), a5 7/15 (mean 4903 s; KM median beyond the cap). d(H) over life 6.76 / 2.58 / 1.68. This is the range the pilot world lacked. |
| **C1 (a3 vs a2)** | A3 is better in every seed: observed time +3051, +2352, +2703 s; d(H) over life -5.01, -5.21, -2.34; EATs per minute +11.3, +11.6, +7.9. Survival curves differ (KM median 281 s vs 1543 s, log-rank p < 0.0001); the share of creatures that died does not (15/15 vs 13/15, Fisher p = 0.48). |
| **C2 (a3 vs a5), the main contrast** | **Opposite to the paper's hypothesis: the fixed order does better than arbitration.** Deaths 13/15 vs 7/15 (Fisher p = 0.050), KM median 1543 s vs beyond the cap (log-rank p = 0.029); observed time a3 - a5 -2415, -2583, -757 s (every seed); deaths share +0.4, +0.6, +0.2. d(H) over life is mixed: +0.45, -0.18, +2.42 (a5 lower on average, 1.68 vs 2.58; trial 3 of a3 is the outlier). |
| **B (budget)** | The controller is used here: efficiency mean 0.44 (a3) / 0.41 (a5), affordable in 70% / 63% of decisions, **episodic decides 62% / 63%** of decisions (pilot world: 7% affordable, 4.5-6.7% deciding), and its share rises over life (a3 by decile 60% to 72%). The pilot's idle controller was a property of the calm world, not of the budget as such. |
| **Cal** | Operant statistic per trial (a3 1.085, 1.059, 1.245; a5 1.211, 0.861, 1.035) inside [0.8, 1.25]; a2 has too few updates to be read (n/a, 0.106). Episodic z: mean +0.02 / +0.07, variance 0.48 (a3) / 0.27 (a5): conservative error bars, more so than in the pilot world. |
| **H (lock off)** | The override disappears (0.0000 of decisions). Share of actions that are SLEEP stays high (22% a2, 24-25% a3 / a5) with the lock off and the tendency off. |
| **T (tendency off)** | Without the lists the action mix is close to uniform over the five candidates and EAT is 0.6% (a2) to 1.3-1.4% (a3, a5): WANDER 22-30%, SLEEP 22-25%, AVOID 16-20%, APPROACH 14-17%, OBSERVE 13-17%. The episodic arms eat about twice as often as a2 (EATs per minute 17.2 / 19.5 vs 6.9) though they still spend about a quarter of cycles asleep. |

**Caveats.** (1) All arms have both priors off, as chosen: the effects of the two switches are not separated from the world's, so nothing here says what turning the lock or the tendency off does by itself (the pilot's arms were not re-run in this world). (2) n = 3 trials; per-seed signs are given, but a3's trial 3 is an outlier on d(H). (3) The pre-registered early-life food share is not comparable across arms here: a2 creatures live about 5 minutes, so "first 20% of life" is under a minute against 10 to 20 minutes for a3 and a5 (a3 - a2 -0.19, a3 - a5 -0.05). (4) The gray-vs-food APPROACH mechanism (R3 of the pilot) was not re-measured. (5) A3 and A5 differ only in the deciding rule, yet the same share of decisions (62% vs 63%) goes to the episodic controller: which decisions it takes, not how many, separates them, and this run does not show which. (6) The recipe's own sizing rule (16 trials) is not tested by this run.

**Consequences.** The harder world gives the ladder what the pilot world could not: a ladder of survival A2 < A3 < A5, with the fixed order on top. That contradicts the paper's main contrast (arbitration should beat the fixed order) and should be reported as such, and tested before the campaign with the lock and the tendency on and off separately, in this world, at the campaign's sample size. The pre-registered A3 criterion (early food share) is the wrong measure for a world where lifetimes differ by an order of magnitude; survival and d(H) are the primary outcomes there.
