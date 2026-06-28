# TestingCreature — Single-Threaded Functional Test Harness (issue #45)

## Context

DL2L creatures are Akka classic actors. Today, exercising "a full stimulation
cycle" requires standing up an `ActorSystem`, an `MLServiceExtension`, a
`SimulationSettingsExtension`, a JPA EntityManager, a `CollisionDetectorActor`,
a `Holder`, etc. That makes functional testing of the creature pipeline
painful: tests are slow, flaky, and hard to assert against because messages
flow through real schedulers, dispatchers, and a `ComponentMailbox` that
batches stimuli concurrently.

Issue #45 asks for a `TestingCreature` that:
- Implements the existing `Creature` interface
- Wires the **same** component classes the real creature uses (`Eye`, `Nose`,
  `Mouth`, `Body`, `SensoryCortex`, `EffectorCortex`, `PartialAppraisal`,
  `FullAppraisal`, `HomeostaticRegulation`, `Valuation`, `EmotionalSystemActor`,
  `OperantConditioningActor`, `MemorySystemActor`)
- Does **not** bootstrap an `ActorSystem`
- Runs single-threaded (the test thread is the only thread)
- Exposes utilities to inject external stimuli, intercept outbound
  messages, and observe the stimulus chain
- Has functional tests for the most common simulation flows

The only way to satisfy "same components" **and** "no `ActorSystem`" is to
decouple the component base class from `UntypedActor`. Today components inherit
from `CreatureComponent extends UntypedActor` and call
`creature.X().tell(stim, self())` on `ActorRef`s returned by the `Creature`
interface. `ActorRef` cannot be constructed outside an `ActorSystem`. So the
refactor must touch the `Creature` interface, the `CreatureComponent` base,
and every concrete component — but only mechanically.

## Approach

1. Introduce a small `ComponentRef` abstraction. Each component endpoint
   (`eye()`, `mouth()`, `holder()`, …) returns a `ComponentRef` instead of
   an `ActorRef`. A `ComponentRef` has one method: `void tell(Object msg)`.
2. Decouple `CreatureComponent` from `UntypedActor`. The class keeps all
   its current responsibilities (state, `onReceive(List<Stimulus>)`,
   `nextStimulusId()`, `persist(...)`) but is a plain abstract Java class.
3. Add a thin Akka adapter `ComponentActor extends UntypedActor` that
   owns a `CreatureComponent` instance and forwards mailbox messages to it.
   The existing `ComponentMailbox` keeps batching `List<Stimulus>`.
4. `CreatureActor` (production) keeps its current responsibilities; it just
   wraps the `ActorRef`s it returns in an `AkkaComponentRef` adapter so the
   public `Creature` interface returns `ComponentRef`.
5. Build `TestingCreature` — plain class implementing `Creature`, wires the
   real components in-process with synchronous `ComponentRef`s.
6. Build test utilities: a stimulus recorder, an external-world sink that
   captures outbound messages (to `holder`, `collisionDetector`,
   `memoryConsolidator`, `bd`), and a manual tick driver.
7. Add functional tests covering the eat-, pain-, sleep-, and wander/observe
   cycles.

The refactor is mechanical — every behaviour stays the same. The only
production-side change is the type returned by `Creature.X()` and the way
components send messages (`tell(msg)` instead of `tell(msg, self())`).

## File changes

### New (production)

- `src/main/java/br/cefetmg/lsi/l2l/creature/ComponentRef.java`
  - One-method interface: `void tell(Object msg)`.
- `src/main/java/br/cefetmg/lsi/l2l/creature/AkkaComponentRef.java`
  - Wraps an `ActorRef`; `tell(msg)` → `ref.tell(msg, ActorRef.noSender())`.
  - Used only by `CreatureActor` to bridge old actor refs into the new
    `Creature` interface.
- `src/main/java/br/cefetmg/lsi/l2l/creature/components/ComponentActor.java`
  - `extends UntypedActor`. Holds a `CreatureComponent` instance constructed
    via `Props.create(...)`. In `preStart()` it resolves the parent
    `Creature` via `TypedActor` (the same lookup `CreatureComponent.preStart`
    does today) and wires it into the component, then calls the component's
    `init(persister, parentRef, system)` hook. `onReceive(message)` delegates
    to `component.onReceive(message)`. `postStop()` delegates to
    `component.postStop()`.
- `src/main/java/br/cefetmg/lsi/l2l/creature/bd/Persister.java`
  - Interface: `void persist(PersistenceState... states)` + `void close()`.
- `src/main/java/br/cefetmg/lsi/l2l/creature/bd/JpaPersister.java`
  - Production implementation; owns the `EntityManager` currently created in
    `CreatureComponent` line 46-48. Same buffered transaction code.
- `src/main/java/br/cefetmg/lsi/l2l/creature/bd/NoOpPersister.java`
  - Test/disabled implementation; drops all calls.

### New (test)

- `src/test/java/br/cefetmg/lsi/l2l/creature/testing/TestingCreature.java`
  - Implements `Creature`. Constructor takes `SequentialId`, `Point position`,
    `Point worldBoundaries`, `LearningSettings`, and a `TestingHarness` that
    holds the recorders and external sinks.
  - Mirrors `CreatureActor.init()` but using plain instantiation:
    constructs each `CreatureComponent`, wires it with the shared `Creature`
    ref, a `NoOpPersister`, and a `RecordingComponentRef` that wraps the
    target component's `BatchingDispatcher`.
  - `kill()` flips `alive=false`, drains any buffered messages, then
    notifies the external `holder` sink (mirroring `CreatureActor.kill()`
    line 197).
- `src/test/java/br/cefetmg/lsi/l2l/creature/testing/BatchingDispatcher.java`
  - Mirrors `ComponentMessageQueue`: buffers single stimuli into a list;
    when `drain()` is called, delivers the list to the target component's
    `onReceive(List)`. Synchronous, single-threaded, no concurrency. Drains
    are scheduled by the harness in a depth-first loop so re-entrant
    `tell()` calls (e.g. `HomeostaticRegulation.triggerImmuneResponseIfNeeded`,
    line 143) are processed in FIFO order to match production semantics.
- `src/test/java/br/cefetmg/lsi/l2l/creature/testing/RecordingComponentRef.java`
  - Wraps a `BatchingDispatcher` (or external sink). Records every
    `tell` in a `List<Recorded>` with `{source, target, message,
    timestamp}`. The harness exposes filters: `byType(Class<?>)`,
    `last()`, `count()`, etc.
- `src/test/java/br/cefetmg/lsi/l2l/creature/testing/ExternalSink.java`
  - `ComponentRef` impl for `holder`, `collisionDetector`,
    `memoryConsolidator`, and `bd`. Just records inbound messages. Tests
    assert on `holder.messages()` to verify `DestructiveStimulus` was
    emitted.
- `src/test/java/br/cefetmg/lsi/l2l/creature/testing/TestingHarness.java`
  - Top-level facade. `harness.creature()` returns the `TestingCreature`;
    `harness.tick()` drives one cognitive cycle (sends `""` to
    `partialAppraisal`, as the real `CreatureActor` scheduler does at
    line 174); `harness.sendLuminous(LuminousStimulus...)` injects a list
    onto `eye`; `harness.expectedAction()` reads the last
    `CorticalStimulus` from the eye/body/mouth recorders.
- `src/test/java/br/cefetmg/lsi/l2l/creature/testing/TestingCreatureTest.java`
  - Functional test cases (see "Test cases" below).

### Modified (production)

- `src/main/java/br/cefetmg/lsi/l2l/creature/Creature.java`
  - Change all `ActorRef X()` accessors (`eye`, `body`, `mouth`, `nose`,
    `sensoryCortex`, `effectorCortex`, `partialAppraisal`, `fullAppraisal`,
    `homeostatic`, `valuation`, `memoryConsolidator`, `bd`, `holder`) to
    return `ComponentRef`.
- `src/main/java/br/cefetmg/lsi/l2l/creature/components/CreatureComponent.java`
  - **Remove** `extends UntypedActor`. Become a plain abstract class.
  - Add lifecycle init method: `init(Creature creature, Persister persister)`
    that `ComponentActor.preStart()` calls. The current `preStart` body
    (`TypedActor` lookup of the parent `Creature`, line 53-58) moves into
    `ComponentActor.preStart()`.
  - Replace the inline JPA setup (line 46-48) with a `Persister` field
    set by `init`. Forward `persist(states...)` to the persister.
  - Remove `self()` / `context()` calls. They are not used inside this
    class — only `creature`, `id`, `logger`, `nextStimulusId()`, `persist`,
    and `em` (now `persister`).
- `src/main/java/br/cefetmg/lsi/l2l/creature/components/Eye.java`,
  `Body.java`, `Mouth.java`, `Nose.java`, `SensoryCortex.java`,
  `EffectorCortex.java`, `PartialAppraisal.java`, `FullAppraisal.java`,
  `HomeostaticRegulation.java`, `Valuation.java`
  - Replace every `creature.X().tell(msg, self())` with
    `creature.X().tell(msg)`. The mechanical change is **dropping the
    second argument**.
  - For `HomeostaticRegulation.triggerImmuneResponseIfNeeded` (line 143
    self-tell): expose a small `selfRef()` accessor on `CreatureComponent`
    that is set by `init`. Production fills it with the component's own
    `AkkaComponentRef`; tests with the component's `BatchingDispatcher`.
  - `PartialAppraisal.preStart` (line 37-42) — keep `circadian` init, but
    take `LearningSettings` from a constructor-injected `Supplier<LearningSettings>`
    (defaults via `SimulationSettingsExtension` for the Akka path; provided
    directly by `TestingCreature` for tests).
  - `FullAppraisal.preStart` (line 63-89) — same: take `LearningSettings`
    and an `MLContext` (a small interface that exposes `modelDir()` /
    `inferenceRouter()`) via constructor. Production passes
    `MLServiceExtension.of(system)`; tests pass a no-op stub that disables
    the `WORLD_MODEL` filter.
- `src/main/java/br/cefetmg/lsi/l2l/creature/CreatureActor.java`
  - `init()` — for each spawned component `ActorRef`, wrap it in
    `new AkkaComponentRef(ref)` and return that from the `eye()` /
    `mouth()` / … accessors (replace lines 222-258).
  - `holder()` — wrap `context().parent()` in `AkkaComponentRef`.
  - `memoryConsolidator()` and `bd()` — same wrap.
  - `init()` line 148-154 — when consolidation is disabled, store
    `new AkkaComponentRef(context().system().deadLetters())`.
  - `init()` — instead of spawning the raw component class
    (line 163-164), spawn `ComponentActor` with `Props.create(ComponentActor.class, componentClass, componentId, ...)` so the
    `ComponentActor` constructs the underlying `CreatureComponent` via
    reflection (or pass a `Supplier<CreatureComponent>`). The
    `withDispatcher("component-dispatcher")` and `ComponentMailbox` calls
    stay unchanged.
  - `init()` line 177 — `collisionDetector.tell(getPositioningAttr(), …)`
    still uses the raw `ActorRef collisionDetector` field (it's external to
    the creature and stays an `ActorRef` internally).

### Modified (tests)

- `src/test/java/br/cefetmg/lsi/l2l/creature/components/EmotionalSystemActorTest.java`,
  `MemorySystemActorTest.java`, `CircadianClockTest.java` — no changes
  needed; these are already plain-Java tests.

## Mechanical rules for the refactor

- Inside any production component, `self()` → drop the argument from
  `tell()`. Where a self-tell is required, use the new `selfRef()`
  accessor set by `init`.
- `context().system()` calls in `preStart` (only in `PartialAppraisal` and
  `FullAppraisal`) → use constructor-injected suppliers instead.
- The `Creature` interface is the only public-facing contract that
  changes. Everything else is private/package-private wiring.
- No test should call `ActorSystem.create(...)` or instantiate `Props`.

## Reused existing code

- `EmotionalSystemActor` (`creature/components/EmotionalSystemActor.java`) —
  already a plain Java class; reused as-is by `TestingCreature`.
- `OperantConditioningActor` (`creature/conditioning/OperantConditioningActor.java`) —
  already plain Java; reused.
- `MemorySystemActor` (`creature/memory/MemorySystemActor.java`) — already
  plain Java; reused.
- `ActiveCircadianClock` / `DisabledCircadianClock` — already plain Java.
- `ComponentMessageQueue` (`common/ComponentMessageQueue.java`) — its
  list-batching logic is the model for `BatchingDispatcher`.
- JUnit 5 (`junit-jupiter`) is already on `pom.xml` line 49-58 — no new
  test deps needed.

## Test cases (functional)

All in `TestingCreatureTest.java`:

1. **Empty perception tick** — no inbound stimuli; one `tick()`; assert
   `PartialAppraisal` emitted an `EmotionalStimulus` with a `Self`
   perception and arousal > 0; assert `EffectorCortex` produced a
   `CorticalStimulus` whose action is one of {SLEEP, WANDER, OBSERVE}.
2. **See-and-approach** — inject a `LuminousStimulus` for a `RED_APPLE`
   at distance 50, angle 0; tick; assert the chain `Eye→SensoryCortex→
   PartialAppraisal→FullAppraisal→EffectorCortex→Body` was traversed in
   order; assert no `DestructiveStimulus` reached the holder sink yet.
3. **Eat cycle** — inject a `LuminousStimulus` at distance 0 (contact);
   tick; force `FullAppraisal` toward `EAT` by pre-seeding
   `OperantConditioning` probabilities; assert a `DestructiveStimulus`
   was sent to the holder sink; inject an `EnergeticStimulus` for the
   same target; tick; assert hunger level decreased.
4. **Pain cycle** — inject a `MechanicalStimulus` (collision with a
   `CACTUS` `PlantType`); tick; assert `Mouth` forwarded
   `NociceptiveStimulus` to `HomeostaticRegulation`; assert pain emotion
   level rose; tick more cycles until pain > immune threshold; assert an
   `AnalgesicStimulus` was queued onto the homeostatic self-ref and
   processed in the next batch.
5. **Sleep cycle** — drive enough idle ticks so `Sleep` emotion crosses
   the SLEEP-action threshold; assert `FullAppraisal` selected `SLEEP`
   and emitted `SleepStarted` to the `memoryConsolidator` sink; tick
   for at least `MIN_SLEEP_TICKS` cycles; assert the creature stayed
   in `SLEEP`; inject a `CholinergicStimulus` enough times to exhaust
   the sleep drive; assert a `WakeUp` was sent.
6. **Wander cycle** — inject many ticks with no perceptions; assert the
   creature alternates between WANDER and OBSERVE based on TEDIUM
   regulation and that its `position` changes after WANDER ticks.
7. **Kill cycle** — call `creature.setAlive(false)`; assert the holder
   sink received the creature's `SequentialId` (the kill notification at
   `CreatureActor.kill()` line 197), and that subsequent ticks are no-ops.

## First implementation step

Before any code changes, copy this approved plan into the project tree at
`docs/plans/testing-creature.md` per the CLAUDE.md repo convention.

## Verification

End-to-end:
1. `mvn package` must compile clean (this is the production-side
   refactor smoke test; no production behaviour changed).
2. `mvn test` must run the existing tests **and** the new
   `TestingCreatureTest` — all green.
3. Run `mvn test -Dtest=TestingCreatureTest` to focus the new suite
   while iterating.
4. **Regression run**: `cd docker && docker-compose up` for one basic
   simulation (`simulations/basic.conf`) and confirm a creature still
   completes a few cognitive cycles without exceptions. This is the
   real-Akka path through the refactored components.

## Out of scope

- Removing the `UntypedActor`/classic-Akka tech debt globally (the
  refactor stays scoped to creature components).
- Persistence (JPA) replacement for production — `JpaPersister` keeps
  identical semantics to today's `CreatureComponent.persist`.
- World-model / DJL integration tests (kept disabled in the test
  harness via the no-op `MLContext`).
- Multi-creature interaction tests.
