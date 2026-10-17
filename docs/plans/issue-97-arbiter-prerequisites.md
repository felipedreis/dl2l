# Issue #97 — Arbiter prerequisites: feeding at speed (#96) and seeded runs (#95)

Plan for the two fixes every arbiter arm depends on (`docs/plans/2026-q4-research-plan.md`,
A0 baseline). Status: **draft, awaiting approval**.

## Part A — Feeding collapses at speed (#96)

### What the code does (traced, not yet confirmed by a test)

Contact is a four-hop path, none of it logged as a perception with `distance > 0`:

1. `CreatureActor.tick()` sends `CreaturePositioningAttr` (position, `visionFieldPosition`,
   `visionFieldOpening`) to `CollisionDetectorActor` once per cycle.
2. `CollisionDetectorActor.checkCreatureCollisions` builds a `CreatureGeometry` and tests
   `geom.mouth.intersects(obj.shape)`, where `mouth = Arc(radius = DEFAULT_MOUTH_RADIUS = 10,
   startAngle = visionFieldPosition, opening = DEFAULT_MOUTH_OPENING = 45)`.
3. A hit sends `MechanicalStimulus` → `Mouth` → `TouchStimulus` → `SensoryCortex`, which emits
   a `ProprioceptiveStimulus` with `distance = 0, angle = 0`.
4. `FullAppraisal.actionsForPerception` affords EAT only for that `distance == 0` perception
   (`FullAppraisal.java:538`); every visual perception has `distance > 0` and gets
   APPROACH/AVOID/SLEEP/OBSERVE/WANDER.

So EAT is a candidate only if, on the single tick the detector evaluates, the mouth arc
overlaps the fruit. The step is `max(MAX_STEP * efficiency, MIN_STEP)` = 3…10 per cycle, the
mouth radius is 10 and the fruit radius 8, so a 6–10 unit step can jump from "seen" to "past"
without any tick landing inside the arc. That matches the speed table in #96.

### Second suspect found while tracing (must be confirmed first)

`visionFieldPosition` is an **absolute bearing in radians** (`Point.angleAlpha` = `atan2`;
`FullAppraisal.produceCortical` says so), but `Arc` treats `startAngle` as **degrees**
(`Math.toRadians(startAngle + angle)`), uses `-sin` (y flipped vs `atan2`), and takes
`startAngle` as the arc's *start*, not its centre. Nothing in the codebase calls `toDegrees`.
If that reading is right, the mouth (and vision) arcs sit in a nearly fixed wedge (~0°–45°)
regardless of heading, and contact with food is partly by chance of where it lies relative to
the creature — which would also explain why contact degrades at speed. This is **not** in
#96's text and changes the baseline, so I want to confirm it before touching anything.

### Steps

1. **Confirm.** Add `CreatureGeometryTest` (pure geometry, no actors): for headings 0, ±90°,
   180° and a fruit placed 8 units ahead along that heading, assert whether `mouth.intersects`.
   Expected to fail for most headings if the suspicion holds. Also a step-overshoot test:
   creature at distance 15 from a fruit, stepping 10 toward it twice.
2. **Fix the contact test** — decision needed (see Questions). Options, in order of preference:
   - **A. Afford EAT from visual range.** In `actionsForPerception`, treat
     `distance <= DEFAULT_MOUTH_RADIUS + FRUIT_RADIUS` (object-type radius) as contact:
     return `actionsAtContact`. Smallest change, independent of the arc geometry, and what the
     issue lists first. `Mouth` then receives a `SomaticStimulus` for a target the detector never
     reported as touching — need to check `DestructiveStimulus` handling in `Holder`/world
     object does not require proximity (verify in step 1).
   - **B. Cap the approach step** at `max(distance − contactDistance, 0)` in the APPROACH branch
     of `produceCortical`, so the creature stops at contact and the existing detector path
     fires. Keeps the legacy semantics but still depends on the arc being oriented correctly.
   - **C. Fix the arc units/orientation** (radians→degrees, centre the arc on the heading).
     Correct, but it changes behaviour of every earlier experiment; only if step 1 confirms the
     bug, and then in addition to A or B.
3. **Verify.** Run the legacy condition (p84 `legacy_nomem` spec) locally with UI on
   (per the local-experiments rule), then `analysis/diagnostics/p84_approach_check.py`:
   EATs > 0 in every life decile while hunger is above set point.

## Part B — Seeded randomness (#95)

Random sources and their owners (all `new Random()` / time-seeded today):
`RandomPositionFactory` (via `Simulation.getPositionFactory`), `RandomFilter`, `MemoryFilter`,
`ActionProbabilityFilter` (built in `FullAppraisal` ~L113–127), and the per-call
`new Random()` in `FullAppraisal` WANDER (~L415).

### Design

- `seed` key in `simulations/*.conf` (`simulation.seed`), overridable with `--seed <long>` in
  `Main`. No seed → `System.nanoTime()`-based seed, **logged** and recorded.
- New `common/SeedSequence` (name TBD): `static long derive(long root, String... parts)` using
  a 64-bit mix (SplitMix64 over `root` and `String.hashCode`-independent UTF-8 hash of the
  parts, so it is stable across JVMs). Stateless, so streams stay stable when creature counts
  change. `Random` instances built as `new Random(derive(seed, creatureId, "memoryFilter"))`.
- Plumbing: `Simulation` exposes `getSeed()`; `Holder` passes it to the creature factory;
  `CreatureActor` hands `(seed, creatureId)` to `FullAppraisal`, which builds one `Random` per
  filter and one for WANDER, replacing the per-call `new Random()`. `RandomPositionFactory`
  takes a `Random`/seed (world: `derive(seed, "world")`). Existing seeded test constructors
  stay; the no-arg self-seeding constructors go.
- Record the seed: add it to the trial `manifest.json` written by `dl2l_data.extract` (Java
  logs `seed=<n>` at startup; the ansible runner already passes args, so the seed per trial is
  `--seed` from the experiment spec's trial index — spec schema change in
  `experiments/README.md` + `scripts/validate_experiment.py`). Run-info table is optional
  and out of scope unless you prefer it over the manifest.

### Tests (acceptance from #95/#97)

- Same seed → identical initial object and creature positions; different seeds differ.
- Filter from a derived seed → identical draw sequence across two constructions; different
  creature ids → different sequences.
- `TestingCreature`/short integration run: same seed → same sequence of *random draws per
  component* (full action-sequence identity is **not** promised — Akka scheduling is
  nondeterministic; see #95 "Limits"). Issue #97's "identical action sequences" criterion is
  therefore tested on a deterministic harness (fixed perceptions fed to `FullAppraisal` in
  order), not on a live cluster run.

## Order of work, branch and PR

Branch `fix/arbiter-prereqs-97`. Commits: (1) geometry tests + confirmation, (2) feeding fix,
(3) seed plumbing + tests, (4) spec/manifest/docs. Java changes go through a PR
(`Resolves #96, #95`, closes #97). Then `mvn package`, then the p84 legacy rerun as a
mini-experiment (recipe in `docs/experiments/` first, spec `experiments/p97_<name>.yml`),
report in `docs/reports/`. `graphify update .` after code changes.

## Questions for you

1. Fix choice for feeding: A (afford EAT within mouth radius), B (cap the step), or A + C if
   the arc bug is confirmed? My recommendation: confirm in step 1, then A, and C only if the
   arc is genuinely mis-oriented (it would move the A0 baseline, so you should see the
   before/after).
2. Seed recording: manifest only, or also a run-info table?
3. Is a rerun of the p84 legacy arm (80 creatures) the intended verification size, or do you
   want a power calculation for a smaller sample first (dev-cycle step 5c)?
