# DL2L research diary

Covers 2026-06-22 → 2026-10-18. Compiled 2026-10-17 (updated 2026-10-18) from git (including the unmerged
`claude/issue-91-*` branch), GitHub issues/PRs, `docs/plans`, `docs/reports`, the CCAD
postmortem, and working files since moved to the alifeworld repo. Numbers are copied from the cited report or commit;
nothing is re-measured here.

Earlier history in one line: imported from Bitbucket (2022-02), Docker image (2022-10),
Java 21 + collision quadtree (2025-01), WebSocket geometry API (2025-06).

## The thread

1. **Jun 22–24.** We built the JEPA world model into the creature in six phases. It made creatures worse.
2. **Jun 26–Jul 2.** The regressions came from our own pipeline: adapter init, and a dataset
   that dropped every WANDER sample. After fixing those, JEPA beat a Mapa-style memory filter on the Pi.
3. **Jul 2–10.** The creature's biology was not faithful to Campos/Mapa. We added emotion
   scoping, neuromodulators, orexin/HPA and attention, then retrained JEPA for the new state.
4. **Jul 9–29.** Five memory-vs-JEPA experiments gave conflicting answers depending on world
   density.
5. **Jul 27–Aug 6.** Those runs turned out to be confounded by infrastructure: blocking Postgres
   writes, OOMs, a metabolic clock tied to cycle count, and a cycle rate 9× nominal. We
   rebuilt persistence (Arrow IPC), the clock and the cognitive tick.
6. **Aug 4–12.** The behaviour-parity campaign against Mapa/Campos (p84) showed that our memory was
   harmful. We reworked it, and memory now raises feeding rate. Three memory bugs are still open.
7. **Aug 15–Oct 16.** Unmerged #91 follow-up; recovered and ran the original ALifeWorld source;
   planned a shared experiment toolkit.
8. **Oct 17–18.** Arbiter prerequisites (#97). We fixed two real bugs (arc units, WANDER heading) and
   seeded every run, but the "creatures stop eating at speed" premise of #96 was wrong: the collapse
   is a property of the world with unrewarding gray apples.

---

## Log

### 06-22 → 06-24 · JEPA integration, Phases 0–6 (epics #3–#8, PRs #31–#38)
- **Why:** give creatures a learned world model (species model + per-creature adapter) for
  deliberative action selection. Design: `docs/hld/JEPA_WM_Integration.md`, `docs/roadmap/JEPA_Integration.md`.
- **P0 coverage:** the reactive policy covers distance and angle well enough, so no random-exploration
  episodes are needed. The probe also showed **memory was dead**: 0 MEMORY selections.
- **P1 granularity:** same-drive collisions occurred in 93.3% of batches (threshold 1%). Decision: `PER_STIMULUS_FROZEN_BASELINE`.
- **P2 species model:** 219,779 tuples; collapse check passed. Best L_pred was 0.0625 at epoch 4,
  degrading to 0.60 by epoch 100. Cause: gradient interference from SIGReg at λ=0.1.
- **P3–P4:** engram memory with eligibility traces; 577,102 engrams from 3 creatures.
- **P5:** sleep-gated adapter consolidation; loss falls 53–67% per lifetime. Gradient explosion fixed
  (zero grads + tanh targets).
- **P6 Mode-2:** fires on 16.8% of decisions, but **lifetime fell to 4.5 min vs ~73 min**.

### 06-26 → 07-02 · Why JEPA hurt; first comparison
- **#43 (06-28):** random adapter init corrupted the trained pipeline. Fix: zero-init the projection,
  so the adapter starts as identity. Lifetime +28% vs P6, still below P5.
- **#48 → #51 (06-29/30):** Critic picked SLEEP ~94% of the time. Retraining and a dual encoder
  changed nothing (94.2/94.4%). Passing `z_internal` to the Critic was needed but not sufficient.
  **Root cause: `prepare_dataset` dropped self-targeted actions → 0 WANDER samples.** After the fix:
  10× lifetime in food-rich worlds and Mode-2 firing −94%. A residual 60% SLEEP bias needs multi-step
  rollout (not done).
- **Plumbing:** world enrichment (rotten fruit, cactus, aloe, OBSERVE, tedium; #40), configurable
  learning (#41), single-threaded `TestingCreature` harness (#45), CI (06-28), four JEPA variants
  (#53), and TypedActor removed from cross-JVM stimulus routing (#49).
- **EXP-P7 (07-02, Pi, 50 creatures/arm):** we implemented `MemoryFilter` + Mapa consolidation as the
  non-JEPA competitor. Baseline 2704 s; memory −51 s (significant); memory+consolidation n.s.;
  JEPA +178 s; JEPA+consolidation +618 s. Mac control: most of the Pi wall-clock gain is inference
  latency. The report's conclusions were left "pending".

### 07-02 → 07-10 · Make the creature faithful to Campos/Mapa
- **Why:** cognition was being compared on a creature whose drives didn't match the models it
  descends from. Gap analysis: `docs/roadmap/Campos2015_Model_Parity.md` (#54).
- **#55/#56 (07-07):** metabolism, arousal and death scoped to the 4 active emotions; swapped
  behavioural-efficiency curves fixed.
- **#57 (07-07/08):** symbolic dopamine/serotonin RPE loop plus innate `ActionTendency`.
  Baseline creatures slept 82.8% of the time; tendency arms forage. CONTINUOUS expectancy ~9% lower MSE.
  The same work fixed an eat-loop bug (the reply never reached the Mouth), made pain nociceptive, and made
  boredom drive WANDER.
- **#58 (07-08):** distance-driven focus; eye closed during sleep.
- **#59 (07-08/09):** orexin wakefulness gate + HPA axis with negative feedback. A stimulus-batching fix
  dropped SLEEP from 44.2% to ~1%; creatures now starve at ~150 s instead of dying of sleep loss.
- **JEPA retrain (07-09/10, #67, #68):** the p9-trained model hit domain shift on the new stack
  (97.5% AVOID). Retrained as `unified_critic` (8-dim internal state, VICReg + EMA). JEPA error is now the RPE source.
- **Infra:** Prometheus/Grafana (#66).

### 07-09 → 07-29 · Memory vs JEPA experiments (5 conditions × 5 trials × 5 creatures)
| Report | World | Headline |
|---|---|---|
| `20260709_memory_vs_wm_v1` | scarce | JEPA 441 s vs baseline 290 s; consolidation hurts JEPA; memory hurts survival |
| `20260713_rotten_fruit_v1` | novel food | JEPA +112% survival; **no aversion learning in any arm** |
| `20260714_dense_reposition` | abundant (CCAD) | JEPA advantage vanishes (p≈1); memory penalty vanishes |
| `20260717_dense_scarce` (07-27) | dense, depleting | JEPA advantage inverts (24/50 deaths vs 0/50) — **confounded, see below** |
| `20260717_dense_no_reposition` (07-29) | same, re-run | JEPA+Consol 123 s vs 74–87 s for the others |

- **Infra in the same window:** Ansible playbook + YAML specs replaced 46 compose files and 10
  scripts (#69, 07-13). CCAD (SLURM + Singularity) replaced the defunct CEFET cluster (07-13 → 07-17,
  #71, #72). Data is uploaded to HF.

### 07-27 → 08-06 · Infrastructure confounds, then the rebuild
- **CCAD node stall (07-27 → 29):** creatures on nodes c1/c2 ran 6–40× slower. Three fixes in
  sequence: oversubscribed thread pools (#74), one JPA factory per actor (#75), and synchronous
  Postgres writes on the cognition path (#76, the dominant cause). Afterwards every node ran 10–20×
  faster than the old "healthy" baseline. See `docs/postmortems/ccad-node-c1-cognitive-cycle-stall.md`.
- **#77 (07-30):** the now-async writer had no backpressure, causing OOMs. Fix: bounded `BDActor` (#78).
- **#79 (07-31 → 08-02):** metabolism advanced per cognitive cycle, so faster cycles killed creatures in
  ~1 min. Phase A rescaled constants (÷5.858). Phase B capped the rate at the pacemaker and dt-weighted
  metabolism. JPA/Postgres were removed in the same PR (#81).
- **Persistence (08-01 → 08-04, #81–#83):** Postgres → Parquet/DuckDB backend (tried, dropped) →
  **off-heap Arrow IPC + embedded-DuckDB extraction**. An A/B showed a "harmless" refactor caused a
  4% lifetime regression (dispatcher pool=2); fixed.
- **#85 (08-06):** cycles actually ran at 259–298 Hz vs a nominal 30, and 82% of cycles saw
  nothing, so perception strobed ~66×/s. **Cognition is now tick-gated**, and the Phase A constants
  are back at round values. CI gained integration tests, preview/release images and a regression benchmark.
- **Tedium (08-06/07):** tedium pinned arousal at 7, collapsing behavioural efficiency.
  We disabled it, then **reverted** that: Mapa does model tedium, and relieves it via a toy/ball
  DL2L doesn't have. Resolved without code: ActionTendency or neuromodulation each keep tedium
  low. See `docs/plans/tedium-saturation.md`.

### 08-04 → 08-12 · Behaviour parity with Mapa/Campos (#84, PR #92)
- **Why:** before writing up JEPA, check the current architecture still behaves like its
  published ancestors. Only shapes and ratios are comparable (different code, no source data).
  Pre-registered in `docs/experiments/p84_behaviour_parity_recipe.md`.
- **First campaign (08-05):** the memory-advantage predictions (P1, P4) were refuted.
- **#88 (08-07):** `MemoryFilter` ranked actions by *sum* of engram value, i.e. by frequency.
  Memory creatures ate 3.6× less and died 27% sooner (p=7e-11).
- **Memory rework (08-07 → 08-10):** ranking by mean instead of sum; selection proportional to value
  instead of argmax; memory picks the object and operant conditioning picks the action; separate
  consummatory store, because APPROACH engrams were 114:1 and diluted the 6.3× caloric discrimination
  down to 1.14×; retention bounded per (action, object). Also fixed a manager startup race.
- **CCAD disk quota (08-05, 08-11):** stopped writing `stimulus_state` (3.2 of 5.6 GB, never read), made
  collection incremental, and salvaged truncated dumps. Reworked arms no longer die, so we capped runs at
  30 min and compare rates.
- **Final campaign (08-12):** 6 arms × 16 trials × 5 creatures = 480; 10/10 schema gates.
  - Memory raises feeding rate in all 3 pairs (e.g. legacy 11.3 → 13.4 EAT/min).
  - **P2 confirmed:** RANDOM collapses when memory is on.
  - **P3 confirmed.**
  - **P4 confirmed in the legacy pair only:** 1.11× vs Campos's 6.7×.
  - P1 and P5 inconclusive; S1 untestable with food replenishment.
  - A truncated-frame metric bug had inverted the feeding result; a dry run caught it before the report.
- **Filed from the data:** #90 (legacy/current arms confounded on chain arbitration; current-stack
  creatures never get hungry), #91 (engram value tied to the dominant drive; sleep for 99.95% of
  engrams, so memory prefers zero-calorie food), #93 (eligibility applied twice). Also, the consolidation
  threshold of 0.1 is unreachable (max observed 0.031), so consolidation never runs.

### 08-15 → 08-16 · #91 follow-up (branch `claude/issue-91-drive-specific-memory-valuation`, no PR)
- Implemented drive-matched object valuation (`911132a`). It is a measured no-op on every existing arm.
- The p91 pilot failed its regime gate twice. The first reading (scarcity) was wrong. The real cause:
  `ActionTendencyFilter` gives EAT only to hunger, so a sleep-tagged EAT engram can't exist.
  The pilot was re-specced with tendency off, which means it is no longer legacy-faithful.
- **Decision doc (`020eca9`):** the #91 inversion is an *eligibility artefact*, not drive pooling.
  Keeping only engrams with eligibility ≥ 0.99 removes it. It recommends reverting `911132a` and trying an
  eligibility threshold, then quantile scoring, offline first. Not acted on.

### 08-15 → 08-17 · Original ALifeWorld source recovered (now in [`felipedreis/alifeworld`](https://github.com/felipedreis/alifeworld), private)
- **Mapa (0.9.5, `Hash_BD` snapshot):** this is the dissertation code; it runs on JDK 23. The dissertation's
  Table 2 swaps Q3/Q4 (verified live). There never was a memory on/off flag. Docs:
  `docs/legacy/alifeworld-0.9.5-architecture.md`; port plan in `docs/plans/mapa-alifeworld-port-to-dl2l.md` (both in that repo).
- **Campos 2015 (0.7.5.1):** the checked-in config ("Experimento27") ≠ the paper's Table II. There is no
  memory-off switch, and the memory filter has bugs (only the last candidate counts; it matches on target only).
  A timestamp bug silently drops all per-step logs. One 56 s smoke replication
  (`data/campos2015_replication/` in that repo); no paper-faithful comparison yet.
- **10-16:** `docs/legacy/action-selection-0.7.5.1-vs-0.9.5.md` (alifeworld repo) compares the two versions' action selection.
  Campos uses memory to pick an action. Mapa uses memory to pick a target and a probability table to pick the action.

### 10-07 · Process
- PR #94 merged: plan for a shared experiment-toolkit repo (dl2l + snake_rl conventions, with
  pre-registration). Draft only, not built.
- #95: trials can't be replayed because no randomness is seeded.

### 10-17 → 10-18 · Arbiter prerequisites: feeding (#96) and seeding (#95), issue #97, PR #118
- **Why:** every arbiter arm needs creatures that keep eating and runs that can be paired by seed.
  Plan: `docs/plans/issue-97-arbiter-prerequisites.md`; recipe (pre-registered, with a revision after the
  first arm failed): `docs/experiments/p97_feeding_fix_recipe.md`; report: `docs/reports/20261018_p97_feeding_fix_report.md`.
- **Bugs found and fixed (tested):**
  - The mouth and vision arcs read a radian bearing as degrees, so they sat in a nearly fixed wedge whatever
    the heading (5 of 7 headings failed the new geometry test). This also changes perception for every arm.
  - EAT is now afforded within mouth radius + object radius, not only on the detector's exact-touch event.
  - WANDER turned around the bearing to the perceived object instead of the heading, contrary to its own comment.
- **Seeding (#95):** `simulation.seed` / `--seed` / `DL2L_SEED`; per-creature child seeds; positions a function of
  (seed, object id), so several holders never replay one stream; stored in a `run_info` table and the manifest;
  specs take `seeds:`. Live replay: two runs with seed 9701 started all 5 creatures at identical positions.
  Akka scheduling keeps trajectories non-identical.
- **Feeding result (8 trials x 5 creatures, seeds 9701-9708, historical unseeded control):** D, the number of life
  deciles with an EAT, is 4.58 pre-fix, 4.50 with the contact fix, 4.97 with the WANDER fix too. Nobody reaches
  P[D>=9]. **The #96 criterion is not met.**
- **What actually causes it:** the gray-free legacy world already feeds in every decile with the *old* code
  (D = 9.91, no deaths). In the mixed world P(EAT|gray) is learned down to 0 within ~50 s while P(APPROACH|gray)
  rises, so after ~30% of life creatures sit inside ~10 px among grays and the local red/green is exhausted. Speed
  rises because hunger rises, not the other way round. This chain fits the data; no intervention has tested it.
- **Decision pending:** meeting the criterion needs a learning-rule change (EAT punishment should not redistribute
  weight to APPROACH on the same target), which touches Mapa/Campos parity. Until then the arbiter A0 baseline
  should use the gray-free world or accept the collapse.

### 10-25 — #100: per-drive outcome vector K

- **What:** `HomeostaticRegulation` now records K = (Δhunger, Δsleep, Δpain, Δtedium), the realized change around each
  regulation event, tagged with the originating action (`EAT`, `SLEEP`, the stimulus' action, else `BASELINE`) in the
  `drive_outcome_state` table, extracted as `drive_outcomes`. Arbitration draft §4.1 / App. A step 2. Nothing reads K yet.
- **Real-data check** (`experiments/p100_k_check.yml`, A0, 5 creatures, seed 12345, 4 min, local Docker, upload off):
  31,502 K rows. Per creature and drive, |Σ K − Σ(final − init) of `drives`| and |Σ K − (last final − first init)| are
  both <= 9e-16, i.e. the sum identity holds to float precision for hunger, sleep and tedium. Pain is exactly 0 in
  this world (no pain source), so its identity is vacuous here — the unit test (`DriveOutcomeTest`) covers it.
- **Reading the rows:** `APPROACH` rows are tedium only (+418 total) and `WANDER` rows relieve it (-404); `BASELINE`
  (metabolic drift) raises hunger and sleep by the same +50.95; `EAT` is 161 nutritive rows (hunger -30.3) plus 358
  separate idle-tedium stimuli (+0.02 each) emitted for that action, never both in one row. Every EAT row has
  dHunger <= 0 and every SLEEP row dSleep <= 0.
- **Not tested:** the Pi path. The Pi controller's registry is plain HTTP (Docker Desktop will not push to it), and a
  GHCR-image trial (job 26) exited rc 1 after 4m26s; its SLURM log could not be read because ssh to the controller
  hung. Cause unknown.

### 11-01 — #104: the arbiter on real runs (PR #126)

- **What:** `ArbiterActionSelector` replaces the filter chain with decisiveness (θ = 0.8), a 3% random floor and a
  Thompson draw (τ = 1) over the operant table; its chosen Q now feeds the pre-emption bootstrap of the episode buffer.
  Plan: `docs/plans/issue-104-arbitration-filter.md`. These runs are smoke checks (one trial each, seed 104, 5 creatures,
  UI on, local Docker), not hypothesis tests. Data: HF `p104_a2_check/` and `p104_a2_stress/`.
- **Check 1** (`p104_a2_check`, A0 world, 15 min): `selection_type = ARBITER` on all 133,205 decisions, 0 deaths, and no drive
  ever reached 2.0 (max hunger 1.28, against 2.83 in the #110 run of the same length). So no episode opened (`focal_drive` is
  NULL on every decision, `episode_return` has 0 rows) and the operant table was never trained: this run measured the
  action-tendency prior plus near-uniform draws, not a learned habit. Actions: WANDER 54.5%, APPROACH 27.2%, SLEEP 14.6%,
  EAT 3.8% (the #110 run: 28.9 / 64.1 / 6.1 / 0.9%). Decisions per 15 min were equal (133,205 vs 133,399).
- **Stress runs** (`p104_a2_stress`, 30 min, scarce food: 10 + 10 nutritive apples respawning after 60 s, and 20 + 20 with
  no respawn): every creature died (5 of 5 in both; mean lifetime 255 s and 205 s), hunger at or above 2.0 in 76% and 59% of
  samples. Episodes opened and closed: `DEATH`, `PREEMPTED` and `TIMED_OUT` rows, no NaN values.
- **Now verified on real data:** the death close-out (all 5 creatures in each condition have `DEATH` rows, one episode each;
  mean value -0.47 and -1.19), and a non-zero pre-emption bootstrap, so the arbiter's chosen Q reaches the buffer: 149 of 161
  pre-empted sleep steps (30 of 30 in finite food) and 56 of 315 pre-empted hunger steps (0 of 197 in finite food). Cycle rate
  is unchanged with a trained table (about 29.7 decisions per creature per second, against 29.6).
- **Not established:** the zero hunger bootstraps (plausibly because most hunger-episode actions earn about 0 value in these
  worlds, not checked); behaviour of a creature that stays alive while learning (these worlds kill everyone within 3-5.5
  min); anything about regulation or survival against A1 (that is #109). Most steps in the scarce world are `TIMED_OUT`
  hunger steps (26,532 in the 5 creatures), i.e. creatures wander without finding food.
- **Process findings:** a code review (Opus) found no correctness bug in the core; open items are configuration
  (issue #125) and an uncaught timeout on the blocking operant estimate call. `Self` had no `equals`, so every
  self-perception made a new operant cell that never learned; fixed by keying object types by name.

### 11-02 — #105: the arbiter's decisions as a table, and a death close-out bug (PR to follow)

- **What:** one row per arbiter decision (`arbitration_decision_state`, extracted as `arbitration_decisions`): focal drive,
  efficiency, the chosen, best and runner-up estimates, the executed action's estimate after the hysteresis gate, the
  exploration source, and per-controller columns (operant filled, episodic and planner declared and NULL). The episode id and
  the realised value are joined at extraction on `(creature, cycle = step_cycle)`. Plan: `docs/plans/issue-105-arbitration-decision-table.md`.
  The `GREEDY`/`THOMPSON` label now counts a pick as greedy when its Q equals the maximum (ties included); the row also
  stores the number of candidates tied at the maximum.
- **Check run** (`p105_decision_check`, the two p104 stress worlds, 1 trial each, seed 104, data on HF `p105_decision_check/`):
  38,320 and 30,580 decision rows, one per decision, joined to the episode steps. In these worlds 66% and 83% of decisions
  have a single candidate after the pre-filters; the epsilon floor is 3.1% and 3.2% of multi-candidate rows; 12-15% of
  decisions are all-tied (uninformed); 0.37% were overridden by the gate. The descriptive z of realised values against the
  executed Q is about 0 with variance 0.001-0.008 on the TIMED_OUT steps (about 96% of joined rows), and -4.7 / -10.6 on death
  steps. This is material for #115 (calibration), not a result.
- **Bug found in #110 and fixed:** a creature's last hunger episode (71 decisions) had no `DEATH` rows. `kill()` reads the death
  cycle, the arbiter can record one more decision before the components stop, and `EpisodeBuffer.close` rejected a step later
  than the close; the exception was swallowed. Now the death closes at the later cycle. Reproduced in two tests first. The real
  race is the hypothesis; the logs were gone. Not re-run on a simulation.
- **Rule:** tests must be deterministic; anything that cannot be tested that way is measured in the experiment metrics instead.
  The older #104 `ArbiterTest` seed-search and statistical-bound cases still break it (to clean up separately).

### 11-04 — #107 arbiter-ladder pilot, stage 1 gate: A2 never trains in the A0 world

- **What:** ran the gate of `docs/plans/arbiter-ladder-pilot.md` (approved the same day): one trial each of A1, A2 and A2 with
  finite food, seed 10700, 5 creatures, 120 min (30 for finite food), local Mac. Recipe `docs/experiments/p107_pilot_recipe.md`,
  data on HF `p107_pilot_gate/`. Added `simulations/arbiter_a1.conf`, `arbiter_a2.conf` and `experiments/p107_pilot_gate.yml`.
- **G1 fails.** A2 closed 0 episodes in all 5 creatures over about 217,600 decisions each (focused share 0.0); there is no
  `episode_returns` file. Max hunger 1.59, mean 0.30: no drive reaches the open threshold, so the operant table is never trained.
  This is the fixed point predicted from p104, now at full length.
- **A1 telemetry is fine** (G3): 17.5-22.1% of decisions focused, 9,162 episodes (counted per creature and id: `episode_id` restarts for each creature), 93% of them PREEMPTED by another drive. A1 is
  less well regulated than predicted (max hunger 3.79, max tedium 4.03, mean hunger 1.34 vs 0.30 in A2).
- **Death close-out holds on a real run** (G4): all 5 dead creatures in the finite-food world have focused decisions equal to
  episode steps and at least one DEATH row (the p105 miss was 1 creature of 10).
- **Not done, by the plan:** stage 2 (3 arms x 3 trials) does not start after a G1 fail. Next is the user's choice among
  lowering theta_on, a world where drives bind, or training the NONE bucket / falling back to the A1 filters when unfocused.
- **Infrastructure findings:** the Pi attempt failed twice for reasons unrelated to the simulation. The Mac's Docker had no
  insecure-registry entry for the Pi registry (added). Then the NFS share the Pis mount from the Mac stopped answering
  (`nfsd` connections stuck in `CLOSE_WAIT`), hanging the running Pi job and new submissions; needs `sudo nfsd restart` on the
  Mac, not yet done. Separately, the local playbook skips the HF upload (`experiment_finished` is set on a different host than
  the one that checks it in `collect_upload`); the upload was run by hand with `dl2l_data.upload`. Not fixed.

### 11-06 — #107 pilot: focus thresholds tuned to 0.4 / 0.15, gate 2 passes

- **What:** made the focus tracker's `thetaOn` / `thetaOff` config keys (`feature/focus-theta-config`, not merged; also written
  to `run_info`), tuned them on A2 (seed 10710, 30 min, four settings; recipe `docs/experiments/p107_theta_tune_recipe.md`), and
  re-ran the three gate conditions with the chosen 0.4 / 0.15 (recipe `docs/experiments/p107_gate2_recipe.md`, data on HF
  `p107_theta_tune/` and `p107_pilot_gate2/`). Plan: `docs/plans/focus-tracker-theta-config.md`.
- **Tuning:** 0.4 / 0.15 was the only setting where >= 4 of 5 creatures had >= 3 closed episodes and focused share >= 0.02 (19-41
  episodes per creature in 30 min); 0.8 / 0.3 had almost none. The recipe's rule was ambiguous; read at setting level it would have
  chosen 0.6 / 0.2, and the user chose 0.4 / 0.15. My prediction (0.5 or 0.6) was wrong.
- **Gate 2 passes G1-G5.** A2 closed 137-165 episodes per creature in 120 min, focused 4.2-5.1% of decisions, informed share 1.0;
  its regulation is unchanged from gate 1 (mean hunger 0.30, max 1.55), so training the table did not cost regulation. The death
  close-out holds again (5 of 5, DEATH rows in all).
- **A1 changed with the thresholds** (they are global): focused about 68% of decisions (was 17-22%), mean hunger 0.98 (was 1.34),
  and one creature died at 4,946 s (gate 1: none). One trial, so no attribution, but the pre- and post-change A1 are different
  arms for #108.
- **Mistakes found on the way:** (1) the Java branch was cut from `main` and lacked `arbiter_a1/a2.conf`; HOCON ignores a missing
  include, so the first tuning run silently had no world and no creatures (30 min lost, data deleted). The new confs' includes are
  now checked before running. (2) Two shell slips of mine (`timeout` and `setsid` do not exist on macOS) had me misread Docker as
  down earlier in the Pi attempt.
- **Next:** stage 2 is allowed by the plan, after merging the Java branch and settling the open decisions (environment, 5 vs 3
  creatures, `regulated_emotion`, backups, and whether to add the random-selection control).

### 11-07/08 — #107 pilot stage 2, the #106 analysis, the untrained-table control and a bug that turned out not to matter

- **Stage 2 (CCAD, `p107_pilot`, 3 arms x 3 trials x 5 creatures, seeds 10701-10703, data on HF `p107_pilot/`).** The first attempt failed for A1 and A2: the CCAD
  job script waited at most about 90 minutes for a simulation that runs 120; ceiling raised to about 150 (`run_trial.sh.j2`), failed attempt kept aside on the cluster,
  all nine trials re-run. Results: d(H) over life A0 3.66, A1 1.24, A2 0.16; deaths 15/15, 1/15, 0/15; creatures alive at the cap are observed to about 7,195 s.
  Plan and recipe: `docs/experiments/p107_pilot_stage2_recipe.md`.
- **#106 analysis module** (`analysis/experiments/arbiter_ladder.py`, plan `docs/plans/issue-106-arbiter-ladder-analysis.md`, 8 unit tests): runs end to end on the pilot
  and writes the report skeleton. Episode ids restart for every creature, so episodes are counted as `(creature_key, episode_id)`; some earlier episode totals in the
  gate entries were undercounted that way and are corrected. Data caveat found: in creatures that survive to the cap, `mouth_interactions` holds a whole number of
  128-row blocks (and `body_states` exactly 217,728 rows), so EAT counts of A1/A2 survivors are lower bounds (short by up to 127 each).
- **Sample size for #107** (`docs/reports/p107_pilot_sample_size.md`): paired trials per arm are small because the effects dwarf the between-seed SD (A2 - A1 on d(H) over life
  needs 2-3). Non-inferiority of A2 against A1 with equal true means needs 7 trials at a 0.06 margin (22 on the upper-bound SD), 3 (7) at 10% of the A1 mean, 3 (4) at 20%. The
  margin is #107's decision. A0's focus-switch statistic cannot be sized (approximation), and survival is better a guard than a powered outcome.
- **Control: A2 with a table that never trains** (`focusThetaOn = 99`, `p107_pilot_ctl`, same seeds): d(H) over life 0.160 against 0.163 for the trained A2, per-seed
  differences +0.002, +0.006, +0.002, inside the pre-registered +-0.06; feeding identical (red+green 25.4 against 25.3 per minute), all 15 alive. So training the table adds nothing
  measurable in this world, and A2's advantage over A1 comes from replacing the filter chain with the arbiter, which with an untrained table chooses uniformly among the candidates.
- **A suspected bug that did not matter.** Reading the arbiter and the filters showed that `ActionProbabilityFilter` (the `AFFORDANCE` filter of A0 / A1) returned its first
  candidate (APPROACH) whenever every learned weight was 0 (0/0 = NaN). Fixed without a flag (`fix/affordance-zero-weight`, issue #130) and tested on the pilot's seeds
  (`p107_pilot_fix`): A0 still dies 15/15, the APPROACH share is unchanged (about 65%), d(H) rises (A0 3.66 -> 4.27, A1 1.24 -> 1.63, A1 deaths 1 -> 4 of 15). I had first
  written that the bug probably explained A0 starving and put withdrawal notices on 15 reports; the measurement refuted that and the notices on the older reports were removed
  (`docs/known-issues/affordance-zero-weight.md`). Lesson: measure the effect before invalidating. Why A0 / A1 spend about 65% of cycles on APPROACH and eat 0.9% of the time is unexplained.
- **Process slips on the way:** Homebrew upgraded `python3` to 3.15 without the analysis packages, which made the ansible spec validation fail on three submissions and
  cost a night (libraries now installed); the CEFET VPN dropped once and a submission waited for it.
- **Open for #107:** the margin, the pre-registration document itself (`docs/experiments/arbiter_ladder_recipe.md`), equal tuning budget (the focus thresholds were tuned on A2 only),
  config keys for the arbiter's theta / epsilon / tau, and a random-selection control is now answered by the untrained-table control.

### 11-10/11 — The per-outcome operant table learns the right values and makes the creatures worse, under every tendency set

- **What:** the review (`docs/plans/arbiter-proposal-review.md`) made the A2 operant table a basic per-outcome learner keyed by (object type, action), like the
  legacy table and Mapa (no drives, no episodes). The sample is the outcome's valence (+1 / -1, no change counts as -1): the size of the drive reduction was tried
  first and could not tell eating a gray apple (0) from an untried action (prior 0). A switch (`operantLearningEnabled`) freezes the table for the controls, and
  `actionTendencySet = CAMPOS | MAPA` selects the tendency lists. Offline simulations (`analysis/diagnostics/operant_sample_sim.py`, `tendency_variants_sim.py`)
  checked the mechanism before each cluster run.
- **`p107_operant` (CAMPOS tendencies, both worlds):** the table learns (gray EAT about -0.99, food +0.8) and changes choices a little (gray share 0.58 -> 0.52),
  but regulation is worse in every seed (d(H) 0.17 -> 0.31): learners linger next to gray apples they will not eat, because under hunger dl2l's tendency filter
  (Campos's lists) leaves only EAT or WANDER at contact.
- **Mapa keeps AVOID:** in ALifeWorld 0.9.5 every emotion lists approach, avoid, eat, sleep, wander, touch, play (`HUNGER_ACTIONS = 7`); dl2l copied Campos's
  0.7.5.1 lists in #57.
- **`p107_tendency` (MAPA lists and no filter, both worlds):** learning still avoids gray apples (gray share -0.20 with MAPA) but all 30 pilot-world learners starved
  within 3-10 minutes, against 3 and 9 of 15 frozen. They chose food EATs about half as often; sleep is not the cause; the hysteresis gate overrides about 40% of
  decisions in these variants (6% under CAMPOS). Not explained yet.
- **Where this leaves A2:** in every variant tested, per-outcome operant learning makes regulation worse, while the untrained arbiter (uniform choice) regulates
  near the floor. The next step is a diagnosis of why learned EAT values lower food choices (candidate lists and gate decisions per cycle), before any campaign.
- **Process:** the size-of-reduction run was stopped after its scarce world; python3 3.15's duckdb 2.0 broke the analysis, which now runs on python3.13.

### 11-11 — Diagnosis of the operant result; configuration settled; #107 pre-registration drafted

- **Diagnosis from the existing data** (`analysis/diagnostics/p107_exposure_diagnosis.py`): at food, learners eat at least as readily as frozen creatures;
  they spend half as long at food and 2-3x as long next to gray apples. The habit table cannot value APPROACH (no immediate outcome), so learners
  approach gray apples as readily as food, refuse them, and hop to the next one; under Mapa's lists they mostly sleep next to them (10-cycle sleep lock).
- **Settled:** A0's classic configuration (CAMPOS tendencies) for every arm; the operant controller as implemented. A2 is not tuned further; its
  limitation is a finding and the contrast A3 is meant to address (review §10).
- **#107:** `docs/experiments/arbiter_ladder_recipe.md` drafted for review: arms A0-A3, A5; primary outcome d(H) over life; adoption criteria
  from the paper's §8.1 with the thresholds fixed (margin 0.124, calibration in [0.8, 1.25]); zero tuning budget; 16 trials x 5 creatures, seeds
  10800-10815; six open items for the reviewer.

---

## Which results still stand

| Claim | Status |
|---|---|
| SLEEP bias came from a dataset bug, not the architecture | Stands; residual 60% SLEEP unaddressed |
| JEPA improves survival over baseline/memory (P7, v1, rotten fruit, no_reposition) | **Provisional.** All lifetime data predates the 08-02 clock fix and the 08-06 tick fix; P7 is also latency-confounded |
| JEPA's advantage depends on density/scarcity | Same caveat; the runs disagree with each other |
| Consolidation hurts JEPA | Same caveat; P7 and no_reposition show the opposite |
| Old `MemoryFilter` harms foraging | Confirmed mechanism (#88); superseded by the rework |
| Reworked memory raises feeding rate | Stands (p84, post-fix data); valuation bugs #91/#93 still open |
| Aversion learning (rotten fruit) | Never demonstrated |
| Memory consolidation does anything | No — its threshold is currently unreachable |
| Creatures stop eating mid-life because contact fails at speed (#96) | **Refuted.** Contact fixes do not restore feeding; the collapse is gray-world learning dynamics (D 9.91 without gray). Causal chain untested by intervention |
| Runs are replayable by seed (#95) | Initial world only (live-verified); trajectories stay nondeterministic |

No JEPA experiment has run since the 08-02/08-06 fixes. The deployed `unified_critic` was
trained (07-10) on data collected before them.

## Open threads (as of 10-18)
- **Memory correctness:** #93 (eligibility²), #91 (decide between the branch's two designs; revert `911132a`?), consolidation threshold.
- **Parity:** #90 (arm confound), S1 needs a depleting world, Mapa port plan (Phases 1–6).
- **JEPA:** re-validate on the fixed clock/tick before citing any survival claim; #65 full-architecture run; K-step rollout.
- **Feeding:** #96 stays open; decide on the learning rule (EAT punishment -> APPROACH redistribution) and test the causal chain by intervention. PR #118 awaits merge.
- **Reproducibility/infra:** #94 toolkit, #80 blocking TypedActor accessors, #70 image distribution.
- **Housekeeping:** #84, #85, #88 still open though largely addressed. PR #87 is superseded by #92, and PR #47 is stale.
  #49 is still open even though its TypedActor fix landed 07-01.
