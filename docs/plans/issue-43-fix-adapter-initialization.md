# Issue #43 — Fix adapter initialization

## Context

Milestone 6 wired the per-creature `IndividualAdapter` into the inference path
(`encoder → adapter → predictor → critic`) inside
`WorldModelEngine.predictEmotionalCost()`. The adapter is meant to learn an
**additive delta** on top of a frozen, well-trained species Predictor; its
weights are updated only during sleep consolidation.

The exported `src/main/resources/models/species_adapter.pt` is currently
produced from a fresh `IndividualAdapter(latent_dim)` constructed in
`ml/scripts/export_model.py:54` with PyTorch's default Kaiming init. Result:
on **every creature's first life-tick**, and for many ticks before sleep
consolidation has done useful work, the adapter injects random noise into
the predictor input, corrupting Critic predictions used by the Mode-2
`WorldModelFilter`. EXP-P6-1's assumption #2 explicitly named this
("Injecting a random adapter between encoder and predictor corrupts the
base pipeline during early life") — issue #43 is the request to remove
the corruption.

Goal: initialise the adapter so its output is exactly zero at construction
time. The additive composition then equals the species Predictor exactly,
and sleep consolidation grows the delta from zero — the canonical
LoRA / ControlNet / Houlsby "zero-init the output projection" pattern.

## Approach

### 1. Zero-init the adapter's output projection

File: `ml/jepa/model.py` — `IndividualAdapter.__init__` (around line 89).
Keep the first `nn.Linear(latent_dim, hidden)` with its default Kaiming
init so gradients have a useful direction to flow; zero the **weight and
bias** of the final `nn.Linear(hidden, latent_dim)`:

```python
def __init__(self, latent_dim: int, hidden: int = 32):
    super().__init__()
    self.net = nn.Sequential(
        nn.Linear(latent_dim, hidden),
        nn.ReLU(),
        nn.Linear(hidden, latent_dim),
    )
    # Identity init: zero the output projection so adapter(z) == 0 at start.
    # predictor(z, a) + adapter(predictor(z, a)) then equals predictor(z, a),
    # and gradients still flow back through the first Linear during sleep.
    nn.init.zeros_(self.net[-1].weight)
    nn.init.zeros_(self.net[-1].bias)
```

This is the only behavioural source change. Every downstream consumer
already constructs the adapter through this class (Python export script,
both training script and tests import `IndividualAdapter`).

### 2. Regenerate the bundled TorchScript artifact

Run from `ml/`:

```
python -m scripts.export_model --ckpt checkpoints --out ../src/main/resources/models
```

This rewrites:
- `src/main/resources/models/species_adapter.pt` (the file Java loads)
- `src/main/resources/models/model_contract.json` (the `model_hash` field —
  computed in `ml/jepa/export.py:_sha256_files` over all four `.pt` files —
  will change)

Both files are tracked binaries; they need to be committed together.

### 3. Add a Java guard test

The `.pt` artifact is what production loads, so the regression guard
belongs in Java. Add to
`src/test/java/br/cefetmg/lsi/l2l/creature/ml/ConsolidationPipelineTest.java`
a new `@Test` next to the existing `adapterForwardShape()` (line 207):

```java
/** Identity init: freshly loaded adapter must output ~0 (issue #43). */
@Test
void adapterStartsAsIdentity() throws Exception {
    try (ZooModel<NDList, NDList> ada = loadTrainable("species_adapter");
         Trainer trainer = ada.newTrainer(adapterConfig());
         NDManager mgr = NDManager.newBaseManager()) {
        NDArray z       = mgr.randomNormal(new Shape(8, contract.latentDim));
        NDArray out     = trainer.forward(new NDList(z)).singletonOrThrow();
        float  maxAbs   = out.abs().max().getFloat();
        assertTrue(maxAbs < 1e-5f,
                "adapter output must be ~0 at construction, got " + maxAbs);
    }
}
```

This catches both "we forgot to re-export" and "we changed the init back to
random" regressions.

### 4. Mini experiment

- **Configuration**: reuse `simulations/exp_p6_1_mode2_selection.conf` and
  `docker/docker-compose-exp-p6-1.yml` (same harness as EXP-P6-1 — 3
  creatures, 1 holder, 180 fruits — for direct comparison).
- **Hypothesis (H1)**: median creature lifetime with identity-init adapter
  is ≥ EXP-P5-1 baseline median (no inference-time corruption from the
  random adapter).
- **Hypothesis (H2)**: sleep consolidation still works — adapter loss
  decreases across sleep episodes from a non-trivial starting value (the
  first Linear is still randomly initialised, so loss > 0 initially).
- **Sample**: 3 creatures × 3 seeds = 9 lifetime samples per condition
  (current `random-init` baseline already captured in EXP-P6-1; new
  `identity-init` runs with the patched build). Use Mann-Whitney U
  (small-sample, non-parametric) on lifetimes.
- **Analysis & report**: `docs/reports/EXP_43_ADAPTER_IDENTITY_INIT.md`
  with sections Purpose / Assumptions / Hypothesis / Results and
  Analysis, plus a lifetime CDF figure under `docs/reports/figures/`.
  Reuse the existing extractor (`--extractor --save`) and an analysis
  script alongside the existing `analysis/` Python 3 scripts.

## Files touched

| File | Change |
|------|--------|
| `ml/jepa/model.py` | Zero-init final Linear in `IndividualAdapter` (≈4 lines) |
| `src/main/resources/models/species_adapter.pt` | Regenerated by export script |
| `src/main/resources/models/model_contract.json` | Regenerated `model_hash` |
| `src/test/java/br/cefetmg/lsi/l2l/creature/ml/ConsolidationPipelineTest.java` | New `adapterStartsAsIdentity()` test |
| `docs/reports/EXP_43_ADAPTER_IDENTITY_INIT.md` (+ figure) | New mini-experiment report |

No Java production code changes — `MLServiceExtension.getOrCreateAdapter()`,
`WorldModelEngine`, and `MemoryConsolidator` already do the right thing
once the artifact is fixed.

## Verification

1. `mvn package` — clean build, fat jar produced.
2. `mvn test -Dtest=ConsolidationPipelineTest` — all four existing tests
   plus the new `adapterStartsAsIdentity()` pass.
3. `mvn test` — full suite green.
4. `cd docker && docker-compose -f docker-compose-exp-p6-1.yml up` — run
   the mini-experiment, extract data via the `--extractor` mode, run the
   analysis script, write the report.
5. Confirm in the report that median lifetime is statistically ≥ the
   EXP-P5-1 / EXP-P6-1 baseline (Mann-Whitney p reported).
