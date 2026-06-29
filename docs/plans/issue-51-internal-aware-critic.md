# Issue #51 — Internal-Aware Critic (fix SLEEP bias)

## Problem

EXP-48B (dual-encoder, PR #50) showed 94.4% SLEEP rate — no improvement over the 94.6% baseline.
Root cause confirmed: the Critic receives `(z_next[64], action[9])` but NOT `z_internal[16]`.
It cannot distinguish "SLEEP when hungry" from "SLEEP when well-fed", so the TD signal never
corrects the bias.

## Fix

Pass `concat(z_next[64], z_internal[16])` → 80-dim vector to the Critic instead of just `z_next`.

### `ml/jepa/model.py` — `DualSpeciesModel`

- Instantiate `Critic(combined_latent_dim, ...)` instead of `Critic(latent_dim, ...)`
- In `forward()`: `emotion = self.critic(torch.cat([z_next, z_internal], dim=-1), a_t)`

### `ml/jepa/export.py`

- For dual-encoder, trace Critic with `dummy_z_critic = zeros(1, latent_dim + internal_latent_dim)`
  instead of `dummy_z_world = zeros(1, latent_dim)`.

### `creature/ml/WorldModelEngine.java`

- Hoist `zInternal` variable out of the if block.
- After predictor call: `criticInput = concat(nextLatent, zInternal)` when dual-encoder.

### `creature/ml/ConsolidationPipelineTest.java` — `runBatch()`

- Hoist `zInternal` out of the if block.
- Pass `concat(nextZ, zInternal)` to critic when `contract.hasDualEncoder`.

## Model re-export

Added `ml/scripts/export_fresh_dual.py` to regenerate `src/main/resources/models/` with the
updated Critic architecture (random weights — for shape correctness only). The real model
weights come from a full training run (see Training Plan below).

## Training Plan

1. **Collect data** — run `docker-compose-train-p7.yml` (holder+detector on same JVM, 6-object
   world with CACTUS/ALOE now collidable from PR #50). Extract to `data/train_p8/`.

2. **Prepare dataset** — `ml/scripts/prepare_dataset.py --data data/train_p8 --dual`

3. **Train** — `ml/scripts/train_species.py --dual --ckpt checkpoints/exp_b2`

4. **Collapse check** — `ml/scripts/check_collapse.py --dual --ckpt checkpoints/exp_b2`

5. **Export** — `ml/scripts/export_model.py --dual --ckpt checkpoints/exp_b2`

6. **Build** — `mvn package` (90/90 tests must pass)

7. **Validate** — run `docker-compose-exp-48-val.yml`, extract, compare SLEEP rate vs.
   EXP-48 baseline (94.6%). Target: < 20%. Report in `docs/reports/EXP_51_INTERNAL_CRITIC.md`.

## Acceptance criteria

- [x] Critic takes `concat(z_next, z_internal)` in training and inference
- [x] `mvn package` passes 90/90 tests
- [x] `ConsolidationPipelineTest` passes for dual-encoder
- [ ] Training run + experiment shows statistically significant SLEEP rate reduction
- [ ] Report written to `docs/reports/EXP_51_INTERNAL_CRITIC.md`
