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

*(to be filled in after the run)*
