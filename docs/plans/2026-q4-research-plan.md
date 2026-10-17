# Research plan, 2026-10-17 → 2026-12-31

Status: agreed 2026-10-17. Context: `docs/research-diary.md`; the arbitration draft in
`docs/roadmap/20261016_Uncertainty-arbitrated_action_selection_for_emotion-drive_artificial_creatures.pdf`.

## Goal

One submitted paper by year's end: uncertainty-based arbitration with MFEC-style episodic
memory in dl2l, built one change at a time against a validated baseline. In parallel, and
without blocking it, start the advisor's track: a rewrite of ALifeWorld that reproduces the
Campos (2015) and Mapa (2009) results, in a separate repository.

## Decisions

1. **Neuromodulation is off** for the baseline (`neuromodulationEnabled`, `expectancyEnabled`,
   `orexinEnabled`, `endocrineEnabled` = false; they are already default-off flags, nothing is
   deleted). Reason: none of it is in the published lineage, it was never studied, and it is
   why hunger never binds in the current stack (#90: dopamine flattens the operant table, 66%
   of choices fall through to RANDOM, hunger peaks at 1.07 of 7).
2. **Baseline A0 = the p84 legacy configuration** (action tendency on, neuromodulation off),
   where hunger binds (mean 2.44, max 7.0), plus the fixes listed under Track A.
3. **The arbiter is independent of neuromodulation**: constant Thompson width, τ = 1.
   Neuromodulation can return later as one ladder step that only modulates τ.
4. **Old architectures are lineage, not baselines** for the paper. Comparisons are against
   modern controllers (operant/habit, MFEC episodic control, fixed-order chain, random).
5. **Fair effort**: published or principle-fixed constants are recorded in the
   pre-registration; any tuning gets the same budget (configurations, held-out seeds, search
   method) for every arm, baseline included, and the report states it.
6. **The ALifeWorld rewrite lives in its own repository** (`felipedreis/alifeworld`, private),
   sharing tooling (Parquet tables, `dl2l_analysis`, HF upload, #94 toolkit), not code.

## Track A — dl2l paper (priority)

| Weeks | Step | Done when |
|---|---|---|
| 1–2 | **A0 baseline**: seeding (#95), eligibility applied once (#93), contact/overshoot bug (#96, feeding stops mid-life, see below) | Validation campaign: hunger binds, feeding sustained across life, trials replayable from a seed |
| 3–4 | **A1** focus tracker (episodes by attentional focus, hysteresis). **A2** arbiter with the operant table only, drive-reduction value V(K\|H) | A1: fewer focus switches, survival not lower. A2: regulation not worse, calibration in range |
| 5–6 | **A3** + MFEC episodic controller, fixed hand-built key (λ = 0.5). **A5** same controllers in fixed order | Pre-registered; A3 vs A5 is the clean arbitration contrast |
| 7–8 | Campaigns and analysis. In parallel: pre-registered 200-world NumPy multi-context run | Reports per CLAUDE.md step 5 |
| 9–10 | Write | Submitted |

Outcomes beyond survival: regulation (mean deficit), feeding rate, and at least one
behavioural signature from the draft's §8.2 if time allows (devaluation first).

**Close as superseded:** #84, #85, #88, #90 (neuromodulation off), #91 (per-drive outcome
vectors fix it by design); abandon branch `claude/issue-91-drive-specific-memory-valuation`.
**Parked:** JEPA (ladder step A6, needs critic retargeted and retraining on post-fix data),
neuromodulation as τ modulation (A7, with emotion-as-precision weights).

### Finding behind the A0 contact fix (2026-10-17, p84 `legacy_nomem`, 80 creatures)

Eating works when chosen (3,728 of 3,932 EAT decisions produce an EAT), but feeding stops
at ~40% of life: EATs per life decile 974, 1074, 903, 587, 152, 28, 9, 1, 0, 0. Speed follows
behavioural efficiency and rises 3 → ~10 as arousal approaches 3.5; EAT share falls from
0.3% at speed 3 to 0.0% at 9–11, while 90% of approaches target food already within the
mouth radius (10). EAT is afforded only at `perception.distance == 0`
(`FullAppraisal.java:538`); the likely cause is stepping past the food at 6–10 units per step.
Script: `analysis/diagnostics/p84_approach_check.py`.

## Track B — advisor (paced, must not block Track A)

- Repository `felipedreis/alifeworld`: original 0.7.5.1 (Campos) and 0.9.5 (Mapa) sources,
  legacy analyses, dissertations.
- Rewrite from the papers, with the original code as an oracle where a paper is ambiguous;
  every paper-vs-code discrepancy recorded and, where it matters, run both ways.
- Order: Campos 2015 (memory 6.7× survival), then Mapa 2009 (1,850 → 4,700 cycles).
- Realistic by year's end: Campos spec and reproduction under way; Mapa in early 2027.

## Risks

- Two tracks in ten weeks. If Track A slips, Track B pauses, not the reverse.
- Effects may be small (draft §7.1: 0.06 on a deficit of 0.6); behavioural signatures make
  the stronger claim.
- The advisor may not accept dl2l as the substrate; the A0 validation campaign is the answer
  to that concern and must be reported in the paper.
