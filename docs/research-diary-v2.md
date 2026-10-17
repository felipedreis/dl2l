# DL2L research diary

Covers 2026-06-22 → 2026-10-16. Compiled 2026-10-17 from git (including the unmerged
`claude/issue-91-*` branch), GitHub issues/PRs, `docs/plans`, `docs/reports`, the CCAD
postmortem, and untracked working files. Numbers are copied from the cited report or commit;
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

### 08-15 → 08-17 · Original ALifeWorld source recovered (untracked files)
- **Mapa (0.9.5, `Hash_BD` snapshot):** this is the dissertation code; it runs on JDK 23. The dissertation's
  Table 2 swaps Q3/Q4 (verified live). There never was a memory on/off flag. Docs:
  `docs/legacy/alifeworld-0.9.5-architecture.md`; port plan in `docs/plans/mapa-alifeworld-port-to-dl2l.md`.
- **Campos 2015 (0.7.5.1):** the checked-in config ("Experimento27") ≠ the paper's Table II. There is no
  memory-off switch, and the memory filter has bugs (only the last candidate counts; it matches on target only).
  A timestamp bug silently drops all per-step logs. One 56 s smoke replication
  (`data/campos2015_replication/`); no paper-faithful comparison yet.
- **10-16:** `ACTION_SELECTION_ALIFEWORLD.md` compares the two versions' action selection.
  Campos uses memory to pick an action. Mapa uses memory to pick a target and a probability table to pick the action.

### 10-07 · Process
- PR #94 merged: plan for a shared experiment-toolkit repo (dl2l + snake_rl conventions, with
  pre-registration). Draft only, not built.
- #95: trials can't be replayed because no randomness is seeded.

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

No JEPA experiment has run since the 08-02/08-06 fixes. The deployed `unified_critic` was
trained (07-10) on data collected before them.

## Open threads (as of 10-17)
- **Memory correctness:** #93 (eligibility²), #91 (decide between the branch's two designs; revert `911132a`?), consolidation threshold.
- **Parity:** #90 (arm confound), S1 needs a depleting world, Mapa port plan (Phases 1–6).
- **JEPA:** re-validate on the fixed clock/tick before citing any survival claim; #65 full-architecture run; K-step rollout.
- **Reproducibility/infra:** #95 seeding, #94 toolkit, #80 blocking TypedActor accessors, #70 image distribution.
- **Housekeeping:** #84, #85, #88 still open though largely addressed. PR #87 is superseded by #92, and PR #47 is stale.
  #49 is still open even though its TypedActor fix landed 07-01.
