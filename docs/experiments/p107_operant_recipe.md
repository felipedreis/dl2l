# Experiment recipe — A2 with the per-outcome operant table, against the same table frozen, in two worlds

Plans: [`arbiter-proposal-review.md`](../plans/arbiter-proposal-review.md), [`operant-per-outcome.md`](../plans/operant-per-outcome.md)
Spec: `experiments/p107_operant.yml` · Data: `ml/data_p107_operant`, HF `p107_operant/` · Code: `feature/focus-theta-config` (image from `preview/focus-theta-config`).
Pre-registration: written before any run. A changed step is changed here first.

> **Revision 1 (2026-11-10, before the re-run).** The first run used the magnitude of the drive reduction as the operant sample. In the scarce world
> (all six trials done) the table learned (R1 held: updates in every `sn` trial, none in `sf`) but could not tell eating a gray apple (sample 0) from an
> untried action (prior 0): gray share of EATs 0.93-0.94 in both arms, decisive in under 0.2% of decisions; all 30 creatures died in both arms. The
> pilot-world trials were cancelled at about 57 minutes. Data kept on CCAD as `~/l2l/data/p107_operant_magnitude_superseded` (not uploaded, not analysed
> further). The sample is now the valence (+1 / -1, Mapa and the legacy table; review D3). Everything else below is unchanged and the four arms are re-run.

## 1. Question

Does the operant table, now trained after each outcome like the legacy table (keyed by object type and action), change what A2 does, and does it
help regulation where food is scarce? The previous A2 / A2u pilot tested the old, episode-trained table and is superseded.

## 2. Design

Four arms, 3 trials each, seeds 10701-10703 (the pilot's), 5 creatures, CCAD:
- `a2n`: A2 (`p107_gate2_a2.conf`: pilot world, 120 min, focus thresholds 0.4 / 0.15), per-outcome operant table.
- `a2f`: the same with `operantLearningEnabled = false` (table frozen at its prior; the new control; `focusThetaOn = 99` no longer stops learning).
- `sn`: A2 in the scarce world of p104 (`p104_a2_scarce_regrow.conf`: 10 red + 10 green apples respawning after 60 s, 300 gray, 30 min), thresholds 0.4 / 0.15.
- `sf`: `sn` with the table frozen.

## 3. Readings (fixed now; per seed, no p-values at 3 pairs)

Pilot world (`a2n` vs `a2f`):
- **R1 (it learns):** every `a2n` creature has rows in `operant_updates`; `a2f` has none. If R1 fails, nothing else is read: it is a bug.
- **R2 (it changes choices):** the gray share of EATs is lower in `a2n` than in `a2f` in all three seeds.
- **R3 (decisiveness):** the share of multi-candidate decisions with operant decisiveness >= 0.8 is higher in `a2n` in all three seeds.
- **R4 (regulation):** d(H) over life within +-0.06 of `a2f` in all three seeds (equivalent; expected, since the untrained arbiter already regulates near the floor).

Scarce world (`sn` vs `sf`):
- **H1 (learning helps regulation where it is hard):** d(H) over life lower in `sn` by at least 0.06 in all three seeds, or fewer deaths in all three. Anything else: not shown.

## 4. Procedure

1. `mvn test` green; push the branch and `preview/focus-theta-config` (image trigger); pin the `sha-` tag.
2. Submit to CCAD; collect with `-e rescue=true`; upload by hand with `dl2l_data.upload`; check `run_info` seeds and observed times.
3. `PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p107_operant`.

## 5. Predictions (before data)

R1, R2 and R3 hold (with the valence, eating a gray apple is a steady -1, below the prior of untried actions); R4 holds (equivalent). H1 is open; my prior is a small or no effect, because the table only has evidence about EATs
(gray apples carry no food) while the scarce world mainly needs finding food, which the operant table cannot value.
