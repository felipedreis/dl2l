# Plan: arbiter-ladder pilot (A0 / A1 / A2), feeding #106 and #107

Status: **approved 2026-11-04; gate 1 FAILED G1; thresholds tuned (0.4 / 0.15); gate 2 PASSED all of G1-G5 (2026-11-06); stage 2 not started (see "Stage 1 results", "Stage 1b" and "Gate 2 results")**. Date: 2026-11-02. Base: `main` at `bd19886` (#102–#105, #110 and the #127
death close-out fix merged). Issues served: #107 (sample size from a pilot), #106 (analysis "runs end to end on a pilot's
data"); precedes #108 (A1 campaign) and #109 (A2 campaign). Story map: `docs/plans/2026-q4-research-plan.md`.

## Purpose

1. Measure, per arm, the between-trial and within-trial spread of the outcomes #107 will pre-register (mean drive
   deficit d(H), feeding, survival, focus switches, A2 decision telemetry), so #107 can size the campaigns.
2. Produce real data with all three arms on which #106's `analysis/experiments/arbiter_ladder.py` must run end to end.
3. Find out, before any campaign, whether A2 actually trains its operant table in the A0 world (the risk below).

Descriptive, not a hypothesis test. No pilot number enters a campaign result; pilot seeds are disjoint from every
earlier and every future campaign seed.

## What the existing data already says (verified 2026-11-02, Parquet under `ml/`)

| Run (data dir) | Config | Creatures alive at end | Hunger, mean / max | Episodes |
|---|---|---|---|---|
| p97 v2 (`ml/data_p97_feeding_fix_v2`, 40 creatures, pre-#99 code) | A0 world, focal off | **0 of 40**: all die, observed_s median 242 s (190–485) | rises to 7.0 (death) | n/a |
| p100 (`ml/data_p100_k_check`, current A0, 4-min cap) | `arbiter_a0.conf` | 5 of 5 at 227 s (cap) | 4.2 / 6.7 at 210 s, climbing | n/a |
| p110 (`ml/data_p110_a0_check`, 15 min) | A0 + focal + operant, FILTER_BASED (behaves as A1: the estimator is read by nobody in FILTER_BASED) | 5 of 5 | ~1.3 / 2.8, flat after 1 min | 60–120 closed per creature (mostly hunger/tedium PREEMPTED); 16% of actions have a focal drive |
| p104 (`ml/data_p104_a2_check`, 15 min) | A2 (`p104_a2_check.conf`) | 5 of 5 | **0.30 / 1.28, flat** | **none** (no `episode_returns.parquet`; `actions.focal_drive` all null) |
| p105 (`ml/data_p105_decision_check`) | A2, stress worlds | 0 of 10 (191–299 s) | n/a | many, 96–97% TIMED_OUT |

Consequences for this plan:

- **A0 creatures starve in ~4 min** (the p97 feeding collapse, `docs/reports/20261018_p97_feeding_fix_report.md`); the
  120-min cap never binds for A0. A1 and A2 creatures survived 15 min with bounded drives, so for them the cap probably
  binds and survival is censored. A0-vs-A1 survival and mean deficit over life compare a 4-minute life with a 2-hour
  one; the pilot also reports a common-window deficit (below).
- **A2 sits in a self-consistent fixed point**: with an untrained table the arbiter chooses close to uniformly among the
  pre-filtered candidates, which feeds the creature better than the A0/A1 filter chain (1,683 red/green EATs in 15 min
  vs 527 in p110), so no drive reaches the open threshold, so no episode closes, so the table never trains. Tedium stays
  at 0.19 (vs 1.3 in p110). Nothing in 15 minutes trends upward. **The gate below is expected to fail**; it is run anyway
  because 15 min is not 120, and so that the decision rests on a full-length run.
- Even when episodes exist, unfocused decisions read the `NONE` bucket of `OperantEstimator` (key = focal drive, object
  type, distance bin, action), which nothing ever trains (`OperantReturnSink` records steps under their drive). In
  p110-like dynamics 84% of decisions are unfocused, so A2 is uninformed on most cycles by design. The pilot measures it.
- Episode thresholds and arbiter parameters are hard-coded: `FocusTracker.Params.defaults()` (θ_on 2.0, θ_off 0.5,
  δ 0.3, t_max 200; urgency = level − 0.18) and `Arbiter.Params.defaults()` (θ 0.8, ε 0.03, τ 1), both constructed in
  `FullAppraisal.preStart`. No config key reaches them. Consequence for #107: tuning θ and ε needs a small Java change
  (config keys) first; λ exists only from A3 (#113).

## Assumptions

- Simulations run in wall-clock time (`maxRuntimeMinutes` is wall time; ~30 decisions per creature-second on this Mac,
  p105). Cycle counts per second depend on the machine, so pilot and campaigns should run on the same environment.
- A trial is the independent unit: the 5 creatures of a trial share a world, food supply and root seed.
- Same seed in every arm pairs the initial world only; Akka scheduling keeps trajectories nondeterministic (p97 H3).
- A1 writes `episode_returns` (the episode buffer is gated on `focalDriveEnabled`, `MemorySystemActor`/`Valuation`), and
  no `arbitration_decisions` (FILTER_BASED: zero rows, no Parquet file).

## Predictions (recorded before data, not tested)

- P1: A0 creatures all die, observed_s 3–8 min.
- P2: A1 creatures survive to the cap; hunger bounded below ~3; 10–20% of decisions focused.
- P3: A2 opens at most a handful of episodes in 120 min (max hunger ≤ ~1.5).

## Stage 1: gate (one trial per condition, local)

Spec `experiments/p107_pilot_gate.yml` (text below), seed 10700, local Docker, UI on (`inventories/local`).

| Condition | Config | Why |
|---|---|---|
| `a1` | `simulations/arbiter_a1.conf` (new) | first full-length A1 run; decides stage-2 cost and checks focus/episode telemetry |
| `a2` | `simulations/arbiter_a2.conf` (new) | the gate proper |
| `a2_finite_food` | `simulations/p104_a2_finite_food.conf` (existing) | re-verifies the #127 death close-out on a real run (A0-world creatures do not die with open episodes) |

Cost: local trials run **sequentially** (`trial_runner_local`: `include_tasks one_trial.yml` over the condition × trial
matrix, 6 h ceiling per trial), so ≈ 2 × 120 min + ~5 min + build, compose and extraction ≈ 4.5–5 h.

### Gate criteria (DuckDB over `ml/data_p107_pilot_gate/<cond>/trial_1/*.parquet`)

**G1 — A2 opens and closes episodes** (`a2/arbitration_decisions.parquet`):

```sql
SELECT creature_key, count(*) AS n,
       avg((focal_drive IS NOT NULL)::INT)              AS focused_share,
       count(DISTINCT episode_id)                        AS closed_episodes
FROM 'a2/arbitration_decisions.parquet' GROUP BY 1;
```

**G2 — the table informs focused choices** (same file): share of focused, multi-candidate decisions that are not fully
tied, in the second half of each creature's run:

```sql
WITH d AS (SELECT *, seq > max(seq) OVER (PARTITION BY creature_key) / 2 AS late
           FROM 'a2/arbitration_decisions.parquet')
SELECT creature_key, avg((n_tied_at_max < candidate_count)::INT) AS informed_share
FROM d WHERE late AND focal_drive IS NOT NULL AND source <> 'SINGLE' GROUP BY 1;
```

- **PASS**: in ≥ 4 of 5 creatures, `closed_episodes ≥ 10`, `focused_share ≥ 0.02` and `informed_share ≥ 0.5`
  (p110's A1-like run had 60–120 episodes and 16% focus in 15 min, so these are low bars).
- **FAIL**: `closed_episodes = 0` (or `a2/episode_returns.parquet` absent) in ≥ 4 of 5 creatures.
- **MARGINAL**: anything else. Treated as FAIL for proceeding; reported with the numbers.

**G3 — A1 telemetry present** (`a1/`): 5 creatures; `actions.focal_drive` non-null share > 0 for every creature;
`episode_returns.parquet` exists. Failure here is a bug, not a design question: stop and investigate.

**G4 — death close-out (#110/#127)** (`a2_finite_food/`): for every creature with `creatures.died`,

```sql
SELECT d.creature_key,
       count(*) FILTER (WHERE d.focal_drive IS NOT NULL) AS focused_decisions,
       (SELECT count(*) FROM 'a2_finite_food/episode_returns.parquet' e WHERE e.creature_key = d.creature_key) AS steps,
       (SELECT count(*) FROM 'a2_finite_food/episode_returns.parquet' e
         WHERE e.creature_key = d.creature_key AND e.close_type = 'DEATH') AS death_rows
FROM 'a2_finite_food/arbitration_decisions.parquet' d GROUP BY 1;
```

Pass: `focused_decisions = steps` for every dead creature, and `death_rows ≥ 1` wherever the creature's last decision
row has a focal drive; no join-mismatch warning in the extraction output. (In p105 this failed for 1 of 10 creatures.)

**G5 — operational**: `run_info.seed = 10700` in every condition; raw dump size and extraction time per trial recorded
(they size stage 2; ~10 MB raw per creature-minute measured on p104, so ~6 GB per 120-min 5-creature trial).

### On a FAIL or MARGINAL G1

Stop and bring the numbers (G1, G2, and per-creature max `final_hunger/sleep/pain/tedium` from `drives`). Not decided
here; the options, each changing an arm definition and therefore needing its own plan and a #107 revision:

1. Lower θ_on (config key for `FocusTracker.Params`; Java, PR). Changes A1 as well.
2. A world where drives bind for every arm (fewer red/green; the stress worlds kill in 3–5 min, so it needs
   calibration). Changes A0, which #99/#108 fix.
3. Train the `NONE` bucket from unfocused decisions too, or make A2 fall back to the A1 filters when unfocused. Changes
   what A2 is.
4. Run A2 untrained. Not viable for #109: its done-when needs operant calibration, which needs closed episodes.

Because options 1–2 change A1/A0, stage 2 does not start after a FAIL, even for A0/A1.

### Stage 1 results (run 2026-11-04, local Mac, seed 10700, data on HF `p107_pilot_gate/`)

Recipe: `docs/experiments/p107_pilot_recipe.md`. All three conditions ran and extracted with no failures (the first
attempt on the Pi cluster was abandoned: the NFS share served from the Mac wedged and that job's data never reached disk;
none of it is used). Creature observed time 7,187–7,188 s in `a1` and `a2` (all 10 alive at the cap), 196–217 s in
`a2_finite_food` (all 5 died).

| Gate | Result |
|---|---|
| **G1** A2 opens and closes episodes | **FAIL.** 5 of 5 creatures: 0 closed episodes, focused share 0.0, over 217,509-217,655 decisions each; `a2/episode_returns.parquet` absent |
| **G2** table informs focused choices | not evaluable (no focused decisions) |
| **G3** A1 telemetry | **PASS.** 5 creatures, focused share 0.175-0.221 per creature, 9,162 episodes in `episode_returns` (PREEMPTED 8,527, REGULATED 620, TIMED_OUT 27; counted as distinct `(creature_key, episode_id)`, because `episode_id` restarts for every creature. An earlier count of 1,997 was distinct `episode_id` across creatures and undercounts) |
| **G4** death close-out | **PASS.** For all 5 dead creatures focused decisions = episode steps (3,245 / 3,225 / 3,225 / 3,225 / 3,564), each with >= 1 DEATH row (29 / 9 / 9 / 9 / 123); no mismatch warning in extraction |
| **G5** operational | `run_info.seed` = 10700 in all three. Raw dump 5.8 GB (`a1`), 6.9 GB (`a2`), 0.2 GB (`a2_finite_food`) with `backup: true`; about 30 decisions per creature-second |

Drives (`drives.parquet`, all rows): A1 max hunger 3.79, sleep 3.74, pain 0.18, tedium 4.03, mean hunger 1.34. A2 max hunger
1.59, sleep 1.34, pain 0.18, tedium 0.47, mean hunger 0.30. Predictions: P2 held on survival and focus share (17.5-22.1%,
predicted 10-20%) but A1's max hunger (3.79) exceeded the predicted ~3; P3 (A2 opens "a handful" of episodes) was too optimistic:
it opened none. As in p104, with an untrained table the arbiter feeds the creature well enough that no drive reaches
theta_on, so nothing closes and nothing trains.

**Per the plan, stage 2 does not start.** The options in "On a FAIL or MARGINAL G1" each change an arm definition and need
their own plan and a #107 revision; which one is the user's decision.

### Stage 1b: focus-threshold tuning (run 2026-11-05; recipe `docs/experiments/p107_theta_tune_recipe.md`, HF `p107_theta_tune/`)

Option 1 of "On a FAIL": `focusThetaOn` / `focusThetaOff` became config keys (branch `feature/focus-theta-config`, not merged; see
`docs/plans/focus-tracker-theta-config.md`). A2, tuning seed 10710, 1 trial x 5 creatures, 30 minutes, four settings. Closed
episodes per creature / focused share per creature:

| thetaOn / thetaOff | closed episodes | focused share | creatures meeting both (>= 3 and >= 0.02) |
|---|---|---|---|
| 0.8 / 0.3 | 6, 3, 6, 0, 5 | .012 .004 .009 .000 .006 | 0 of 5 |
| 0.6 / 0.2 | 20, 9, 8, 9, 14 | .035 .012 .019 .013 .027 | 2 of 5 |
| 0.5 / 0.2 | 8, 9, 13, 12, 12 | .013 .008 .025 .018 .010 | 1 of 5 |
| 0.4 / 0.15 | 30, 19, 34, 31, 41 | .039 .017 .027 .035 .039 | **4 of 5** |

The recipe's rule was ambiguous (per creature vs. a setting average of the focused share). Read per creature, as the plan's G1 is,
only 0.4 / 0.15 qualifies; read as a setting average, 0.6 / 0.2 (mean about 0.021, three creatures below 0.02) would also. The user
chose **0.4 / 0.15** (2026-11-06). The prediction (0.5 or 0.6) was wrong; "0.8 fails" held. One seed: a tuning pick, not an estimate.
A failed first attempt (the tuning confs included `arbiter_a2.conf`, absent from the Java branch; HOCON ignores a missing include, so
no world and no creatures were created) was discarded and its data deleted.

### Gate 2 results (run 2026-11-06, thresholds 0.4 / 0.15 in A1 and A2, seed 10700; recipe `docs/experiments/p107_gate2_recipe.md`, HF `p107_pilot_gate2/`)

All three conditions ran and extracted with no failures; the criteria are those of the first gate, unchanged.

| Gate | Result |
|---|---|
| **G1** A2 opens and closes episodes | **PASS.** 5 of 5 creatures: closed episodes 149 / 160 / 148 / 137 / 165, focused share 0.048 / 0.047 / 0.042 / 0.043 / 0.051, late-half informed share 1.0 in all five (bars: >= 10, >= 0.02, >= 0.5) |
| **G2** table informs focused choices | **PASS** (the informed share above). "Informed" means not fully tied at the maximum, not that the choice is correct |
| **G3** A1 telemetry | **PASS.** 5 creatures, focused share 0.659-0.686, `episode_returns` present: 30,707 episodes (gate 1: 9,162), counted as `(creature_key, episode_id)` pairs |
| **G4** death close-out | **PASS.** 5 of 5 dead creatures: focused decisions = episode steps (5,405 / 5,295 / 6,445 / 5,450 / 5,124), DEATH rows 179 / 17 / 121 / 111 / 89 |
| **G5** operational | `run_info`: seed 10700 and focus_theta_on/off 0.4 / 0.15 in all three. Raw 5.4 GB (`a1`), 6.9 GB (`a2`), 0.2 GB (`a2_finite_food`) |

A2: all 5 alive at the cap (7,188 s), hunger max 1.55 / mean 0.30, sleep max 1.66, tedium max 0.47 (gate 1: 1.59 / 0.30, so
regulation is unchanged while the table now trains). A2 episodes by close type (creature, id pairs; 759 in all): REGULATED 585,
TIMED_OUT 108, PREEMPTED 66.

A1 at the new thresholds: focused share about 0.68 (gate 1: 0.175-0.221), mean hunger 0.98 (gate 1: 1.34), and **one of five
creatures died** at 4,946 s (max hunger and tedium 7.0; gate 1: all 5 alive, max hunger 3.79). One trial cannot separate the
threshold effect from run-to-run variation, but A1's definition has changed and #108 should treat the pre- and post-change A1 as
different arms. Episode outcomes of A1 ((creature, id) pairs): PREEMPTED 27,146, REGULATED 3,610, TIMED_OUT 29, DEATH 1; so the prediction
"most TIMED_OUT or PREEMPTED" held only for PREEMPTED (88%).

**The gate passes, so stage 2 may start**, subject to the plan's open decisions (environment, creatures per trial, `regulated_emotion`
column, backups) and to merging `feature/focus-theta-config`, so that the image used for stage 2 carries the keys and the stage-2
confs set 0.4 / 0.15 explicitly. A random-selection control (A2 with an untrained table) is still undecided.

## Stage 2: pilot (3 arms × 3 trials), only after a PASS

Spec `experiments/p107_pilot.yml`, seeds `[10701, 10702, 10703]`, the same three in every arm (paired by initial
world). World, creature count and cap unchanged from A0: 5 creatures, 100/100/300 mixed world, 120 min. #107 says
"3 trials × 3 creatures"; 5 per trial keeps the pilot's world identical to the campaign's (creature count changes food
competition), so the variance transfers. Recorded as a deviation from the issue text. Arbiter and tracker parameters at
their defaults; no tuning.

Cost: 3 A0 trials of ~5–8 min, 6 A1/A2 trials of 120 min, ~36 GB raw if backups are kept.

| Environment | Wall clock | Notes |
|---|---|---|
| Local Mac | ≈ 13–14 h (sequential) | UI on; simplest; 381 GB free |
| Pi cluster | ≈ 4.5 h (one trial per worker, `--exclusive`, 3 workers; conditions one after another) | slower CPUs: cycle rate per second unmeasured for this build; shared-FS volume |
| CCAD | ≈ 2.5 h + submit/rescue (all 9 trials in parallel; `ccad_sim_time` 3 h fits 120 min) | VPN; `image: {source: registry}`; pin `-e dl2l_image=ghcr.io/felipedreis/dl2l:sha-<short>` |

Recommendation: **run stage 2 where the campaigns will run.** A campaign of ≥ 10 trials per arm at 120 min is 20+ h per
arm sequentially on the Mac, so the campaigns probably go to CCAD, and so should stage 2 (cycle rate, hence per-second
outcomes, depends on the machine).

## Measurements and their sources

All per creature, aggregated to trial means (the unit). "Row rank" = 1-based position of a row in the creature's
time-ordered `actions` (one row per decision cycle; `actions` has no cycle column, #105 Q7; `make_tick_rank_attacher`
in `dl2l_analysis.loading` already builds it).

| Measure | Arms | Source | Status |
|---|---|---|---|
| Mean deficit d(H) over life (primary) | all | `drives.final_hunger, final_sleep, final_pain, final_tedium`: d = √Σ max(0, h − 0.18)² per row (`HomeostaticValue.defaults()`), mean per creature | available |
| d(H) over a common window (first 180 s of life) | all | same, rows with `time − born_time ≤ 180 000` | available; needed because A0 lives ~4 min |
| Feeding rate | all | `mouth_interactions` (`interaction_type = 'EAT'`), per minute of `creatures.observed_s` and per 100 row ranks; RED/GREEN separately from GRAY | available |
| Feeding across life, D (deciles with ≥ 1 EAT) | all | `per_creature` in `analysis/experiments/p97_feeding_fix.py` | available |
| Survival | all | `creatures.observed_s`, `creatures.died` (Kaplan–Meier; `dl2l_analysis.stats.kaplan_meier`, censored at cap) | available |
| Focus switches per 100 cycles | A1, A2 | `actions.focal_drive` by row rank: changes between non-null drives, and opens (null → drive), counted separately | available for the focal drive |
| Same, comparable for A0 | A0 (and unfocused A1/A2 cycles) | the emotion the filters regulate when unfocused (`emotional.getMaxEmotion()`) is **not persisted**. Approximation: argmax over `drives.final_*` per row, aligned by time | **gap** (see below) |
| Episodes: count, length, close-type mix | A1, A2 | `episode_returns` (`episode_id`, `close_type`, `steps_to_close`, `drive`) | available |
| Focused share, uninformed share, source mix, gate overrides | A2 | `arbitration_decisions` (`focal_drive`, `n_tied_at_max` vs `candidate_count`, `source`, `executed_source`) | available |
| Operant calibration | A2 | `arbitration_decisions` (`realised_value`, `executed_q/se/sigma`, `close_type`, `bootstrap`) → z per step | **definition missing**: z and the [0.8, 1.25] statistic belong to #115 (open). Pilot reports p105's descriptive z (mean, variance, RMS by close type) only |
| Controller share over life | A2 | `arbitration_decisions.deciding_controller` | trivially 100% OPERANT in A2; meaningful from A3 |

**Gap: A0's focus switches.** #108's A1 criterion compares A1's focus switches with A0's, but A0 persists no per-cycle
regulated emotion (`actions.focal_drive` is null in A0 and on unfocused cycles), and `drives` rows align with decisions
only by time (row counts differ by ~0.5–1%). Exact measurement needs one STRING column, e.g. `regulated_emotion` in
`chosen_action_state` (the value `FullAppraisal` returns as the cycle's emotion), plus the `actions` SQL. That is a Java
change (PR, ~half a day with the schema/golden tests). Recommendation: do it **before stage 2** so the pilot measures the
statistic #108 will test; otherwise the pilot uses the `drives` argmax approximation and says so.

## Sample-size computation for #107

Outcome per trial = mean over its creatures; contrasts paired by seed: A1 − A0 (focus switches, d(H) common window),
A2 − A1 (d(H) over life, non-inferiority).

1. Paired differences D_s over the 3 seeds; s_D with 2 df. Because 3 pairs give a very loose SD, size on the one-sided
   80% upper bound s_D · √(2 / χ²₀.₂₀,₂) = 2.12 · s_D (also report the point estimate).
2. Superiority (A1 vs A0): n = ((z₀.₉₇₅ + z₀.₈₀) σ_D / Δ)², then +1–2 for the t correction.
   Non-inferiority (A2 vs A1, margin M): n = ((z₀.₉₅ + z₀.₈₀) σ_D / M)².
3. Δ and M are #107's decision. Proposed default for the table: 0.06 on d(H) (draft §7.1, quoted in the Q4 plan), plus
   10% and 20% of the A1 pilot mean.
4. Cross-check at creature level: `icc1` over trials and `required_n(d, icc, cluster_size=5)` from
   `dl2l_analysis.stats`, to show what adding creatures per trial instead of trials would buy.
5. Survival is not sized from the pilot if A1/A2 are fully censored at the cap (expected); #107 then states survival as
   a guard ("not lower"), not a powered outcome.

## Files (text only; written after approval)

`simulations/arbiter_a1.conf` (campaign-reusable name, alongside `arbiter_a0.conf`):

```hocon
# A1 = A0 + the focus tracker (issue #108; docs/plans/arbiter-ladder-pilot.md). No seed here (#95).
include "arbiter_a0.conf"
simulation.learningSettings.focalDriveEnabled = true
```

`simulations/arbiter_a2.conf`:

```hocon
# A2 = A1 + the arbiter over the operant table (issue #109). Same as p104_a2_check.conf without the 15-min cap.
include "arbiter_a1.conf"
simulation.learningSettings.operantEstimatorEnabled = true
simulation.learningSettings.actionSelector = ARBITER
simulation.learningSettings.enabledFilters = [TARGET_DISTANCE]
```

`experiments/p107_pilot_gate.yml`:

```yaml
# Arbiter-ladder pilot, stage 1 (gate): docs/plans/arbiter-ladder-pilot.md. Descriptive, not a hypothesis test.
name: p107_pilot_gate
trials: 1
seeds: [10700]
conditions:
- key: a1
  simulation: simulations/arbiter_a1.conf
  label: A1 focus tracker (gate)
  color: '#3b7dd8'
- key: a2
  simulation: simulations/arbiter_a2.conf
  label: A2 arbiter over the operant table (gate)
  color: '#d8733b'
- key: a2_finite_food
  simulation: simulations/p104_a2_finite_food.conf
  label: A2, finite food (death close-out re-check)
  color: '#7a3bd8'
image:
  source: build
data_dir: ml/data_p107_pilot_gate
extract: {format: parquet, backup: true, tables: all}
upload: {enabled: true, prefix: p107_pilot_gate}
```

`experiments/p107_pilot.yml`:

```yaml
# Arbiter-ladder pilot, stage 2: docs/plans/arbiter-ladder-pilot.md. Seeds paired across arms, disjoint from campaigns.
name: p107_pilot
trials: 3
seeds: [10701, 10702, 10703]
conditions:
- {key: a0, simulation: simulations/arbiter_a0.conf, label: A0 baseline,        color: '#333333'}
- {key: a1, simulation: simulations/arbiter_a1.conf, label: A1 focus tracker,   color: '#3b7dd8'}
- {key: a2, simulation: simulations/arbiter_a2.conf, label: A2 operant arbiter, color: '#d8733b'}
image:
  source: registry          # if CCAD/Pi; `build` if local
data_dir: ml/data_p107_pilot
extract: {format: parquet, backup: true, tables: all}
upload: {enabled: true, prefix: p107_pilot}
# analysis: {module: arbiter_ladder}   # added once #106 exists
```

Seeds: used so far are 104, 110, 12345, 9701–9708. Pilot uses 10700–10703. Suggested reservation for #107 (its
decision): 10710–10799 for tuning held-out seeds, 10800+ for campaigns.

## Order of work

1. User approves this plan and answers the decisions below.
2. Pilot recipe `docs/experiments/p107_pilot_recipe.md` (the pre-registration rule applies to pilots too): this plan's
   gate criteria, predictions and measurements in the recipe format. Direct to main.
3. `arbiter_a1.conf`, `arbiter_a2.conf`, `p107_pilot_gate.yml`; `python3 scripts/validate_experiment.py` on the spec.
   Direct to main.
4. Run the gate locally; evaluate G1–G5 with the queries above; results into this plan, the research diary
   (`docs/research-diary.md`), HF upload verified by file count. Direct to main. **Stop on FAIL/MARGINAL.**
5. Start #106 on the gate data (+ `ml/data_p105_decision_check` for the A2 telemetry paths) in parallel with step 6; own
   short plan per the dev cycle; Python only, direct to main.
6. If approved: the `regulated_emotion` column (branch + PR: Java, schema, extraction, tests; `graphify update .`).
7. Stage 2 (`p107_pilot.yml`) on the chosen environment, image pinned to the post-step-6 `sha-` tag.
8. Run #106 end to end on stage-2 data (its done-when); sample-size table for #107; report
   `docs/reports/<date>_p107_pilot_report.md` (Purpose / Assumptions / Hypothesis / Results / Analysis), diary entry.
   Direct to main.

## Risks

- **A2 untrained** (above). Most likely outcome; handled by the gate.
- **A2 looks good for the wrong reason**: if it trains only on focused cycles, "regulation not worse than A1" can hold
  because near-uniform choice beats the collapsing filter chain, not because of the arbiter. The uninformed share makes
  this visible; #107 may want a random-selection control (the Q4 plan lists "random" among comparisons).
- **A0 is not a valid baseline as specified in #108** ("feeding sustained across life"): p97 shows it is not, and the
  pilot will show it again. That is #108's question, but the user should expect it.
- 3 pairs give a wide SD interval; sizing on the upper bound may ask for many trials, or a second pilot batch.
- Volume: ~6 GB raw per long trial (≈ 12 GB gate, ≈ 36 GB stage 2) with `backup: true`; HF upload slow and has hung
  before (verify by listing). `DL2L_ARROW_COMPRESSION` exists in `ArrowIpcBackend` but is not passed through ansible.
- Machine dependence of cycle rate if gate (Mac) and stage 2 (CCAD) differ; the gate is a qualitative check, so only
  stage 2 feeds #107.

## Decisions for the user

1. Approve the two-stage design, including `a1` and the finite-food re-check in the gate (+2 h locally).
2. Where stage 2 runs: local (~13–14 h), Pi (~4.5 h, untested rate), or CCAD (~2.5 h, recommended if campaigns go
   there).
3. 5 creatures per trial (recommended) vs #107's 3.
4. Add `regulated_emotion` to `chosen_action_state` before stage 2 (recommended), or accept the `drives` approximation.
5. Keep raw backups (`backup: true`, ~48 GB to HF in total) or Parquet only.
6. If G1 fails: which of options 1–3 to plan next (decided after seeing the gate data).
