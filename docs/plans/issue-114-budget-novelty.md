# Issue #114 — Efficiency budget and novelty bonus

Status: **plan, 2026-11-11.** Paper §4.5. Depends on #104, #113.

## Discrepancy resolved: the novelty bonus
The issue writes `0.1 / sqrt(1 + n_eff)`; the recipe §4 and the text extract of the paper read `0.1 / (1 + n_eff)`. On the rendered
PDF (§4.5, "Three terms complete exploration") the square root is there: **`0.1 / sqrt(1 + n_eff)`**, which is what is implemented. The text
extractor puts the radical on a separate line. The recipe's §4 is corrected in the same commit.

## Budget
`e = behaviouralEfficiency` already is `E(arousal)/10` in [0, 1] (`PartialAppraisal.normalizedBehaviouralEfficiency`: Campos's inverted U
with at least `COMPLEX_TASK` percepts, `1 - exp(-0.4 a)` otherwise), carried by `SelectionContext.efficiency`. No new computation.
`Arbiter.Controller` gets `minEfficiency` (OPERANT 0, EPISODIC 0.3, PLANNER 0.7, from the type by default). `decide(..., efficiency)`
stops the cheapest-first consultation at the first controller with `minEfficiency > e`; `mostExpensiveAffordable` becomes real (it was
a placeholder in #105). The 3-argument `decide` keeps working and treats everything as affordable (efficiency 1), so R1 tests stay as they are.
Consequence to report: a calm creature (few percepts, arousal below ~0.9) has e < 0.3 and cannot afford episodic memory; that is the paper's design.

## Novelty
`Arbiter.Params` gains `noveltyScale = 0.1`. The Thompson draw is `Q + novelty + tau * SE * z` with `novelty = noveltyScale / sqrt(1 + nEff)`
for estimates that carry an `nEff` (episodic), 0 otherwise. The bonus is deterministic, so the random stream is consumed exactly as before.
Telemetry: `arbitration_decision` gains `novelty_bonus` (the chosen candidate's), beside the existing `efficiency` and `affordable_controller`.

## Tests
Unaffordable controller is not consulted; affordability boundary (>=); novelty value, its effect on the draw with SE = 0, and that the
stream is consumed identically with and without it; telemetry round trip and golden extraction.
