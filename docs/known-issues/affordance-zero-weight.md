# Known issue: the affordance filter returned the first candidate when every weight was 0

**Status:** fixed on `fix/affordance-zero-weight` (2026-11-08), no flag. Results produced before the fix with the filter in the chain are
**potentially affected**; the ones named below as *withdrawn* are not to be cited. Plan: [`../plans/affordance-zero-weight-fix.md`](../plans/affordance-zero-weight-fix.md).

## What happened

`ActionProbabilityFilter` (`AFFORDANCE`, the legacy learned action-probability table) samples one action per target object type from learned weights.
Only EAT is ever reinforced, so for a red or green apple seen at a distance (candidates APPROACH, AVOID, SLEEP, OBSERVE, WANDER, no EAT) every weight is 0,
the normalisation gave NaN and the filter returned the first candidate, APPROACH, every time. Regression test: `ActionProbabilityZeroWeightTest`.
Whether this is what made the A0 baseline starve is **not established**; the fixed-vs-old comparison is `docs/experiments/p107_fix_recipe.md`.

## Withdrawn (do not cite)

- The pilot's reading of the ladder (`docs/reports/p107_pilot_report_skeleton.md`, the plan's and diary's pilot entries): that A1 -> A2 is explained by
  removing the filter chain, that the tracker alone rescues A0, and the A0-vs-A1 survival and regulation contrasts. The numbers describe the buggy A0 / A1,
  not the arbiter ladder as designed. A2, the A2 control and the threshold tuning do not use the filter and stand.
- The sample-size rows that involve A0 or A1 (`docs/reports/p107_pilot_sample_size.md`).
- The p97 conclusion that the feeding collapse is "a property of the world with unrewarding gray apples" and not of the code (`docs/reports/20261018_p97_feeding_fix_report.md`): the filter's fixed-APPROACH fallback is a candidate cause.

## Potentially affected (size of the effect unknown, not re-validated)

Every other result from an arm whose `enabledFilters` contain `AFFORDANCE` (the default chain does). Memory and world-model arms are affected too, because
they choose among the candidates the affordance filter leaves. Comparisons *between* arms that share the filter are less exposed than absolute levels, but not immune.

| Experiment spec | Arms using the filter | Arms not using it |
|---|---|---|
| `20260709_memory_vs_wm_v1` | 1_baseline, 2_memory_only, 3_memory_consolidation, 4_jepa_rpe_only, 5_jepa_rpe_consolidation | - |
| `20260714_memory_vs_wm_dense_reposition` | 1_baseline, 2_memory_only, 3_memory_consolidation, 4_jepa_rpe_only, 5_jepa_rpe_consolidation | - |
| `20260714_memory_vs_wm_dense_reposition_backfill_baseline` | 1_baseline | - |
| `20260714_memory_vs_wm_dense_reposition_backfill_jepaconsol` | 5_jepa_rpe_consolidation | - |
| `20260717_memory_vs_wm_dense_no_reposition` | 1_baseline, 2_memory_only, 3_memory_consolidation, 4_jepa_rpe_only, 5_jepa_rpe_consolidation | - |
| `20260728_tick_rate_diagnostic` | 1_baseline | - |
| `_validation_dense_reposition` | 1_baseline | - |
| `arrow_ipc_write_path_ccad_validation` | 1_baseline | - |
| `ci_regression_benchmark` | ci_bench | - |
| `p100_k_check` | a0 | - |
| `p104_a2_check` | - | a2_arbiter |
| `p104_a2_stress` | - | scarce_regrow, finite_food |
| `p105_decision_check` | - | scarce_regrow, finite_food |
| `p107_pilot` | a0, a1 | a2 |
| `p107_pilot_ctl` | - | a2u |
| `p107_pilot_fix` | a0f, a1f | - |
| `p107_pilot_gate2` | a1 | a2, a2_finite_food |
| `p107_theta_tune` | - | t080, t060, t050, t040 |
| `p110_a0_check` | a0_operant | - |
| `p79_ccad_baseline_validation` | 1_baseline | - |
| `p79_metabolic_rescale` | 1_rescaled | - |
| `p79_tick_rate_measurement` | 1_baseline | - |
| `p84_behaviour_parity` | legacy_nomem, legacy_mem, current_nomem, current_mem, legacy_nomem_simple, legacy_mem_simple | - |
| `p84_pilot` | legacy_nomem, legacy_mem | - |
| `p84_rerun_current_mem` | current_mem | - |
| `p84_rerun_current_nomem` | current_nomem | - |
| `p84_rerun_legacy_mem_simple` | legacy_mem_simple | - |
| `p84_rerun_legacy_nomem_simple` | legacy_nomem_simple | - |
| `p97_feeding_fix` | legacy_nomem | - |
| `p97_feeding_fix_v2` | legacy_nomem | - |
| `p97_seed_replay` | run_a, run_b | - |
| `rotten_fruit_v1` | 1_baseline, 2_memory_only, 3_memory_consolidation, 4_jepa_rpe_only, 5_jepa_rpe_consolidation | - |
| `smoke` | smoke | - |

Specs not listed use the arbiter selector. Reports that carry a notice: see the first line of each.
