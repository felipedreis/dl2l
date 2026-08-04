# Behaviour parity with the previous architecture versions (issue #84)

> **Dependency.** This work branches off `claude/arrow-ipc-read-path-pr2`, not `main`. That
> branch carries `_dedup_creatures` in `scripts/dl2l_data/extract.py`, which collapses the
> birth+death row pair `CreatureState` writes per creature. Without it the `creatures` table has
> two rows per creature and every lifetime statistic — i.e. the P4/P5 survival claims below — is
> silently wrong. The read-path branch must land before, or together with, this one.

## Context

Before we run the JEPA experiment for publication, we need evidence that the current
architecture still behaves like the published versions it descends from — Mapa (2009) and
Campos et al. (2015). We have neither their code nor their data, so the comparison is
qualitative in shape and, where they published numbers, quantitative in ratio.

Reading both papers back (`docs/bib/suelenmapa.pdf` §6.4–6.5,
`docs/bib/2015_Campos_Concurrent_Minimalist_Agent.pdf` §V–VI) against our data path shows the
issue actually contains **three** source figures, not two:

1. **Mapa Fig. 47** (§6.4) — mean interval (ms) to find and interact with an object, k = 1..10,
   with and without memory. *(issue image 1)*
2. **Mapa Fig. 50** (§6.5) — mean lifetime vs interaction count, with and without memory, run
   until death. *(issue images 3 and 4 — her survival experiment, 10 sessions, which is what
   "in another 10 trials" in the issue refers to)*
3. **Campos Fig. 5/6** — cumulative selections per criterion, over lifetime and zoomed to the
   first 1000 interactions, with and without memory. *(issue image 5)*

Two gaps block this, both in persistence:

- **The operant conditioning table is never persisted.** `OperantConditioningActor` is a plain
  in-memory object that `Valuation` mutates and discards.
- **`MemoryFilter` records nothing about its own decisions** — we can see *that* memory won a
  decision (`selection_type == MEMORY`) but not how much evidence it had.

Everything else the three figures need is already extracted.

Grooming decisions already made with the user (recorded on the issue):

- Run **both** a legacy-minimal arm and a current-default arm, so a mismatch is attributable.
- Mapa's balls/toys are substituted by `GRAY_APPLE`; only `EAT` interactions are counted.
- **Do not** replicate Mapa's low/medium/high initial conditioning sweep. Instead plot the
  *learned* conditioning trajectory and read off how close to her "high" level we get.
- Match Campos's object **density** when sizing Mapa's (unstated) world.
- Pilot locally, run the full campaign on CCAD; power-reduce Campos's n if runs are too slow.
- Add a **consolidation arm** to the Campos spec only.
- Add a **`MemoryDecisionState`** diagnostic table.

## What the source setups actually specify

| | Mapa §6.4 (Fig. 47) | Mapa §6.5 (Fig. 50) | Campos (Fig. 5/6) |
|---|---|---|---|
| Agents | 1 | 1 | 1 |
| Objects | 12 red + 18 green apples + 25 balls = 55 | 15 red + 20 green apples + 10 stones + 10 bees + 30 toys = 85 | 20 red + 20 green + 60 gray = 100 |
| World | not stated | not stated | **860 × 720 px** |
| Vision | not stated | not stated | radius **150 px**, opening 70° |
| Replenishment | none | none | same-kind fruit at a random position |
| Metric | interval between interaction k−1 and k | lifetime vs interaction count, to death | cumulative selections per criterion |
| Repetitions | 20 sessions | 10 sessions | 50 realizations |
| Published numbers | ±24 % low-vs-high conditioning | — | lifetime **1.4×10⁴ s** (σ 7.8×10³) with memory vs **2.1×10³ s** (σ 3.1×10²) without |

Three facts make this tractable:

- `Constants.DEFAULT_VISION_FIELD_RADIUS = 150` — **exactly** Campos's figure. Our vision
  geometry is inherited from that lineage, so matching density in pixel units is meaningful.
  (Our opening adapts in `[MIN_VISION_FIELD_OPENING=50°, 150°]` rather than Campos's fixed 70°
   — noted as an assumption, not corrected.)
- `SimulationManager`'s `Repose` handler (`SimulationManager.java:95-106`) spawns a new object
  of the same type at a random position on consumption — exactly Campos's replenishment rule.
  `reposition = false` gives Mapa's depleting world.
- **`MemorySystemActor` is created unconditionally** (`CreatureActor.java:132`), regardless of
  `enabledFilters`. The `MEMORY` filter gates only *use*. So engrams form and persist
  identically in the no-memory arms: **formation is a matched control and only evocation
  differs between arms.** This is a free within-experiment control and the backbone of the
  memory analysis below.

**World sizes.** Campos's world is used verbatim: **860 × 720** with 100 objects
= 6,192 px² per object. Mapa's 55 objects at that density need 340,560 px²; scaling Campos's
world by √(55/100) = 0.7416 preserves the aspect ratio and gives **638 × 534**
(= 6,194 px²/object, 0.03 % off). Sanity check: the vision cone at the 50° resting opening
covers (50/360)·π·150² ≈ 9,817 px², so ~1.6 objects are in view on average — non-degenerate.

**Survival.** Mapa's §6.5 world needs a tedium-regulating toy, which we do not have. So the
**quantitative** survival claim comes from the Campos spec (his published 6.7× ratio, and
`creatures.lifetime_s` is already extracted — zero extra work), and the Mapa spec additionally
yields a **shape-only** lifetime-vs-interaction curve on its own object mix. No third world.

## Work breakdown

### 1. Java — two new persistence tables (one PR)

#### 1a. `ActionProbabilityState` — the operant conditioning trajectory

Follow `ExpectancyState` exactly; it is the same shape of record, written from the same
component.

**New `creature/bd/ActionProbabilityState.java`** — plain `PersistenceState` (no JPA
annotations; the Arrow path is schema-driven), modelled on `ExpectancyState.java`:

| field | type | note |
|---|---|---|
| `creatureKey` | long | from `id.key`, as `ExpectancyState` does |
| `seq` | long | per-creature monotonic event counter, orders events within a cycle |
| `timeMs` | long | `System.currentTimeMillis()`, same clock as `ChangeStimulusState.time` |
| `cycle` | long | `memorySystem.currentDecisionCycle()` |
| `target` | String | `WorldObjectType.name()` |
| `action` | ActionType | which action this row's probability belongs to |
| `probability` | double | **raw** stored value after the update |
| `reinforcedAction` | ActionType | the action that was just evaluated |
| `delta` | double | signed increment applied to `reinforcedAction` this event |

`reinforcedAction`/`delta` are constant across an event's six rows but make the table
self-contained — the legacy arms have no `ExpectancyState` rows to join against.

Do **not** add an `experience` column: `ProbabilityBasedExperience.getExperience()` is dead
code (`incrementExperience()` is never called) and exposing it would mean widening the
`OperantConditioning` interface for a field that is always 0.

**`Valuation.java`** — one private helper emitting all six rows in a single `persist(...)`
call (array form, per `CreatureComponent.persist`'s atomicity contract), called immediately
after each `varyProbability`:

- `evaluateLegacy` (`Valuation.java:87`) — `delta = valence ? 1 : -1`
- `evaluateWithExpectancy` (`Valuation.java:118`) — `delta = rpe > 0 ? |rpe| : -|rpe|`

**Both call sites are mandatory** — the legacy-minimal (`L_*`) arms have expectancy disabled
and therefore run `evaluateLegacy`. `ExpectancyState` is written only on the expectancy path;
that is exactly the trap to avoid here.

#### 1b. `MemoryDecisionState` — what the memory filter actually had to go on

**`MemoryFilter.java`** — record the last decision in fields and expose
`Optional<Decision> takeLastDecision()` that **returns and clears** it, so a stale record can
never be persisted twice when the filter isn't consulted in a given cycle
(`ActionSelection.selectOne` breaks early once a filter narrows to one action, so `MemoryFilter`
is not reached every cycle — a row therefore means "memory was consulted", a meaningful
denominator).

Captured per consultation: `engramWindow` (engrams considered), `candidates` (incoming action
count), `scored` (candidates matching an engram), `winningScore`, `runnerUpScore` (NaN when
< 2 scored — the margin says how decisive it was), `decided` (single action returned vs
pass-through).

**New `creature/bd/MemoryDecisionState.java`** — `creatureKey`, `seq`, `timeMs`, `cycle`, the
six fields above, plus `action` and `target` (`SequentialId`, via the `seqCols` helper).

**`FullAppraisal.java`** — hold a `memoryFilter` field, assigned in the `case MEMORY ->` branch
of the `preStart` filter chain (`FullAppraisal.java:113`). This mirrors the existing
`affordanceFilter` (:108) and `worldModelFilter` (:116) fields exactly — no new coupling
pattern. At the `ChosenActionState` persist site (:310) call
`memoryFilter.takeLastDecision()` and persist a `MemoryDecisionState` when present.

#### 1c. Registration and tests

**`TableSchemas.java`** — one `all.add(table(...))` block per new entity, alongside
`expectancy_state` (~line 265), columns alphabetical after `id`, using the existing `seqCols`
helper for `MemoryDecisionState.target`. This is the only registration needed:
`BDActor.tableFor()` delegates to `TableSchemas.forState()`, `ArrowIpcBackend` iterates
`TableSchemas.ALL`, and extraction globs `*.arrow`.

**Tests** — extend `src/test/.../components/ValuationRpeTest.java` (or a sibling
`ValuationConditioningPersistenceTest`) plus a new `MemoryDecisionPersistenceTest`, both using
the existing `TestingHarness`: `h.inject(...)` then `h.bdSink().ofType(<State>.class)` —
`ExternalSink` already flattens `PersistenceState[]` for exactly this. Assert:

- six `ActionProbabilityState` rows per valuation event, one per `ActionType`, tagged with the
  evaluated target;
- rows are emitted on the **legacy** path too (build with `expectancyOff()`);
- the reinforced action's probability moved in the direction implied by valence;
- a `MemoryDecisionState` is emitted only when `MemoryFilter` was consulted, and
  `takeLastDecision()` does not re-emit a stale record on a cycle where it wasn't.

**Explicitly not changed:** `ActionProbability.varyProbability` clamps at 0 while
`OperantConditioningActor` applies the compensating `-delta/(n-1)` to the other five entries
unconditionally, so the table's sum is not conserved once any entry bottoms out.
`ActionProbabilityFilter` already hides this by normalising at selection time. Fixing it would
change the learning dynamics we are trying to validate — file a follow-up issue, and have the
analysis plot **normalised shares**.

### 2. Python — extraction (`scripts/dl2l_data/tables.py`)

Two new `TABLES` entries plus their names in `TABLE_ORDER`:

```sql
-- conditioning
SELECT creature_key, seq, time_ms, cycle, target, action,
       probability, reinforced_action, delta
FROM data.action_probability_state
ORDER BY creature_key, seq, action

-- memory_decisions
SELECT creature_key, seq, time_ms, cycle,
       engram_window, candidates, scored,
       winning_score, runnerup_score, decided,
       action, key AS target_key, sequential AS target_sequential
FROM data.memory_decision_state
ORDER BY creature_key, seq
```

Also widen two existing queries to carry object identity, which the Arrow schema already has
(`TableSchemas.java:161-179`) but the SQL drops:
- `perceptions` — add `oss.key AS object_key, oss.sequential AS object_sequential`
- `mouth_interactions` — add `mis.key AS object_key, mis.sequential AS object_sequential`

No other file needs touching — `extract.tables: all` picks new tables up automatically, and
`scripts/validate_experiment.py` validates names against `TABLES`.

### 3. Simulation configs and experiment specs

Arms, 1 creature each. No arm enables `WORLD_MODEL` — neither source architecture had one.

| Key | Label | Subsystems | `enabledFilters` |
|---|---|---|---|
| `L_nomem` | Legacy | circadian/consolidation/expectancy/neuromodulation/actionTendency/orexin/endocrine **all false** | `[TARGET_DISTANCE, AFFORDANCE, RANDOM]` |
| `L_mem` | Legacy+Mem | same | `[TARGET_DISTANCE, AFFORDANCE, MEMORY, RANDOM]` |
| `C_nomem` | Current | current default stack | `[TARGET_DISTANCE, AFFORDANCE, RANDOM]` |
| `C_mem` | Current+Mem | same | `[TARGET_DISTANCE, AFFORDANCE, MEMORY, RANDOM]` |
| `C_mem_consol` | Current+Mem+Consol | same **+ `consolidationEnabled = true`** | same as `C_mem` | 

`C_mem_consol` is **Campos spec only** — the Mapa world caps at 10 interactions, too few sleep
episodes for consolidation to do anything measurable. With no `WORLD_MODEL` in the chain,
`CreatureActor.java:157-168` selects `MemoryTraceConsolidator`, populating `memory_traces` and
the `consolidation_*` tables. It sits outside the P1–P3 comparisons, which are made **within**
the `L_*` and `C_*` pairs, so it cannot confound them.

**9 config files** `simulations/p84_{mapa,campos}_<arm>.conf`:

- Mapa world: `worldSize {width = 638, height = 534}`, 12 `RED_APPLE` / 18 `GREEN_APPLE` /
  25 `GRAY_APPLE`, `reposition = false`, `maxRuntimeMinutes` as a safety cap (set from pilot).
- Campos world: `worldSize {width = 860, height = 720}`, 20 `RED_APPLE` / 20 `GREEN_APPLE` /
  60 `GRAY_APPLE`, `reposition = true`, `repositionDelaySeconds = 0`.

All use `noUI = false` (project convention for local runs) and
`positionFactory = "br.cefetmg.lsi.l2l.world.RandomPositionFactory"`.

**4 spec files** (schema in `experiments/README.md`):

| Spec | Arms | Trials | Purpose |
|---|---|---|---|
| `experiments/p84_mapa_pilot.yml` | 4 | 2 | throwaway smoke — `upload.enabled: false` (the documented exception for pilots) |
| `experiments/p84_campos_pilot.yml` | 5 | 2 | ditto; also the run-to-death wall-clock measurement |
| `experiments/p84_mapa_interaction_interval.yml` | 4 | 20 | Mapa's session count. `image: {source: registry}`, `upload.prefix: p84` |
| `experiments/p84_campos_selection_criteria.yml` | 5 | from pilot power analysis, ≤ 50 | ditto |

Validate each with `python3 scripts/validate_experiment.py experiments/<name>.yml`.

### 4. Analysis

One shared addition to `analysis/dl2l_analysis/loading.py` (per the "Adding an analysis
capability" guidance in `experiments/README.md`):

```python
def interaction_intervals(mouth, creatures, max_k=None, interaction_type="EAT"):
    """Per (condition, trial, creature): intervals between successive interactions.
    Interval 1 is measured from born_time. Returns long-form
    (condition, trial, creature_key, k, interval_s, cumulative_s)."""
```

Everything else reuses what exists: `load_all`, `attach_born_time_and_ticks`,
`attach_elapsed_s`, `make_tick_rank_attacher`, `cond_stats`, `kruskal_test`,
`figures.save`/`plt`/`DECILE_LABELS`, `report.ReportBuilder`.

**`analysis/experiments/p84_mapa_interaction_interval.py`** — `run(cfg)`, structured like
`rotten_fruit_v1.py`:
- mean interval vs k = 1..10, one line per arm, error bars across trials — **in ms** (mirrors
  Mapa's axis) and **in decision cycles** (via `make_tick_rank_attacher`; machine-independent).
- cumulative time vs interaction count = Mapa Fig. 50's axes, shape-only (§ Survival above).
- `kruskal_test` on per-trial mean interval: `L_mem` vs `L_nomem`, `C_mem` vs `C_nomem`.
- P1 test: sign of (no-mem − mem) per k, and whether the gap grows with k (Spearman vs k).
- **Conditioning trajectory** (from `conditioning`): small multiples, one panel per target type,
  all six actions' **normalised** share over time (normalise per event as `p_i / Σp`, matching
  what `ActionProbabilityFilter` actually samples); plus a headline panel of normalised
  `APPROACH` share with reference lines at **0.25 / 0.40 / 0.70** = Mapa's low/medium/high.
  Descriptive only. This world depletes, so learning shows most cleanly here.

**`analysis/experiments/p84_campos_selection_criteria.py`** — `run(cfg)`:
- cumulative selections per `selection_type` over event index — full lifetime (Campos Fig. 5)
  and first-1000 zoom (Fig. 6), memory vs no-memory panels, for both `L_*` and `C_*`.
- P2 quantified: slope of the `RANDOM` curve in the last third vs the first third, per arm.
  Campos's claim is that this ratio collapses with memory and stays ≈ 1 without.
- P3 quantified: `TARGET_DISTANCE` and `AFFORDANCE` as a *fraction* of total selections,
  memory vs no-memory.
- P4 quantified: lifetime distributions per arm (`figures.boxplot_by_condition`), and the
  **mem/no-mem lifetime ratio** against Campos's published 6.7×. Ratio, not absolute seconds
  (see Assumptions).

**Memory mechanism figures** (shared module `analysis/experiments/p84_memory_common.py`,
imported by both, since the Mapa and Campos runs both need them):

- **M1 — formation vs use.** Cumulative engrams formed (`engrams`) and cumulative MEMORY-won
  decisions (`actions` where `selection_type == MEMORY`) on one cycle axis, per arm. Because
  formation is identical in the no-mem arms (`MemorySystemActor` is unconditional), the
  no-mem arm's engram curve is the matched control. This is a direct test of Campos's stated
  "memories form from the start but aren't used until ~interaction 150".
- **M2 — consultation outcome.** From `memory_decisions`: rate of `decided` vs pass-through
  per life decile, and `scored / candidates`. Shows memory going from "no opinion" to
  "decisive" as experience accumulates.
- **M3 — decision confidence.** `winning_score` and the winning-minus-runner-up margin,
  distribution per life decile. Distinguishes "fired confidently on many engrams" from "fired
  on one weak engram".
- **M4 — engram quality.** Mean `eligibility` and `emotion_delta` of engrams laid, and
  `cycle_gap`, per life decile.
- **M5 — consolidation** (`C_mem_consol` only): `consolidation_episodes` /
  `consolidation_batches` / `memory_traces` per trial, against lifetime and against M2's
  decisiveness — does consolidation make memory decide more often, or better?
- **M6 — is memory helpful?** Per-trial scatter of **lifetime against MEMORY-decision count**
  (and against MEMORY as a fraction of all decisions), pooled across arms, with a rank
  correlation. This is the single plot that most directly answers the question, and it uses
  the arms as natural variation rather than relying on the between-arm contrast alone.

### 5. Pilot and calibration gates

```bash
cd ansible
ansible-playbook -i inventories/local run-experiment.yml -e experiment=p84_mapa_pilot   -e analyze=true
ansible-playbook -i inventories/local run-experiment.yml -e experiment=p84_campos_pilot -e analyze=true
```

Gates before submitting the campaign:
1. `conditioning.parquet` and `memory_decisions.parquet` exist per trial, non-empty, with
   ≈ 6 conditioning rows per valuation event; conditioning rows **present in the `L_*` arms**
   (proves the legacy call site fires) and memory_decisions rows **absent from the `*_nomem`
   arms** (proves the filter really isn't in the chain).
2. `engrams` rows appear in the `*_nomem` arms too (confirms the matched-formation control).
3. Mapa arms reach ≥ 10 `EAT` interactions well inside `maxRuntimeMinutes`. If not, shrink both
   worlds by the same factor so the density match is preserved.
4. `C_mem_consol` produces non-empty `memory_traces` / `consolidation_*`.
5. Measure Campos per-trial wall-clock. Feed pilot variance of the P2 statistic (`RANDOM`
   late/early slope ratio) and P4 (lifetime) into a two-sample power calculation (α = 0.05,
   power = 0.8) to set `trials`, capped at Campos's 50. Report the computed n.
6. Both analysis modules run clean end-to-end on pilot data.

### 6. Campaign and report

```bash
cd ansible
ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=p84_mapa_interaction_interval
ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=p84_campos_selection_criteria
# later, repeatable:
ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=<name> -e rescue=true
```

Then `docs/reports/<date>_p84_behaviour_parity_report.md` with the mandated
Purpose / Assumptions / Hypothesis / Results / Analysis sections, all figures inlined.

## Acceptance criteria

Reported as *confirmed* / *refuted* / *inconclusive*, **separately for the `L_*` and `C_*`
arms**:

- **P1 (Mapa Fig. 47).** Memory arm's mean interaction interval ≤ no-memory arm's, and the gap
  widens with k.
- **P2 (Campos Fig. 5/6).** With memory, the `RANDOM` curve flattens once `MEMORY` starts being
  selected; without memory, no trend.
- **P3 (Campos).** `TARGET_DISTANCE` and `AFFORDANCE` used similarly with and without memory.
- **P4 (Campos, quantitative).** Memory arm's mean lifetime substantially exceeds no-memory's;
  compare the **ratio** against Campos's published 6.7× (1.4×10⁴ s vs 2.1×10³ s).
- **P5 (Mapa Fig. 50, shape only).** Lifetime rises with interaction count, memory arm above
  no-memory. Shape-only because our Mapa world lacks her tedium-regulating toys.
- **D1 (descriptive).** Normalised `APPROACH` share trajectory against Mapa's 0.25/0.40/0.70.
- **D2 (descriptive).** M1–M6: memory forms, is increasingly used and increasingly decisive,
  and memory use correlates with survival.

## Assumptions to restate in the report

- `GRAY_APPLE` (caloricValue 0) substitutes Mapa's balls and toys; only `EAT` is counted. We do
  not reproduce her tedium-regulating play, which she cites as the cause of the *oscillation* in
  Fig. 47 — divergence in oscillation is expected, not a parity failure. Her §6.5 survival world
  (stones, bees, toys) is likewise not reproduced; P5 is shape-only.
- Initial arousal stays at our defaults; Mapa's 0.18 / 0.0 / 0.36 are not configurable and no
  knob is being added.
- Our vision opening adapts in [50°, 150°]; Campos's was fixed at 70°. Radius matches exactly.
- Absolute wall-clock ms are not comparable across machines (see the p59/p79 tick-rate work).
  Only curve shape and **ratios** are compared; the cycle-indexed variants are the reproducible
  ones. This is why P4 is stated as a ratio.
- Our conditioning mechanism evaluates experiences (expectancy/RPE valuation) — architecturally
  different from Mapa's. Divergence in the conditioning trajectory is a **finding**, not a bug.
- The initial table stays at 25/25/25/25/0/0 over six actions; Mapa used five at 25 each with
  `play` non-zero.
- Consolidation exists in neither source architecture; `C_mem_consol` is an extension arm, not
  part of any parity claim.

## Files touched

**New:** `creature/bd/ActionProbabilityState.java`, `creature/bd/MemoryDecisionState.java`;
9 `simulations/p84_*.conf`; 4 `experiments/p84_*.yml`;
`analysis/experiments/p84_mapa_interaction_interval.py`,
`analysis/experiments/p84_campos_selection_criteria.py`,
`analysis/experiments/p84_memory_common.py`;
`docs/plans/behaviour-parity-legacy-architectures.md` (committed copy of this plan, per
CLAUDE.md); the report.

**Modified:** `creature/bd/TableSchemas.java`; `creature/components/Valuation.java`;
`creature/components/FullAppraisal.java`; `creature/actionSelector/MemoryFilter.java`;
`scripts/dl2l_data/tables.py`; `analysis/dl2l_analysis/loading.py`;
`src/test/.../ValuationRpeTest.java` + a new memory-decision test.

## Verification

1. `mvn package` — compiles clean, fat jar builds.
2. `mvn test` — **all** tests pass, including the new persistence assertions.
3. `python3 scripts/validate_experiment.py experiments/p84_*.yml` — all four validate.
4. Local pilot (§5) passes all six calibration gates; inspect
   `ml/data_p84_*/<arm>/trial_1/{conditioning,memory_decisions,engrams}.parquet` directly.
5. `PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p84_mapa_pilot` (and the Campos
   pilot) run clean and write figures.
6. CCAD campaign completes via submit/`rescue`; data lands on HF under `p84/`.

## Sequencing

The Java PR (§1) and extraction change (§2) must land before any run produces conditioning or
memory-decision data. §3 configs can be written in parallel. §4 analysis is drafted against
pilot data. Per the project's PR convention, §1's Java changes go through a PR; §2–§4 (Python,
specs, analysis) can go direct to main.
