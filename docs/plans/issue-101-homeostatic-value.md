# Issue #101 — HomeostaticValue: deficit d(H) and value V(K|H)

Status: **plan, awaiting approval.**
Context: arbiter story map (`docs/plans/2026-q4-research-plan.md`); arbitration paper draft §4.1
(`docs/bib/uncertainty_arbitration.pdf`). Consumes the K recorded by #100; read by the estimators (A2).

## Scope

In: a pure, stateless value function

- `d(H) = ‖ w ⊙ max(0, H − H*) ‖₂`
- `V(K | H) = d(H) − d(H + K)`

with H* = 0.18 (`Constants.MIN_AROUSAL_LEVEL`, the lower edge of the #59 band) and w = 1.

Out: wiring it into any decision path, reading K from `drive_outcome`, emotion-modulated w (the
"experiment that follows" in §4.1), the urgency/focus rule (already `FocusTracker`).
No flag, no persistence, no behaviour change.

## Design

`br.cefetmg.lsi.l2l.creature.arbiter.HomeostaticValue` — immutable, thread-safe, no actor state (so it can be
called from any component without the shared-instance anti-pattern; it holds only two constant arrays).

- Drive order is `FocusTracker.DRIVES` = (hunger, sleep, pain, tedium), the same order as K in #100's
  `DriveOutcomeState`. One order everywhere avoids index mix-ups.
- Constructor takes `setPoint` (per drive) and `weights` (per drive); `HomeostaticValue.defaults()` gives
  H* = 0.18, w = 1 for the four drives. Weights are a constructor argument now so emotion-modulated w is a later
  caller change, not a rewrite.
- `double deficit(double[] h)` and `double value(double[] k, double[] h)`.
- Dimension is checked against the configured drives (the paper's worked example is 2-drive, so the class is
  length-generic rather than hard-coded to 4; defaults() fixes it to 4).
- `H + K` is **not** clamped to [0, 7]: `max(0, ·)` already floors it, and clamping the ceiling would hide the
  value of an outcome that overshoots. Callers pass realized K (#100 is post-clamp already).

## Tests (`HomeostaticValueTest`)

1. Paper example, 2 drives, H* = 0.18: K = (−2, 0) → V ≈ 1.95 at H = (5, 1) and ≈ 0.07 at H = (1, 5)
   (tolerance 0.005). Hand check: d = √(4.82²+0.82²) = 4.889; after: 2.937 → 1.95; second: 4.889 → 4.82 → 0.07.
2. d = 0 when every drive ≤ H*; drives below H* contribute 0 (not negative).
3. V = 0 for K = 0; V ≥ 0 for any K ≤ 0 componentwise (monotone: relief never hurts); V < 0 when K raises a deficit.
4. Weights: w doubling on the relieved drive scales its share; w = 0 drive is ignored.
5. Irrelevant-drive relief: K on a drive below H* is worth exactly 0.
6. Dimension mismatch → `IllegalArgumentException`.

## Proposals needing a decision

- **Q1 — Weights.** Constructor argument, default 1 (recommended) vs. a fixed constant until the emotion-as-precision
  experiment.
- **Q2 — Dimension.** Length-generic with a 4-drive default (recommended) vs. hard-coded to 4 and test the paper's
  2-drive example through a private helper.
- **Q3 — Where set points live.** One shared H* (0.18 from `Constants.MIN_AROUSAL_LEVEL`, recommended) vs. a
  per-drive array in `LearningSettings` so the A-series can sweep it. Per-drive is supported by the class either way;
  this only decides whether a config knob is added now.

## Verification

`mvn test -Dtest=HomeostaticValueTest`, full `mvn test`, `mvn package`. Per CLAUDE.md step 5 a mini-experiment is
only meaningful once V is read by something; nothing reads it here, so the evidence for this issue is the
reproduced worked example from the paper. No hypothesis experiment/report until A2.
