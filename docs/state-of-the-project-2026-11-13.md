# State of the project — 2026-11-13

A one-page map for the researcher and the advisor: where we were going, what we built, what we learned, and the choice in front of us. Terms in *italics* are explained at the end.

## 1. The goal, and how we got onto the current path

The thesis question is whether a learned *world model* (the *JEPA*) helps an artificial creature compared with the classic architecture (Campos / Mapa lineage: emotions, memory, *operant table*).

On 2026-10-17 we agreed a plan for the year end (`docs/plans/2026-q4-research-plan.md`): one paper on **arbitration**, meaning several controllers each say how sure they are and the most confident one drives behaviour. The JEPA was **parked, not dropped**. It was to enter as the last step (the "planner" controller) once the classic controllers worked. The *arbiter* was meant to be the fair way to put the JEPA and the classic parts side by side. The steps before it were A0 (baseline), A1 (attention), A2 (arbiter with the operant table), A3 (+ episodic memory) and A5 (same controllers in a fixed order).

## 2. What we built, and what works

- **The experiment machinery:** cluster runs on CCAD, pre-registered recipes, read-only status and limits checks, a data pipeline (Arrow → Parquet → HuggingFace), and the analysis module. All reusable for any future experiment, the JEPA included.
- **The arbiter, the *episodic memory* and the attention tracker:** implemented and tested (about 580 unit and integration tests).
- **Three cluster experiments this round** (`p117_pilot`, `p117_plants`, `p117_factorial`: 111 trials, 555 creatures), on top of the earlier p107 pilots; all documented in `docs/reports/` and `docs/experiments/`.

## 3. What we learned (each backed by data)

1. **Every creature that dies, dies of hunger.** Pain and sleep never kill in these worlds.
2. **The hand-written rules do the real work.** "When hungry, only eat, approach or wander" (the *tendency lists*) keeps every arm alive in the standard world. Remove them and every learner starves within minutes.
3. **So the standard world can't tell controllers apart.** With the rules on, everything survives; with them off, everything struggles. Learning has no room to matter.
4. **Arbitration lost to the simple fixed order** in every setting where the two could be told apart (96-trial experiment, `docs/reports/p117_factorial_report.md`). The cause is a design flaw we pinned down exactly (`docs/reports/p117_factorial_mechanism.md`):
   - the arbiter trusts the operant table when it is confident about one option;
   - the table then picks almost at random among the 10-30 options it has never learned about;
   - so the food on offer is eaten less than 1 time in 10.
5. **Episodic memory learns sensible things** (food good, gray apple worthless, cactus harmful). A rule in the paper erases its credit for *approaching* food, which was the very thing it was added to provide.

## 4. Why it feels like a loop

- **We mixed up two ideas.** *Operant conditioning* (fast learning from consequences) and a *habit* (slow, automatic behaviour) are different things. The paper calls our table a habit; it is an operant table.
- **We asked the operant table the wrong question.** In Mapa, memory picks *which object* to go for and the operant table picks *what to do with it*. We merged both questions into one list of 30 options, and the table can't answer the first.
- **We compared confidences measured in different units** (the table works in ±1, memory in "drive reduced").
- **We tested in a world where learning can't pay off.** The literature tests arbitration where the world *changes*, e.g. a food becomes worthless (devaluation), and fixed rules then do badly.

None of this is a dead end: each point has a known fix. But fixing them changes the paper's design, and the year-end deadline is about seven weeks away.

## 5. The options

| Option | What it means | Cost | Main risk |
|---|---|---|---|
| **A. Rescope the arbiter paper** | Report why naive arbitration fails, fix the three flaws, test in a world that changes (devaluation) | New design and pre-registration, then 2-3 cluster campaigns | Tight for 31 December; the result could still be null |
| **B. Back to JEPA vs classic** | Park the arbiter. Compare the JEPA-based creature with the classic one on the validated baseline | Retrain the JEPA critic on post-fix data (current models predate the August fixes), then campaigns | The JEPA was last validated before those fixes; its earlier advantages are provisional |
| **C. Write up what we have** | A shorter paper or thesis chapter on the classic baseline and the arbitration findings, as a careful negative result with its mechanism; JEPA after | Mostly writing | Less ambitious than planned |

These can be combined: for example C now (it secures what is done) and B next.

## 6. Decisions needed

1. What should the year-end deliverable be: the arbitration paper (A), the JEPA comparison (B), or a write-up of what we have (C)?
2. Is the arbiter still the way the JEPA should be integrated, or should the JEPA be compared on its own against the classic creature?
3. Does the advisor's ALifeWorld reproduction track change the priority?

## Terms

- **Operant table:** for each (object, action) pair, a running average of how well that action worked on that object (the "Welford table" is just the formula for keeping that average).
- **Habit:** behaviour that has become automatic through repetition and keeps going even when its outcome no longer pays (distinct from the operant table).
- **Episodic memory:** stores specific past situations and what followed them; values a new situation by the most similar past ones.
- **Arbiter:** decides, each moment, which controller drives behaviour, based on how sure each one is.
- **Tendency lists:** fixed rules such as "when hungry, only eat, approach or wander".
- **JEPA:** the learned world model that predicts what happens next; the thesis's main contribution.
