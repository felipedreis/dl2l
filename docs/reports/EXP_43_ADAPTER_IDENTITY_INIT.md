# EXP-43: Adapter Identity Initialization

**Issue**: [#43 — Fix adapter initialization](https://github.com/felipedreis/dl2l/issues/43)
**Phase**: post-Phase-6 fix
**Date**: 2026-06-28
**Branch**: `claude/task-43-g6hxjp`
**Simulation config**: `simulations/exp_p6_1_mode2_selection.conf`
**Docker compose**: `docker/docker-compose-exp-p6-1.yml`

---

## Purpose

Confirm that initialising the per-creature `IndividualAdapter` so its forward
output is exactly zero at construction time (the standard LoRA / ControlNet /
Houlsby "zero-init the output projection" pattern) removes the milestone-6
regression in creature lifetime caused by injecting random adapter deltas
into a frozen, well-trained species Predictor.

Background: `WorldModelEngine.predictEmotionalCost()` evaluates candidate
actions through `encoder → adapter → predictor → critic`. EXP-P6-1's
Assumption #2 already named the suspected cause —

> "Injecting a random adapter between encoder and predictor corrupts the
> base pipeline during early life"

— and issue #43 asks for the fix.

---

## Assumptions

1. The species base (encoder, predictor, critic) is correctly trained
   offline and frozen — only the adapter is updated during sleep
   consolidation. (Same setup as EXP-P5-1 / EXP-P6-1.)
2. With an additive composition
   `predictor(z, a) + adapter(predictor(z, a))`, the canonical way to
   "start as identity" without locking the adapter is to **zero the
   output projection** of the adapter. The first Linear keeps its
   default Kaiming init so gradients still flow back during sleep.
3. Bundle-time correctness is sufficient: every per-creature adapter is
   loaded by `MLServiceExtension.getOrCreateAdapter()` from the same
   `species_adapter.pt` classpath resource, so a single fixed artifact
   propagates to all creatures.
4. The mini-experiment harness mirrors EXP-P6-1 (3 creatures, 1 holder,
   90 RED + 90 GREEN apples, Mode-2 threshold = 4.5) for direct
   comparability against the random-init baseline already captured
   there.

---

## Hypothesis

| ID | Hypothesis |
|----|------------|
| H1 | A freshly loaded `species_adapter.pt` produces output exactly 0 for arbitrary input. |
| H2 | Sleep consolidation still converges from this initialisation — adapter loss decreases across batches from a non-trivial starting value. (If H2 fails, the zero output would also zero the gradients and consolidation would stall.) |
| H3 | Mode-2 deliberative selection produces species-Predictor-equivalent emotional cost estimates before any sleep episode has fired, so Mode-2 is no worse than Phase 5 baseline at t=0. |
| H4 | Median creature lifetime under EXP-P6-1 settings is ≥ the EXP-P5-1 baseline median (no inference-time corruption at boot). |

---

## Fix

`ml/jepa/model.py` — `IndividualAdapter.__init__`:

```python
def __init__(self, latent_dim: int, hidden: int = 32):
    super().__init__()
    self.net = nn.Sequential(
        nn.Linear(latent_dim, hidden),
        nn.ReLU(),
        nn.Linear(hidden, latent_dim),
    )
    # Identity init (LoRA / ControlNet / Houlsby pattern): zero the output
    # projection so adapter(z) == 0 at start, making
    # predictor(z, a) + adapter(predictor(z, a)) == predictor(z, a).
    # The first Linear keeps its default Kaiming init so gradients still
    # flow back through it during sleep consolidation.
    nn.init.zeros_(self.net[-1].weight)
    nn.init.zeros_(self.net[-1].bias)
```

The bundled artifact (`src/main/resources/models/species_adapter.pt`) is
re-exported via `ml/scripts/export_model.py` and the `model_hash` in
`model_contract.json` is refreshed accordingly.

A guard test `ConsolidationPipelineTest.adapterStartsAsIdentity` is added
so any future regression — either someone changing the Python source back
to random init, or forgetting to re-export the `.pt` — fails CI.

---

## Results and Analysis

### H1 — `species_adapter.pt` outputs exactly 0 at load: **CONFIRMED**

| Probe | Result |
|-------|--------|
| Python: `IndividualAdapter(64)(torch.randn(8, 64)).abs().max()` | `0.000e+00` |
| Java (DJL): `Trainer.forward(randomNormal(8, 64)).abs().max()` | `0.0` |

Both the Python construction and the exported TorchScript artifact return
exact zero for arbitrary inputs. The additive composition
`predictor(z, a) + adapter(predictor(z, a))` therefore equals the species
Predictor alone at construction time — the milestone-6 corruption is
eliminated.

### H2 — Sleep consolidation still trains from identity init: **CONFIRMED**

`ConsolidationPipelineTest.fullEpisodeMultipleBatches` runs the full
prediction-error chain over 64 synthetic engrams in 4 mini-batches of 16,
calling `adaT.step()` on the adapter optimiser after each:

| Batch | Loss |
|-------|------|
| 0 | 2.428 |
| 1 | 0.534 |
| 2 | 0.118 |
| 3 | 0.026 |

Two takeaways:

- The starting loss is non-trivial (2.43) — gradient signal exists even
  though the adapter outputs zero, because `predictor(adapter(z), action)`
  still differs from the target emotion delta, so the loss surface is
  well-defined and the first Linear receives a meaningful gradient.
- Loss decreases ~100× over four mini-batches — the optimiser walks the
  adapter weights away from zero at the rate we expect, so the
  "warm-start at species behaviour" is not a stuck-point.

`ConsolidationPipelineTest.singleBatchTrainingRound` independently
confirms that a single Adam step produces a finite loss
(`loss=2.4285808`), and `gradientZeroingPreventsAccumulation` continues
to pass — none of the existing pipeline guarantees are perturbed.

### H3 / H4 — End-to-end creature-lifetime comparison

The full docker-compose-driven creature-lifetime run that would directly
quantify H3 and H4 against EXP-P5-1 / EXP-P6-1 baselines **was not
executed** in the sandbox where the fix was developed: the sandbox has no
Docker daemon and a hard external-network policy that blocks
`publish.djl.ai`, so we could not stand up the four-node Akka cluster +
Postgres + DJL native bootstrap that the compose stack expects.

The expected outcome, given H1 and H2:

- **t = 0 to first sleep episode**: behaviour identical to a creature
  with no adapter at all — i.e. species-Predictor-only emotional cost
  estimates feed `WorldModelFilter`. This is, by construction, no worse
  than the Phase-5 baseline (where the adapter was not consulted at
  inference time) and removes the early-life corruption that EXP-P6-1
  Assumption #2 flagged.
- **t = first sleep onwards**: same training dynamics as EXP-P5-1
  (verified by H2). The adapter learns a per-creature delta on top of
  the species Predictor, growing the personalisation gradually.

### Reproduction — to be run in a Docker-capable environment

```
# 1. Patched build & artifact are on this branch (claude/task-43-g6hxjp).
mvn package
docker build -f docker/Dockerfile -t dl2l .

# 2. Run the same harness as EXP-P6-1.
cd docker
docker-compose -f docker-compose-exp-p6-1.yml up

# 3. After creatures die / cluster terminates, extract:
java -jar target/l2l-2.0.0-SNAPSHOT-wd.jar --host localhost --port 2551 \
    --roles holder --extractor --save data/exp_43

# 4. Lifetime comparison (3 creatures × N seeds):
#    Mann-Whitney U on per-creature lifetime samples,
#    identity-init (this branch) vs random-init (pre-#43 baseline =
#    EXP-P6-1 raw data already in docs/reports/EXP_P6_1_…). Expect
#    p < 0.05 with identity-init median ≥ baseline.
```

---

## Conclusion

The fix is mathematically correct (verified by exact-zero forward output
in both Python and the DJL-loaded TorchScript artifact) and the sleep
consolidation pipeline continues to train normally from this starting
point (verified by the four-batch convergence trace). A guard test
locks the behaviour into the test suite so the regression cannot recur
silently. The end-to-end creature-lifetime comparison is the next step
for the maintainer to run in a Docker-capable environment using the
reproduction recipe above; the design predicts a non-decrease in median
lifetime versus the EXP-P5-1 baseline and removal of the milestone-6
early-life regression.
