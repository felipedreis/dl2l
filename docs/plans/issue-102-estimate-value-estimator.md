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

## Tests (`EstimateTest`, `ValueEstimatorTest`)

1. `Estimate` accepts valid values; rejects NaN/∞ in any field and negative `se`/`sigma`; `q` may be negative.
2. Record equality/accessors.
3. `ConstantValueEstimator` (test double, in `src/test`): returns a fixed `Estimate` for every candidate;
   preserves order and size, empty list → empty list.
4. A per-action double (map ActionType → Estimate) proving the interface can express "different candidate,
   different estimate" — the property #103 needs.

"Done when… used by #103": satisfied structurally here (signature chosen against #103's text: per
(focal drive, object type, distance bin) cells need only candidate + H); actual use lands with #103.

## Proposals needing a decision

- **Q1 — Package.** The issue and paper Appendix A say `creature.arbitration`; the code already has `creature.arbiter`
  (`FocusTracker`, `HomeostaticValue`). Recommend **`arbiter`** (one package, no rename churn).
- **Q2 — Candidate type.** Existing `Action` (recommended) vs. a new `Candidate(ActionType, WorldObjectType)`
  value type. `Action` carries the live `Perception` (object id, distance) which #103's distance bins need; a
  new type would just be re-derived from it.
- **Q4 — Efficiency tier.** §4.5 gives each controller `min_efficiency` (0 / 0.3 / 0.7). Leave it off this
  interface (recommended; the arbiter ticket owns tiers and can hold them in its own controller list) vs. add
  `double minEfficiency()` now.
- **Q5 — SE calibration helper.** §4.4 rescales SE by a clipped RMS of recent z. Add
  `Estimate.withSeScaled(double factor)` now (small, recommended) vs. leave to the calibration issue.
- **Q3 — Read-only interface.** Estimate-only (recommended) vs. also declaring `update(...)` now.

## Verification

`mvn test -Dtest='EstimateTest,ValueEstimatorTest'`, full `mvn test`, `mvn package`, `graphify update .`.
No mini-experiment: nothing reads the estimator yet (same reasoning as #101); evidence is the contract tests.
