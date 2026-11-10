# Experiment recipe — A2 under three action-tendency variants, learning vs frozen operant table, two worlds

Plan: [`arbiter-proposal-review.md`](../plans/arbiter-proposal-review.md) · Spec: `experiments/p107_tendency.yml` · Data: `ml/data_p107_tendency`, HF `p107_tendency/`
Code: `feature/focus-theta-config` (`actionTendencySet = CAMPOS | MAPA`, `actionTendencyEnabled`). Pre-registration: written before any run.

## 1. Question

In `p107_operant` the per-outcome operant table learned correct values but made A2 regulate worse: a hungry creature at an object can only
EAT or WANDER under dl2l's tendency filter (Campos's lists: hunger = eat, approach, wander), so learners lingered next to gray apples they
would not eat. Mapa (ALifeWorld 0.9.5) gives every emotion approach, avoid, eat, sleep, wander, touch, play. Does learning help once AVOID is
available, with Mapa's lists (MAPA) or with no tendency filter (NONE)? And how do those arms compare with the current CAMPOS arms?

## 2. Design

Eight new arms, 3 trials each, seeds 10701-10703, 5 creatures, CCAD; pilot world (120 min) and scarce world (30 min), as `p107_operant`:
`mn` / `mf` (MAPA, learning / frozen), `xn` / `xf` (NONE), and the scarce-world `smn`, `smf`, `sxn`, `sxf`. The CAMPOS arms are the `p107_operant`
data (`a2n`, `a2f`, `sn`, `sf`, same seeds, same code path). Everything else is A2 as in `p107_operant` (focus thresholds 0.4 / 0.15; valence sample).

## 3. Readings (fixed now; per seed; no p-values at 3 pairs)

For MAPA and NONE separately, learning vs frozen:
- **R1:** every learning creature has `operant_updates` rows; frozen arms have none (else: a bug; stop).
- **R2:** gray share of EATs lower with learning in all three seeds.
- **R4 (pilot world):** d(H) over life of the learning arm not worse than frozen by more than 0.06 in all three seeds.
- **H1 (scarce world):** d(H) over life lower with learning by at least 0.06 in all three seeds, or fewer deaths in all three.

Across variants:
- **V1:** the MAPA or NONE learning arm has lower d(H) over life than the CAMPOS learning arm (`a2n`; `sn` in the scarce world) in all three seeds.
- Reported, not criteria: SLEEP, AVOID and APPROACH shares of decisions, the share next to a known-bad gray apple, decisiveness, survival.

## 4. Predictions (before data; from `analysis/diagnostics/tendency_variants_sim.py`)

R1 and R2 hold for both variants (the simulation: gray share 0.65 -> 0.24 with MAPA learning, 0.59 -> 0.21 with NONE). SLEEP takes about 30% of decisions
in MAPA and NONE (0% in CAMPOS) and APPROACH is diluted, so V1 fails (both regulate worse than CAMPOS). Confidence is low: the same simulation
predicted that learning helps CAMPOS, and the cluster showed the opposite.

## 5. Procedure

1. `mvn test` green; push; `preview/focus-theta-config` (image trigger); pin the `sha-` tag.
2. Submit to CCAD; collect with `-e rescue=true`; upload by hand (`dl2l_data.upload`, python3.13); check `run_info` and observed times.
3. `PYTHONPATH=analysis python3.13 -m dl2l_analysis --experiment p107_tendency`.
