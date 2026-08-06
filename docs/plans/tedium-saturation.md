# Tedium saturates and collapses behavioural efficiency

Status: **implemented** (superseding §5's options below) as a `tediumEnabled` flag on
`LearningSettings`, default **false**. Found while verifying the issue #85 tick-gating fix
on a local Docker run; not caused by it (see §6).

## 0. Resolution actually taken

None of §5's F1-F5 options. Discussed live: neither Mapa (2009) nor Campos (2015) - the
architectures p84 validates parity against - modelled tedium at all; it is a DL2L addition
(issue #57). Decision was to disable it at the base architecture rather than retune it, so
the default config matches what p84 is validating against, and re-running p84 does not need
to first resolve the mechanism questions in §3/§5 (WANDER-only relief, the
`ActionTendencyFilter` coupling, the F1 reclassification) - those remain open if tedium is
ever turned back on for its own sake, but they no longer block the parity work.

**What changed:**
- `LearningSettings.tediumEnabled` (new flag, default false, config key
  `simulation.learningSettings.tediumEnabled`).
- `FullAppraisal.dispatchTediumStimulus` — whole method now a no-op unless the flag is on
  (was already conditionally skipped when the neuromodulator loop is active; now also
  skipped when tedium itself is off).
- `NeuromodulatorSystem.onTick` (passive boredom rise) and `onDopamine` (reward-triggered
  relief) — both gated on the same flag; dopamine/serotonin/orexin decay and baseline
  synthesis in `onTick` are unaffected.
- `EmotionalSystemActor.ACTIVE` and `getMaxArousal()` are untouched - with both write paths
  gated, tedium simply never leaves `MIN_AROUSAL_LEVEL`, so it never wins the max without
  needing a read-side change.

**Verified locally** (fresh Docker run, single-creature world, 8 samples over 160 s, 45
fruit eaten): tedium held at floor (0.18) for the entire run; `getMaxArousal()` tracked
hunger throughout (peak 0.82, settling to a 0.2-0.4 band as eating keeps pace) instead of
being permanently pinned at tedium's 7.0. Full test suite (268 unit/functional + 9
integration) passes. See the commit for exact numbers.

**Re-enabling tedium for its own investigation** (as opposed to p84 parity) still needs
§3-§5 below worked through first - turning the flag back on alone would reproduce the
saturation this document opened with.

## 1. Symptom

On `simulations/baseline_1node_1creature.conf`, tedium reaches `MAX_AROUSAL_LEVEL` (7.0)
about 40 s into a run and stays pegged there for the rest of the creature's life, while the
other three active emotions sit at or near the `MIN_AROUSAL_LEVEL` floor of 0.18.

Measured (fresh run, `e18463f`, 30.5 Hz cognitive cycle):

| t | cycles | eats | hunger | sleep | tedium |
|---|---|---|---|---|---|
| 20 s | 311 | 8 | 0.24 | 0.18 | 4.72 |
| 40 s | 924 | 15 | 0.36 | 0.21 | **7.0** |
| 80 s | 2144 | 26 | 0.21 | 0.18 | **7.0** |
| 160 s | 4583 | 63 | 0.33 | 0.21 | **7.0** |

The creature is *not* idle while this happens: it eats 63 fruit in 160 s and keeps hunger
near the floor. A busy, well-fed creature is being modelled as maximally bored.

This was invisible before `ab91f96` added `dl2l_creature_emotion_level` — the single
`dl2l_creature_arousal` gauge just read 7.0 with no attribution.

## 2. Mechanism

`FullAppraisal.dispatchTediumStimulus` classifies the selected action:

```java
if (learningSettings.isNeuromodulatorLoopActive()) return;   // legacy path only
if (selectedAction == ActionType.SLEEP) return;
double delta = switch (selectedAction) {
    case WANDER  -> -Constants.TEDIUM_WANDER_RELIEF;   // -5.0e-2
    case OBSERVE ->  Constants.TEDIUM_OBSERVE_RATE;    // +5.0e-2
    default      ->  Constants.TEDIUM_IDLE_RATE;       // +2.0e-2
};
```

**`APPROACH` and `EAT` fall through to `default`.** The two actions that constitute
successful foraging — pursuing food and consuming it — accrue tedium at the same rate as
doing nothing. Only `WANDER` relieves it.

`neuromodulationEnabled` and `expectancyEnabled` both default to **false**
(`Simulation.parseLearningSettings` uses `hasPath && getBoolean`), so this legacy path is
the active one in any config that does not explicitly opt in — including the default
baseline and p84's legacy-minimal arms.

Confirmed by arithmetic against the live run rather than by reading alone: at t+20 s,
311 cycles × `TEDIUM_IDLE_RATE` 2.0e-2 = 6.22 against an observed 4.72 (mostly-idle
classification with some wander relief). The neuromodulator path would predict
311 × `BOREDOM_RISE_RATE` 8.0e-4 = 0.25, which is not what happened.

## 3. Why it does not self-correct — the missing half of the loop

`LearningSettings.DEFAULT_ACTION_TENDENCIES` maps `TEDIUM -> EnumSet.of(ActionType.WANDER)`,
and `ActionTendencyFilter` keeps only candidates in the dominant emotion's set. So when
tedium dominates *and the tendency filter is enabled*, action selection is restricted to
WANDER — the one action that relieves tedium. That is a closed negative-feedback loop, and
it is almost certainly the design intent.

`actionTendencyEnabled` also defaults to **false**. With it off, nothing converts "I am
bored" into "therefore wander", so the accrual has no counterweight and runs away.

**This is the leading hypothesis and it is not yet verified** — see §5, step 1. If it holds,
the legacy tedium path is not independently well-formed; it is only correct in combination
with the tendency filter, and that coupling is undocumented.

## 4. Impact — this is not cosmetic

`PartialAppraisal.buildEmotionalStimulus` computes behavioural efficiency from
`emotionalSystem.getMaxArousal()`, which is the max over **all** active emotions, affects
included. With tedium pegged at 7.0 it is permanently the winner, so arousal is pinned at
`MAX_AROUSAL_LEVEL` regardless of the creature's actual drives.

`normalizedBehaviouralEfficiency`'s complex-task branch (used when `perceptions >=
COMPLEX_TASK`, i.e. 2+ objects in view) is an inverted-U peaking at arousal 3.5:

| arousal | BE (simple, <2 objects) | BE (complex, >=2 objects) |
|---|---|---|
| 0.18 | 0.069 | 0.100 |
| 3.5 | 0.753 | **1.000** |
| 6 | 0.909 | 0.490 |
| 7 | 0.939 | **0.000** |

At arousal 7 the complex-task curve is **exactly zero**. `FullAppraisal.produceCortical`
then computes:

- `defaultSpeed = max(MAX_STEP * 0, MIN_STEP)` = `MIN_STEP` (3, from a possible 10)
- `defaultFocus = max(MAX_VISION_FIELD_OPENING * 0, MIN_VISION_FIELD_OPENING)` = 50 (of 150)

So whenever two or more objects are in view, the creature crawls at minimum speed with
minimum vision — permanently. A post-#85 run averages 1.95 perceived objects per cycle, so
this branch is taken constantly.

Secondary consequence: tedium being the permanent argmax means `getMaxArousal()` never
reports hunger or sleep, so any consumer keyed on the dominant emotion is reading a
constant. That includes the tendency filter in configs where it *is* enabled.

## 5. Options

| | change | pro | con |
|---|---|---|---|
| **F1** | Reclassify actions: `EAT` relieves, `APPROACH` neutral, keep `WANDER` relief / `OBSERVE` penalty | Targets the actual defect; legacy path becomes self-consistent without depending on the tendency filter; small | Picks new semantics for APPROACH that need a call |
| **F2** | Compute behavioural efficiency from `getMaxDriveArousal()` (drives only) instead of all emotions | Stops any affect from hijacking Yerkes-Dodson; `getMaxDriveArousal()` already exists for the death check | Changes arousal semantics globally; affects pain too; diverges from Campos's general-arousal reading |
| **F3** | Enable the neuromodulator loop by default | The DA/RPE path already models tedium as reward-absence properly | Does not fix legacy-minimal arms, which deliberately run without it; much larger behavioural change |
| **F4** | Lower `TEDIUM_IDLE_RATE` | Trivial | Treats the symptom — a foraging creature still accrues monotonically and still pegs, just later |
| **F5** | Drive relief from actual mouth interactions rather than action type | Cleanest semantics | Duplicates the neuromodulator path; largest change |

**Recommendation: F1**, and treat F2 as a separate question worth answering on its own
merits regardless of F1 — the `BE = 0` cliff at exactly `MAX_AROUSAL_LEVEL` is a trap for
*any* emotion that saturates, not just tedium (a starving creature hits it too, right when
it can least afford minimum speed).

Do **not** proceed to implementation before step 1 below settles whether the tendency filter
already closes this loop. If it does, F1 is a robustness fix rather than a bug fix, and its
priority drops sharply.

## 6. Relationship to issue #85

Not caused by tick-gating. `TEDIUM_IDLE_RATE` accrues per cognitive cycle, and the pre-#76
effective rate was ~30 Hz (`Constants.java`'s `DELTA` note: "the pre-#76 p59 baseline: ~150 s
lifespan / ~4600 cycles" = 30.7 Hz), which is what `TARGET_CYCLE_HZ` restores. So the accrual
rate per wall-clock second is approximately what it has always been, and tedium has very
likely been pegged in every legacy-path run ever recorded — including p84.

What #85 changed is only that it is now *visible* and *stable* rather than varying with
whatever cycle rate a given machine happened to produce.

`e18463f` (unwinding Phase A's S-rescale) restored `TEDIUM_IDLE_RATE` from 3.41422e-3 to its
original 2.0e-2, which moves saturation from ~67 s to ~11 s of un-relieved accrual. It makes
the symptom arrive sooner; it does not create it.

## 7. Validation plan (CLAUDE.md development-cycle step 5)

### Step 1 — settle the tendency-filter hypothesis (do this first, it is cheap)

Two arms on the single-creature world, identical but for `actionTendencyEnabled`:

- If tedium **oscillates** with the tendency filter on, §3 is confirmed: the loop is real and
  the defect is that the legacy path is only valid alongside it. Fix scope shrinks to
  documenting the coupling plus F1 as hardening.
- If tedium **pegs in both arms**, the loop does not close in practice and F1 is required.

Metric: fraction of cycles with `tedium >= 0.95 * MAX_AROUSAL_LEVEL`, from
`dl2l_creature_emotion_level`.

### Step 2 — hypotheses for the F1 arm

- **H1.** Time-at-saturation (fraction of a lifetime with tedium >= 6.65) falls from ~100%
  to < 20% for an actively foraging creature.
- **H2.** Mean behavioural efficiency rises above 0; mean realised speed exceeds `MIN_STEP`.
- **H3.** Foraging rate (eats per minute) increases, because H2 lifts speed and vision.
- **H4 (control).** Mean lifespan is unchanged — `DELTA` is dt-weighted and hunger dynamics
  are untouched, so a lifespan change would indicate an unintended coupling.

### Step 3 — arms

| key | config |
|---|---|
| `legacy` | current behaviour, tendency off (reproduces the symptom) |
| `legacy_tendency` | tendency on (step 1's test) |
| `f1` | F1 reclassification, tendency off |
| `f1_tendency` | F1 + tendency on |

### Step 4 — sample size

Pilot 3 trials per arm to estimate the variance of eats-per-minute and time-at-saturation,
then size the real run from a two-sample power calculation at alpha 0.05 / power 0.8 using
`dl2l_analysis.stats`. Do not fix trial count before the pilot — single-creature runs have
high between-trial variance, and the p84 campaign showed ICC effects large enough that
guessing n is not defensible.

### Step 5 — deliverables

Experiment spec `experiments/p85_tedium.yml`, analysis `analysis/experiments/p85_tedium.py`,
report `docs/reports/<date>_p85_tedium.md` with the mandated Purpose / Assumptions /
Hypothesis / Results / Analysis sections.

## 8. Risks

- Any change here alters foraging behaviour, so **p84 must be re-run afterwards**, not
  before — otherwise the parity data describes code that no longer exists.
- F1 changes what "tedium" means operationally. If the Campos/Mapa parity work depends on
  the current semantics, that dependency has to be checked before changing it.
- If tedium has been pegged in all historical legacy-path runs, then previously reported
  behavioural-efficiency figures were computed at a pinned arousal. Worth re-reading any
  conclusion that rested on behavioural efficiency varying.
