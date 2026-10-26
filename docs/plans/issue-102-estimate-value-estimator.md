# Issue #102 — `Estimate(Q, SE, σ)` record and `ValueEstimator` interface

Status: **approved with recommendations and implemented.**
Context: arbiter story map (`docs/plans/2026-q4-research-plan.md`), A2; arbitration paper draft
(`docs/bib/uncertainty_arbitration.pdf`) §4.4 (what each controller reports), §4.5 (the `decide` pseudocode that
calls `ctrl.estimate(candidates, H)`), Appendix A step 3 (names this record and interface).
Revised after reading the paper: first draft had a per-candidate signature that did not match §4.5.
Consumed by #103 (operant Welford estimator, depends on #98/#100/#101/#102); the arbiter proper reads these.

## Scope

In: the contract between "a controller's belief about how good a candidate is" and the arbiter.

- `Estimate(double q, double se, double sigma)` — a record.
  - `q`: expected value V(K|H) of the candidate (§4.1, `HomeostaticValue`).
  - `se`: uncertainty about `q`; shrinks with evidence.
  - `sigma`: spread of the outcome itself; does **not** shrink with evidence.
- `ValueEstimator` — an interface returning one `Estimate` per candidate (action, target) given internal state H.
- A trivial test double and a test pinning the contract.

Out: any real estimator (#103), the arbiter/Thompson draw, wiring into `FullAppraisal`, persistence,
a flag. No behaviour change.

## Design

Package: `br.cefetmg.lsi.l2l.creature.arbiter` (see Q1).

```java
public record Estimate(double q, double se, double sigma) {
    // compact ctor: q finite; se and sigma finite and >= 0
}
```

Validation mirrors `HomeostaticValue`'s (IllegalArgumentException on non-finite or negative spread), so a
NaN can never reach a Thompson draw silently. A static `Estimate.uninformed(double sigma0)` is deliberately
**not** added: the prior (Q0 = 0, σ0 = 1, κ) is #103's decision, not part of the contract.

```java
public interface ValueEstimator {
    /** One Estimate per candidate, same order and size as {@code candidates}. */
    List<Estimate> estimate(List<Action> candidates, double[] h);
}
```

Batch, not per-candidate, because §4.5 calls it once per controller over the whole candidate set (the arbiter then
needs the top-two Q/SE for `conf = Φ((Q1−Q2)/√(SE1²+SE2²))`), and the episodic kNN and planner rollouts are
naturally set-level.

- Candidate is the existing `creature.common.Action` (type + `Perception`, whose `getTarget()` is the
  `WorldObjectType`), so no new candidate type and no adapter for #103.
- `h` is in `FocusTracker.DRIVES` order — the one order used by #100 and #101.
- The three controllers of §4.4 all fit: operant (Welford cells, #103), episodic (kNN over key, later issue), planner
  (`WorldModelEngine` wrapper). Inputs they need beyond (candidates, H) — the focal drive for operant cell keys, the
  latent state for the planner — are **constructor/collaborator state of the concrete estimator**, not interface
  arguments (the paper's pseudocode passes only candidates and H). Flagged for #103, which needs the focal drive.
- Read-only: `estimate` never mutates estimator state. Learning (`update` from closed episodes) is **not** on
  this interface, because the arbiter only reads; #103 adds its own update method on the concrete class.
  This keeps the double trivial and avoids freezing a learning signature before #103/#110 exist.
- The estimator is per-creature actor-owned state, not a shared instance (Akka anti-pattern rule); the
  interface says nothing that would encourage sharing.

## Tests (`EstimateTest`)

1. `Estimate` accepts valid values; rejects NaN/∞ in any field and negative `se`/`sigma`; `q` may be negative.
2. Record equality/accessors.
3. `withSeScaled`: scales `se` only, rejects invalid factors (NaN, infinite, negative). An overflowing product
   is rejected by the constructor (documented on `withSeScaled`; not separately tested).

No test for `ValueEstimator` itself: an interface with no real implementation would only test its own stub.
Its contract (one estimate per candidate, same order and size) is asserted by each implementation's tests, starting
with #103's.

"Done when… used by #103": satisfied structurally here (signature chosen against #103's text: per
(focal drive, object type, distance bin) cells need only candidate + H); actual use lands with #103.

## Decisions

- **Q1 — Package: `arbiter`.** The issue and paper Appendix A say `creature.arbitration`; the code already has
  `creature.arbiter` (`FocusTracker`, `HomeostaticValue`). Chose `arbiter`: one package, no rename churn.
- **Q2 — Candidate type: existing `Action`.** `Action` carries the live `Perception` (object id, distance) that
  #103's distance bins need; a new `Candidate` type would only re-derive it.
- **Q3 — Estimate-only interface.** `ValueEstimator` declares no `update(...)`. The arbiter only reads; learning
  is the concrete estimator's concern (#103 adds its own write side).
- **Q4 — No `minEfficiency()`.** The efficiency tiers of §4.5 are owned by the arbiter ticket and held in its own
  controller list, not on this interface.
- **Q5 — `Estimate.withSeScaled(double factor)` added now.** Small; supports the SE calibration of §4.4 without
  changing the record.

`ValueEstimatorTest` (a test double pinning the interface) was dropped in e830692: it only exercised its own stubs.
The file may still exist untracked in a working tree; it is not part of the contract.

## Verification

`mvn test -Dtest='EstimateTest'`, full `mvn test`, `mvn package`, `graphify update .`.
No mini-experiment: nothing reads the estimator yet (same reasoning as #101); evidence is the contract tests.
