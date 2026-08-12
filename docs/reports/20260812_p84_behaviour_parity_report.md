# Behaviour parity with Mapa (2009) and Campos (2015)

Issue: [#84](https://github.com/felipedreis/dl2l/issues/84)
Recipe: [`docs/experiments/p84_behaviour_parity_recipe.md`](../experiments/p84_behaviour_parity_recipe.md)
Data: `felipedreis/dl2l-experiments` prefix `p84/` · image `ghcr.io/felipedreis/dl2l:sha-da1763c`
Campaign: 6 arms × 16 trials × 5 creatures = **480 creatures**, collected 2026-08-11/12, all ten schema gates passing.

---

## Purpose

Establish whether the current architecture still behaves like the published versions it
descends from — Mapa (2009) and Campos et al. (2015) — before the JEPA results are written up.
We have neither their code nor their data, so the comparison is qualitative in shape and
quantitative only in ratios.

The campaign also became the test bed for a substantial rework of the memory subsystem, which
the first run of this experiment forced: memory as it stood made creatures **eat 3.5–45× less
and die sooner**, in every arm pair.

---

## Assumptions

Restated from the recipe because several of them determine what the results can mean:

1. **Absolute times are not comparable to the papers.** `time` is `System.currentTimeMillis()`
   and our ms-per-cycle moves with host load. Only shape and ratios carry across.
2. **`GRAY_APPLE` (caloricValue 0) stands in for Mapa's balls/toys**, and only EAT is counted —
   `MouthInteractionState` is written solely for `EnergeticStimulus`.
3. **Replenishment is on**, unlike Mapa. This is what makes several creatures per trial sound,
   and it is also why S1 cannot be tested (§ Analysis).
4. **Creatures share a world.** They cannot perceive each other, but the clustering is real and
   every test is run at both creature and trial level.
5. **Arms do not share an observation window.** The legacy pair runs to death (~250 s, 80/80);
   the other four never terminate on the reworked build and are capped at 30 minutes. Both
   members of every pair share a cap, so within-pair comparisons hold; **counts are not
   comparable across pairs, rates are.**
6. **The `current_*` arms are confounded** on chain arbitration and have no hunger pressure —
   [#90](https://github.com/felipedreis/dl2l/issues/90).

---

## Hypothesis

| ID | Claim |
|---|---|
| **P1** | Memory shortens the interaction interval, and the gap widens with k (Mapa Fig. 47) |
| **P2** | With memory, RANDOM is displaced as memory engages (Campos Fig. 5/6) |
| **P3** | Nearest and Affordances are used similarly with and without memory |
| **P4** | Memory extends life; ratio compared against Campos's 6.7× |
| **P5** | Time alive rises with interaction count, memory above no-memory (shape only) |
| **S1** | In the all-rewarding world the interval decreases monotonically, unlike the mixed world |
| **D1** | Learned APPROACH share against Mapa's 0.25/0.40/0.70 |
| **D2** | Memory forms, is increasingly used and increasingly decisive; use tracks survival |

---

## Results

### Memory raises the feeding rate in all three pairs

Rate, not count, because the arms terminate differently (assumption 5).

| pair | no-memory | memory | Cliff's δ | creature-level | trial-level |
|---|---|---|---|---|---|
| legacy | 11.31 | **13.37** | +0.718 | p=4.4e-15 | p=1.5e-06 ✓ |
| current | 73.50 | **88.68** | — | p<1e-15 | p=1.5e-06 ✓ |
| simple | 39.07 | **41.01** | — | p=0.001 | p=0.004 ✓ |

All `consistent` — creature-level and trial-level agree, so this is not within-trial
pseudo-replication.

### P2 — confirmed, and it is the strongest parity result

RANDOM collapses to near zero wherever memory is enabled, and the operant table absorbs it:

| pair | RANDOM (no-mem → mem) | AFFORDANCE (no-mem → mem) |
|---|---|---|
| legacy | 0.283 → **0.008** | 0.536 → 0.748 |
| current | 0.668 → **0.003** | 0.228 → 0.873 |
| simple | 0.668 → **0.048** | 0.288 → 0.773 |

This is Campos's central claim — random choice stops being needed once memory engages — and it
reproduces in every pair, `consistent` at both levels.

### P3 — confirmed

`TARGET_DISTANCE` share is essentially unchanged by memory: 0.180 → 0.176 (legacy, p=0.34),
0.104 → 0.124 (current). Nearest is used the same way with and without memory, as Campos reports.

### P4 — confirmed in the only pair that can test it

| pair | mortality | KM median | log-rank |
|---|---|---|---|
| legacy | 80/80 vs 80/80 | 243 s → **269 s** (1.11×) | χ²=15.8, **p=0.0001** |
| current | 0/80 vs 0/80 | beyond cap | no events |
| simple | 0/80 vs 0/80 | beyond cap | no events |

Memory extends life, but at **1.11×** against Campos's published **6.7×** — we reproduce the
direction, not the magnitude. The other two pairs cannot test P4 at any cap: their creatures do
not die (#90).

### Diet composition — memory helps where hunger binds, and harms where it does not

| arm | gray (0 cal) share | calories per EAT |
|---|---|---|
| `legacy_nomem` | 53.6% | 0.166 |
| `legacy_mem` | **41.1%** | **0.213** |
| `current_nomem` | 56.9% | 0.153 |
| `current_mem` | **64.0%** | **0.128** |

In the legacy stack memory cuts worthless-fruit intake and raises nutrition. In the current
stack it does the opposite — see Analysis.

### P1, P5, S1 — not decidable in this design

Mean interaction interval is flat: 2.204 vs 2.217 s (legacy, p=0.64). The simple pair shows
2.708 vs 2.317 but reads **clustering-sensitive**, so it is not claimed.

### D2 — memory forms, is used, and is decisive

Memory influences 27–41% of consultations in the legacy pair, 60–64% in the simple pair and
**65–67%** in the current pair, with >90% of consultations returning more than one action —
i.e. memory narrows to an object and leaves the action to the operant table, as designed.

---

## Analysis

### The memory rework is what made these results possible

The first run of this campaign found memory to be actively harmful. Reading both source papers
against the implementation identified three structural divergences, all now fixed:

1. **Both papers are stochastic where memory meets choice; we were a deterministic argmax.** An
   argmax over a store written by its own choices has a fixed point. Selection is now
   proportional to remembered value (Campos's rule).
2. **Mapa separates object-choice from action-choice; we had collapsed them.** `MemoryFilter`
   returned a single action and ended the filter chain before the filters that would have
   chosen EAT ever ran. It now picks an *object* and hands every action on it to the operant
   table — which is exactly what P2's AFFORDANCE absorption shows happening.
3. **Approach traces were drowning the consummatory signal.** EAT engrams discriminate fruit by
   caloric value at **6.3×**; APPROACH engrams manage **1.09×**, because an approach's outcome
   depends on what happens next and the trace credits every recent approach indiscriminately.
   Approaches outnumbered consummatory acts 114:1, collapsing 6.3× to 1.14×. Consummatory
   engrams now have their own store, and retention is bounded *per (action, object) key* so
   common experience expires against itself rather than against everything else.

### Why memory harms the current stack — issue #91

The current stack is the one case where memory makes the diet worse, and the cause is
measurable. An engram records `emotionDelta` against **whichever drive dominates at decision
time**. In the current stack that is sleep (mean 2.60) rather than hunger (0.31):

| drive the engram is measured against | count | share |
|---|---|---|
| sleep | 85,143 | **99.95%** |
| hunger | 37 | 0.043% |

And the value it records depends entirely on which:

| object | caloric value | under hunger (n=37) | under sleep (n=85,143) |
|---|---|---|---|
| GREEN_APPLE | 0.5 | **+0.219** | −0.0008 |
| RED_APPLE | 0.2 | **+0.112** | −0.0005 |
| GRAY_APPLE | **0.0** | +0.0002 | **+0.0003** |

Under hunger the discrimination is **1103× and correctly ordered by calories**. Under sleep —
99.95% of the evidence — it inverts, and the zero-calorie fruit becomes the only positively
valued food. The architecture *can* learn the right thing and almost never gets the chance.

The legacy stack works because hunger dominates there (~68% of engrams are hunger-tagged), so
the correct signal is what gets recorded. The fix is drive-specific valuation — scoring an
object from engrams matching the drive being regulated — which is what Campos actually
specifies (his LTM value is a *deprivation difference*).

### Three criteria are neutralised by design decisions, not by results

This is the most important caveat in the report, and each was found from the data:

| criterion | why undecidable | issue |
|---|---|---|
| **S1** (and P1's "widens with k") | **Replenishment.** Mapa ran a *depleting* world, where each consumed object makes the next harder to find — that growth *is* her Fig. 47 shape, and the unrewarding interactions she credits for its oscillation modulate a trend depletion creates. Our density is stationary, so the curve has nothing to trend with. Measured: no monotonic trend in any of four arms, all p>0.2. | recipe §7 |
| **P4** in `current_*`/`simple` | Creatures never die at any cap — max hunger 1.13 of a lethal 7.0 in the current stack. | [#90](https://github.com/felipedreis/dl2l/issues/90) |
| **P3/P2 across stacks** | The legacy/current pair is confounded on **chain arbitration**, not the emotion→action mechanism it was designed to isolate. `ActionTendencyFilter` narrows the candidate set; neuromodulation only reweights within it and cannot substitute. AFFORDANCE decides 51.7% of choices in legacy against 23.1% in current. | [#90](https://github.com/felipedreis/dl2l/issues/90) |

A null result on any of these is **not** evidence against the architecture.

### Verdict against the source figures

| ID | Verdict |
|---|---|
| **P1** | **Inconclusive** — interval flat; the "widens with k" clause needs a depleting world |
| **P2** | **Confirmed** in all three pairs, `consistent` |
| **P3** | **Confirmed** — Nearest unchanged by memory |
| **P4** | **Confirmed** in the legacy pair (p=0.0001) at 1.11× vs Campos's 6.7×; untestable elsewhere |
| **P5** | **Inconclusive** — shape only, and the non-terminating arms cannot contribute |
| **S1** | **Untestable** under replenishment |
| **D1** | Descriptive — see `f6_conditioning.png` |
| **D2** | **Confirmed** — memory forms, is consulted, and is decisive on 27–67% of consultations |

---

## Figures

All in `docs/reports/figures/p84_behaviour_parity/`:

`f1_interaction_interval.png` · `f2_time_alive.png` · `f3_cumulative_selections.png` ·
`f3b_criterion_shares.png` · `f4_cumulative_first1000.png` · `f5_lifetime.png` ·
`f6_conditioning.png` · `m1_formation_vs_use.png` · `m2_consultation_outcome.png` ·
`m3_decision_confidence.png` · `m4_engram_quality.png` · `m6_memory_use_vs_survival.png`

---

## Follow-ups

- [#90](https://github.com/felipedreis/dl2l/issues/90) — arms confounded on arbitration; current stack has no hunger pressure
- [#91](https://github.com/felipedreis/dl2l/issues/91) — engram value conditioned on the dominant drive
- **S1 needs a depleting single-creature arm** (`reposition = false`) to be testable at all
- `MEMORY_CONSOLIDATION_THRESHOLD = 0.1` is **unreachable** at the current delta scale (largest
  observed group mean 0.031), so consolidation is silently dead code
- `manifest.json` now records the image tag and commit; the ansible pipeline still does not
