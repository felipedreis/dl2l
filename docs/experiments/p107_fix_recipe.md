# Experiment recipe — A0 and A1 with the affordance zero-weight fix, against the pilot's A0 / A1

Plan: [`affordance-zero-weight-fix.md`](../plans/affordance-zero-weight-fix.md) · Spec: `experiments/p107_pilot_fix.yml` · Data: `ml/data_p107_pilot_fix`, HF `p107_pilot_fix/`
Code: branch `fix/affordance-zero-weight` (image `preview-affordance-zero-weight`). Pre-registration: written before any run.

## 1. Question

Does the all-zero-weight fallback explain why A0 starves (15 of 15 creatures dead within about 5 minutes) and why A1 regulates worse than A2?

## 2. Design

`a0f` (`arbiter_a0.conf`) and `a1f` (`p107_gate2_a1.conf`, focus thresholds 0.4 / 0.15) on the fixed image; 3 trials, seeds 10701-10703, 5
creatures, 120-minute cap, CCAD. Compared by seed with the pilot's `a0` and `a1` (old image, same confs, same seeds; Java otherwise identical
apart from this fix and the commits already in `feature/focus-theta-config`). The pilot's `a2` and the control `a2u` do not use the filter and
are shown as references.

## 3. Reading the result (fixed now)

Measures per trial (mean of 5 creatures): d(H) over life, deaths, EATs per minute, RED+GREEN EATs per minute, APPROACH share of cycles.
- **The fix is material for A0:** d(H) over life of `a0f` is lower than `a0` by at least 0.5 in all three seeds.
- **It explains the starvation:** at most 5 of 15 `a0f` creatures die (the pilot's A0: 15 of 15).
- **It explains A1's gap to A2:** `a1f` d(H) over life within 0.06 of A2's (the margin #107 proposes) in all three seeds.
- Anything else is reported as it is; no p-values at 3 pairs.

## 4. Procedure

1. `mvn test` green; push `fix/affordance-zero-weight` and `preview/affordance-zero-weight`; wait for the image and pin its `sha-` tag.
2. Submit to CCAD (`-e dl2l_image=...`), collect with `-e rescue=true`, upload by hand (`dl2l_data.upload`; the playbook skips it), check `run_info` and 15 creatures per arm observed to their death or to about 7,195 s.
3. `PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p107_pilot_fix`.

## 5. Predictions (before data)

The fix lowers the APPROACH share of A0 / A1 from about 64% toward the A2 level and raises feeding. Whether A0 stops starving is genuinely open: if the
dithering hypothesis is right it may still starve, because the unchanged `RANDOM` filter still switches targets every cycle.
