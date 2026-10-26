# Issue #103 — Operant estimator: Welford cells per (focal drive, object type, distance bin)

Status: **approved 2026-10-25 with recommendations (Q1, Q3–Q6; Q5 includes the #102 review fixes); Q2 decided: decay is a constructor parameter, default 0.995 (paper §4.4).**
Silva/Mapa's operant table (alifeworld `ProbabilityMemory`) has no decay (zero-sum redistribution only), so there is no
legacy rule to follow; `decay = 1` reproduces "forgets nothing".
Context: arbiter story map (`docs/plans/2026-q4-research-plan.md`), A2; arbitration paper draft
(`docs/bib/uncertainty_arbitration.pdf`) §4.4 "Operant table (habit)", Fig. 1 tier 1, App. A step 5.
Depends on #98 (focal drive), #100 (K), #101 (V), #102 (`Estimate`, `ValueEstimator`) — all merged/on this branch.
Closes the loop for #110 (episode close-out in `Valuation` calls `record`) and #104 (arbiter calls `estimate`).

## Scope

In: `OperantEstimator implements ValueEstimator` — the cheapest controller (always affordable). A table of
running statistics of the value V experienced per (focal drive, object type, distance bin, action), turned into
`Estimate(Q, SE, σ)` by the paper's formulas:

- `Q = (n·v̄ + κ·Q0) / (n + κ)`, `SE = σ / √(n + κ)`; κ = 1, Q0 = 0.
- σ: prior `σ0 = 1` while `n ≤ 1`; then **`σ² = (ν0·σ0² + (n−1)·s²) / (ν0 + n − 1)`, ν0 = 1** — the sample variance blended with
  the prior. **Deliberate departure from the paper's `σ = s`**: with `σ = s` a constant outcome gives `s = 0` from `n ≥ 2`,
  so `SE` is exactly 0 forever; #104's `Φ((Q1−Q2)/√(SE1²+SE2²))` then saturates and #115's `√(SE²+σ²)` is 0. The blend keeps
  σ and SE strictly positive and shrinking with evidence, and converges to `s` for a noisy cell. The blend equals the prior at
  `n = 1`, so the paper's `n ≥ 3` gate is dropped: it would have made σ (and SE) step down at `n = 3` (code review). Found when the first
  implementation's "done when" test could only assert SE narrowing until it hit 0.
- Each update multiplies the count by 0.995 first, then adds the observation (Welford).

Out (by design): detecting episode close and computing V(G | H) — that is #110, which will call `record`;
wiring into `FullAppraisal` / the arbiter (#104); SE calibration (#115); persistence; any flag. The existing
`OperantConditioning` is untouched (kept for the non-arbiter arms). No behaviour change.

## Design

Three small pieces in `br.cefetmg.lsi.l2l.creature.arbiter`, so the learning backend can be swapped without touching
callers:

- **`ValueLearner`** (write side): `void record(String focalDrive, Action action, double value)`. The only thing
  the close-out depends on. `value` must be finite.
- **`ValueEstimator`** (read side, #102). Unchanged.
- **`WelfordTable`** (package-private, policy-free): keyed decayed running statistics. `observe(key, v)` and
  `stats(key)` → (n, mean, sd). No binning, no priors, no `Estimate`.
- **`OperantEstimator implements ValueEstimator, ValueLearner`**: binning, key construction, the paper's formulas
  (κ, Q0, σ0) over a `WelfordTable`.

The legacy `OperantConditioning` interface is **not** reused: it returns action probabilities (no honest SE to
derive) and is nudged per outcome by valence, whereas this learner takes one value sample per decision when its
episode closes (paper §4.2: `G_t = Σ γʲ K_{t+j}`, valued as `V(G | H)`).

- **Key** = `(focalDrive, objectType, distanceBin, ActionType)`. Candidates with no object (WANDER, SLEEP, …) use a
  `NONE` object bucket and distance bin 0; no focus uses a `NONE` focal bucket.
- **Distance bins** from `Perception.distance` (decision-time): sorted upper edges given at construction, default
  three bins at 1/3 and 2/3 of `Constants.DEFAULT_VISION_FIELD_RADIUS`; calibrated later (#107).
- **Statistics** (per key): decayed count `n`, mean `v̄`, `M2`. Update with `d = 0.995`:
  `n ← d·n; n' = n + 1; δ = v − v̄; v̄ += δ/n'; M2 ← d·M2 + δ·(v − v̄); n ← n'`. With `d = 1` this is exactly
  Welford (tested against a batch computation). `s = √(M2 / (n − 1))` for `n > 1`, blended into σ as above; else `σ0 = 1`.
- **Unseen key**: `n = 0` → `Q = 0`, `σ = 1`, `SE = 1/√κ = 1`.
- **Focal drive** is read when estimating from a `Supplier<String>` given at construction (the arbiter passes
  `() -> tracker.focalDrive().orElse(null)`); `record` takes the drive explicitly, since the close-out knows the
  closed episode's drive. The estimator holds no `FocusTracker`.
- `estimate(candidates, h)` is read-only, one `Estimate` per candidate in order; `h` is unused here (V is already
  evaluated against H when recorded) — documented, not an error.
- **Ownership, deliberately open.** Plain, not thread-safe, no owner assumed. Where it runs is #110/#104's call:
  `Valuation` is the natural writer (it already values each outcome and consolidates memory — Mapa's design) and
  episodes close on the tracker's `FocusEvent`s, but `FullAppraisal` (which reads for selection and, since #99, owns
  the tracker) is a different actor. Sharing one instance across them is the Akka anti-pattern; #110 must pick a
  facade or a message-passed read model. Nothing in #103 constrains that choice.

## Tests (`OperantEstimatorTest`) — real implementation, no stubs

1. **Done-when (#88):** repeating one action with constant V = 0.5, 50 times: SE strictly decreases every step;
   Q never exceeds v̄ = 0.5, rises monotonically toward it, and Q after 50 < 0.5 (shrinkage). Same for
   constant negative V (Q never below v̄). Frequency alone never inflates Q: two cells with equal mean V but 5 vs
   50 observations have Q_5 ≤ Q_50 ≤ mean.
2. Unseen key → `(0, 1, 1)`.
3. Welford: with decay 1, mean/variance equal a batch computation on a random sequence (tolerance 1e-9).
4. Decay: count after k updates equals the closed-form decayed count; old evidence fades (an early outlier's
   influence shrinks).
5. σ: prior `1` unseen; `sqrt(1/n)` for a constant outcome (no step); blends the sample variance with the prior;
   converges to the sample SD for a noisy cell while SE keeps shrinking.
6. Cell separation: different focal drive / object type / distance bin / action type do not share statistics.
7. Distance bins: edges respected on boundaries; no-object candidate uses the `NONE` bucket; null focal → `NONE`.
8. `estimate` preserves order and size, empty list → empty list, and is read-only (calling it does not change a
   later result).
9. `record` rejects NaN/∞.
10. `WelfordTable` alone (via the estimator's tests plus a direct table test): keys independent, decay math.

## Decisions

- Q1 3 distance bins at 1/3 and 2/3 of the vision radius, edges a constructor argument. Q2 decay is a constructor
  argument in (0, 1], default 0.995, applied to the updated key only (Silva/Mapa's table has no decay). Q3 sample
  variance `M2/(n−1)`, blended with the prior (see above). Q4 focal drive from a `Supplier<String>`. Q5 branch shared
  with #102, whose review fixes were folded in. Q6 `ValueLearner` + `ValueEstimator` + `WelfordTable`.

## Verification

`mvn test -Dtest=OperantEstimatorTest`, full `mvn test`, `mvn package`, `graphify update .`. Per CLAUDE.md step 5
a mini-experiment needs a reader of Q/SE; nothing reads this until #104/#110, so the evidence for this issue is
the property tests above (the #88 inflation property is exactly the stated "done when"). The A2 campaign (#109)
is where the hypothesis is tested.
