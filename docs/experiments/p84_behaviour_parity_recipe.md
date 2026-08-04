# Experiment recipe — behaviour parity with Mapa (2009) and Campos (2015)

Issue: [#84](https://github.com/felipedreis/dl2l/issues/84)
Plan: [`docs/plans/behaviour-parity-legacy-architectures.md`](../plans/behaviour-parity-legacy-architectures.md)
Report: `docs/reports/<date>_p84_behaviour_parity_report.md` (written against this recipe)

This is the executable protocol. Every number in the report must be traceable to a step
here; if a step is changed, this file changes with it and the report says so.

---

## 1. Question

Does the current architecture still behave like the published versions it descends from?
We have neither their code nor their data, so the comparison is qualitative in shape and
quantitative only in ratios.

Three source figures:

| Ref | Source | Quantity |
|---|---|---|
| Mapa Fig. 47 (§6.4) | Mapa (2009) | mean interval to find and interact with an object, k = 1..10, with/without memory |
| Mapa Fig. 50 (§6.5) | Mapa (2009) | time alive at the k-th interaction, with/without memory |
| Campos Fig. 5/6 | Campos et al. (2015) | cumulative selections per selection criterion, whole life and first 1000 decisions |

Plus one number: Campos reports mean lifetime **1.4×10⁴ s** with memory against
**2.1×10³ s** without — a **6.7×** ratio.

Sources: `docs/bib/suelenmapa.pdf`, `docs/bib/2015_Campos_Concurrent_Minimalist_Agent.pdf`.

---

## 2. Design decisions and why

**One experiment, not two.** The three figures are different *measurements* of the same
runs, not different worlds. Mapa ran without replenishment and Campos with it, but the
quantity taken from Mapa is a foraging latency — how long a creature takes to find and
interact with the next object — and replenishment does not distort that. Splitting into
two experiments would double the compute to produce the same evidence.

**Replenishment on everywhere.** This is what makes several creatures per trial sound:
consumed fruit is replaced, so creatures sample in parallel instead of competing over a
draining world. Creatures cannot perceive each other in this build, so there is no other
coupling between them. Implemented by `reposition = true` with
`repositionDelaySeconds = 0`, which routes through `SimulationManager`'s `Repose` handler
— a same-kind object respawned at a random position, exactly Campos's rule.

**The creature is the replication unit, not the trial.** The aim is to observe the same
behaviour, not to reproduce either paper's sample-size procedure. Creatures within a trial
are nonetheless *clustered* (shared world, food supply, RNG stream), which the statistics
must handle rather than ignore — see §6.

**Density is held at Campos's published figures**, because he is the only one of the two
who states a world size (860×720 px, 100 fruits, one organism). That gives **6192 px² per
object** and **619,200 px² per creature**. Area and object count both scale with creature
count, so adding creatures adds sampling without changing what any one creature
experiences. Our `Constants.DEFAULT_VISION_FIELD_RADIUS` is already exactly his 150 px, so
matching in pixel units is meaningful.

**Two worlds.** Mapa explains that Silva's (2008) interval curve decreased monotonically
because he used a single rewarding object type, while hers oscillated because some
interactions were unrewarding. The `simple` arms turn that explanation into something
testable instead of assumed.

---

## 3. Conditions

Comparisons are read **within a pair** — the only thing differing across a pair is the
MEMORY filter. Never compare across stacks or worlds.

| Arm | Stack | Filters | World |
|---|---|---|---|
| `legacy_nomem` | legacy-minimal | TARGET_DISTANCE, AFFORDANCE, RANDOM | mixed |
| `legacy_mem` | legacy-minimal | + MEMORY | mixed |
| `current_nomem` | current default | TARGET_DISTANCE, AFFORDANCE, RANDOM | mixed |
| `current_mem` | current default | + MEMORY | mixed |
| `legacy_nomem_simple` | legacy-minimal | TARGET_DISTANCE, AFFORDANCE, RANDOM | simple |
| `legacy_mem_simple` | legacy-minimal | + MEMORY | simple |

- **legacy-minimal** = circadian, consolidation, expectancy, neuromodulation,
  actionTendency, orexin, endocrine all `false`. A mismatch here is attributable to the
  core architecture rather than to one of six later subsystems.
- **current default** = the `20260717_memory_vs_wm` baseline stack.
- **No arm enables `WORLD_MODEL`** — neither source architecture had one.
- **mixed** world = Campos's 20/20/60 red/green/gray. `GRAY_APPLE` has `caloricValue = 0`,
  so some interactions are unrewarding.
- **simple** world = red/green only, 50/50. Every interaction regulates hunger.

At 5 creatures: **1923 × 1610 px, 500 objects** (6192 px²/object, 619,206 px²/creature).

Engrams form in the `*_nomem` arms too — `MemorySystemActor` is created unconditionally
(`CreatureActor.java:132`) and the MEMORY filter gates only *use*. Formation is therefore
a matched control and evocation is the single difference within a pair.

---

## 4. Procedure

### 4.0 Prerequisites (once)

```bash
python3 -m pip install --break-system-packages pandas pyarrow duckdb
```

Extraction reads the raw Arrow dump through DuckDB. Without it a trial runs to completion
and *then* dies in the extract step, wasting the simulation time.

### 4.1 Build and test

```bash
mvn package            # must compile clean; produces the fat jar
mvn test               # all tests must pass
python3 scripts/validate_experiment.py experiments/p84_pilot.yml
python3 scripts/validate_experiment.py experiments/p84_behaviour_parity.yml
```

### 4.2 Sizing pilot — 2 arms × 3 trials × 3 creatures

```bash
cd ansible
ansible-playbook -i inventories/local run-experiment.yml -e experiment=p84_pilot -e analyze=true
```

Its job is to produce the numbers that size the campaign, and to prove the data path.
**Schema gates — all must hold before the campaign is submitted:**

| # | Gate | Why it matters |
|---|---|---|
| G1 | `conditioning.parquet` non-empty in `legacy_nomem` | expectancy is off there, so `ExpectancyState` writes nothing; this proves the *legacy* `Valuation` hook fires |
| G2 | exactly 6 conditioning rows per `seq`, one per `ActionType` | the whole table is snapshotted per reinforcement |
| G3 | conditioning events == EAT interactions | one reinforcement per object-directed interaction |
| G4 | `memory_decisions.parquet` **empty** in `legacy_nomem`, non-empty in `legacy_mem` | the MEMORY filter really is in/out of the chain |
| G5 | `engrams.parquet` non-empty in **both** arms | the matched-formation control holds |
| G6 | `creatures.parquet` has one row per creature | the birth+death dedup is applied; otherwise every lifetime statistic is wrong |
| G7 | every creature reaches ≥ 10 EAT interactions inside `maxRuntimeMinutes` | k = 1..10 is measurable; otherwise shrink the world, preserving density |

### 4.3 Size the campaign

The pilot analysis prints a sizing table per outcome: observed standardised effect,
intra-class correlation across trials, design effect, and the creatures/trials needed at
α = 0.05, power = 0.80. Set `trials:` in `experiments/p84_behaviour_parity.yml` to the
largest requirement among the outcomes actually being claimed, and **record the table in
the report**.

An outcome whose effect is near zero will demand an impractical n. That is a finding about
the effect, not a reason to keep adding trials.

**Executed 2026-08-04**, 3 trials × 3 creatures, `legacy_nomem` vs `legacy_mem`, real
post-pacemaker/OOM-fix data:

| outcome | d | ICC | trials needed |
|---|---|---|---|
| lifetime (s) | −0.078 | 0.000 | 977 |
| mean interaction interval (s) | +0.037 | 0.126 | 5483 |
| RANDOM share of decisions | −4.854 | 0.000 | 1 |
| AFFORDANCE share | −0.577 | 0.000 | 19 |
| TARGET_DISTANCE share | n/a (always 0) | — | — |
| RANDOM late/early ratio | +0.245 | 0.000 | 101 |

Lifetime and interval effects are indistinguishable from zero at this design — no
reasonable n resolves them, so P1/P4/P5 may legitimately come back *inconclusive* rather
than *confirmed*, and that is a finding, not a sizing failure (§9 has a plausible
mechanism: mean interval ≈ 6 s and mean lifetime ≈ 900 s here put creatures at only ~150
interactions per life, the same threshold Campos reports for memory to start dominating).
AFFORDANCE share is the one outcome with a tractable, moderate effect. **`trials` set to
8** — comfortable margin over AFFORDANCE's requirement, without chasing the others.

### 4.4 Campaign

```bash
cd ansible
ansible-playbook -i inventories/local run-experiment.yml -e experiment=p84_behaviour_parity -e analyze=true
# or, on CCAD (requires the CEFET VPN; submit-then-collect):
ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=p84_behaviour_parity
ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=p84_behaviour_parity -e rescue=true
```

Data uploads to `felipedreis/dl2l-experiments` under `p84/`. Never disable that.

### 4.5 Analysis

```bash
PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p84_behaviour_parity
```

---

## 5. Data → figure map

Six tables. Two of them (`conditioning`, `memory_decisions`) were added for this issue.

| Fig | Source | Plotted | Compared to |
|---|---|---|---|
| F1 | `mouth_interactions`, `creatures.born_time` | mean seconds between interaction k−1 and k, k = 1..10 | Mapa Fig. 47 |
| F2 | same | cumulative seconds alive at the k-th interaction | Mapa Fig. 50 |
| F3 | `actions.selection_type` | cumulative selections per criterion, whole life | Campos Fig. 5 |
| F4 | `actions` | same, first 1000 decisions | Campos Fig. 6 |
| F3b | `actions` | each criterion's share of a creature's decisions | Campos's frequencies |
| F5 | `creatures.lifetime_s` | lifetime per arm; memory/no-memory **ratio** | Campos's 6.7× |
| F6 | `conditioning` *(new)* | normalised APPROACH share over reinforcement events | Mapa's 0.25 / 0.40 / 0.70 |
| M1 | `engrams` + `actions` | cumulative engrams laid vs cumulative MEMORY-won decisions | Campos's "~150 interactions" |
| M2 | `memory_decisions` *(new)* | P(memory decides \| consulted) over life deciles | — |
| M3 | `memory_decisions` | winning score and margin over runner-up | — |
| M4 | `engrams` | eligibility / emotion delta / lay→reinforce gap | — |
| M5 | `consolidation_*`, `memory_traces` | consolidation activity vs lifetime | — |
| M6 | `actions` + `creatures` | lifetime against MEMORY-decision count | — |

Criterion names map one-for-one: Nearest → `TARGET_DISTANCE`, Affordances → `AFFORDANCE`,
Memory → `MEMORY`, Random → `RANDOM`.

**`conditioning.probability` is the raw stored value and must be normalised before
plotting.** `ActionProbability.varyProbability` clamps at 0 while
`OperantConditioningActor` applies the compensating `−delta/(n−1)` unconditionally, so a
target's raw sum drifts off 100. `ActionProbabilityFilter` normalises at selection time;
the analysis does the same, per `(target, seq)`.

---

## 6. Statistics

Lifetimes and intervals are strongly right-skewed, so everything is rank-based. No
t-tests, no ANOVA.

| Purpose | Test | Unit |
|---|---|---|
| Memory vs no-memory, per outcome | Mann-Whitney U | creature |
| Effect size | Cliff's delta (a monotone function of U, so it reports the same comparison the test does) | creature |
| All arms jointly | Kruskal-Wallis → pairwise Mann-Whitney, Bonferroni | creature |
| "Memory's advantage grows with k" | Spearman ρ of the (no-mem − mem) gap against k | k |
| "Interval decreases" (simple world) | Spearman ρ of mean interval against k | k |
| "RANDOM is displaced" | per-creature RANDOM rate, last third ÷ first third, then Mann-Whitney between arms | creature |
| Memory use vs survival | Spearman ρ | creature |

**Clustering.** Creatures inside a trial are not independent. Every primary test is run
twice — at creature level (all the data, the headline) and at trial level (immune to
clustering, low-powered). The intra-class correlation and design effect
`1 + (m−1)·ICC` are reported alongside.

- Both levels agree → the result is read as real (`consistent`).
- They disagree → reported as `clustering-sensitive` and **not** claimed; the effect is
  riding on within-trial pseudo-replication.

Implemented in `analysis/dl2l_analysis/stats.py` (`compare_arms`, `cliffs_delta`, `icc1`,
`design_effect`, `required_n`).

---

## 7. Claims and how each is decided

Each is reported *confirmed* / *refuted* / *inconclusive*, separately per arm pair.

| ID | Claim | Decided by |
|---|---|---|
| **P1** | Memory arm's interaction interval ≤ no-memory's, gap widening with k | F1 + Mann-Whitney on `mean_interval_s` + Spearman of gap vs k |
| **P2** | With memory, RANDOM is displaced as MEMORY engages; without memory, no trend | F3/F4 + Mann-Whitney on the late/early RANDOM ratio |
| **P3** | Nearest and Affordances used similarly with and without memory | F3b + Mann-Whitney on their shares |
| **P4** | Memory extends life; ratio compared to Campos's 6.7× | F5 + Mann-Whitney on `lifetime_s` |
| **P5** | Time alive rises with interaction count, memory above no-memory (**shape only**) | F2 |
| **S1** | In the all-rewarding `simple` world the interval decreases monotonically, unlike the mixed world | F1 + Spearman of interval vs k, `*_simple` vs mixed |
| **D1** | Learned APPROACH share against Mapa's 0.25/0.40/0.70 (**descriptive**, no pass/fail) | F6 |
| **D2** | Memory forms, is increasingly used and increasingly decisive; use tracks survival | M1–M6 |

---

## 8. Assumptions the report must restate

1. **Absolute times are not comparable to the papers.** `time` is
   `System.currentTimeMillis()` and our ms-per-cycle moves with host load and dispatcher
   sizing (see the p59/p79 tick-rate work). Only shape and ratios carry across; P4 is
   therefore stated as a ratio.
2. **`GRAY_APPLE` stands in for Mapa's balls/toys, and only EAT is counted.**
   `MouthInteractionState` is written solely for `EnergeticStimulus` (`Mouth.java:67`), so
   PLAY/TOUCH never reach the data. We do not reproduce her tedium-regulating play.
3. **Mapa's §6.5 survival world (stones, bees, toys) is not reproduced** — P5 is shape-only.
4. **Initial arousal stays at our defaults.** Her 0.18 / 0.0 / 0.36 are not configurable
   and no knob was added.
5. **Our vision opening adapts in [50°, 150°]**; Campos's was fixed at 70°. The radius
   matches exactly at 150 px.
6. **Our conditioning mechanism evaluates experiences** (expectancy/RPE valuation) —
   architecturally unlike Mapa's fixed step. Divergence in the conditioning trajectory is
   a finding, not a defect.
7. **The initial operant table is 25/25/25/25/0/0 over six actions**; Mapa used five at 25
   with `play` non-zero. Her low/medium/high initial-conditioning sweep is **not**
   replicated — F6 shows what is learned instead.
8. **Replenishment is on although Mapa ran without it** — justified in §2, and the reason
   several creatures can share a world.
9. **Creatures share a world**, unlike either single-agent source. They cannot perceive
   each other, and replenishment keeps per-creature food supply stationary, but the
   clustering is real and handled in §6.

---

## 9. Known limitation found during piloting

In the first real trial the selection types recorded were `RANDOM` (65,936) and
`AFFORDANCE` (11,284) — **`TARGET_DISTANCE` won zero decisions.**
`ActionSelection.selectOne` attributes a decision to a filter only when that filter alone
narrows the candidate set to one, and TargetDistance rarely does. Campos reports "Nearest"
as a substantial share of his selections.

P3 may therefore be refuted for structural reasons rather than behavioural ones. The
report must distinguish the two, and should present P3 against the *recorded-attribution*
semantics rather than silently treating our `TARGET_DISTANCE` count as equivalent to his
Nearest count.

**Lifetime and interval may be near-zero effects, not underpowered ones.** The sizing
pilot's mean interval (~6 s) and mean lifetime (~900 s) put creatures at only ~150
interactions per life on average — the same figure Campos cites as roughly when memory
starts to dominate over random choice. If most creatures die before reaching that
threshold, P1/P4/P5 would show no memory advantage not because the mechanism doesn't
work, but because most creatures don't live long enough to benefit from it. F1 (interval
vs k) and M1 (formation vs use, on a cycle axis) are the figures that test this directly;
the report must check whether the campaign's creatures cross ~150 interactions before
concluding P1/P4/P5 are refuted rather than not-yet-observable at this lifespan.

**CCAD image provenance.** `image.source: registry` always resolves to whatever
`ghcr.io/felipedreis/dl2l:latest` currently is, and CI only rebuilds that tag on push to
`main` (`.github/workflows/cd.yml`). A feature branch's Java changes are therefore invisible
to a CCAD run unless either (a) the branch is merged first, or (b) a distinctly-tagged
image is built and pushed by hand and the run overrides `dl2l_image` via `-e`. This
experiment's `ActionProbabilityState`/`MemoryDecisionState` persistence exists only on
`claude/p84-behaviour-parity`, not `main`, at the time this recipe was executed — the
campaign run used `-e dl2l_image=ghcr.io/felipedreis/dl2l:p84-behaviour-parity` (built
`--platform linux/amd64`, matching CCAD's architecture, from commit `eb8d0d8`), not the
default `:latest`. **`manifest.json` does not record the image tag or digest used** — this
is a gap, not something to rely on. Until it's fixed, the only record of which image a run
used is this note plus the ansible invocation itself; a follow-up should add the pulled
`ref`/digest to the manifest so a dataset is self-describing about its own provenance.

---

## 10. Artefacts

| Kind | Path |
|---|---|
| Worlds | `simulations/p84_{pilot_,}{legacy,current}_{no,}mem{,_simple}.conf` |
| Specs | `experiments/p84_pilot.yml`, `experiments/p84_behaviour_parity.yml` |
| Analysis | `analysis/experiments/p84_behaviour_parity.py`, `analysis/experiments/p84_memory_common.py` |
| Shared stats | `analysis/dl2l_analysis/stats.py` |
| Extraction | `scripts/dl2l_data/tables.py` (`conditioning`, `memory_decisions`) |
| Persistence | `creature/bd/ActionProbabilityState.java`, `creature/bd/MemoryDecisionState.java` |
| Data | `ml/data_p84_pilot/`, `ml/data_p84_behaviour_parity/`, HF prefix `p84/` |
| Figures | `docs/reports/figures/p84_behaviour_parity/` |
