# Memory architecture: closing the gap with Mapa (2009) and Campos (2015)

Issues: [#88](https://github.com/felipedreis/dl2l/issues/88) (proximate bug), [#84](https://github.com/felipedreis/dl2l/issues/84) (the parity study that exposed it)
Sources: `docs/bib/suelenmapa.pdf` §5.3.2, `docs/bib/2015_Campos_Concurrent_Minimalist_Agent.pdf` §III-C/§III-D

## 1. Why

The issue #84 rerun measured that enabling `MemoryFilter` makes creatures **eat 3.5–45x less
and die sooner**, in all three arm pairs and both subsystem stacks. Reading both source papers
against our implementation shows this is not one bug but a structural divergence from *both*
of them, in two independent ways.

### 1.1 Both papers are stochastic where memory meets choice. We are not.

| | where the randomness is |
|---|---|
| Mapa | *"é feito um **sorteio randômico ponderado**, com base na intensidade emocional"* — samples **which memory** to consult |
| Campos | *"Actions with a positive value are selected with a **probability proportional to this value**"* — samples **which action** |
| **ours** | **deterministic argmax**. None. |

A deterministic argmax over a store that is *written by its own choices* has a fixed point:
whatever wins gets reinforced, so it keeps winning. Neither paper can lock in this way, and
neither avoids it by accident — proportional selection is Herrnstein's matching law,
intensity-weighted retrieval is standard strength-based sampling (SAM). We removed the one
property that makes the loop stable.

### 1.2 Mapa separates object-choice from action-choice. We collapsed them.

> "o PartialAppraisal irá calcular a **expectativa de interação com cada objeto**… FullAppraisal
> **seleciona o estímulo** que possui maior valor de expectativa… Em seguida… o **condicionamento
> operante** é acionado para **selecionar a ação**"

Memory says *what to engage with*; operant conditioning says *what to do to it*. Our
`MemoryFilter` scores `(action, object)` pairs and returns a **single action**, which ends the
filter chain (`ActionSelection.selectOne` stops at one candidate). Because memory almost always
holds APPROACH/WANDER engrams and rarely EAT ones, it recommends approaching and terminates
before AFFORDANCE or RANDOM — the filters that would have chosen EAT — ever run.

Measured: memory decides in **99.4%** of consultations, and **75.4%** of those decisions had a
candidate it could not score at all (mean 1.46 scored of 2.44 candidates). The `unscored` list
in `MemoryFilter.filter` is built, filled, and never read — it is precisely the set of options
discarded without evidence.

### 1.3 We contradict Campos outright on negative values

> "those with a **negative value are not selected**"

Our test asserts the reverse as intended behaviour:
`action_with_no_matching_engram_wins_only_when_all_scored_are_negative` —
*"even negative scores get picked over no score"*. A remembered-as-harmful action beats an
unexplored one.

## 2. Design principles adopted

1. **Sample, don't maximise**, at the point where memory influences choice. (Both papers.)
2. **Memory chooses the object; operant conditioning chooses the action.** (Mapa.) This also
   means memory stops terminating the filter chain.
3. **An unexplored option must never lose to a remembered-bad one.** (Campos's rule, fixing our
   direct contradiction of it.)
4. **Nothing is permanently excluded.** (Covers Campos's weak point — see §4.)
5. **Novelty is mildly attractive, not neutral.** (Covers Mapa's weak point — see §4.)

## 3. Changes

### Phase 1 — the selection rule (`MemoryFilter`)

**1.1 Score objects, not `(action, object)` pairs.** Aggregate engram value per
`WorldObjectType` across all actions taken on it. Eligibility-trace credit already propagates
consummatory outcomes back to the APPROACH that preceded them (that is why APPROACH carries
positive value at all), so an object's aggregate is a legitimate estimate of its worth.

**1.2 Return every candidate action targeting the chosen object**, not a single action. The
chain continues; `ActionProbabilityFilter` (our operant table) makes the action choice, exactly
Mapa's division of labour. This is the change that dissolves the crowding-out mechanically.

**1.3 Choose the object by weighted sampling, not argmax**, with weight proportional to
expectation. Restores the property from §1.1.

*Alternative considered:* Mapa's exact form — sample **which memory** to consult by emotional
intensity, then argmax the resulting expectation. Equivalent in effect (stochasticity at the
same interface) and closer to her text; more machinery for the same behavioural property. Worth
revisiting if we later want per-episode retrieval traces.

**1.4 Unknown object → small positive prior**, not exclusion and not zero. Makes unexplored
food attractive rather than invisible. Note this makes Campos's fall-through *emergent*: when
every candidate object is unknown they share one weight, sampling is uniform, and the result is
indistinguishable from his `Random()` branch — no special case needed.

**1.5 Negative expectation → heavily down-weighted, never zero.** Satisfies Campos's rule in
practice (a punished object loses to an unknown one) without his absorbing state.

**1.6 Delete the dead `unscored` list.**

### Phase 2 — keep memory measurable

With 1.2, memory rarely ends the chain, so `chosen_action_state.actionselectiontype` will
rarely read `MEMORY`. That is faithful to Mapa but costs comparability with **Campos**, who
*does* report Memory as one of four selection criteria.

`MemoryDecisionState` (added for #84) already records every consultation independently of who
gets chain credit, so "memory influenced X% of decisions" stays measurable. The analysis should
report memory's *influence* rate from that table, and stop inferring it from `selection_type`.

### Phase 3 — verification

Re-run `p84_behaviour_parity` and judge against predictions fixed in advance:

| prediction | falsified if |
|---|---|
| memory arms feed at least as often as their no-memory controls | `legacy_mem` stays near 14 EAT/creature vs 49 |
| memory-arm mortality falls below 40/40 in at least one pair | all three pairs stay at 100% |
| decisions-with-unscored-candidates drops sharply from 75.4% | unchanged |
| EAT share of memory-influenced decisions rises above the no-memory arm's 0.68% | stays at ~0.28% |

## 4. Weak points of the sources we deliberately do **not** inherit

| source | weakness | our choice |
|---|---|---|
| Campos | punished actions **permanently** excluded — no extinction, no renewal, cannot relearn a changed world | down-weight, never zero (1.5) |
| Mapa | unknown = **0** — indifferent to novelty, where real animals show dopaminergic novelty-seeking | small positive prior (1.4) |
| Mapa | `max`-if-positive / `min`-if-negative valence switch — ad hoc, not derived | not adopted; use the aggregate directly |
| Mapa | non-retrieved memories are **extinguished** — conflates "not retrieved" with extinction, which requires cue-without-outcome | not adopted; we already evict by FIFO only (`MAX_ENGRAM_SIZE`), which is a capacity limit, not a learning rule |
| Mapa | flashbulb memories pinned at maximum intensity forever — she cites Talarico & Rubin (2003), who actually showed flashbulb *accuracy* decays like ordinary memory while only confidence stays high | not adopted |
| Campos | WM = single most recent action, functionally vestigial | unchanged; our `ShortTermMemory` already holds a real buffer |

## 5. Known gaps left open (deliberately)

- **No spatial memory, in any of the three.** Both papers store `(action, object, value)` with
  no place, time or sequence — so "LTM" is a semantic/procedural value store, not episodic
  memory in the hippocampal sense. For a foraging organism this is the largest shared
  omission, and closing it is a feature, not a fix.
- **We split what Campos unified.** For him LTM *is* operant conditioning; we have both
  `ActionProbabilityFilter` (labelled AFFORDANCE, actually an operant table) and
  `MemoryFilter`. His "Affordances" is merely the situationally-possible action set. Merging
  them would eliminate one of his four reported criteria and change what P2/P3 measure — left
  as an open question, not resolved here.
- **Fixed serial arbitration.** Both papers, and we, run filters in a fixed cascade; systems
  arbitration is thought to track relative uncertainty between controllers (Daw, Niv & Dayan
  2005). Out of scope.
- **Mapa encodes only consummatory acts** (`comer, brincar e tocar`); we encode every action.
  With 1.1 aggregating per object this should not matter, and dropping APPROACH engrams would
  also discard the eligibility-trace credit that makes approach-then-eat learnable. Revisit
  only if per-object aggregation proves insufficient.

## 6. Consequences

- Behavioural change for **every** experiment using the MEMORY filter; pre-change datasets are
  not comparable.
- #84's P1/P4/P5/D2 must be re-derived; P2/P3 change meaning (see Phase 2) and D1 and the
  no-memory arms are unaffected.
- P4 additionally needs a longer runtime cap regardless: two `*_nomem` arms had **zero** deaths
  at 90 minutes, so the survival ratio has no denominator.
- PR #89 (mean instead of sum) is subsumed. It removes a real frequency artefact but was
  measured at ~4% of the effect; keep or drop it on its own merits.
