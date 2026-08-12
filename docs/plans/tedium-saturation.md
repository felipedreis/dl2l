# Tedium saturates and collapses behavioural efficiency

Status: **resolved — no code change required.** Found while verifying the issue #85
tick-gating fix on a local Docker run; not caused by it (see §5). Fully investigated and
closed same session; this doc records the path so it isn't re-derived.

## 1. Symptom

On `baseline_1node_1creature.conf` with default `LearningSettings` (i.e. `neither
actionTendencyEnabled` nor `neuromodulationEnabled`/`expectancyEnabled` set), tedium
reaches `MAX_AROUSAL_LEVEL` (7.0) within ~40s and stays pegged, while the other three
active emotions sit near the `MIN_AROUSAL_LEVEL` floor of 0.18 — even while the creature
actively forages (measured eating every ~2-2.5s across several runs).

`PartialAppraisal.buildEmotionalStimulus` computes behavioural efficiency from
`emotionalSystem.getMaxArousal()` — the max over *all* active emotions. With tedium
permanently the winner, arousal is pinned at `MAX_AROUSAL_LEVEL`, and
`normalizedBehaviouralEfficiency`'s complex-task branch (used whenever 2+ objects are in
view — a post-#85 run averages 1.95/cycle) is an inverted-U that hits **exactly 0.000** at
arousal 7. So whenever the creature can see more than one object, it moves at `MIN_STEP`
with `MIN_VISION_FIELD_OPENING` focus, permanently. Not cosmetic.

## 2. Two wrong turns before the real answer (kept for the record)

**First:** assumed neither Mapa (2009) nor Campos (2015) modelled tedium at all, and
disabled it outright via a new `LearningSettings.tediumEnabled` flag (default false),
gating both write paths. **Wrong** — Mapa explicitly models tedium as one of four active
emotions with a full sympathetic/parasympathetic loop
(`docs/roadmap/Campos2015_Model_Parity.md`, citing Mapa p.121). Only Campos (2015) omits
it. Reverted.

**Second:** read the roadmap doc's "parasympathetic (↓ per MEE)" as "any completed
interaction relieves tedium, matching how eating relieves hunger," and added `EAT` (then
`APPROACH`) as relief actions in `FullAppraisal.dispatchTediumStimulus`, with guessed
magnitudes. Measurably insufficient both times — a forager's cycle count is dominated by
pursuit, and each guess left a small but real net-positive drift that would still
eventually saturate. **Also wrong**, and for a more basic reason discovered when the guessing
was abandoned in favour of reading the source directly: Mapa's dissertation
(`docs/bib/2009_Mapa_Modelagem_Organismos_Artificiais_Memoria_Experiencial.pdf`, p.135,
"Comportamentos emergentes") specifies tedium relief as **playing
with a toy/ball**, not "any interaction":

> *"do tédio quando o ASCS não interagir com brinquedos"* — tedium rises when the agent
> does not interact with toys.
> *"Para diminuir o tédio o agente precisa brincar com a bola, recebendo... estímulos de
> serotonina"* — to decrease tedium the agent needs to play with the ball, receiving
> serotonin stimuli.

This is structurally absent from DL2L: `ActionType.PLAY` is fully wired through the
pipeline (`EffectorCortex`, `Mouth`, `ProbabilityBasedExperience`, the JEPA world model)
but `src/main/java/br/cefetmg/lsi/l2l/world/` has only `Fruit`/`Plant` — no toy/ball
`WorldObjectType` exists at all. PLAY has nothing to ever target. Implementing Mapa's
actual mechanism is a real feature (new object type, spawning, PLAY-interaction wiring),
not a rate fix, and out of scope for this investigation. Both guessed relief constants
were reverted; `Constants.java`/`FullAppraisal.java` are back to their pre-session shape
(only `WANDER` relieves tedium in the legacy dispatch, as before).

## 3. What actually resolves it — measured, not guessed

Rather than keep guessing at the legacy per-action dispatch, tested the two mechanisms the
codebase already has for exactly this, isolating each on the same world
(`baseline_1node_1creature.conf`, 1 creature, 180 fruit) with the filter chain matched to
p84's actual arms (`[TARGET_DISTANCE, AFFORDANCE, RANDOM]` — the first pass omitted this
and got a confounded, misleading "creature gets stuck" result from the full
`MASTER_FILTER_ORDER` defaulting in instead).

**Arm A — `actionTendencyEnabled = true` alone.** `LearningSettings.DEFAULT_ACTION_TENDENCIES`
maps `TEDIUM -> {WANDER}`: when tedium dominates, `ActionTendencyFilter` restricts
candidates to WANDER, which relieves it. 180s measured: tedium held in **0.18-0.42** the
entire run, 136 fruit eaten (more than any other configuration tested this session).

**Arm B — `expectancyEnabled + neuromodulationEnabled = true`.** Routes tedium through
`NeuromodulatorSystem` instead of the legacy dispatch (`isNeuromodulatorLoopActive()` skips
`dispatchTediumStimulus` entirely): `onDopamine` relieves tedium on any positive
reward-prediction error, `onTick` applies a slow passive rise damped by serotonergic
satiety. 180s measured: tedium held in an even tighter **0.18-0.22** the entire run,
dopamine tonic climbing steadily from real reward events, 26 fruit eaten (fewer than Arm A,
not concerningly so - tedium itself never moved).

**Both, independently, completely prevent domination — using only existing, already-shipped
configuration.** No constant needed inventing.

## 4. What this means for p84

Checked against the actual arm configs on `claude/p84-behaviour-parity`
(`simulations/p84_current_nomem.conf`, `simulations/p84_legacy_nomem.conf`):

- **`current_*` arms** set `expectancyEnabled`, `neuromodulationEnabled` **and**
  `actionTendencyEnabled` all `true` together - a superset of both arms tested above.
  Neither mechanism alone showed any sign of adversarial interaction with the other, so
  tedium domination is not expected there. Not committed here to disable/change tedium.
- **`legacy_*` arms** set all three `false` - deliberately, to isolate "everything DL2L has
  added since Mapa/Campos" as the variable under test. This is exactly the configuration
  where tedium saturates. That is very likely **not new to this session** - the pre-#76
  effective cycle rate was already ~30 Hz (same arithmetic as issue #85's own finding), so
  legacy-arm runs have probably saturated tedium for as long as the legacy arm has existed,
  including the already-completed p84 campaign.

**Open question for the p84 report, not blocking:** whether the `legacy_*` arms' tedium
saturation is itself a parity gap against Mapa specifically. Mapa's own model has a working
relief mechanism (the toy/ball); DL2L's legacy arm has neither that mechanism nor AT/neuromod
as a substitute, so it may be running with *less* tedium regulation than Mapa's own minimal
model did. Worth a line in the report's limitations section; does not require code changes to
proceed with re-running p84.

## 5. Relationship to issue #85

Not caused by tick-gating. `TEDIUM_IDLE_RATE` accrues per cognitive cycle at whatever the
real cycle rate is; the pre-#76 effective rate was already ~30 Hz (`Constants.java`'s
`DELTA` comment: "the pre-#76 p59 baseline: ~150s lifespan / ~4600 cycles" = 30.7 Hz),
which #85 restores. So the accrual rate per wall-clock second is approximately what it has
always been in the legacy-minimal configuration; #85 only made it visible and stable
instead of varying with whatever cycle rate a given run happened to produce.

## 6. If Mapa's actual toy/ball mechanism is ever wanted

Not planned as follow-up work here, just scoped for whoever picks it up:
`WorldObjectType`/`FruitType`-style new type (a `Toy`/`Ball`), spawn config support,
`Mouth`/`EffectorCortex` PLAY-interaction wiring (largely present already —
`ActionType.PLAY` already routes through both), and a `TEDIUM -> {PLAY}` (or
`{PLAY, WANDER}`) tendency-map entry. Would need its own `docs/plans/` doc and dedicated
experiment per CLAUDE.md's development cycle, not a rate tweak on the existing dispatch.
