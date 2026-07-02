# Roadmap — Reaching Parity with Campos et al. (2015) "A concurrent, minimalist model for an embodied nervous system"

**Reference:** `docs/bib/2015_Campos_Concurrent_Minimalist_Agent.pdf`
**Scope:** compare the current DL2L creature architecture against the nervous-system model
described in the paper, identify functional gaps, missing stimulation loops and regulation
paths, catalogue latent bugs, and lay out an implementable task list to close the distance.

**How to read this.** DL2L has evolved *beyond* the 2015 paper (it adds pain/nociception,
tedium/curiosity, a circadian clock, sleep-driven memory consolidation, olfaction and a JEPA
world model). So "gap" here means one of two things: (a) a mechanism the paper specifies that
DL2L never realised, or (b) a mechanism DL2L realised in a way that *contradicts* the paper's
intent and is worth reconsidering. Every item is tagged **[GAP]**, **[LOOP]** (missing/incomplete
stimulation-regulation loop), **[BUG]**, or **[DIVERGENCE]** (intentional evolution — recorded
for completeness, generally *not* something to "fix"). Priorities: **P0** correctness, **P1**
behavioural fidelity, **P2** structural fidelity.

---

## 1. The reference model in one page

Subsystems (paper Fig. 1), grouped by function:

| Group | Elements |
|---|---|
| Sensory | Vision Sensor, Gustatory Sensor (mediate signals from the organs) |
| Sensory organs | Eye, Mouth, Rest-of-body (skin, tactile), (Nose is **not** in the paper) |
| Motor organs | Mouth, Rest-of-body, Skin effector |
| Cognitive | Partial Appraisal, Full Appraisal, Action Selection, **Affordances** |
| Emotional | Homeostatic Regulation (HR), Valuation, **Behavioural Efficiency**, **Drives** |
| Memory | Working Memory (last action), Long-Term Memory (experiences + emotional value) |
| Effector | Focus Effector, Mouth Effector, Locomotion Effector |

Message/stimulus vocabulary (paper Fig. 1): Luminous, Visual, Mechanical, Tactile, Spatial,
Nutritious→Nutritional, Decrease, Increase, Emotional, Valuational, Cortical/Cognitive,
Somatic, Movement, Attentional, Reflex, Activate, Destructive.

Core dynamics the paper commits to:

1. **Two drives only** — Hunger and Rest — with a deprivation level in `[Pmin=0.18, Pmax=7.0]`.
   Reaching `Pmax` kills the organism. Each step adds a fixed metabolic increment `∆=0.005` to
   **both drives**; eating subtracts the fruit's nutritional value from Hunger.
2. **Behavioural efficiency** `E = −aA² + bA + c` (Hebb concave arousal→efficiency curve), a
   single quadratic with a unique optimum; `E` governs step speed.
3. **Field-of-view modulation** — the visual arc narrows (50°) as the organism *approaches* a
   target and widens (150°) when nothing is in view; it **closes** while resting/sleeping.
4. **Action Selection = cascade of filters** until one action remains (Algorithm 1):
   `Affordances(situational)` → `∩ ActionTendency(dominant drive)` → `Nearest` (drop identical
   farther targets) → `Memory (LTM-weighted random)` → `Random`.
5. **Memory** — WM stores the last chosen action; LTM stores each experience with a **boolean**
   emotional value (`true` iff `N + D ≥ 0`) and an additive weight `Wᵢ = Wᵢ₋₁ + Qᵢ`. Positive
   experiences are selected with probability proportional to their value; negative ones are never
   selected.

---

## 2. Current-vs-paper mapping

| Paper element | DL2L realisation | Status |
|---|---|---|
| Vision Sensor | folded into `Eye` → `SensoryCortex` | [DIVERGENCE] P2 |
| Gustatory Sensor + Nutritional→Decrease | `Mouth` emits `NutritiveStimulus` straight to HR; HR does `regulate(HUNGER, −value)` | [DIVERGENCE] P2 |
| Eye / Mouth / skin | `Eye`, `Mouth` (tactile via `MechanicalStimulus`) | present |
| Nose / olfaction | `Nose`, `OlfactoryStimulus` | addition (not in paper) |
| Partial Appraisal | `PartialAppraisal` | present |
| Full Appraisal | `FullAppraisal` | present |
| Action Selection cascade | `ActionSelection` + `ActionFilter` chain | present, **order & content differ** |
| **Affordances** structure | implicit in `FullAppraisal.actionsForPerception(...)` | [GAP] P2 |
| **ActionTendency(dominant drive)** | **absent** — `toRegulate` Emotion is threaded through filters but unused | [GAP] P1 |
| Homeostatic Regulation | `HomeostaticRegulation` | present, **scope differs** |
| Drives = {Hunger, Rest} | 9 emotions, only Hunger/Sleep/Pain/Tedium live; 5 are inert placeholders | [BUG] P0 (see §4.1) |
| Behavioural Efficiency (Hebb quadratic) | `PartialAppraisal.normalizedBehaviouralEfficiency` — 3-branch ad-hoc curve | [DIVERGENCE] P1 |
| Valuation / emotional value | `Valuation` → `OperantConditioning` + engram reinforcement | [DIVERGENCE] P2 |
| Working Memory | `ShortTermMemory` | present |
| Long-Term Memory (boolean value + weight) | engrams w/ eligibility + operant probabilities | [DIVERGENCE] P2 |
| Focus / Mouth / Locomotion Effector | all folded into `EffectorCortex` | [DIVERGENCE] P2 |
| Focus effector: narrow-on-approach, close-on-sleep | focus ∝ efficiency; **never distance-driven; eye stays open in SLEEP** | [LOOP] P1 (see §3.2) |
| Metabolic Increase → Hunger & Rest | `AdrenergicStimulus` → HR `regulateAll(∆)` over **all 9 emotions** | [BUG] P0 (see §4.1) |
| Death at `Pmax` | `PartialAppraisal` kills when `getMaxArousal() ≥ 7` | present but see §4.1 |

---

## 3. Missing / incomplete stimulation loops & regulation paths

### 3.1 [GAP] Drive → Action-Tendency gating (paper Algorithm 1, line 3) — **P1**
The paper intersects the situational affordances with the *action tendencies of the dominant
drive* before any other filter, so the currently-attended need biases what the organism even
considers. In DL2L the dominant `Emotion` (`toRegulate`) is passed into
`ActionSelection.selectOne(...)` and forwarded to every `ActionFilter.filter(actions, toRegulate)`,
but **no filter reads it**. The dominant drive therefore influences only movement speed (via
behavioural efficiency), never action choice. This is the single largest behavioural divergence
from the model. Covered by Task 2.

### 3.2 [LOOP] Focus/attention regulation is open-loop w.r.t. target distance and sleep — **P1**
Paper: the Focus Effector narrows the visual arc as the organism nears a target (highlighting it,
suppressing new sightings) and **closes the eye during rest**. DL2L computes
`focus = max(MAX_VISION_FIELD_OPENING · efficiency, MIN)` in `FullAppraisal.produceCortical`, which
is a function of arousal, not target proximity; and the `SLEEP` branch sets `speed = 0` but leaves
`focus` at its efficiency-derived value, so the eye never closes. The attentional feedback loop
(approach → narrower field → fewer distractor sightings → stable pursuit) is absent. Covered by
Task 5.

### 3.3 [GAP] No explicit Affordances table keyed by situation — **P2**
Paper Table I encodes `(target, seeing | seeing+touching | not-seeing) → allowed actions` as a
hardwired structure. DL2L hardcodes equivalent logic inline in
`FullAppraisal.actionsForPerception / actionsAtDistance / actionsAtContact`. Functionally close,
but there is no first-class, inspectable, configurable affordance map, which makes it hard to (a)
verify parity and (b) extend per object type. Covered by Task 6.

### 3.4 [DIVERGENCE] Sensory & effector sub-organs collapsed — **P2**
Vision Sensor, Gustatory Sensor, and the three effectors (Focus/Mouth/Locomotion) are folded into
`SensoryCortex` / `EffectorCortex`. This is a reasonable simplification but hides the paper's
message taxonomy (Nutritional, Decrease, Attentional, Somatic-per-organ). Optional structural
refactor — Task 7.

---

## 4. Latent bugs

### 4.1 [BUG] Metabolic increment inflates all nine emotions, including five with no decay path — **P0**
`PartialAppraisal.onReceive` emits an `AdrenergicStimulus(∆)` every cognitive cycle;
`HomeostaticRegulation.handleAdrenergic` calls `creature.emotions().regulateAll(∆)`, and
`EmotionalSystemActor.regulateAll` adds `∆` to **every** emotion in `simpleEmotions`:
`hunger, sleep, apathy, stress, pain, tedium, fear, curiosity, fertility`.

Two consequences:
1. **APATHY, STRESS, FEAR, CURIOSITY, FERTILITY have no regulation handler anywhere** (grep
   confirms HR only ever touches HUNGER, SLEEP, PAIN, TEDIUM). They therefore increase
   monotonically and *never* decrease.
2. `PartialAppraisal` kills the creature when `getMaxArousal() ≥ MAX_AROUSAL_LEVEL (7)`, and
   `getMaxArousal()` ranges over **all nine** emotions.

Net effect: regardless of how well the creature feeds, the five inert placeholder emotions climb
at `∆ = 1.5e-3`/cycle and cross 7 at ~`(7 − 0.18)/1.5e-3 ≈ 4547` cycles, imposing a hard,
undocumented lifetime ceiling and biasing every longevity measurement. Pain and tedium are also
double-counted: they receive the metabolic `∆` here *and* their own dedicated stimuli
(`NociceptiveStimulus`, `TediumStimulus`).

The paper is explicit that the metabolic Increase updates **only Hunger and Rest**. Covered by
Task 1.

### 4.2 [BUG] `getMaxArousal` (death & efficiency signal) is polluted by placeholder emotions — **P0, follows from 4.1**
Even after 4.1 is fixed for the *increment*, `getMaxArousal()` and behavioural-efficiency inputs
should be defined over the **active drive set**, not over inert placeholders that exist only to
shape the JEPA emotion vector. Death and speed must be driven by real needs. Covered by Task 1
(shared fix: define an explicit "active drive" set and compute arousal/death over it).

### 4.3 [BUG] Dead branch in behavioural-efficiency curve — **P2**
`normalizedBehaviouralEfficiency` branches on `arousal < Constants.MIN_AROUSAL_LEVEL (0.18)`, but
`Emotion.setLevel` clamps every level to `>= 0.18`, so that branch is unreachable. Harmless today,
but it signals the efficiency curve was never reconciled with the clamp or with the paper's single
quadratic. Covered by Task 4.

### 4.4 [DIVERGENCE→verify] Behavioural-efficiency constants are undocumented and non-concave-by-parts — **P1**
The 3-branch formula (linear ramp / quadratic / saturating-exponential) replaces the paper's single
Hebb quadratic `E = −aA² + bA + c` with `a=−40/49, b=40/7, c=0`. No derivation is recorded and the
piecewise form is not guaranteed to have a single interior optimum, which is the whole point of the
Hebb curve (there must be an arousal level of *maximum* efficiency). Needs either a documented
justification or a return to the paper's quadratic. Covered by Task 4.

### 4.5 [OBSERVATION] Sleep drive receives two independent increments — **P1**
`SLEEP` is incremented by the circadian `AdenosinergicStimulus` (Task-correct) *and* by the
metabolic `regulateAll`. Once 4.1 restricts metabolism to {Hunger, Sleep} this is intentional
(metabolic tiredness + circadian rhythm), but the two rates (`DELTA` vs
`BASE_SLEEP_DRIVE ± CIRCADIAN_AMPLITUDE`) should be reconciled so sleep pressure is calibrated, not
accidental. Folded into Task 1's acceptance criteria.

---

## 5. Roadmap

Tasks are ordered so the P0 correctness fixes and their validation land first. Each task is
independently shippable and independently testable.

---

### Task 1 — Restrict metabolism & death to the active drive set — **P0** [BUG 4.1/4.2/4.5]

**Context.** `regulateAll(∆)` inflates all nine emotions; five have no decay path; death and
efficiency read `getMaxArousal()` over all nine. This creates a hidden lifetime cap and biases
longevity results. The paper's metabolic Increase and the death condition are defined over the two
drives only (Hunger, Rest).

**Implementation approach.**
- Introduce an explicit notion of *active drives* in `EmotionalSystemActor` — e.g. a
  `Set<String> ACTIVE_DRIVES = {HUNGER, SLEEP}` (extendable to PAIN/TEDIUM if we want them to
  count toward arousal/death; decide and document this).
- Replace `regulateAll(delta)` semantics: `handleAdrenergic` must apply the metabolic `∆` to the
  active drives only. Either add `regulateActiveDrives(delta)` or have HR emit targeted regulate
  calls for HUNGER and SLEEP. Keep the 9-element vector intact for the JEPA contract — placeholders
  simply stay at `MIN_AROUSAL_LEVEL`.
- Change `getMaxArousal()` (and any death/efficiency use) to range over the active-drive set. Keep
  the raw per-emotion accessor for the world-model encoder.
- Reconcile sleep pressure: confirm metabolic `DELTA` on SLEEP plus circadian drive gives a
  sensible pre-fix-equivalent tiredness curve; adjust `BASE_SLEEP_DRIVE` if needed and record the
  chosen values.
- Audit `model_contract.json` emotion index order is untouched (the vector still has 9 slots).

**Acceptance criteria.**
- In a headless run, APATHY/STRESS/FEAR/CURIOSITY/FERTILITY remain at `0.18` for the creature's
  whole life (assert via extractor or a unit test on `EmotionalSystemActor`).
- A well-fed creature no longer dies at ~4550 cycles from placeholder arousal; lifetime is bounded
  by hunger/sleep dynamics only.
- `mvn package` clean; existing emotional-system / HR tests updated and green.
- The JEPA emotion vector still exports 9 columns in the documented order.

---

### Task 2 — Implement Drive → Action-Tendency gating in Action Selection — **P1** [GAP 3.1]

**Context.** Algorithm 1 line 3 intersects situational affordances with the action tendencies of
the *dominant drive*, so the attended need shapes the candidate set. DL2L threads the dominant
`Emotion` into the filter chain but no filter uses it.

**Implementation approach.**
- Define an `ActionTendency` map: `drive → Set<ActionType>` (e.g. `HUNGER → {APPROACH, EAT,
  WANDER, OBSERVE}`, `SLEEP → {SLEEP, AVOID}`, plus PAIN/TEDIUM tendencies if those become active
  drives). Keep it a first-class, configurable structure (see Task 6 — same config surface).
- Add an `ActionTendencyFilter implements ActionFilter` inserted **first** in
  `LearningSettings.MASTER_FILTER_ORDER` (before `TARGET_DISTANCE`), matching the paper's ordering
  `Affordances → ∩ ActionTendency → Nearest → Memory → Random`. It reads the `toRegulate` Emotion
  it already receives and drops actions not in the dominant drive's tendency set — but must
  **never empty the list** (if the intersection is empty, pass through unchanged, per the paper's
  robustness).
- Reconcile filter order: today it is `TARGET_DISTANCE → AFFORDANCE → MEMORY → WORLD_MODEL →
  RANDOM`. The paper is `tendency → nearest → memory → random`. Decide the canonical order and
  document why WORLD_MODEL slots where it does.

**Acceptance criteria.**
- With SLEEP dominant and a fruit in view, `EAT`/`APPROACH` candidates are gated out unless
  tendency includes them; unit test on the new filter with a stubbed `Emotion`.
- Empty-intersection input returns the input unchanged (no crash, no starvation of options).
- A/B mini-experiment (see §6) shows the tendency filter changes action-criteria frequencies
  toward the paper's Fig. 4 profile without collapsing lifetime.

---

### Task 3 — Metabolic Increase strictly two-drive, matching ∆ semantics — **P1** [BUG 4.1 follow-through]

**Context.** Separate from the arousal/death scope (Task 1), the *metabolic Increase message* in
the paper is a fixed `∆` added to Hunger and Rest each step. DL2L's `DELTA = 1.5e-3` differs from
the paper's `0.005`, and the increment currently rides on `regulateAll`.

**Implementation approach.**
- After Task 1, make the adrenergic metabolic path explicitly emit/regulate HUNGER and SLEEP by
  `∆`. Expose `∆` (and the sleep-specific rate) via `Constants` with a comment tying them to the
  paper's Table II so future calibration is traceable.
- Decide whether to adopt the paper's `∆ = 0.005` or keep `1.5e-3`; either way, document the choice
  and the resulting `Mmax = (Pmax − Pmin)/∆` theoretical no-eat lifetime as a sanity anchor.

**Acceptance criteria.**
- Unit test: one `AdrenergicStimulus` raises HUNGER and SLEEP by exactly `∆` and leaves all other
  emotions unchanged.
- `Mmax` computed from configured `∆` matches an observed starve-to-death run within tolerance.

---

### Task 4 — Reconcile behavioural-efficiency curve with the Hebb quadratic — **P1** [BUG 4.3/4.4]

**Context.** The paper's `E = −aA² + bA + c` is a single concave curve with a unique optimum;
DL2L's 3-branch curve has undocumented constants and a dead `arousal < 0.18` branch.

**Implementation approach.**
- Either (a) replace `normalizedBehaviouralEfficiency` with the paper's quadratic (normalised so
  `E ∈ [0,1]` maps to `[MIN_STEP, MAX_STEP]`), or (b) if the piecewise curve is intentional (e.g.
  the exponential branch models complex-task behaviour beyond the paper), **document each branch's
  rationale and its domain**, and remove the unreachable `arousal < MIN_AROUSAL_LEVEL` branch.
- Verify the chosen curve has a single interior maximum on `[0.18, 7]` (that is the Hebb property).
- Feed efficiency from the active-drive arousal (Task 1), not raw `getMaxArousal` over placeholders.

**Acceptance criteria.**
- Unit test asserts a unique arousal `A*` maximising `E`, and `E(0.18) < E(A*) > E(7)`.
- No dead branches; `check_collapse`/extractor efficiency traces still in `[0,1]`.
- Speed still clamps to `[MIN_STEP, MAX_STEP]`.

---

### Task 5 — Close the attention/focus regulation loop — **P1** [LOOP 3.2]

**Context.** The Focus Effector should narrow the field as the organism approaches a target and
close the eye during rest; today focus is arousal-driven and the eye stays open in SLEEP.

**Implementation approach.**
- In `FullAppraisal.produceCortical`, make `focus` depend on the target's `perception.distance`
  for `APPROACH`/`EAT` (narrower as distance → 0), interpolating within
  `[MIN_VISION_FIELD_OPENING, MAX_VISION_FIELD_OPENING]`; keep the wide field for `WANDER`/`OBSERVE`
  and when no target is defined.
- For `SLEEP`, set `focus` to a closed/minimal value so `Eye` effectively stops seeing (the paper's
  "eye shut while resting"). Confirm `Eye.setVisionFieldOpening` handles the closed case (no
  spurious `LuminousStimulus` processing while asleep — or gate `Eye` on a closed field).
- Keep the change purely in the cortical→focus→eye path; no new message types required
  (`FocusStimulus` already exists).

**Acceptance criteria.**
- Extractor/log shows vision-field opening monotonically decreasing as `APPROACH` distance shrinks
  in a scripted scenario.
- During a `SLEEP` episode the eye is closed and no `VisualStimulus` is emitted (assert on
  `ObjectSeenState` count == 0 across the sleep window).
- No regression in feeding behaviour in a smoke run.

---

### Task 6 — First-class, configurable Affordances table — **P2** [GAP 3.3]

**Context.** Paper Table I is an explicit `situation → actions` map; DL2L hardcodes it inline in
`FullAppraisal`. A first-class table makes parity auditable and per-type extension trivial, and it
is the natural home for the Task-2 tendency map.

**Implementation approach.**
- Extract `actionsForPerception / actionsAtDistance / actionsAtContact` into an `Affordances` class
  keyed by `(InteractionState seeing|touching|smelling|none, WorldObjectType)` → `List<ActionType>`.
- Provide a default map that reproduces current behaviour exactly (regression-safe), then allow
  overrides from the simulation config (`simulations/*.conf`).
- Co-locate the `ActionTendency` map (Task 2) so both live behind one configurable façade.

**Acceptance criteria.**
- `FullAppraisal` delegates candidate generation to `Affordances`; behaviour byte-identical to
  pre-refactor on a fixed-seed run (diff the chosen-action stream).
- A config override can add/remove an affordance without code change (unit test).

---

### Task 7 (optional) — Restore the paper's sensory/effector message taxonomy — **P2** [DIVERGENCE 3.4]

**Context.** Vision/Gustatory sensors and Focus/Mouth/Locomotion effectors are collapsed into the
two cortices. Splitting them would make the message vocabulary (Nutritional, Decrease, Attentional,
per-organ Somatic) explicit and closer to Fig. 1, at the cost of more actors.

**Implementation approach.**
- Only pursue if a downstream need (e.g. finer JEPA action/observation channels, or teaching
  fidelity) justifies it. If so, introduce `GustatorySensor` (Nutritional→Decrease) and split
  `EffectorCortex` into Focus/Mouth/Locomotion effectors emitting distinct stimuli.
- Keep it behaviour-preserving; this is a structural refactor, not a behavioural change.

**Acceptance criteria.**
- Message flow matches Fig. 1 labels; no behavioural diff on a fixed-seed run.
- Documented decision record if we choose *not* to do this (so it is not re-litigated).

---

## 6. Validation plan (per CLAUDE.md experiment protocol)

Before/after each P0–P1 task, run a mini-experiment through Docker and analyse in Python:

- **Hypothesis (Task 1):** removing placeholder-emotion inflation removes the ~4550-cycle lifetime
  ceiling; well-fed creatures live longer and lifetime variance is explained by hunger/sleep, not a
  hidden clock. **Sample:** 50 independent realisations (matching the paper's n=50) per arm
  (before/after). **Metric:** lifetime in selected actions `S`; expect the pre-fix distribution to
  show a hard cap near 4550 that the post-fix distribution lacks.
- **Hypothesis (Task 2):** drive-tendency gating shifts action-selection-criteria frequencies
  toward the paper's Fig. 4 profile (Affordances ≈ 42%, Memory ≈ 41%, Nearest ≈ 15%, Random ≈ 1%)
  and does not reduce mean lifetime.
- Report to `docs/reports/` with the standard sections (Purpose, Assumptions, Hypothesis, Results,
  Analysis) and the lifetime/criteria-frequency figures.

---

## 7. Priority-ordered backlog summary

| # | Task | Tag | Priority |
|---|---|---|---|
| 1 | Active-drive-only metabolism, arousal & death | BUG | **P0** |
| 3 | Two-drive metabolic Increase with documented ∆ | BUG | P1 |
| 2 | Drive→ActionTendency gating filter | GAP | P1 |
| 4 | Hebb-consistent behavioural-efficiency curve | BUG/DIV | P1 |
| 5 | Distance-driven focus + eye-closed-in-sleep loop | LOOP | P1 |
| 6 | First-class configurable Affordances table | GAP | P2 |
| 7 | Split sensory/effector sub-organs (optional) | DIV | P2 |

The two **P0/P1 bug fixes (Tasks 1 & 3)** are the highest-leverage: they remove a hidden lifetime
cap that silently confounds every longevity comparison, including the JEPA experiments. **Task 2**
is the largest *behavioural* fidelity gain relative to the paper. Tasks 4–7 tighten fidelity and
maintainability.
