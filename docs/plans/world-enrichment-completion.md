# Plan: World Enrichment Completion

## Context

PR #39 implemented all three world-enrichment features (ROTTEN_APPLE, CACTUS/ALOE, OBSERVE
action, full tedium regulation). Those changes are on the current branch. However two gaps
remain before JEPA experiments can benefit from the richer world:

1. Experiment simulation configs still only spawn RED_APPLE and GREEN_APPLE — no creature
   ever encounters ROTTEN_APPLE, CACTUS, or ALOE, so no pain/tedium signals appear in the
   training data.
2. `EmotionalSystemActor` still labels PAIN (index 4) and TEDIUM (index 5) as "placeholder"
   in code comments, contradicting `model_contract.json` (`live_emotion_dims: [0,1,4,5]`).

A third gap — WorldModelEngine / WorldModelFilter / model_contract.json don't encode OBSERVE
or the new object types — is deferred because it is coupled to offline model retraining:
changing `action_dim` or `input_dim` without a matching retrained `.pt` file causes an
`IllegalStateException` at startup.

---

## Changes

### 1 — Fix EmotionalSystemActor comments

**File:** `creature/components/EmotionalSystemActor.java`

Change the in-line comments on PAIN and TEDIUM from "placeholder" to "live".  No logic
change; purely corrects the misleading annotation that conflicts with the model contract.

### 2 — Update simulation configs to include enriched world objects

All `baseline_*` and experiment `exp_*` simulation configs need ROTTEN_APPLE, CACTUS, and
ALOE added alongside the existing apple types.  Quantities should be proportional to world
area so density is consistent across configs regardless of world size:

- Determine world area for each config; compute a per-unit density from `basic.conf`
  (ROTTEN_APPLE ≈ 6.25e-4/unit², CACTUS ≈ 3.75e-4/unit², ALOE ≈ 2.5e-4/unit²).
- Round to nearest 5 to keep configs readable; floor at 5 for small worlds so at least
  a handful of each object type exists.

Configs to update:
- `simulations/baseline_1node_1creature.conf` (no worldSize → defaults to 8000×6000)
- `simulations/baseline_1node_{2..10}creature.conf`
- `simulations/baseline_2node_10creature.conf`
- `simulations/baseline_2nodes.conf`
- `simulations/baseline_3node_10creature.conf`
- `simulations/baseline_4node_10creature.conf`
- `simulations/baseline_5node_10creature.conf`
- `simulations/exp_1_1.conf` through `exp_3_5.conf`
- `simulations/exp_p4_1_eligibility_traces.conf`
- `simulations/exp_p5_1_sleep_consolidation.conf`
- `simulations/exp_p6_1_mode2_selection.conf`
- `simulations/phase2_1node_10creature.conf`
- `simulations/exp1_1node_6creature.conf`

For configs that list an explicit `worldSize`, derive quantities from that area.
For configs without `worldSize`, use the default 8000×6000.

---

## Deferred (requires offline model retraining)

The following changes must wait until a new JEPA model is trained on enriched data:

| Component | Change needed | Why deferred |
|---|---|---|
| `model_contract.json` | Add `"type_ROTTEN_APPLE"`, `"type_CACTUS"`, `"type_ALOE"` to `perception_feature_order`; increment `input_dim` from 6 to 9; add `"OBSERVE"` to `action_index_order`; increment `action_dim` from 9 to 10; update `model_hash` | Hash validation + tensor shape mismatch at inference time without matching `.pt` files |
| `WorldModelFilter.encodePerception` | Add one-hot slots for ROTTEN_APPLE, CACTUS, ALOE | Needs matching `input_dim` in contract |
| `WorldModelEngine.ACTION_ORDER` | Append `ActionType.OBSERVE` | Engine validates contract `actionIndexOrder.size() == ACTION_ORDER.length` at startup |

---

## Acceptance Criteria

- `mvn package` compiles clean.
- `mvn test` — all tests pass.
- Every simulation config that previously had only RED_APPLE and GREEN_APPLE now also
  includes ROTTEN_APPLE, CACTUS, and ALOE with quantities proportional to world area.
- `EmotionalSystemActor` comments correctly label PAIN and TEDIUM as "live".
