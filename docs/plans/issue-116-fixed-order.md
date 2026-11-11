# Issue #116 — Fixed-order mode (A5)

Status: **plan, 2026-11-11.** Depends on #103, #113.

One switch (`arbiterMode = ARBITRATION | FIXED_ORDER`, config `arbiterFixedOrder`; `Arbiter` constructor mode) turns the arbitration rule into
the chain's positional rule: **episodic decides whenever it is affordable and has evidence, operant otherwise.** Everything else is identical
to A3: same two estimators, same budget (episodic still needs e >= 0.3, so the contrast isolates the *rule*, not the budget), same value, episodes, key,
Thompson draw with novelty, epsilon floor and random stream.

Evidence (an A5-only constant, fixed in the recipe): **every candidate's action has `n_eff >= 3`** in the episodic estimate. This reads
"memory has seen each of the options". With arbitration the same estimates are weighed by decisiveness instead.

Implementation: in FIXED_ORDER the arbiter evaluates the same cheapest-first list but chooses the deciding controller by that rule instead of
`conf >= theta`; consultations are recorded the same way (telemetry columns unchanged). Random draws are consumed exactly as in ARBITRATION.

Done when: a seeded test runs both modes on identical stub controllers and asserts that chosen estimates and stream consumption are identical
whenever both modes pick the same controller, and that the modes differ only in `decidingController` (and what follows from it) otherwise.
