# Sleep hysteresis switch

Status: **plan, 2026-11-12** (branch `feature/a3-episodic`).

## Why
`FullAppraisal.enforceHysteresisGate` replaces any non-SLEEP action by SLEEP until a sleep episode has lasted `MIN_SLEEP_TICKS` = 10 cycles (about 0.33 s), the anti-micro-nap
gate of the orexin boundary. It is unconditional: no config flag. In the pilot it overrides 4.1% of decisions (A3, A5; 6.15% in the earlier A2), and the earlier tendency experiment
found the lock made every arm worse when SLEEP was in the tendency lists. It is one of the hand-built priors that sit under the arbiter (with the CAMPOS tendency lists), so
any statement about what the arbiter's controllers add needs a way to run without it.

## Change
- `LearningSettings.sleepHysteresisEnabled`, default **true** (every existing configuration unchanged), config key `sleepHysteresisEnabled`, getter `isSleepHysteresisEnabled()`.
- `FullAppraisal`: the gate's condition becomes a pure package-private method `holdsSleep(enabled, episodeActive, dwellTicks)`; with the switch off the selected action is executed as chosen.
  Sleep episode bookkeeping (onset, dwell, wake, `sleep_episodes`, cholinergic clearing) is unchanged: only the override goes away.
- Deterministic tests: `holdsSleep` over its truth table and the boundary (dwell 9 holds, 10 does not), the setting's default and its parsing from a simulation config.

## Not changed
Default behaviour, the `MIN_SLEEP_TICKS` constant, the tendency lists. The switch is an experimental control, like `operantLearningEnabled`.
