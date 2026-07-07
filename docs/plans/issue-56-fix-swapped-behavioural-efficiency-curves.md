# Plan: Fix swapped behavioural-efficiency curves (Issue #56)

## Problem

`PartialAppraisal.normalizedBehaviouralEfficiency(arousal, perceptionsCount)` has two bugs:

1. **Swapped branches**: the monotonic curve (Mapa Eq 4.1, simple tasks) is wired to the complex branch and the inverted-U curve (Eq 4.2, complex tasks) is wired to the simple branch — exactly backwards.
2. **Dead code**: the `arousal < MIN_AROUSAL_LEVEL` guard is unreachable because `Emotion.setLevel` already clamps arousal to ≥ `MIN_AROUSAL_LEVEL`.

## Current implementation

```java
if (arousal < Constants.MIN_AROUSAL_LEVEL) {
    efficiency = 5.55 * arousal / 90.0;                               // dead
} else if (perceptionsCount < Constants.COMPLEX_TASK) {
    efficiency = (arousal * (5.714 - (0.816 * arousal))) / 9.303;     // WRONG: inverted-U for simple
} else {
    efficiency = (16 * (1 - Math.exp(-0.4 * arousal))) / 15.0;        // WRONG: monotonic for complex
}
```

## Correct assignment (Mapa §4.1.4, Eq 4.1/4.2)

| Task | Condition | Curve | Formula |
|---|---|---|---|
| Simple (0–1 objects) | `perceptionsCount < COMPLEX_TASK` | Monotonic (Eq 4.1) | `16(1−e^{−0.4A}) / 15.0` |
| Complex (≥2 objects) | `perceptionsCount >= COMPLEX_TASK` | Inverted-U (Eq 4.2) | `(A × (5.714 − 0.816A)) / 9.303` |

Normalisation rationale:
- Monotonic: max of `16(1−e^{−0.4A})` as A→∞ is 16.0; dividing by 15.0 keeps the curve <1 at finite arousal and reaches ≈1 around A=7.
- Inverted-U: maximum is at A* = 280/(2×40) = 3.5 → value `−(40/49)×3.5² + (280/49)×3.5 = 10`; dividing by 9.303 maps the peak to just above 1 (acceptable; `FullAppraisal` clamps speed to `[MIN_STEP, MAX_STEP]`).

## Changes

### 1. `PartialAppraisal.java` — `normalizedBehaviouralEfficiency`

Swap the two branch bodies and remove the dead `arousal < MIN_AROUSAL_LEVEL` guard:

```java
// Mapa §4.1.4 / Diamond et al. (2006) Yerkes-Dodson curves:
//   Simple task  (0–1 objects): monotonic Eq 4.1 — 16(1−e^{−0.4A}) / 15
//   Complex task (≥2 objects):  inverted-U Eq 4.2 — (A·(280/49 − (40/49)·A)) / 9.303
private double normalizedBehaviouralEfficiency(double arousal, int perceptionsCount) {
    if (perceptionsCount < Constants.COMPLEX_TASK) {
        return (16 * (1 - Math.exp(-0.4 * arousal))) / 15.0;
    } else {
        return (arousal * (5.714 - (0.816 * arousal))) / 9.303;
    }
}
```

### 2. New test class — `PartialAppraisalTest.java`

Location: `src/test/java/br/cefetmg/lsi/l2l/creature/components/PartialAppraisalTest.java`

Tests to add (mirroring the acceptance criteria from the issue):
- **Simple task monotonically increasing**: sample several arousal values in `[MIN_AROUSAL_LEVEL, 7]` with `perceptionsCount=0` (and `=1`) and verify each is strictly greater than the previous.
- **Complex task inverted-U**: sample arousal values around the expected optimum A*=3.5 and verify `E(0.18) < E(3.5) > E(7)`.
- **Both curves in `[0, 1]`**: at `arousal = MIN_AROUSAL_LEVEL` and `arousal = MAX_AROUSAL_LEVEL` for both task types.

## Out of scope

- The `FullAppraisal` speed-clamping to `[MIN_STEP, MAX_STEP]` is already in place; no change needed.
- The validation experiment (speed vs arousal by task complexity) will be run after the fix per CLAUDE.md §step 5.

## Acceptance criteria

- [ ] Unit test: complex task has interior maximum; `E(0.18) < E(3.5) > E(7)`.
- [ ] Unit test: simple task is monotonically increasing in arousal.
- [ ] No dead branches.
- [ ] Both curves return values in `[0, 1]` across `[MIN_AROUSAL_LEVEL, MAX_AROUSAL_LEVEL]`.
- [ ] `mvn package` clean.
