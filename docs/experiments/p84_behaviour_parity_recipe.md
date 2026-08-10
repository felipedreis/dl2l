# Experiment recipe — behaviour parity with Mapa (2009) and Campos (2015)

Issue: [#84](https://github.com/felipedreis/dl2l/issues/84)
Plans: [`behaviour-parity-legacy-architectures.md`](../plans/behaviour-parity-legacy-architectures.md) (the campaign),
[`memory-architecture-mapa-campos-synthesis.md`](../plans/memory-architecture-mapa-campos-synthesis.md) (the memory rework)
Report: `docs/reports/<date>_p84_behaviour_parity_report.md` (written against this recipe)

This is the executable protocol. Every number in the report must be traceable to a step
here; if a step is changed, this file changes with it and the report says so.

> **Revision, 2026-08-10 — all earlier runs are superseded.** The memory architecture was
> reworked after the v3 campaign (see the synthesis plan): `MemoryFilter` now picks an
> *object* by weighted sampling and hands every action on it to the operant table, instead of
> picking a single action by argmax; `MASTER_FILTER_ORDER` puts MEMORY ahead of AFFORDANCE.
> **Every arm with the MEMORY filter behaves differently, so no memory-arm result from any
> previous run — including the CCAD campaign of 2026-08-05 — carries into this report.** The
> `*_nomem` arms are structurally unaffected, but are re-run anyway because the runtime cap
> also changed (§4.4) and a control must share its treatment's conditions.
>
> Three further changes follow from it: the run cap goes to **2 h**, survival becomes
> **censoring-aware** (§6), and memory's influence is read from `memory_decisions` rather
> than from `selection_type` (§5).

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

| Arm | Stack | Filter chain | World |
|---|---|---|---|
| `legacy_nomem` | legacy-minimal | Tendency → TARGET_DISTANCE → AFFORDANCE → RANDOM | mixed |
| `legacy_mem` | legacy-minimal | Tendency → TARGET_DISTANCE → **MEMORY** → AFFORDANCE → RANDOM | mixed |
| `current_nomem` | current default | TARGET_DISTANCE → AFFORDANCE → RANDOM | mixed |
| `current_mem` | current default | TARGET_DISTANCE → **MEMORY** → AFFORDANCE → RANDOM | mixed |
| `legacy_nomem_simple` | legacy-minimal | Tendency → TARGET_DISTANCE → AFFORDANCE → RANDOM | simple |
| `legacy_mem_simple` | legacy-minimal | Tendency → TARGET_DISTANCE → **MEMORY** → AFFORDANCE → RANDOM | simple |

The chain order is `LearningSettings.MASTER_FILTER_ORDER`, not the order `enabledFilters`
lists — that field is parsed as a set.

- **legacy-minimal** = circadian, consolidation, expectancy, neuromodulation, orexin,
  endocrine all `false`, **`actionTendencyEnabled = true`**. A mismatch here is attributable
  to the core architecture rather than to one of the later subsystems.
- **current** = expectancy + neuromodulation + orexin + endocrine `true`,
  **`actionTendencyEnabled = false`**.
- **No arm enables `WORLD_MODEL`** — neither source architecture had one.
- **mixed** world = Campos's 20/20/60 red/green/gray. `GRAY_APPLE` has `caloricValue = 0`,
  so some interactions are unrewarding.
- **simple** world = red/green only, 50/50. Every interaction regulates hunger.

### Why action tendency is ON in legacy and OFF in current

`ActionTendencyFilter` is **not** a modern addition: its javadoc attributes the tendencies
to Campos (2006) and its pass-through rule to the 2015 paper being replicated here, and
`LearningSettings.DEFAULT_ACTION_TENDENCIES` cites Campos (2006) directly. It belongs to
the source architecture. An earlier run of this experiment had it `false` in the legacy
arms, which wrongly *removed* a mechanism both sources had — that run's legacy results
should not be compared against these.

The modern layer that stands in for it is neuromodulation.
`docs/plans/tedium-saturation.md` measured the two as independently sufficient for the same
job (tendency alone held tedium at 0.18–0.42; neuromodulation alone at 0.18–0.22), which is
what licenses treating them as a substitution rather than as an addition. So the arms carry
exactly one emotion→action mechanism each, and the pair isolates *which* mechanism rather
than *how many*.

Note this is deliberately **not** the shipped production default, which has both enabled.
That configuration answers a different question (external validity for the JEPA experiment)
and is out of scope here.

### What the MEMORY filter does, and why MEMORY < AFFORDANCE in the chain

Memory chooses **what to engage with**; operant conditioning chooses **what to do to it**.
On consultation `MemoryFilter` scores each candidate `WorldObjectType` as the mean of
`-emotionDelta x eligibility` over the recent engram window, draws one object with
probability proportional to that value, and returns **every** candidate action targeting it.
Unexplored objects carry an optimistic prior (the mean value of the known-good candidates,
scaled by tonic dopamine); objects remembered as harmful keep a small floor rather than
being excluded.

That is Mapa's §5.3.2 division of labour and Campos's proportional selection, and it is why
the order changed. Campos's `Affordances` (Algorithm 1, line 1) is the candidate *generator*
— our `definePossibleActions` — not our AFFORDANCE filter, which is an operant table; in his
design the operant mechanism sits *inside* the Memory step. Our AFFORDANCE previously
occupied a slot found in neither paper. The MEMORY → AFFORDANCE pair now fills his single
Memory slot, with TARGET_DISTANCE (his `excludeIdenticalTargets`) still ahead of it so memory
scores one instance per object type.

**The consequence for measurement is the important part.** Memory now seldom ends the filter
chain, so it seldom receives the `selection_type` credit — AFFORDANCE, running next, usually
does. `chosen_action_state.actionselectiontype = MEMORY` has therefore become a *structural
floor*, not a measure of memory's influence, and a low MEMORY share must not be reported as
memory going unused. Influence is read from `memory_decisions.decided` instead (§5). This is
the price of keeping the two filters separate rather than merging them as Campos did, and it
is paid deliberately: merging would make memory's contribution unmeasurable, which is the
question the experiment exists to answer.


At 5 creatures: **1923 × 1610 px, 500 objects** (6192 px²/object, 619,206 px²/creature).

**Run cap: `maxRuntimeMinutes = 120`** (was 90). Two `*_nomem` arms reached the 90-minute cap
with zero deaths, which left P4 with no denominator. The extra 30 minutes gives slower
creatures room to die — but it is not expected to be sufficient on its own, and it is not
relied on: with replenishment on, a successfully foraging creature may never die at any cap.
That is what §6's censoring-aware survival is for. The cap is a sampling decision, not the
fix. The pilot configs stay at 45 minutes; their job is schema gates and sizing, not survival.


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

**Schema gates — all must hold before the campaign is submitted.** Run them; do not eyeball
them:

```bash
python3 scripts/check_experiment_gates.py ml/data_p84_pilot   # exits 1 if any gate fails
```

Gates are evaluated **per trial**, and an arm passes only when all of its trials do —
checking the concatenation lets one broken trial hide behind its healthy siblings. A table
that is absent is reported SKIP, never PASS.

**G0 checks that every trial came from the same run**, by mtime spread across the whole data
dir. A data dir is written trial by trial and never cleared first, so a re-run that dies
partway leaves fresh and stale trials side by side and every other gate then averages across
two different builds of the simulator. This is not hypothetical: a mid-run check during the
2026-08-10 pilot mixed one fresh trial with five from three days earlier and reported a
confident G7 failure that was really the *previous* architecture's behaviour. **Delete the
data dir before re-running a pilot.**

| # | Gate | Why it matters |
|---|---|---|
| G1 | `conditioning.parquet` non-empty in `legacy_nomem` | expectancy is off there, so `ExpectancyState` writes nothing; this proves the *legacy* `Valuation` hook fires |
| G2 | exactly 6 conditioning rows per `seq`, one per `ActionType` | the whole table is snapshotted per reinforcement |
| G3 | conditioning events == EAT interactions | one reinforcement per object-directed interaction |
| G4 | `memory_decisions.parquet` **empty** in `legacy_nomem`, non-empty in `legacy_mem` | the MEMORY filter really is in/out of the chain |
| G5 | `engrams.parquet` non-empty in **both** arms | the matched-formation control holds |
| G6 | `creatures.parquet` has one row per creature | the birth+death dedup is applied; otherwise every lifetime statistic is wrong |
| G7 | every creature reaches ≥ 10 EAT interactions inside `maxRuntimeMinutes` | k = 1..10 is measurable; otherwise shrink the world, preserving density |
| G8 | `creatures.parquet` has `died` and `observed_s`, and `observed_s` is non-null for **every** creature | the censoring columns exist and cover survivors; without them F5/P4 skips entirely |
| G9 | `memory_decisions.parquet` has `object_type`, `returned`, `objects`; `returned <= candidates` and `decided == (returned < candidates)` on every row | the influence metric is well-formed — this is the only instrument for memory's effect now |
| G10 | in `legacy_mem`, `mean(decided) > 0` and at least some rows have `returned > 1` | memory both acts and leaves the action choice open; `returned == 1` everywhere would mean it is still collapsing to a single action |

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

| outcome                       | d              | ICC   | trials needed |
| ----------------------------- | -------------- | ----- | ------------- |
| lifetime (s)                  | −0.078         | 0.000 | 977           |
| mean interaction interval (s) | +0.037         | 0.126 | 5483          |
| RANDOM share of decisions     | −4.854         | 0.000 | 1             |
| AFFORDANCE share              | −0.577         | 0.000 | 19            |
| TARGET_DISTANCE share         | n/a (always 0) | —     | —             |
| RANDOM late/early ratio       | +0.245         | 0.000 | 101           |

Lifetime and interval effects are indistinguishable from zero at this design — no
reasonable n resolves them, so P1/P4/P5 may legitimately come back *inconclusive* rather
than *confirmed*, and that is a finding, not a sizing failure (§9 has a plausible
mechanism: mean interval ≈ 6 s and mean lifetime ≈ 900 s here put creatures at only ~150
interactions per life, the same threshold Campos reports for memory to start dominating).
AFFORDANCE share is the one outcome with a tractable, moderate effect. **`trials` set to
8** — comfortable margin over AFFORDANCE's requirement, without chasing the others.

**Re-executed 2026-08-10** on the reworked memory architecture, 3 trials x 3 creatures,
`legacy_nomem` vs `legacy_mem`. All ten gates pass. The earlier table above is superseded —
it measured the pre-rework filter.

| outcome                              | d      | ICC   | trials needed |
| ------------------------------------ | ------ | ----- | ------------- |
| lifetime (s), among those that died  | −0.759 | 0.000 | 11            |
| mean interaction interval (s)        | −0.311 | 0.000 | 63            |
| RANDOM share of chosen               | −4.577 | 0.000 | 1             |
| AFFORDANCE share of chosen           | +4.618 | 0.016 | 1             |
| TARGET_DISTANCE share of chosen      | +0.577 | 0.048 | 20            |
| RANDOM late/early ratio              | −0.471 | 0.000 | 28            |

Survival (P4, censoring-aware): mortality 9/9 in both arms, KM median 246 s vs 235 s,
ratio 0.96x, log-rank p = 0.063. **Memory no longer harms survival** — the pre-rework
campaign had 187 s against 253 s — but it does not yet help either, in this world. Both arms
die around 240 s, so the ~150-interaction threshold discussion in §9 still applies and P4
remains the outcome most likely to come back inconclusive.

Mechanism check on the same data: memory influences 21-30% of consultations, and **92.5% of
them return more than one action**, so the operant table really is making the action choice.
Feeding is unchanged between arms (min 39 vs 38 EAT/creature) where the pre-rework filter cut
it 3.5x.

### 4.4 Publish the image, then run

`image.source: registry` is required on CCAD (no docker daemon there), and it resolves to
whatever `dl2l_image` names — by default `:latest`, which CI only rebuilds on push to `main`.
A branch's Java changes are therefore invisible to a CCAD run unless the image is published
first. **This is now done by the preview pipeline rather than by hand:**

```bash
git push origin HEAD:preview/memory-architecture
```

`.github/workflows/preview-image.yml` triggers on `preview/**`, builds the fat jar, and
publishes `ghcr.io/felipedreis/dl2l:preview-memory-architecture` plus a `sha-<short>` tag.
(`docker/metadata-action`'s `type=ref,event=branch` turns the `/` into a `-`, so the branch
name carries the `preview` prefix itself.) Wait for the run to go green, then pin the
campaign to it:

```bash
cd ansible
ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=p84_behaviour_parity \
  -e dl2l_image=ghcr.io/felipedreis/dl2l:preview-memory-architecture
# later, repeatable, same flag:
ansible-playbook -i inventories/ccad run-experiment.yml -e experiment=p84_behaviour_parity \
  -e dl2l_image=ghcr.io/felipedreis/dl2l:preview-memory-architecture -e rescue=true
```

Locally, the same image or a plain local build:

```bash
ansible-playbook -i inventories/local run-experiment.yml -e experiment=p84_behaviour_parity -e analyze=true
```

Three things to know about the preview image:

1. **It is not test-gated.** The workflow runs `mvn package -DskipTests`, unlike
   `release.yml`. §4.1's `mvn test` is the gate, and it is not optional.
2. **It is linux/amd64 only** — the GitHub runner's native architecture, which matches CCAD.
   It will not run on the arm64 Pi cluster.
3. **Prefer the `sha-<short>` tag in the report.** `preview-<branch>` moves with every push
   to that branch, so quoting it does not identify a build. `manifest.json` still does not
   record the image tag or digest (§9), so the report must state the tag *and* the commit.

Data uploads to `felipedreis/dl2l-experiments` under `p84/`. Never disable that.

### 4.5 Analysis

```bash
PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p84_behaviour_parity
```

---

## 5. Data → figure map

| Fig | Source | Plotted | Compared to |
|---|---|---|---|
| F1 | `mouth_interactions`, `creatures.born_time` | mean seconds between interaction k−1 and k, k = 1..10 | Mapa Fig. 47 |
| F2 | same | cumulative seconds alive at the k-th interaction | Mapa Fig. 50 |
| F3 | `actions.selection_type` | cumulative selections per criterion, whole life | Campos Fig. 5 |
| F4 | `actions` | same, first 1000 decisions | Campos Fig. 6 |
| F3b | `actions` | each criterion's share of a creature's decisions | Campos's frequencies |
| F5 | `creatures.observed_s`, `creatures.died` | **Kaplan-Meier survival + mortality per arm** | Campos's 6.7× |
| F6 | `conditioning` | normalised APPROACH share over reinforcement events | Mapa's 0.25 / 0.40 / 0.70 |
| M1 | `engrams` + `memory_decisions` | cumulative engrams laid vs cumulative memory-**influenced** decisions | Campos's "~150 interactions" |
| M2 | `memory_decisions` | P(decides \| consulted); `scored/objects`; `returned/candidates` | — |
| M3 | `memory_decisions` | winning score and margin over runner-up | — |
| M4 | `engrams` | eligibility / emotion delta / lay→reinforce gap | — |
| M5 | `consolidation_*`, `memory_traces` | consolidation activity vs lifetime | — |
| M6 | `memory_decisions` + `creatures` | lifetime against memory-influenced decision count | — |

Criterion names map one-for-one: Nearest → `TARGET_DISTANCE`, Affordances → `AFFORDANCE`,
Memory → `MEMORY`, Random → `RANDOM` — but see §3 on why the `MEMORY` share is now a floor.

### What changed in the data, 2026-08-10

Nothing was removed; three columns were added and one renamed. Re-extraction is required —
these are extraction-query changes, so a raw Arrow dump from any earlier run can be
re-extracted without re-simulating, **but the memory arms must be re-simulated anyway**
because the filter itself changed.

| Table | Change | Why |
|---|---|---|
| `creatures` | **+`died`** (bool), **+`observed_s`** (s) | `lifetime_s` is NULL for a creature alive at the cap, and every consumer dropped those rows — right-censoring silently treated as missing data, biasing against whichever arm survives best. See §6. |
| `memory_decisions` | `action` → **`object_type`** | Memory no longer picks an action; it picks an object. |
| `memory_decisions` | **+`returned`** (int) | Candidate actions passed on. `decided == (returned < candidates)` is the influence signal that replaces `selection_type == MEMORY`. |
| `memory_decisions` | **+`objects`** (int) | Distinct candidate objects — the denominator for `scored`. Without it `scored/candidates` is objects-over-actions, which is meaningless. |

Reinterpreted, same column name: `scored` now counts candidate **objects** with engram
evidence (was: candidate actions); `winning_score` is the sampled object's value and
`runnerUpScore` the best among the objects passed over, so "did memory sample the argmax?"
is answerable after the fact.

### New measures

| Measure | From | Answers |
|---|---|---|
| **memory influence rate** | `mean(memory_decisions.decided)` | how often memory actually constrained the choice — the honest replacement for the MEMORY selection share |
| **choice-set coverage** | `scored / objects` | how much of what is in view the creature has any experience of |
| **narrowing factor** | `returned / candidates` | how far memory cut the action set when it did act |
| **mortality rate** | `mean(creatures.died)` | did they die at all — a real outcome once the cap truncates the study |
| **KM median survival** | `observed_s`, `died` | median lifetime that survivors do not bias; NaN when over half the arm outlives the cap |
| **log-rank test** | same | P4's significance test, valid with either arm partly or wholly censored |

**`conditioning.probability` is the raw stored value and must be normalised before
plotting.** `ActionProbability.varyProbability` clamps at 0 while
`OperantConditioningActor` applies the compensating `−delta/(n−1)` unconditionally, so a
target's raw sum drifts off 100. `ActionProbabilityFilter` normalises at selection time;
the analysis does the same, per `(target, seq)`.

---

## 6. Statistics

### Criterion shares are computed over *chosen* decisions

Campos reports four selection criteria; our `ActionSelection.selectOne` can report five,
because it credits whichever filter first narrows the candidate set to one — and
`ActionTendencyFilter` can do that. `DEFAULT_ACTION_TENDENCIES` maps `TEDIUM -> {WANDER}`,
a singleton, so any cycle where tedium dominates is *determined* by the constraint with no
choice left for the scoring filters. Measured at **86% of decisions** in the earlier
campaign's current arms, where it was silently omitted from the figures entirely.

`ACTION_TENDENCY` decisions are therefore **reported as their own category and excluded
from the four-way shares**, which are computed over decisions where a criterion actually
chose. `constraint_determined_frac` is reported per arm so the denominator is always
visible.

Two alternatives were considered and rejected. *Reattributing* those decisions to the next
filter in the chain is worse than doing nothing: every downstream filter is a pass-through
on a one-element list, so the entire 86% would land on `TARGET_DISTANCE` and manufacture a
spurious match with Campos's substantial "Nearest" share. *Making the tendency soft* (pass
through unless ≥2 survive) would restore a meaningful four-way split, but removes the forced
WANDER that `docs/plans/tedium-saturation.md` measured as what prevents tedium saturation in
exactly the legacy configuration this experiment runs — so it trades a reporting problem for
a behavioural one.

### Survival is censored, and must be analysed as such

A creature still alive when the run hits `maxRuntimeMinutes` is **right-censored**: we know
it lived at least that long. `creatures.lifetime_s` is NULL for those creatures and every
consumer dropped the NaNs, so survivors were silently excluded from every lifetime
statistic. That is not a rounding issue — it biases in the worst possible direction, because
the arm that survives best loses the most observations and has its mean dragged toward the
arm that dies young. In the v3 campaign it was worse still: two `*_nomem` arms had **zero**
deaths at 90 min, so P4's ratio had no denominator and was simply uncomputable.

Raising the cap to 2 h does **not** fix this on its own, and may not fix it at all: with
replenishment on, a creature that forages successfully has no reason to die at any cap. So
P4 is decided by censoring-aware statistics, which work regardless:

| Quantity | Test | Reported when |
|---|---|---|
| mortality rate per arm | Fisher's exact | always |
| KM median survival | — | always; `beyond cap` when >50% outlive the horizon |
| memory/no-memory median ratio | — | only when **both** medians are reached; `n/a (censored)` otherwise |
| survival difference | **log-rank** | always, provided at least one death exists |

`analysis/dl2l_analysis/stats.py` (`kaplan_meier`, `km_median`, `logrank_test`,
`survival_comparison`). Hand-rolled rather than pulling in `lifelines`; the log-rank
implementation is checked against the Freireich (1963) 6-MP trial, the standard worked
example (χ² = 16.79, p = 4.2×10⁻⁵).

**The report must state mortality per arm before quoting any survival number.** A ratio
computed where nobody died is not a small effect; it is an unobserved one.

### Tests

Lifetimes and intervals are strongly right-skewed, so everything is rank-based. No
t-tests, no ANOVA.

| Purpose | Test | Unit |
|---|---|---|
| **Memory extends life (P4)** | **mortality (Fisher) + log-rank on `(observed_s, died)`** | **creature** |
| Lifetime *among those that died* | Mann-Whitney U — a conditional comparison, not P4 | creature |
| Memory vs no-memory, other outcomes | Mann-Whitney U | creature |
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
| **P2** | With memory, RANDOM is displaced as memory engages; without memory, no trend | F3/F4 + Mann-Whitney on the late/early RANDOM ratio. **"as memory engages" is now read from M1's influence curve, not from the MEMORY selection share** (§3) |
| **P3** | Nearest and Affordances used similarly with and without memory | F3b + Mann-Whitney on their shares. **AFFORDANCE now also absorbs the credit for memory-narrowed decisions, so a rise in its share in the memory arm is expected and is not evidence against P3**; cross-check against M2's narrowing factor before drawing any conclusion |
| **P4** | Memory extends life; ratio compared to Campos's 6.7× | F5 + mortality (Fisher) + log-rank on `(observed_s, died)`; median ratio only when both medians are reached (§6) |
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

## 9. Known limitations found during piloting and the CCAD campaign

**CCAD ran out of disk quota partway through the campaign (2026-08-05).** `engrams.parquet`
alone is 100–563 MB per trial at this experiment's scale (5 creatures, up to 90 min) —
legacy-minimal trials produce far more than current-stack ones (~550 MB/trial vs
~100 MB/trial; the current stack's creatures apparently accumulate proportionally fewer
engram rows, itself a finding worth carrying into the report). The shared CCAD submit/rescue
framework (`ansible/roles/trial_runner_ccad/`) syncs each trial's parquet back to the local
Mac on `rescue=true` but never deletes the remote copy afterward — every successfully
extracted trial's full output sits on CCAD's quota-limited `$HOME` forever. With 6 conditions
× 8 trials at this table size, the account's quota was exhausted well before the campaign
finished; jobs submitted afterward failed near-instantly (SLURM couldn't even open their
`--output` log file).

Two consequences, both handled but worth carrying forward to the shared framework itself:

1. **A `DONE` sentinel does not mean a trial succeeded** — it's written unconditionally by
   the script's `EXIT` trap, so a trial that died mid-extraction from quota exhaustion still
   marks itself "done," with only some of its tables ever written (confirmed live: two
   `current_nomem` trials had `creatures.parquet`/`actions.parquet`/`drives.parquet` but were
   missing `engrams.parquet`, `mouth_interactions.parquet`, `conditioning.parquet`, and five
   others — individually valid, parseable parquet files, just an incomplete set). Trusting
   the ansible role's own "N/8 done" count is not sufficient; every trial must be checked for
   the *full expected table set*, not just that the files present are readable. See the
   validator pattern in the loop script referenced below.
2. **The fix used here** (not yet folded into the shared role) was a polling wrapper that,
   after each `rescue=true` sync, validates every locally-synced trial against the union of
   tables its own condition's other trials produced, and for each now-valid trial, deletes
   the remote copy over a direct SSH `rm -rf`. This bounds CCAD's remote footprint to
   "trials currently in flight" rather than "every trial ever run." A proper fix would add
   this cleanup (and the stricter completeness check) directly to `rescue_condition.yml` —
   filed as a follow-up, not done here to limit the blast radius of an unattended overnight
   change to shared infra.

**A related, purely operator-side bug**: when manually resubmitting the missing/broken
trials' array indices (`sbatch --array=X-Y <script>` reusing the rendered job script),
running it from inside `~/l2l` (i.e. `cd l2l && sbatch ...`) breaks every job instantly —
`remote_work_dir` is a *relative* path (`"l2l"`, resolved against `$HOME`) baked into the
script's `#SBATCH --output` directive, so submitting from inside that directory doubles it
to `~/l2l/l2l/logs/...`, which doesn't exist; SLURM can't open the output file and every
task fails in under a second with no log at all. Submit `sbatch --array=X-Y l2l/jobs/<script>`
from `$HOME`, exactly as ansible itself does — never `cd` into `remote_work_dir` first.

In the first real pilot trial the selection types recorded were `RANDOM` (65,936) and
`AFFORDANCE` (11,284) — **`TARGET_DISTANCE` won zero decisions.**
`ActionSelection.selectOne` attributes a decision to a filter only when that filter alone
narrows the candidate set to one, and TargetDistance rarely does. Campos reports "Nearest"
as a substantial share of his selections.

P3 may therefore be refuted for structural reasons rather than behavioural ones. The
report must distinguish the two, and should present P3 against the *recorded-attribution*
semantics rather than silently treating our `TARGET_DISTANCE` count as equivalent to his
Nearest count.

**The 2026-08-05 CCAD campaign is superseded and is not used in the report.** Its memory
arms ran the pre-rework `MemoryFilter` (single-action argmax, AFFORDANCE before MEMORY), so
their behaviour is not the behaviour being reported on; its `creatures.parquet` predates
`died`/`observed_s`, so F5/P4 cannot be computed from it either. Its `*_nomem` arms are
structurally unchanged and could in principle be reused, but are re-run anyway — the run cap
changed, and a control that did not share its treatment's conditions is not a control. The
data stays on HuggingFace under `p84/` as a record; the operational findings it produced
(quota exhaustion, the `DONE`-sentinel trap, the `sbatch` path bug) are all still live and
are documented above.

**Lifetime and interval may be near-zero effects, not underpowered ones.** The sizing
pilot's mean interval (~6 s) and mean lifetime (~900 s) put creatures at only ~150
interactions per life on average — the same figure Campos cites as roughly when memory
starts to dominate over random choice. If most creatures die before reaching that
threshold, P1/P4/P5 would show no memory advantage not because the mechanism doesn't
work, but because most creatures don't live long enough to benefit from it. F1 (interval
vs k) and M1 (formation vs use, on a cycle axis) are the figures that test this directly;
the report must check whether the campaign's creatures cross ~150 interactions before
concluding P1/P4/P5 are refuted rather than not-yet-observable at this lifespan.

**CCAD image provenance.** Building and pushing a tagged image by hand — what the
2026-08-05 campaign did, with `--platform linux/amd64` from commit `eb8d0d8` — is superseded
by the preview pipeline (§4.4). The underlying gap remains: **`manifest.json` records neither
the image tag nor its digest**, so a dataset is not self-describing about the build that
produced it. Until that is fixed the report must state the tag *and* the commit sha, and
should prefer the immutable `sha-<short>` tag over the moving `preview-<branch>` one. Filed
as a follow-up.

---

## 10. Artefacts

| Kind | Path |
|---|---|
| Worlds | `simulations/p84_{pilot_,}{legacy,current}_{no,}mem{,_simple}.conf` |
| Specs | `experiments/p84_pilot.yml`, `experiments/p84_behaviour_parity.yml` |
| Analysis | `analysis/experiments/p84_behaviour_parity.py`, `analysis/experiments/p84_memory_common.py` |
| Gates | `scripts/check_experiment_gates.py` |
| Shared stats | `analysis/dl2l_analysis/stats.py` (`compare_arms`, `survival_comparison`, `kaplan_meier`, `logrank_test`) |
| Extraction | `scripts/dl2l_data/tables.py` (`creatures` censoring cols, `conditioning`, `memory_decisions`) |
| Persistence | `creature/bd/ActionProbabilityState.java`, `creature/bd/MemoryDecisionState.java` |
| Selection rule | `creature/actionSelector/MemoryFilter.java`, `cluster/settings/LearningSettings.java` |
| Image | `.github/workflows/preview-image.yml` → `ghcr.io/felipedreis/dl2l:preview-<branch>` |
| Data | `ml/data_p84_pilot/`, `ml/data_p84_behaviour_parity/`, HF prefix `p84/` |
| Figures | `docs/reports/figures/p84_behaviour_parity/` |
