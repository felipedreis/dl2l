# JEPA planner: the plan in one page (epic #139)

Status: **for approval, 2026-11-27.** Details live in `jepa-planner-details.md` (read a section only when its stage starts).

## The question
Does a learned world model help a creature, compared with the classic architecture (`a3_kal`, PR #132)? We answer it in stages, each
with its own hypothesis. The simplest path: **train the model once, for beam search, and add the search later.**

## The model (trained once)
A world model with two parts, nothing else:
- **Predictor:** (state of the candidate object, action) → next state. Trained on per-cycle transitions.
- **Cost head:** (state, action, drives) → the immediate drive change K of that act (hunger, sleep, pain, tedium).

No return label, no episode cut. Value comes from search: chain predictor and cost head, discount, sum. APPROACH gets credit through
the predicted consequences (closer to the apple, then EAT), not through a label. All parts are retrained: the July model is stale in every part.

## The stages
| # | Question | How | Done when |
|---|---|---|---|
| 1 | Does the model learn the world? | Collection run → train → offline check on held-out seeds | Predictor error low; cost head separates food / gray / cactus at contact; the "any prior" control (shuffled head) is worse |
| 2 | Does a depth-1 planner choose well at contact? | Score actions on the target: EAT vs AVOID | Eats food, avoids gray and cactus, offline first, then a pilot in the simulator |
| 3 | Can it forage? (beam search) | Planner alone vs `a3_kal`, same world | Survival and feeding, pre-registered |
| 4 | Does it help the classic creature? | Third controller of the arbiter | Survival with vs without the planner; this is the arbiter paper's A6 |

Stage 2 is blind to approach by construction (APPROACH has no immediate K), so it tests the at-contact choices only. Approach and distance
come at stage 3. Stages 3 and 4 are different hypotheses and different experiments.

## Data (stage 1)
A dedicated collection run, not the p117 data: `a3_kal` plus a little forced exploration, seeds disjoint from the tests, a world without the test
manipulations, and per-cycle logging of percepts, drives, action and K (what is missing today: `jepa-planner-details.md` §A). A 2-trial pilot first,
to check how often hunger reaches 4. The audit (#133) says the existing data are not enough: no transitions, zeroed APPROACH labels, shared seeds.

## Deferred on purpose
Beam search (stage 3); calibration rescale, OOD distance gate, the budget arms and the arbiter wiring (stage 4); devaluation (a frozen model cannot adapt, so
it would favour the classic arm for the wrong reason; return with the adapter on); the prototype on the old data.

## Risks
- **Time.** Paper deadline 2026-12-31. Stage 1 alone needs the logging changes, a collection window and training. Stages 3 and 4 are likely a pilot, not a campaign, by then.
- **The July results are not evidence for the JEPA:** its critic was fitted to hand-written targets (`train.py` `critic_loss`), not to outcomes.
- **Per-object rollouts.** The state is one object, so search plans sequences of actions on one object, not "avoid the cactus, then find food".

## Decisions for you
1. Is stage 2 (depth-1 at contact) the first experiment, with stage 3 as the first real campaign?
2. Collection world: same as the Kalman plants world minus the test manipulations?
3. Exploration level above the arbiter's 0.03 floor: one level (my suggestion: 0.15)?
