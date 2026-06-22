# High-Level Design: World-Model Integration for DL2L

**Status:** Draft for discussion. Captures the design reasoning developed before any
implementation. Companion to the phased roadmap (`JEPA_Integration_Roadmap_v5.md`) and the
instrumentation roadmap addendum.

**Audience:** Anyone touching the `creature` package, plus whoever implements the
PyTorch/DJL side. Read this before the roadmap — the roadmap is *what* to build; this is
*why*, and the *why* is where the non-obvious constraints live.

---

## 1. Goal and scope

DL2L implements the Campos et al. (2015) "Artifice" minimal nervous system: an embodied,
emotion-driven creature whose subsystems run concurrently and asynchronously as Akka actors,
producing coherent foraging behaviour with no central controller and no training phase. It
maps cleanly onto five of the six modules in LeCun's *A Path Towards Autonomous Machine
Intelligence* (perception, intrinsic cost, critic, actor, short-term memory) but has **no
world model** — nothing that predicts the consequences of an action before taking it.

The goal is to add that missing module: a learned model that lets the creature evaluate
candidate actions by their predicted *emotional* consequences, enabling deliberative
(LeCun "Mode-2") action selection on top of the existing reactive (Mode-1) loop — **without
betraying the architecture's foundational commitment to continuous-time, asynchronous,
uncoordinated operation and lifelong (not phase-separated) learning.**

Two hard constraints follow from that commitment and shape every decision below:

- **No global clock.** The system is time-continuous and has no simulation tick. Any
  mechanism that needs "when" must use a *per-creature, message-paced* notion of time, not
  wall-clock and not a global counter.
- **Learning is lifelong, not a separate phase.** Training and inference must coexist. We
  reconcile this with neural-network training (which wants batches) via a sleep-gated
  two-timescale scheme (Section 4).

---

## 2. How DL2L maps onto LeCun's architecture

| LeCun module | DL2L counterpart | State today |
|---|---|---|
| Perception | `Eye`, `Nose`, `SensoryCortex`, `Perception` | Present, complete |
| Intrinsic cost | `Emotion` + `HomeostaticRegulation` (bounded, hard-wired arithmetic) | Present; only 2 of 9 emotion dims live |
| Critic | `Valuation` + `OperantConditioning` | Present but tabular over a fixed fruit enum; no generalization |
| Actor | `FullAppraisal` + `ActionSelection` filter chain | Present but reactive; evaluates only current affordances, no rollout |
| Short-term memory | `MemorySystem` / `ShortTermMemory` | Largely stub; STM writes happen but reads return empty |
| **World model** | **— none —** | **The gap this project fills** |

The intrinsic-cost mapping is the strongest and most important: LeCun specifies the
intrinsic cost as immutable, non-trainable, and computing pain/pleasure/hunger as a scalar
energy. `HomeostaticRegulation.regulate(...)` is exactly that — pure bounded arithmetic, no
learning. This is why the emotional system is the natural anchor for the critic: we are
learning to *predict* a signal the architecture already computes by hand.

---

## 3. The core problem: credit assignment without a clock

### 3.1 Why the existing system needs no world model

Trace an eating event in the current code. The creature eats; the *nutrient* emits a
`NutritiveStimulus`; `HomeostaticRegulation` lowers hunger and emits an `EvaluationStimulus`
that **hard-codes `ActionType.EAT`**. The system never asks "what did I just do?" — it knows
the causing action by construction, because in the current world *only an EAT can produce a
nutritive stimulus*. Credit assignment is solved by the physics of the world (one consequence,
one possible cause), not by memory.

This is robust precisely because every rewarding signal is delivered through a
type-dedicated pathway that identifies its own cause. It is also why the architecture has,
to date, needed nothing resembling a world model or a general credit-assignment mechanism.

### 3.2 Why adding a world model breaks that

A world model must learn transitions that are **not** consummatory acts with dedicated
pathways — the long-horizon chains ("I was hungry, I wandered, I found and ate food, now I'm
sated") where the rewarding signal *cannot* identify its own cause from its type. The
nutritional stimulus that satiates the creature arrives as an ambient world event with no
provenance linking it to the locomotion several decisions earlier that made it possible.

Critically: **this link was never represented in the architecture.** It isn't hidden or
discarded — it does not exist as an object. No amount of downstream correlation can
reconstruct a causal link that timing never carried. This is the classic *credit-assignment
problem* (Bennett, *A Brief History of Intelligence*), and it is the true root of the
integration challenge.

### 3.3 Why a clock — global *or* wall-clock — is the wrong fix

Three correlation strategies were considered and rejected:

- **Global tick / cycleId.** Contradicts the architecture's defining premise (no supervising
  agent, no global clock). Rejected on principle.
- **Causal/provenance IDs threaded through the consequence path.** Works only when the
  consequence is *delivered as a consequence*. Here the nutrient emits an ambient event with
  no return path to thread an ID through; threading one would couple world objects to creature
  decision episodes — a worse violation than the clock. Rejected: no path to carry the ID.
- **Wall-clock (`System.nanoTime`) eligibility decay.** Tempting because all components share
  one JVM. Rejected because it makes *what a creature learns depend on machine load*: under
  scheduler pressure / GC / more creatures per node, more wall-clock time elapses between an
  action and its consequence, changing the association. The OL2A work shows scaling creature
  count already perturbs timing; wall-clock decay would couple learning to that. The damage is
  invisible (hidden in a decay constant, not in any message field).

**Even a global clock would not fix this**, because the creature is continuously hungrier
every cycle regardless of what it did. Temporal adjacency and causality have come apart:
"what happened right after the action" is not reliably "the consequence of the action."
Post-hoc correlation by time is structurally unable to solve this.

### 3.4 The chosen mechanism: eligibility traces on the creature's own cognitive clock

Adopt eligibility traces (Bennett's first credit-assignment primitive): a committed action
leaves a decaying tag in the creature; when a reinforcing emotional change arrives later, it
reinforces whatever traces are still warm. This supplies exactly the link the architecture
lacks, using a biologically real, *local* mechanism — no global coordination, no provenance
threading.

**The trace decays over the creature's own cognitive-cycle count, not wall-clock time.** A
"cognitive cycle" is one batched `onReceive` pass. The codebase already paces everything this
way: the custom mailbox batches all queued stimuli into one list per `onReceive`, and
`PartialAppraisal` is driven by a scheduler-injected periodic message (Akka actors are
otherwise purely reactive). The creature's subjective "now" *is* one such pass. Decaying by
counting passes is therefore not merely acceptable — it is the only choice consistent with how
every other rhythm in the creature is defined, and it is immune to machine-load effects by
construction.

The existing type-dedicated fast path (EAT/SLEEP → `EvaluationStimulus`) is left untouched: it
is effectively a length-1 trace that the world disambiguates for free. The trace mechanism
handles only the cases the fast path cannot.

### 3.5 Selecting among competing cues (later phase)

Eligibility traces solve *when* (bridge the temporal gap). They do **not** solve *which* —
when several cues are simultaneously eligible, which deserves the credit. Bennett's other
three mechanisms address this and are noted here as future refinements, not v1 scope:

- **Overshadowing** — salient cues capture associative strength from weaker co-present ones.
- **Blocking** (Kamin) — a cue already predicting the outcome leaves no prediction error for a
  newly added cue. Note `OperantConditioning` is already Rescorla–Wagner-flavoured, so
  prediction-error-driven learning is partly present; the open question is whether it carries
  into the world model.
- **Latent inhibition** — pre-exposed-without-consequence cues are harder to associate later
  (a learned salience prior favouring novelty).

Without at least one *which*-mechanism, the world model will learn spurious correlations
(e.g. "a green fruit was visible" → "hunger rose", because hunger rises every cycle and fruit
is often visible). This is the exact failure overshadowing/blocking exist to suppress, and it
is the same "ignore the trees by the roadside" invariance LeCun wants the JEPA encoder to
learn. Sequencing: ship traces first; add cue-competition once the trace pipeline is
producing engrams.

---

## 4. Reconciling lifelong learning with neural-network training

Neural JEPA training wants batched, diverse, roughly-stationary data and an anti-collapse
regulariser that needs a reasonable batch to estimate the latent distribution. A single
creature's per-tick experience stream is the opposite: small, autocorrelated, non-stationary.
Naive per-cycle SGD would likely collapse or drift.

Resolution mirrors biological **systems consolidation**: fast, cheap changes during waking
experience; slow, batch-like consolidation during sleep, when recent experience is replayed.
DL2L already has the behavioural hook — `CholinergicStimulus` and the `SLEEP` action, during
which the eye closes and `speed == 0`. That sleep state is a free, architecturally native gate
for "now is when batch training runs."

Two timescales:

- **Waking (fast, online):** lay down eligibility traces; form engrams when reinforcement
  lands; optionally update a small per-creature adapter via cheap, reversible fast-weights-style
  deltas. No collapse-prone batch objective runs here.
- **Sleep (slow, batched, gated, cancellable):** a `WorldModelActor` on its own dispatcher
  pulls recent engrams and runs a bounded number of gradient steps on the freshest experience
  (test-time-adaptation-scale, not full retraining). Interruptible: if the creature is woken
  (predator, circadian end), training aborts cooperatively (Section 6).

The heavy "species" base model is trained fully offline on population data and frozen
(LeWorldModel-style, SIGReg anti-collapse). Each creature carries a small additive adapter
(not LoRA — at this network scale low-rank buys nothing) that its lifetime experience updates.
This respects Campos's innate/learned split (affordances are "hardwired, not learnt"): the
species base is innate, the adapter is individual.

---

## 5. Data model: what an engram is, and where it comes from

An **engram** is one experiential transition. Given Section 3, the cleanly observable form is:

```
Engram = ( s_t , a_t , Δemotion , cognitiveCycle )
```

- `s_t` (`Perception`) and `a_t` (`CorticalStimulus`) are **bonded at the decision point by
  construction** — confirmed in `FullAppraisal.produceCortical`, which does
  `Perception perception = action.perception` and builds the `CorticalStimulus` with
  `action.perception.id` as its `target`. No correlation needed; they share one object graph.
- `Δemotion` is the reinforcement signal, sourced from a snapshot already computed in
  `HomeostaticRegulation` (the `before`/`after` `EmotionalState` pair persisted inside
  `InternalDynamicState`). Today that delta is persisted for offline analysis and otherwise
  discarded; the trace mechanism *routes* it to warm traces instead of only to the
  type-determined `EvaluationStimulus`.
- `cognitiveCycle` is the per-creature pass counter that times trace decay.

Note we deliberately model **emotional consequences of actions**, not full perceptual
next-states `s_{t+1}`. The nutrient example shows `s_{t+1}` is not recoverable as a clean
consequence, and an emotion-driven architecture arguably wants the emotional-consequence model
first anyway. This makes v1 closer to LeCun's *critic* (state+action → future intrinsic cost)
than his full *predictor* (state+action → next state). The full predictor is deferred until we
decide whether/how to represent perceptual outcomes.

---

## 6. Key technical risks and the decisions that address them

1. **No cross-actor message ordering.** Akka guarantees order only per single sender→receiver
   pair; `MemorySystem` has three senders, and the custom mailbox strips sender identity and
   batches without sub-order guarantees. → Never assemble engrams by arrival order. The
   decision-point bonding (Section 5) sidesteps this for `(s_t, a_t)`; the trace handles the
   outcome. *(This invalidated the original v4 plan's linear STM state machine.)*

2. **Per-stimulus vs per-batch reinforcement delta.** Inside one `onReceive` batch, multiple
   regulating stimuli may arrive; the current loop's per-stimulus `before` is order-dependent
   (each baseline includes prior in-batch effects). Three options: pure per-batch
   (order-independent, blurs distinct causes); naive per-stimulus (sharp, order-dependent
   noise); **per-stimulus against a frozen batch-entry baseline (sharp AND order-independent)**.
   The third is preferred *if* multi-regulation batches are common — **which the instrumentation
   phase measures before we commit** (see addendum). If such batches are rare, pure per-batch
   is simpler with near-identical behaviour.

3. **Distribution shift (offline → online).** The species model is trained on baseline
   (Mode-1) trajectories but, once the filter steers behaviour, visits unseen states. → A
   Phase-0 coverage probe, a live latent-prediction-error monitor, and confidence-gating that
   self-disables the filter (degrading to Mode-1) when error is high.

4. **DJL `Predictor` is not thread-safe; CEM inference is per-cycle and costly.** → Load the
   model once per node but never share one `Predictor` across creature actors (pool /
   ThreadLocal / per-call). Impose a hard per-cycle inference budget; over budget → Mode-1
   fallback; gate Mode-2 to fire only when deliberation is warranted.

5. **Cancellable sleep training.** `CompletableFuture.cancel(true)` does **not** interrupt an
   already-running `runAsync` body. → Cooperative abort via an `AtomicBoolean` checked between
   batches, with `NDManager` closed in a `finally` to avoid native-memory leaks (the very OOM
   the design is trying to avoid).

6. **Graceful degradation is mandatory, not optional.** The world model is one `ActionFilter`
   in the existing chain. Uninitialised, mid-consolidation, or low-confidence → return
   possibilities unmodified. The filter never throws and never blocks the cognitive cycle. This
   is the safety property that makes the whole integration incrementally shippable: every step
   before the filter is enabled leaves the creature behaving exactly as it does today.

---

## 7. Component additions at a glance

| New component | Package | Role |
|---|---|---|
| Eligibility trace buffer | `creature.memory` | Holds warm `(s_t, a_t, emotion@t, cycle)` tags; decays by cognitive cycle; reinforced by Δemotion |
| `MemorySystem` (rework) | `creature.memory` | Becomes a real store: engrams in, episodic retrieval out (currently stub) |
| `WorldModelActor` | `creature.ml` | Sleep-gated, cancellable consolidation on its own dispatcher |
| `WorldModelEngine` / DJL impl | `creature.ml` | Wraps species base + individual adapter; inference + adapter training |
| `MLServiceProvider` | `creature.ml` | One model load per JVM node; injected into creatures |
| `WorldModelFilter` | `creature.actionSelector` | Mode-2: CEM over continuous action params, scored by predicted emotional cost; budgeted, optional |
| Species JEPA (PyTorch) | `/ml` (offline) | Offline-trained, SIGReg-regularised, exported to TorchScript |
| Trace-instrumentation extractors + analysis | `analysis` + `/analysis` | Measures multi-regulation batch frequency to settle decision #2 |

---

## 8. Sequencing principle

Everything observable and testable comes first; the behaviour-altering filter comes last.
Concretely: **instrumentation → emotion-dimension map → memory/trace pipeline (testable with
no ML) → offline species training (parallel) → DJL + sleep consolidation → filter (behind
budget + confidence gates).** Memory work and offline training have no mutual dependency and
proceed in parallel. The creature behaves exactly as today until the very last step.
