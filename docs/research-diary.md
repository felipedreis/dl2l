# DL2L Research Diary

Compiled 2026-10-17 from git history, PRs, issues, plans and reports (2026-06-22 → 2026-10-07).
Each entry: **what** was done, **why**, and **what came out**. Details live in the linked
report/plan; this is the index to remember the thread. Report filenames are in `docs/reports/`.

**Thread in one paragraph.** We added a JEPA world model (species model + per-creature adapter,
sleep-gated consolidation, "Mode-2" deliberative action selection) to the creature. It first
failed on bugs in our own pipeline (SLEEP bias, adapter init). After fixing the creature's
biology (emotions, neuromodulators, sleep/stress), JEPA beat a Mapa-style memory filter in
scarce worlds but not in abundant ones. Then infrastructure problems (CCAD, persistence)
turned out to confound results, so we rebuilt persistence and the clock. The behaviour-parity
campaign (p84) against Mapa/Campos then showed our *memory* was broken, which led to a memory
rework and several open bugs.

---

## Phase A — JEPA integration, built in phases (2026-06-22 → 06-24)

Plan: `docs/roadmap/JEPA_Integration.md`, `docs/hld/JEPA_WM_Integration.md`; epics #3–#8, PRs #31–#38.

- **Phase 0/1 — de-risk and instrumentation (06-22).** Why: check the design is viable before
  building. Coverage probe on perception, and a measurement of how many regulating stimuli
  collide per batch. Result: decision `FROZEN-BASELINE PER-STIMULUS` (collision rate far above
  the 1% threshold) — reinforcement stays per stimulus.
- **Phase 2 — species model (06-22).** PyTorch encoder/predictor/critic trained offline
  (SIGReg), collapse check, TorchScript export. Finding: SIGReg interferes with training
  (`EXP_P2_1`).
- **Phase 3–4 — memory engrams and eligibility traces (06-23).** Engram-capable memory,
  cognitive-cycle counter, eligibility-weighted reinforcement (577k engrams in the P4 run).
- **Phase 5 — sleep-gated adapter consolidation (06-24).** Gradient explosion found and fixed
  (zero grads + tanh targets). Loss falls 53–67% per lifetime.
- **Phase 6 — Mode-2 action selection (06-24).** Works mechanically (16.8% of decisions) but
  lifetimes collapsed to 4.5 min vs ~73 min baseline.

## Phase B — Fixing why JEPA made creatures worse (06-26 → 07-02)

- **Adapter init (#43, 06-28).** Cause of the Phase 6 regression: random adapter output
  corrupted the trained pipeline. Fix: zero-init projection → adapter starts as identity. Guard
  test added. Lifetime +28% vs P6-1, but the SLEEP bias remained.
- **SLEEP bias (#48 → #51, 06-29).** Two hypotheses failed (retrain on full world; dual
  encoder: bias stayed ~94%). Giving the Critic `z_internal` helped partly. **Real root cause
  was a data bug**: the dataset builder dropped every self-targeted action (WANDER), so 0
  WANDER samples. After the fix, WANDER regulates hunger (10× lifetime in food-rich worlds).
  Remaining 60% SLEEP bias needs multi-step planning (K-step rollout, not done).
- **Plumbing.** Learning subsystems made configurable (#41), single-threaded
  `TestingCreature` harness (#45), CI (06-28), plants/rotten fruit/OBSERVE added (#40),
  four JEPA variants + Strategy routing (#53). Cross-JVM TypedActor bug (#49) worked around
  by co-locating holder + collisionDetector.
- **P7: memory filter vs world model (06-30 → 07-02).** Implemented Mapa-style `MemoryFilter`
  and sleep consolidator to have a non-JEPA competitor. Pi vs Mac control run to rule out
  inference latency as a confound. Conclusions left pending.

## Phase C — Make the creature's biology faithful to Campos/Mapa (07-02 → 07-10)

Why: we were comparing cognition on top of a creature whose emotions/drives were not
faithful. Gap analysis vs Campos 2015: `docs/roadmap/Campos2015_Model_Parity.md`.

- **#55/#56 (07-07).** Metabolism, arousal and death scoped to the four active emotions;
  swapped behavioural-efficiency curves fixed.
- **#57 neuromodulatory expectancy (07-07/08).** Symbolic dopamine/serotonin RPE loop plus an
  innate emotion→action coupling (`ActionTendency`) to stop over-sleeping. Also fixed the eat
  loop (EnergeticStimulus reply never reached the Mouth) and made boredom drive WANDER.
- **#58 (07-08).** Focus now distance-driven; eye closed while asleep.
- **#59 orexin + HPA axis (07-08/09).** Wakefulness gate and cortisol with negative feedback.
  Last bug: homeostatic stimuli backlog; batching fix dropped SLEEP rate 44% → ~1%.
- **JEPA wired as the RPE baseline, retrained as `unified_critic` (VICReg + EMA)** (#67, #68,
  07-10). Prometheus/Grafana observability added (#66).

## Phase D — Experiments: memory vs world model (07-09 → 07-17)

| Date | Experiment | Result |
|---|---|---|
| 07-09 | `memory_vs_wm_v1` (scarce world) | JEPA (no consol.) survives longer than baseline (441 vs 290 s, p<0.001). Consolidation *hurts* (315 s). Memory filter does **not** help (237–261 s). |
| 07-13 | `rotten_fruit_v1` | JEPA +112% survival, JEPA+Consol +33%. **No aversion learning in any condition** (rotten share ≈ chance). Consolidation hurts in a novel world. |
| 07-14 | `dense_reposition` (abundant food) | JEPA advantage **vanishes** (p≈1); memory penalty vanishes too. Strategy sophistication matters only under scarcity. |
| 07-17 | `dense_scarce` | JEPA advantage **inverts** (24/50 JEPA deaths vs 0/50 baseline). Later shown to be confounded (see Phase E). |
| 07-17 | `dense_no_reposition` (corrected re-run) | Once cognition runs at the right rate, JEPA+Consol has a real, robust edge (123 s vs 74–87 s); memory shows no penalty. Lifetimes short. |

Infra in the same window: experiments moved to one Ansible playbook + YAML specs, replacing
46 compose files (#69, 07-13); CCAD (Singularity/SLURM) became the cluster target
(#71, #72). Experiment data goes to HuggingFace.

## Phase E — Infrastructure confounded the results (07-27 → 08-06)

- **CCAD node c1 stall (07-27 → 07-29).** `dense_scarce` creatures on some nodes ran 6–40×
  slower. Three causes fixed in sequence: thread-pool oversubscription (#74), one JPA
  EntityManagerFactory per actor (#75), synchronous Postgres writes on the cognition path
  (#76, real culprit). Postmortem: `docs/postmortems/ccad-node-c1-cognitive-cycle-stall.md`.
  Lesson: the 07-17 headline results were measured at an unintended cognition rate.
- **#77 OOM (07-30).** Async persistence had no backpressure → bounded `BDActor` (#78).
- **#79 biological clock (07-31 → 08-02).** The fix made cognition 10–20× faster, and since
  metabolism advanced per cognitive cycle, creatures died in ~1 min. Fix: clock decoupled
  from cycle count (pacemaker, dt-weighted metabolism).
- **Persistence rewrite (#79, #82, #83; 08-01 → 08-04).** JPA/Postgres removed entirely. After
  a Parquet attempt, the final path is off-heap **Arrow IPC** + DuckDB extraction. Validated
  locally and on CCAD (`20260804_arrow_ipc_write_path_validation_report.md`).
- **#85 (08-06).** Cognitive cycle measured at ~9× the nominal 30 Hz and perception flickered.
  Cycle is now tick-gated; integration tests run the full actor system in CI.

## Phase F — Behaviour parity with Mapa/Campos, and the memory rework (08-04 → 08-12)

Issue #84; recipe `docs/experiments/p84_behaviour_parity_recipe.md`; PR #92 (merged 08-12).
Why: before writing up JEPA, show the current architecture still behaves like the published
ones. We have neither paper's code nor data (ALifeWorld 0.9.5 / 0.7.5.1 were later recovered
as source — see `docs/legacy/`, `docs/plans/mapa-alifeworld-port-to-dl2l.md`), so only shapes
and ratios compare.

- **First campaign (08-05).** Predictions P1/P4 (memory advantage) refuted. Cause: memory
  ranked actions by *sum* of engram value → frequency, not quality (#88): memory creatures
  ate 3.6× less, died 27% sooner (p=7e-11).
- **Memory rework (08-07 → 08-10).** Mean not sum; memory picks the *object*, operant
  conditioning picks the *action*; engrams tagged with object and drive; separate consummatory
  store; retention bounded per key. Cluster now waits for all roles before starting.
- **CCAD quota exhaustion (08-05, 08-11).** Raw dumps filled the disk quota; fixed with
  incremental collection, dropping `stimulus_state` (60% of the dump), salvaging truncated
  dumps.
- **Final campaign (08-12).** 6 arms × 16 trials × 5 creatures = 480 creatures, 10/10 schema
  gates passing (`20260812_p84_behaviour_parity_report.md`). Confirmed: P2, P3, D2 (memory
  forms and is decisive on 27–67% of consultations). P4 confirmed only in the legacy pair at
  1.11× (Campos: 6.7×). P1, P5 inconclusive; S1 untestable under replenishment.
- **Bugs found by the campaign (all open):** #90 legacy/current arms confounded on chain
  arbitration; #91 engram value tagged with the *dominant* drive (99.95% sleep → memory prefers
  zero-calorie food); #93 eligibility applied twice (effective half-life 2.5 cycles, not 5);
  consolidation threshold 0.1 unreachable (max observed 0.031), so consolidation is dead code.

## Phase G — Tooling and reproducibility (10-07)

No simulation work since 08-12. On 10-07: PR #94 (open, plan only) proposes a shared
experiment-toolkit repo (spec → run → collect → analyse → report); issue #95 notes no trial
can be replayed because randomness is unseeded.

---

## State as of 2026-10-17

**Open, needs a decision:** #91 and #93 (memory correctness — fix before re-running p84 memory
arms), #90 (arms confounded), #88/#84/#85 (still open though largely addressed by the rework
and tick-gate; verify and close), #87 (p84 PR, superseded by merged #92?), #47 (stale), #95
(seeding), #94 (toolkit plan), #80 (blocking TypedActor accessors), #70 (image distribution),
#65 (full-architecture validation with consolidation, never run post-rework), #24 (cue
competition).

**Cross-cutting lessons:**
1. Most "negative JEPA" results traced to our own pipeline bugs (adapter init, dropped WANDER
   samples, CCAD stall, cycle-coupled clock), not to the idea. Check instrumentation before
   interpreting a result.
2. JEPA's edge depends on scarcity and on cognition running at the intended rate; it is not a
   uniform win.
3. Consolidation has never shown a clean benefit (hurt in familiar/novel worlds; helped only in
   `dense_no_reposition`). The memory consolidation threshold is currently unreachable.
4. Aversion learning (rotten fruit) has not been demonstrated in any condition.
