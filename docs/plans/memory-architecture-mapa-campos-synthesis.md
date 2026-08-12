# Memory architecture: closing the gap with Mapa (2009) and Campos (2015)

Issues: [#88](https://github.com/felipedreis/dl2l/issues/88) (proximate bug),
[#84](https://github.com/felipedreis/dl2l/issues/84) (the parity study that exposed it)
Sources: `docs/bib/2009_Mapa_Modelagem_Organismos_Artificiais_Memoria_Experiencial.pdf` §5.3.2, `docs/bib/2015_Campos_Concurrent_Minimalist_Agent.pdf` §III-C/III-D
Supersedes the narrower `docs/plans/memoryfilter-mean-not-sum.md` (PR #89).

## Context

The #84 rerun measured that enabling `MemoryFilter` makes creatures **eat 3.5–45× less and die
sooner**, in all three arm pairs and both subsystem stacks. Reading both source papers against
our implementation shows this is not one bug but a structural divergence from *both*:

1. **Both papers are stochastic where memory meets choice; we are a deterministic argmax.**
   Mapa draws *which memory* to consult by emotional intensity; Campos selects actions "with a
   probability proportional to this value". An argmax over a store written by its own choices
   has a fixed point — whatever wins gets reinforced and keeps winning.
2. **Mapa separates object-choice from action-choice; we collapsed them.** Memory says *what to
   engage with*, operant conditioning says *what to do to it*. Our `MemoryFilter` scores
   `(action, object)` pairs and returns a **single** action, ending the chain
   (`ActionSelection.selectOne` stops at one candidate) before AFFORDANCE or RANDOM — the
   filters that would have chosen EAT — ever run. Memory decides in **99.4%** of consultations,
   and **75.4%** of those had a candidate it could not score at all.
3. **We contradict Campos on negative values.** He: "those with a negative value are not
   selected". Our test `action_with_no_matching_engram_wins_only_when_all_scored_are_negative`
   asserts the reverse as intended: a remembered-harmful action beats an unexplored one.

Intended outcome: memory stops crowding out consummatory behaviour, and the #84 parity claims
become testable against Mapa's Fig. 47/50 and Campos's Fig. 5/6.

## Decisions taken with the user

- **Filter order changes**: swap `AFFORDANCE` and `MEMORY`. Required by change 1.2 below, and it
  moves us *toward* Campos — his `Affordances` (Algorithm 1 line 1) is the candidate generator
  (our `definePossibleActions`), not our operant `ActionProbabilityFilter`, which in his design
  lives *inside* the Memory step. Today AFFORDANCE sits in a slot found in neither paper. After
  the swap the Memory+Affordance pair occupies Campos's single Memory slot:
  `ActionTendency → TARGET_DISTANCE(Nearest) → MEMORY → AFFORDANCE → RANDOM`.
  `TARGET_DISTANCE` stays first deliberately: it dedups to the nearest of each (type, action),
  so per-`WorldObjectType` scoring is unambiguous. The `*_nomem` arms are unaffected (no MEMORY
  in their filter set), so the controls stay comparable.
- **The novelty prior is dopamine-modulated**, mirroring `ActionProbabilityFilter.setModulation`
  — coupled to the *prior*, not a sampling temperature (a temperature flattens everything
  including known-bad options; Bunzeck & Düzel's finding is that novelty is itself rewarding).
  With neuromodulation off in the legacy arms `daTonic = 0`, so the term vanishes and those arms
  stay faithful to the sources.
- **AFFORDANCE and MEMORY stay separate** (was an open question). Merging them, as Campos did,
  would make memory's contribution unmeasurable — and measuring it is the point of #84.

## Design principles

1. Sample, don't maximise, where memory influences choice. (Both papers.)
2. Memory chooses the object; operant conditioning chooses the action. (Mapa.)
3. An unexplored option must never lose to a remembered-bad one. (Campos's rule.)
4. Nothing is permanently excluded — covers Campos's absorbing state.
5. Novelty is mildly attractive, not neutral — covers Mapa's `unknown = 0`.

## The new selection rule

Scores are aggregated per `WorldObjectType` (dropping `ActionType` from the key) as the **mean**
of `-emotionDelta × eligibility` — the #88 mean fix survives, re-keyed. Eligibility traces
already propagate consummatory outcomes back to the preceding APPROACH, so an object's aggregate
is a legitimate estimate of its worth.

```
base   = mean of the POSITIVE candidate scores            if any are positive
       = mean |score| over the candidates that are known  if some are known but none positive
       = 0                                                if none is known
prior  = MEMORY_NOVELTY_OPTIMISM · base · (1 + DA_NOVELTY_GAIN · tanh(max(0, daTonic)))

w(unknown object) = prior
w(score  >  0)    = score
w(score <= 0)     = MEMORY_NEGATIVE_FLOOR · prior

if base is 0 (nothing known about any candidate) → pass through unchanged
else sample one object type ∝ w, return ALL candidate actions targeting it
```

The middle branch of `base` was added during implementation. Without it, a choice between an
object remembered as harmful and an unexplored one gives both a weight of zero — memory would
decline to express the one preference it is most confident about, in exactly the case Campos
legislates for. The fallback keeps the prior on the same scale as the scores whenever there is
any evidence at all.

The prior is **relative**, not an absolute constant: an unknown object is worth the average
known-good one (optimistic initialisation), so it needs no scale calibration.
`MEMORY_NEGATIVE_FLOOR` satisfies Campos's negative-value rule in practice (a punished object is
100× less likely than an unknown one) without his absorbing zero. Actions with a null
`objectType` (WANDER/SLEEP) form their own key, as they do today.

Campos's `Random()` fall-through lands on **pass-through**, not a uniform draw: when no candidate
object has any evidence, memory has nothing to say and the operant table — which is better
informed than a coin flip — decides. Four gates all pass the candidates through untouched:
≤1 candidate; empty engram window; all candidates on one object (no object-level choice to make);
no candidate object known.

## Change list

Java changes branch off `claude/p84-behaviour-parity` (PR #87), which is where
`MemoryDecisionState` and `takeLastDecision` live; they are not on the #88 branch.

### Java — one PR

| File | Change |
|---|---|
| `creature/actionSelector/MemoryFilter.java` | Core rewrite. Key becomes `WorldObjectType` alone; weight function above; weighted sample of one object; **return all candidate actions on it** instead of one; delete the dead `unscored` list. Add `setModulation(double daTonic)` mirroring `ActionProbabilityFilter`. Add a package-private constructor taking a `Random` so tests are deterministic (production keeps the current one-arg ctor). Update `record(...)`/`Decision` for the new fields. |
| `cluster/settings/LearningSettings.java` | `MASTER_FILTER_ORDER` → `TARGET_DISTANCE, MEMORY, AFFORDANCE, WORLD_MODEL, RANDOM`, with a comment recording the Campos mapping. One-line change; no sim config touched. |
| `creature/components/FullAppraisal.java` | Add `memoryFilter.setModulation(daTonic)` beside the existing `affordanceFilter.setModulation(...)` (`:215`), inside the same neuromodulation guard. The `memoryFilter` field already exists on the p84 branch. |
| `common/Constants.java` | Add `MEMORY_NOVELTY_OPTIMISM = 1.0`, `MEMORY_NEGATIVE_FLOOR = 0.01`, `DA_NOVELTY_GAIN = 1.0`, next to `DA_EXPLORATION_GAIN` (`:171`). |
| `creature/bd/MemoryDecisionState.java` | `action` (ActionType) → `objectType` (String, the sampled type); add `returned` (int, candidate actions passed on) and `objects` (int, distinct candidate objects). Redefine `scored` = candidate objects with engram evidence, `decided` = `returned < candidates`. `objects` was added during implementation: without it `scored` has no denominator, since memory scores objects while `candidates` counts actions — the old `scored/candidates` panel would have been objects-over-actions. |
| `creature/bd/TableSchemas.java` | Update the `memory_decision_state` block for those two column changes. Nothing else — `BDActor.tableFor()` delegates to `TableSchemas.forState()`. |
| `test/.../MemoryFilterTest.java` | Rewrite against the new contract with a seeded RNG (19 tests): returns *all* actions on the sampled object; an unknown object beats a remembered-negative one; selection frequency tracks weight; the two #88 mean-vs-sum tests re-expressed per object; `daTonic > 0` raises the unknown-object rate; four pass-through gates; the `Decision` diagnostics. |
| `test/.../MemoryDecisionPersistenceTest.java` | Add the invariant analyses depend on: `returned <= candidates`, `decided == (returned < candidates)`, `objectType` non-null iff decided, `scored <= objects`. |
| `cluster/settings/LearningSettingsTest.java`, `actionSelector/ActionSelectionConfigTest.java`, `bd/TableSchemasTest.java` | Update the pinned filter order and the `memory_decision_state` column list. |
| `src/main/resources/simulation.conf`, `simulations/p84_*.conf` | List `enabledFilters` in the new chain order. Cosmetic — it is parsed as a set — but the file should read as the chain it produces. Historical experiment configs are left alone as the record of what ran. |

### Python

| File | Change |
|---|---|
| `scripts/dl2l_data/tables.py` | `memory_decisions` SELECT: `action` → `object_type`, add `returned` and `objects`. |
| `analysis/experiments/p84_memory_common.py` | Every memory-use quantity switches from `selection_type == MEMORY` to `memory_decisions.decided`: M1's use curve, M6's correlation, and `run_all`'s signature (drops the `actions` argument). M2 gains a third panel and correct denominators — `scored/objects`, `returned/candidates`. |
| `analysis/experiments/p84_behaviour_parity.py` | Read memory influence from `memory_decisions`, not `selection_type`. Note in the output that AFFORDANCE now absorbs credit for memory-narrowed decisions. |

### Docs

`docs/plans/memory-architecture-mapa-campos-synthesis.md` — replace with this file's content
(committed copy, per CLAUDE.md).

## Measurability (why Phase 2 matters more now)

After the swap memory rarely ends the chain, so `chosen_action_state.actionselectiontype` will
seldom read `MEMORY` — faithful to Mapa, but it costs the direct comparison with Campos, who
reports Memory as one of four criteria. `MemoryDecisionState` records every consultation
independently of chain credit, so "memory influenced X% of decisions" stays measurable. This is
the concrete reason the two filters stay separate.

## Verification

1. `mvn package` — compiles clean; `mvn test` — all pass, including the rewritten filter tests.
2. `python3 scripts/validate_experiment.py experiments/p84_*.yml`.
3. Local pilot; inspect `ml/data_p84_*/<arm>/trial_1/memory_decisions.parquet` for the new
   columns, and confirm `*_nomem` arms still emit no rows.
4. Re-run the `p84_behaviour_parity` campaign on CCAD and judge against predictions fixed in
   advance:

| prediction | falsified if |
|---|---|
| memory arms feed at least as often as their no-memory controls | `legacy_mem` stays near 14 EAT/creature vs 49 |
| memory-arm mortality falls below 40/40 in at least one pair | all three pairs stay at 100% |
| decisions with unscored candidates drops sharply from 75.4% | unchanged |
| EAT share of memory-influenced decisions rises above the no-memory arm's 0.68% | stays at ~0.28% |

## Source weaknesses deliberately not inherited

| source | weakness | our choice |
|---|---|---|
| Campos | punished actions **permanently** excluded — no extinction or renewal | down-weight, never zero |
| Mapa | unknown = **0**, indifferent to novelty | relative positive prior, DA-modulated |
| Mapa | `max`-if-positive / `min`-if-negative valence switch, ad hoc | not adopted; use the aggregate |
| Mapa | non-retrieved memories **extinguished** — conflates non-retrieval with extinction | not adopted; FIFO eviction is a capacity limit, not a learning rule |
| Mapa | flashbulb memories pinned at max intensity forever — Talarico & Rubin (2003) actually showed flashbulb *accuracy* decays normally | not adopted |
| Campos | WM = single most recent action, vestigial | unchanged; our `ShortTermMemory` is a real buffer |

## Known gaps left open

- **No spatial memory in any of the three.** All store `(action, object, value)` with no place,
  time or sequence — a value store, not episodic memory. Closing it is a feature, not a fix.
- **Fixed serial arbitration.** Out of scope (cf. Daw, Niv & Dayan 2005).
- **Mapa encodes only consummatory acts**; we encode every action. Per-object aggregation should
  make this moot, and dropping APPROACH engrams would discard the eligibility credit that makes
  approach-then-eat learnable. Revisit only if aggregation proves insufficient.

## Consequences

- Behavioural change for **every** experiment using the MEMORY filter; pre-change datasets are
  not comparable. The no-memory arms are unaffected.
- #84's P1/P4/P5/D2 must be re-derived; P2/P3 change meaning; D1 and the no-memory arms hold.
- P4 needs a longer `maxRuntimeMinutes` regardless — two `*_nomem` arms had **zero** deaths at
  the 90-minute cap, so the survival ratio has no denominator.
- PR #89 is subsumed: the mean survives, re-keyed per object.
- Sequencing: PR #87 must merge first.
