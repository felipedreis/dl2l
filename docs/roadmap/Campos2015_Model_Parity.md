# Roadmap — Cognitive-Emotional Model Parity (ARTÍFICE lineage → DL2L)

**Primary sources** (all in `docs/bib/`):
- **Campos (2006)** — *Dissertação de Mestrado, Luciana Campos* (`Dissertacao_Mestrado_Luciana_Versao_Final-20061124.pdf`). Origin of the ARTÍFICE architecture: the situated cognitive-emotional process, Buck's evolutionary emotion taxonomy, per-emotion **action tendencies**, affordances, behavioural efficiency.
- **Mapa (2009)** — *Modelagem de organismos artificiais cognitivo-emocionais dotados de memória experiencial de longo prazo* (`suelenmapa.pdf`). Deep literature review; introduces **complex (derived) emotions**, the **Yerkes-Dodson/Diamond** behavioural-efficiency curves (simple vs complex tasks), the sympathetic/parasympathetic regulation loop, and the MEE/ECQ memory-valuation model.
- **Campos et al. (2015)** — *A concurrent, minimalist model for an embodied nervous system* (`2015_Campos_Concurrent_Minimalist_Agent.pdf`). The concise, published realisation with the Action-Selection cascade (Algorithm 1).

**What we are modelling.** The target is the **cognitive-emotional process** of the ARTÍFICE lineage,
not merely the 2015 short paper. DL2L is the modern (Akka, non-blocking, distributed) descendant of
ARTÍFICE; it has also grown a JEPA world model, sleep-driven consolidation, circadian rhythm and
olfaction that are *beyond* all three sources. So "gap" means either (a) a mechanism the sources
specify that DL2L never realised, or (b) a mechanism DL2L realised in a way that **contradicts** the
sources. Intentional evolutions are recorded as **[DIVERGENCE — accepted]** and are *not* work items.

Tags: **[GAP]**, **[LOOP]** (missing/incomplete stimulation-regulation loop), **[BUG]**,
**[DIVERGENCE]**. Priorities: **P0** correctness, **P1** behavioural fidelity, **P2** structural.

> **Revision note.** This version incorporates the PR-review feedback and the two dissertations.
> Corrections vs the first draft: (1) `regulateAll` over every emotion is **intentional** (Mapa
> p.121) — the real defect is narrower than "all nine inflate": only the four active emotions should be
> regulated (see Revision 2 for the final scope decision); (2) the behavioural-efficiency curves are
> **correct but assigned to the wrong
> task-complexity branch**; (3) action tendencies are **learned**, so the fix is emotion-conditioned
> operant conditioning, not a hardcoded policy; (4) the smaller metabolic rate and the collapsed
> sensory/effector cortices are **accepted divergences**; (5) splitting sensory/effector organs is
> **out of scope** and removed.
>
> **Revision 2 (design direction).** Deriving complex emotions from the basic ones is rejected as
> biologically misleading: e.g. *stress* is an emotional state driven by cortisol peaks, and cortisol
> is itself produced through several distinct pathways (prolonged discomfort, sleep deprivation,
> starvation) — it is **not** a simple function of the current basic-drive arousals. Complex/affective
> states are therefore **deferred** until the basic layer is solid, to be modelled later from their
> real biological pathways. For now we keep the **four active emotions we designed — `hunger`,
> `sleep`, `pain`, `tedium`** — and **disable** the rest (`stress`, `apathy`, `fear`, `curiosity`,
> `fertility`), pinned inert. The optional innate ActionTendency bias stays **default-off**.

---

## 1. The reference cognitive-emotional process (one page)

The ASCS ("Artificial Situated Cognitive System") sustains **behavioural homeostasis** by keeping
each emotion's *arousal* within tolerable bounds through interaction with the world. The loop:

1. **Emotions are classified by Buck's evolutionary hierarchy** (Campos 2006, p.88): *Reflex →
   Instinct → Drive (bodily need) → Affect → ProSocialAffect*. Each `Emotion` owns an
   **ArousalFunction** (its level) and an **ActionTendency** (a set of actions that regulate it).
2. **Metabolic drift (sympathetic).** `PartialAppraisal` continuously emits an adrenergic/sympathetic
   stimulus that **desregulates (raises) the arousal of every emotion, basic and complex** (Mapa
   p.121) — this is the internal "engine" that keeps the agent motivated even with no external input.
3. **Regulation (parasympathetic).** When the ASCS interacts with an object, the sensor emits a
   cholinergic/parasympathetic stimulus that **lowers** the arousal of the corresponding basic
   emotion (e.g. eating lowers hunger). Each such interaction is a **MEE** (micro-emotional
   experience), valued by `Valuation` as pleasant/unpleasant by the sign of the arousal change.
4. **Dominant-emotion election.** `PartialAppraisal` elects the **most-desregulated basic emotion
   and the most-desregulated complex emotion** and forwards both in the emotional stimulus (Mapa
   p.122).
5. **Action selection.** `FullAppraisal` combines *dominant emotion → its ActionTendency* ∩
   *affordances(situation)*, then lets **operant conditioning + memory** pick the fine action within
   that set (Campos 2006 p.80 Fig.22; 2015 Algorithm 1).
6. **Behavioural efficiency** (Mapa §4.1.4) modulates step speed from the dominant arousal via a
   **task-complexity-dependent** curve (see §2 Finding B).
7. **Memory.** A MEE is stored in ShortTermMemory; sequences of MEE form an **ECQ** (complete
   experience with qualia) consolidated into LongTermMemory, which later biases selection.

---

## 2. Emotion taxonomy & regulation strategy (answers the "how do we segregate motivation systems?" question)

This is the strategy the PR review asked for. It states **which mechanism regulates each level and
when**, and — per design direction (Revision 2) — which emotions are **active now** vs **deferred**.

| Buck level | DL2L emotion(s) | Status | Regulated by | When |
|---|---|---|---|---|
| **Reflex** | (US→UR pairs, e.g. nociception→pain) | active | classical conditioning (Rescorla-Wagner), innate US-UR | immediate, per stimulus |
| **Instinct** | fixed action patterns: wander, avoid, approach | active | innate affordances | per situation |
| **Drive** (bodily need) | `hunger`, `sleep` | **active** | sympathetic (↑ metabolic/circadian) / parasympathetic (↓ per MEE) | per interaction |
| **Affect** (simple) | `pain`, `tedium` | **active** | sympathetic (↑) / parasympathetic (↓ per MEE) | per interaction |
| **Affect** (complex) | `stress`, `apathy` | **deferred** | their own biological pathways (e.g. cortisol peaks) — **not** a function of basics | future |
| **Affect** (declared) | `fear`, `curiosity`, `fertility` | **disabled** | undefined | future |
| **ProSocialAffect** | (future: social) | future | — | future |

Key rules that fall out of this table (and drive the tasks below):

- **Active emotions** are `hunger`, `sleep`, `pain`, `tedium` — the four we designed, each with both a
  sympathetic (↑) and a parasympathetic (↓) path, so they are self-limiting through behaviour. Keep
  them exactly as designed. These are the only emotions that count toward arousal/death for now.
- **Complex affects (`stress`, `apathy`) are deferred, not derived.** Modelling them as functions of
  the basic drives is biologically misleading — e.g. stress tracks cortisol, produced through several
  distinct pathways (prolonged discomfort, sleep deprivation, starvation). They will be modelled later
  from those pathways once the basic layer is solid.
- **Disabled affects (`fear`, `curiosity`, `fertility`)** have no tendency set and no regulation path;
  they are pinned inert and **must not** contribute to metabolism, arousal or death.
- The **9-slot emotion vector** for the JEPA contract is preserved: deferred/disabled emotions stay
  pinned at `MIN_AROUSAL_LEVEL`, so their columns remain present but constant.
- **Homeostatic equilibrium band** (Mapa p.101): arousal in `[0.18, 2]` means "in equilibrium, no
  self-regulation required." Not modelled today — a candidate refinement (Task 5-doc).

---

## 3. Current-vs-model mapping (corrected)

| Model element | DL2L realisation | Status |
|---|---|---|
| Sympathetic metabolic drift | `AdrenergicStimulus` → `HR.regulateAll(∆)` raises **all nine** | must be scoped to the 4 active — **Finding A** |
| Parasympathetic regulation per drive | `Nutritive`/`Cholinergic`/`Nociceptive`↔`Analgesic`/`Tedium` → HR | present for the 4 active emotions |
| Non-active emotions inert | `stress`,`apathy`,`fear`,`curiosity`,`fertility` accumulate with no decay | [BUG] **Finding A** |
| Dominant-emotion election | `PartialAppraisal` sends only `getMaxArousal()` (fine for a single active tier) | OK now; complex election deferred (§Deferred) |
| Behavioural efficiency (simple vs complex curves) | both curves present but **assigned to the wrong branch** | [BUG] **Finding B** |
| Per-emotion **ActionTendency** set | absent; `toRegulate` Emotion threaded through filters but **unused** | [GAP] **Finding C** |
| Learned refinement keyed by dominant emotion | operant conditioning keyed by `(target, action)` only — **no `affectBasic`** | [GAP] **Finding C** |
| Affordances by situation | inline in `FullAppraisal.actionsForPerception(...)` | [DIVERGENCE — accepted] |
| Focus effector: narrow-on-approach, close-on-sleep | focus ∝ efficiency; never distance-driven; eye stays open in SLEEP | [LOOP] **Finding D** |
| Sensory/Effector cortex as thin stimulus mappers | `SensoryCortex`/`EffectorCortex` collapse the sub-organs | [DIVERGENCE — accepted] (PR-confirmed) |
| Metabolic rate `∆` | `1.5e-3` (vs paper `5e-3`) | [DIVERGENCE — accepted] (PR-confirmed; non-blocking arch runs faster) |
| Sleep drive increment | circadian **and** metabolic both raise `sleep` → double | [BUG] **Finding E** |
| Homeostatic equilibrium band `[0.18, 2]` | not modelled | [GAP] P2 (Task 5) |

---

## 4. Findings

### Finding A — Non-active emotions accumulate with no way down — **[BUG] P0**
`HR.handleAdrenergic → EmotionalSystemActor.regulateAll(∆)` raises **all nine** emotions each cycle.
Raising the four **active emotions** (`hunger`, `sleep`, `pain`, `tedium`) is intended (Mapa p.121: the
sympathetic stimulus "desregula o arousal de **todas as emoções**") — each has a parasympathetic
decrease path, so it is self-limiting through behaviour. The defect is the other five, which have
**no regulation handler anywhere** (grep: HR only ever touches HUNGER/SLEEP/PAIN/TEDIUM):
- **`stress`, `apathy`** — to be modelled later from their own biological pathways (deferred, not
  derived; see Revision 2).
- **`fear`, `curiosity`, `fertility`** — declared but unmodelled.

Because `PartialAppraisal` kills the creature when `getMaxArousal() ≥ 7` over **all nine** emotions,
these five monotonically climb at `∆ = 1.5e-3`/cycle and cross 7 at `(7−0.18)/1.5e-3 ≈ 4547` cycles —
an **undocumented hard lifetime ceiling** independent of feeding, silently confounding every
longevity measurement (including the JEPA experiments). Fixed by **Task 1**.

### Finding B — Behavioural-efficiency curves are swapped between simple and complex tasks — **[BUG] P1**
Mapa §4.1.4 (Eq 4.1/4.2, Fig 27), following Yerkes-Dodson as interpreted by Diamond et al. (2006):
- **Simple / non-cortical task** (0 or 1 object in the sensory field) → **monotonic increasing**
  `EC = 16(1 − e^{−0.4A})` (Eq 4.1).
- **Complex / cortical task** (>1 object) → **inverted-U** `EC = −(40/49)A² + (280/49)A` (Eq 4.2).

`PartialAppraisal.normalizedBehaviouralEfficiency(arousal, perceptionsCount)` implements *both*
curves (the constants match: `5.714 = 280/49`, `0.816 = 40/49`, and `16(1−e^{−0.4A})`) but wires them
**backwards**: `perceptionsCount < COMPLEX_TASK` (simple, 0–1 objects) takes the **inverted-U**
branch, and the complex branch takes the **monotonic** one. So simple tasks get the complex curve and
vice-versa. (The two-curve model itself is the intended Mapa evolution — PR-confirmed; only the
branch assignment is wrong.) Additionally the `arousal < MIN_AROUSAL_LEVEL (0.18)` branch is **dead**
(`Emotion.setLevel` clamps to ≥ 0.18). Fixed by **Task 2**.

### Finding C — Dominant emotion never influences *which* action is chosen — **[GAP] P1**
Three linked deficiencies vs Campos 2006 (p.80, p.94-95) and Mapa (p.122):
1. **No ActionTendency set.** Campos 2006 gives each emotion a coarse innate action set
   (`HUNGER_ACTIONS`, `SLEEP_ACTIONS`, `CURIOSITY_ACTIONS`, `FEAR_ACTIONS` in the config, p.139), e.g.
   `Hungry → {eat, approach, wander}`, `Sleep → {sleep, wander}`. DL2L has none; the dominant
   `Emotion` (`toRegulate`) is passed to every `ActionFilter.filter(actions, toRegulate)` but **no
   filter reads it**.
2. **Operant conditioning is emotion-blind.** `ProbabilityBasedExperience`/`OperantConditioningActor`
   key probabilities by `(target, action)` only. Mapa's MEE stores an `affectBasic` attribute (the
   drive being regulated) precisely so tendencies are learned **per drive**. Without it the creature
   cannot learn that "eat red-fruit" is good *for hunger* specifically.
3. **Memory filter is emotion-blind.** `MemoryFilter` scores engrams by `(action, objectType)`,
   ignoring `toRegulate`.

Per PR guidance, the fix is **not** a hardcoded per-emotion policy. It is: (a) use the dominant
emotion as a *coarse innate bias* (the ActionTendency set — a soft, pass-through-when-empty prior,
default-off), and (b) make the **learned** layers (operant conditioning + memory filter)
**emotion-conditioned** so the fine choice is learned per drive. The dominant emotion is always one of
the four active emotions, so no complex-emotion election is needed now. Fixed by **Task 3**.

### Finding D — Attention/focus loop is open w.r.t. distance and sleep — **[LOOP] P1**
The Focus Effector should narrow the visual arc as the ASCS nears a target (highlighting it,
suppressing new sightings) and **close the eye during rest** (2015 §III; Campos 2006 §5.2). DL2L sets
`focus = max(MAX_VISION_FIELD_OPENING · efficiency, MIN)` — a function of arousal, not target
distance — and the `SLEEP` branch of `produceCortical` leaves `focus` open. The attentional feedback
loop (approach → narrower field → fewer distractors → stable pursuit) is absent. Fixed by **Task 5**.

### Finding E — Sleep drive is double-incremented when the circadian clock is enabled — **[BUG] P1**
`PartialAppraisal` raises `sleep` via the metabolic `regulateAll(∆)` **and**, when circadian is
enabled, via `AdenosinergicStimulus(driveRate)`. Per PR guidance: with the circadian system **enabled**
the metabolic path must **not** also raise sleep (circadian owns sleep pressure); with it **disabled**
sleep should fall back to the regular metabolic increment. Fixed inside **Task 1**.

---

## 5. Roadmap

Ordered so P0 correctness and its validation land first. Each task is independently shippable.

---

### Task 1 — Scope metabolism, arousal & death to the four active emotions; disable the rest — **P0** [Finding A, E]

**Context.** Only the four emotions we designed — `hunger`, `sleep`, `pain`, `tedium` — are active and
each has a parasympathetic decrease path. The other five (`stress`, `apathy`, `fear`, `curiosity`,
`fertility`) have no regulation path, accumulate under metabolic drift and feed arousal/death, imposing
a hidden ~4550-cycle lifetime cap. Per design direction they are **disabled for now** (complex affects
deferred to a future biologically-grounded model; declared affects undefined). Sleep is also
double-incremented when circadian is on (Finding E).

**Implementation approach.**
- In `EmotionalSystemActor`, define two explicit groups: `ACTIVE = {HUNGER, SLEEP, PAIN, TEDIUM}` and
  `DISABLED = {STRESS, APATHY, FEAR, CURIOSITY, FERTILITY}`. Keep the 9-slot list so the JEPA emotion
  vector is unchanged.
- Make `regulateAll(∆)` (and every regulation entry point) apply **only** to `ACTIVE`. `DISABLED`
  emotions stay pinned at `MIN_AROUSAL_LEVEL` and are never regulated — behaviour of the four active
  emotions is **unchanged** (keep them exactly as designed).
- `getMaxArousal()` and the death check range over `ACTIVE` only. Keep the raw per-emotion accessor for
  the encoder (disabled columns read a constant `MIN_AROUSAL_LEVEL`).
- Do **not** derive `stress`/`apathy` from basics — that approach is rejected (see Revision 2). Leave a
  short code/doc pointer to the deferred complex-emotion work.
- **Sleep drift (Finding E):** when `learningSettings.isCircadianEnabled()`, exclude `SLEEP` from the
  metabolic drift (circadian `AdenosinergicStimulus` is the sole sleep source); when disabled, include
  `SLEEP` in the metabolic drift and do not tick the circadian drive.

**Acceptance criteria.**
- Headless run: `stress`, `apathy`, `fear`, `curiosity`, `fertility` stay at `MIN_AROUSAL_LEVEL` for the
  creature's whole life (unit test on `EmotionalSystemActor`).
- The four active emotions behave exactly as before this change (regression check on a fixed-seed run).
- A well-fed creature no longer dies near 4550 cycles from disabled-emotion arousal.
- With circadian enabled, `sleep` receives exactly one increment per cycle (circadian); with it
  disabled, exactly one (metabolic). Unit test on both modes.
- JEPA emotion vector still exports 9 columns in the documented order; `mvn package` clean.

---

### Task 2 — Fix the swapped behavioural-efficiency curves — **P1** [Finding B]

**Context.** The Yerkes-Dodson/Diamond two-curve model (Mapa Eq 4.1/4.2) is intended, but simple and
complex tasks are wired to each other's curve, and there is a dead `arousal < 0.18` branch.

**Implementation approach.**
- In `normalizedBehaviouralEfficiency`, route **simple tasks** (`perceptionsCount < COMPLEX_TASK`,
  i.e. 0–1 objects) to the **monotonic** `16(1−e^{−0.4A})` curve and **complex tasks** (≥ 2 objects)
  to the **inverted-U** `−(40/49)A² + (280/49)A` curve, matching Mapa Fig 27.
- Remove the unreachable `arousal < MIN_AROUSAL_LEVEL` branch (or document why it must remain).
- Reconcile the normalisation constants (`/15.0`, `/9.303`) against Mapa's stated ranges (0–16, 0–10)
  so both curves land in `[0, 1]`; document the mapping to `[MIN_STEP, MAX_STEP]`.
- Add a comment citing Mapa §4.1.4 / Diamond et al. (2006) and the Eq numbers.

**Acceptance criteria.**
- Unit test: for a **complex** task the efficiency has a single interior maximum `A*` on `[0.18, 7]`
  with `E(0.18) < E(A*) > E(7)` (the inverted-U); for a **simple** task efficiency is monotonically
  increasing in `A`.
- No dead branches; speed still clamps to `[MIN_STEP, MAX_STEP]`; extractor efficiency traces ∈ `[0,1]`.

---

### Task 3 — Emotion-conditioned action selection (learned tendencies + coarse innate bias) — **P1** [Finding C]

**Context.** The dominant emotion must actually shape action choice. Per PR guidance the tendencies
are **learned** (operant conditioning + memory), with an optional coarse innate ActionTendency bias
on top — not a hardcoded policy.

**Implementation approach.**
- **Learned (required):**
  - Add `affectBasic` (the dominant basic drive) to the operant-conditioning key: key probabilities
    by `(affectBasic, target, action)` instead of `(target, action)`. Thread the dominant emotion
    from `FullAppraisal` into `ActionProbabilityFilter` (it already receives `toRegulate`) and into
    `OperantConditioning.varyProbability(...)` / `getProbabilities(...)`. `Valuation` already knows
    the regulated emotion — pass it through so reinforcement is stored per drive.
  - Make `MemoryFilter` use `toRegulate`: prefer engrams whose `affectBasic`/emotion matches the
    dominant drive when disambiguating.
- **Innate coarse bias (design decision — recommended):** introduce a configurable
  `ActionTendency: emotion → Set<ActionType>` (mirroring Campos 2006 `*_ACTIONS`), applied as a
  soft first filter that **passes through unchanged when the intersection is empty** (2015 robustness
  rule). Default map reproduces Campos 2006 (`hunger → {eat, approach, wander}`,
  `sleep → {sleep, wander}`, …). Ship it **disabled by default** behind a `LearningSettings` flag so
  the pure-learning configuration remains the baseline and we can A/B it.

**Acceptance criteria.**
- Operant conditioning learns divergent probabilities for the same `(target, action)` under different
  dominant drives (unit test: reinforce "eat X" under HUNGER, verify it does not raise "eat X" under
  SLEEP).
- With the innate bias enabled and empty intersection, the candidate list is returned unchanged (no
  crash, no starvation).
- Mini-experiment (see §6) shows emotion-conditioning shifts action-criteria frequencies toward the
  paper's Fig 4 profile without reducing mean lifetime.

---

### Task 4 — Close the attention/focus regulation loop — **P1** [Finding D]

**Context.** Focus should narrow with target proximity and the eye should close during sleep.

**Implementation approach.**
- In `FullAppraisal.produceCortical`, for `APPROACH`/`EAT` make `focus` interpolate within
  `[MIN_VISION_FIELD_OPENING, MAX_VISION_FIELD_OPENING]` as a decreasing function of
  `perception.distance`; keep the wide field for `WANDER`/`OBSERVE`/no-target.
- For `SLEEP`, set `focus` to a closed/minimal value and ensure `Eye` emits no `VisualStimulus` while
  the field is closed (gate `Eye` on a closed field).
- No new message types (`FocusStimulus` already exists).

**Acceptance criteria.**
- Scripted scenario: vision-field opening decreases monotonically as `APPROACH` distance shrinks.
- During a `SLEEP` episode the eye is closed and `ObjectSeenState` count == 0 across the window.
- No feeding regression on a smoke run.

---

### Task 5 — Emotion-taxonomy documentation + homeostatic equilibrium band — **P2** [§2, Finding-set]

**Context.** The PR review asked for an explicit strategy segregating reflex/instinct/drive/affect
and stating when each is regulated. §2 is that strategy; this task makes it executable and adds
Mapa's `[0.18, 2]` no-regulation band.

**Implementation approach.**
- Add a short design note (`docs/hld/`) formalising the §2 taxonomy and the sympathetic/parasympathetic
  + MEE/ECQ regulation timing, as the reference for future emotion work — including the deferred
  complex-affect approach (their own biological pathways, e.g. cortisol) and the disabled affects
  (`fear`, `curiosity`, `fertility`).
- Implement the homeostatic band: while the dominant drive's arousal ∈ `[MIN_AROUSAL_LEVEL, 2.0]`,
  suppress self-regulation urgency (e.g. bias toward `WANDER`/`OBSERVE` rather than consummatory
  actions), matching Mapa p.101. Keep it behind a flag; document the chosen upper bound.

**Acceptance criteria.**
- Design note merged and linked from this roadmap.
- With arousal in-band, the creature does not preferentially seek consummatory actions; unit/behaviour
  test on a crafted in-band state.

---

## 6. Validation plan (per CLAUDE.md experiment protocol)

Before/after each P0–P1 task, run a mini-experiment through Docker and analyse in Python; report to
`docs/reports/` with the standard sections (Purpose, Assumptions, Hypothesis, Results, Analysis).

- **Task 1 hypothesis:** removing placeholder-affect accumulation removes the ~4550-cycle lifetime
  ceiling; well-fed creatures live longer and lifetime variance is explained by hunger/sleep, not a
  hidden clock. **Sample:** 50 realisations per arm (matching the sources' n=50). **Metric:** lifetime
  in selected actions `S`; expect a hard pre-fix cap near 4550 absent post-fix.
- **Task 2 hypothesis:** correcting the efficiency-curve assignment changes speed-vs-arousal such that
  complex-task (multi-object) situations show the inverted-U optimum; verify mean speed at high arousal
  drops for complex tasks and rises monotonically for simple tasks.
- **Task 3 hypothesis:** emotion-conditioned learning shifts action-selection-criteria frequencies
  toward Campos/2015 Fig 4 (Affordances ≈ 42%, Memory ≈ 41%, Nearest ≈ 15%, Random ≈ 1%) without
  reducing mean lifetime.

---

## 7. Priority-ordered backlog

| # | Task | Tag | Priority |
|---|---|---|---|
| 1 | Scope metabolism/arousal/death to the 4 active emotions; disable the rest; sleep double-count | BUG | **P0** |
| 2 | Un-swap behavioural-efficiency curves; drop dead branch | BUG | P1 |
| 3 | Emotion-conditioned operant conditioning + memory; optional (default-off) innate tendency bias | GAP | P1 |
| 4 | Distance-driven focus + eye-closed-in-sleep loop | LOOP | P1 |
| 5 | Emotion-taxonomy note + homeostatic equilibrium band | GAP/doc | P2 |

**Highest leverage:** Task 1 removes a hidden lifetime cap that confounds every longevity comparison
(including JEPA). Task 2 is a small, high-confidence correctness fix. Task 3 restores the
emotion→behaviour coupling at the heart of the ARTÍFICE cognitive-emotional process, realised the DL2L
way (learned, not hardcoded).

**Accepted divergences (not work items):** smaller metabolic `∆` (non-blocking architecture);
collapsed Sensory/Effector cortices; inline affordances. Splitting sensory/effector sub-organs is out
of scope.

## 8. Deferred / future work

- **Complex affects (`stress`, `apathy`) modelled from their own biology.** Not functions of the basic
  drives. E.g. `stress` should track a cortisol-like signal produced by *distinct* pathways (prolonged
  discomfort, sleep deprivation, starvation). Revisit once the four active emotions are solid.
- **Additional affects (`fear`, `curiosity`, `fertility`).** Define arousal source, regulation path and
  action tendencies before enabling.
- **Dominant-complex election / `getMaxComplexArousal`.** Only needed once complex affects exist; wire
  `PartialAppraisal` to forward a dominant complex affect alongside the dominant basic drive then
  (Mapa p.122).
- **ProSocialAffect (social emotions).** Requires multi-creature social interaction, out of current
  scope.
