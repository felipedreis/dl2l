# Controllers spec: what each component is, what it learns, and how the arbiter combines them

Status: **draft, 2026-11-13; updated 2026-11-18** (assignment principle, literature reviews added). Each item is marked **[decided]** or **[open]**. This spec comes before any code change. Its purpose is to stop components from drifting into each other's roles
(see `docs/state-of-the-project-2026-11-13.md`, section 4). Sources: the lineage analyses in `../alifeworld/docs/legacy/`, `docs/plans/memory-architecture-mapa-campos-synthesis.md`,
`docs/plans/arbiter-proposal-review.md`, `docs/reports/p117_factorial_mechanism.md`. Literature reviews for 2.5, 2.6 and 3.2: `controllers-literature-operant-learner.md` (2026-11-13) and
`controllers-literature-two-stage.md` (2026-11-14); both are subagent output, so claims tagged [abs] or [rec] there must be checked before they go in the paper.

## 1. Lineage and what each step is meant to improve [decided]

| Step | Predecessor | What we keep (the intention) | What we change |
|---|---|---|---|
| Innate tendencies | Campos (2006, 2015): action tendencies per emotion | Each drive biases which actions make sense ("when hungry: eat, approach, wander") | Nothing yet; see 3.1 |
| Operant conditioning | Silva (the Campos/Silva substrate of ALifeWorld): per-object action probabilities, nudged by outcomes | **A good outcome makes the creature more likely to do that again with that object; a bad outcome, less likely.** Fast, per outcome. Applies at the object (what to do with it), not to choosing which object to go for | It reports a value and an uncertainty in the common unit (section 2), so the arbiter can weigh it |
| Episodic memory | Mapa (2009): complete emotional experiences, memory chooses the object | Remember what followed past situations and use it in similar ones | MFEC-style memory (kNN over engrams, returns valued against current need), up to date with episodic-control research; validated on the snake curriculum (separate repository) |
| Arbitration | Mapa's fixed split: memory picks the object, operant picks the action | Each component contributes where it knows something | The split is no longer hard-wired: whichever component is more reliable for a choice drives it |
| World model (later) | none in the lineage | — | The JEPA, as the planner controller |

What each component is **not**: the operant table is not a habit (automatic, slow, devaluation-insensitive behaviour) and not a reduced episodic memory (no context, no returns, no episodes).

## 2. The arbiter's contract

1. **Common unit [decided].** Every controller reports, per candidate, the expected drive reduction `V(K | H)` (the deficit removed, paper section 4.1). Its uncertainty `SE` is the uncertainty about that expectation, and `sigma` is the spread of single outcomes, both in the same unit.
2. **No claims without evidence [decided].** A controller that has never learned about a candidate must not hand it its own "lottery ticket" in the draw. It either abstains on that candidate or gives it a prior that is honest on the common scale. The p117 failure came from untried operant cells at Q 0, SE 1 in valence units competing as 10-30 independent draws.
3. **The confidence that stops the consultation is the confidence of the choice actually made [decided].** Today decisiveness compares only the two best candidates, while the draw runs over all of them; the two must agree.
4. **Calibration [decided].** An honest SE gives `z = (V_realised - Q) / sqrt(SE^2 + sigma^2)` with mean about 0 and variance about 1. This is measured per controller and is a requirement, not a tuning target.
   **Reliability must react to change [decided, 2026-11-16].** An SE that only shrinks with the number of samples stays confident about a value that has gone stale, so after a devaluation the controller keeps winning the arbitration. Each controller's SE must grow when its predictions stop matching outcomes. Two mechanisms are within reach: a learner whose uncertainty grows between observations (Kalman, see 3.2), and the calibration rescale of the paper (section 4.4: SE scaled by the controller's recent z), which the pilot switched off. The rescale (or an equivalent) is a **required part of the design**, no longer "evaluated later".
5. **Candidate set [open].** Today every (object, action) pair in view is one flat list of 10-30 candidates. Options: keep the flat list with requirement 2 enforced; or decide in two stages (which object, then which action on it) with arbitration inside each stage. The choice affects what "operant at the object" means operationally.
6. **Combination rule [open].** First decisive controller (current), precision-weighted mixture of the controllers' estimates (Daw, Niv & Dayan 2005; Lee, Shimojo & O'Doherty 2014), or value of information (Keramati et al. 2011). To be chosen after the literature step.
   Literature step done (2026-11-13/14): it does not settle 2.5 (flat versus two-stage is divided; see `controllers-literature-two-stage.md`, section 5) and recommends a precision-weighted mixture with a value-of-information gate for 2.6 (`controllers-literature-operant-learner.md`, section 3). Both stay **[open]** until the pre-registration.
7. **Assignment principle [decided, 2026-11-16].** *Assign by what a controller can learn, never by which question it answers in which world.* Nobody owns a stage by design. What each controller can have an opinion about is fixed by its learning signal, which does not depend on the world: the operant table learns only from the immediate outcome of acting on an object, so it can value "eat this or not" and cannot value "go to that apple" (no outcome ever arrives for approaching); episodic memory learns from what followed in whole episodes, so it can value both. Who decides is left open and is found by the arbiter, per candidate and per moment, by calibrated confidence: in a stable world the operant estimates become accurate and it ends up driving (an outcome, not a design choice); after a change its reliability should drop (requirement 4) and control should move. This is the point of arbitration in Daw, Niv & Dayan 2005 and Lee, Shimojo & O'Doherty 2014. A fixed order, or a stage assigned per world, would rebuild the present mismatch between the architecture's learning abilities and the environment. It follows that option (a) in 3.2 is a property of the operant learning rule, not a role we assign.

## 3. The components

### 3.1 Innate tendencies (Campos)
- **Question answered:** which actions make sense for the current dominant drive.
- **Learns:** nothing; fixed by design.
- **Status [open]:** today a silent pre-filter that removes candidates before any controller sees them; the p117 experiments show it carries survival in a static world. Open: keep it as a filter in every arm, or make it a component that reports in the common unit and can be overridden. Either way it must be named in every arm's description.

### 3.2 Operant conditioning (Silva)
- **Question answered [decided, option (a) of 2026-11-13]:** at an object, which action to take on it. It does not value going to an object (approach); that stays with episodic memory and the innate tendencies, as in the legacy.
- **Learns from [decided]:** the immediate outcome of an action on an object, as in the legacy (`Valuation`: the same evaluation events that reinforce `OperantConditioning`), one update per outcome; outcomes on the creature itself (sleep recovery, tedium) excluded, as in the legacy (review D2).
- **Reports [decided]:** expected drive reduction and its uncertainty, in the common unit (section 2).
- **Timescale [decided]:** fast; recent outcomes dominate.
- **Competition among the actions on one object [open, principle stated by the researcher].** In the legacy, the probabilities of the actions on an object sum to 1, so evidence that one action pays raises it and lowers the others. The proposed principle: *more confidence in one action on an object should reduce the standing of the alternatives on that object.* Whether and how this should hold when the output is a value with an uncertainty (not a probability) is a question for the literature: candidates include Kalman-filter conditioning (Dayan, Kakade & Montague 2000; Kruschke 2008; Gershman 2015), Bayesian Q-learning (Dearden, Friedman & Russell 1998), and normalised or competitive action-value models. Not to be guessed.
- **Literature recommendation [open, for decision]: a per-cell Kalman filter with a pooled prior across objects, abstention for an action never tried on anything, and tau2 chosen so the steady-state gain is about 0.2-0.3 (`controllers-literature-operant-learner.md`, section 2). Its finding on competition: the literature does not support lowering the value or raising the uncertainty of alternatives because one action proved good (mutually exclusive actions share no outcome, so there is no explaining away); it supports competition **at the choice** (softmax, divisive normalisation). The researcher's principle above is therefore implemented at the choice, not in the cells, unless the researcher decides otherwise.
- **The current implementation does not meet this spec:** it reports valence (+1 / -1) instead of the common unit, its untried cells are Q 0 with SE 1 on that scale, and it has independent cells per action with no competition between them.

### 3.3 Episodic memory (MFEC, modernised Mapa)
- **Question answered:** how good a candidate (object and action, in this situation) is, judged by what followed similar past situations.
- **Learns from:** closed episodes; every step stores its discounted return G (a drive-change vector), valued at retrieval against current need, `V(G | H_now)`.
- **Reports:** expected drive reduction (kNN mean), SE from the effective number and distance of neighbours, sigma from their spread.
- **Known defect to fix [decided]:** a timed-out episode zeroes the return of APPROACH steps only (`EpisodeBuffer.java` around L301), which erases the credit for approaching food. APPROACH must not be singled out.
- **Open:** the episode boundary (focus episodes time out 60-80% of the time), the novelty bonus's scale (larger than the typical value gap today), and the budget threshold (e >= 0.3) that decides the episodic share almost by itself in calm creatures.

### 3.4 Episodes and the attention tracker
- **Role:** define the boundaries over which episodic returns are computed. Not a controller.

### 3.5 World model (JEPA), later
- **Question answered:** the predicted consequence of a candidate, valued by a critic in the common unit. Requires the critic to predict the drive-change vector and retraining on post-fix data (paper section 4.4). Out of scope until 3.2 and 3.3 meet this spec.

## 4. How the components should be compared [decided in principle, design open]

A world in which one component learns well cannot show what another adds. The comparison world should have **phases**: a stable phase, where the operant conditioning should do well, then a change partway through life (devaluation or reversal of a food), where adaptation and memory matter. Every arm (A0 through A5, later the JEPA) runs in the same world under one pre-registration, with the same tuning budget, and the innate tendencies are stated for each arm.

## 5. Next steps

1. ~~Literature step for 3.2~~ done; read the two reviews and decide 3.2 (Kalman cell, pooled prior, abstention).
2. Decide 2.5 (candidate set) and 2.6 (combination rule). Questions the reviews leave to the researcher (`controllers-literature-operant-learner.md`, section 5): which devaluation the arbitration claim rests on (need change versus world reversal), scalar or drive-change vector in the operant cell, the clock for tau2 on unobserved cells, whether a pooled prior counts as evidence under 2.2, the arm descriptions for stage 1 in A2. Cheapest first step the second review supports: keep the flat list, tag far-object candidates as goal-level and contact candidates as action-level in the decision record, keep episodic returns per object, and calibrate the two kinds separately.
3. Pre-registration draft for the phased world (section 4).
