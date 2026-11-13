# Issue #112 — EngramKeyEncoder: fixed hand-built key

Status: **plan, 2026-11-11.** Paper §4.3. Depends on #111.

`k = [ z(p)/sqrt(n_p) ; lambda * z(h)/sqrt(n_h) ]`.

## Discrepancy resolved
- The issue says **lambda = 0**; the paper (§4.3: "We use lambda = 0.5, the best value in the simulations") and the recipe
  (§3: A3 "fixed hand-built key, lambda = 0.5") say **0.5**. lambda is a constructor parameter / config key
  (`episodicKeyLambda`); A3 and A5 use **0.5**. lambda = 0 stays available (a test checks that it makes keys ignore h).
- Checked on the rendered PDF (p. 9): the h block is `lambda/sqrt(n_h) * z(h)` (lambda, not its square root), as the issue writes.
- The paper says the standardisation statistics "ship in `model_contract.json`"; that file has no population statistics (only feature
  order, min/max arousal). The statistics are **constants in the encoder**, fixed once, documented below.

## Features (n_p = 11, n_h = 4)
p: one-hot object type over [RED_APPLE, GREEN_APPLE, GRAY_APPLE, ROTTEN_APPLE, CACTUS, ALOE, SELF] (7), distance / vision radius
(clipped to [0, 1]; radius `Constants.DEFAULT_VISION_FIELD_RADIUS` = 150), sin and cos of the bearing (`Perception.angle`, radians),
contact flag (1 when the object is not SELF and distance <= `FullAppraisal.contactDistance(type)`). h: the four drive levels in
`FocusTracker.DRIVES` order.

## Fixed population statistics (assumptions, not measured)
One-hot: mean 1/7, sd sqrt(1/7 * 6/7). Distance ratio: uniform on [0, 1], mean 0.5, sd 0.289. sin, cos: mean 0, sd 1/sqrt(2).
Contact: mean 0.1, sd 0.3. Drive level: uniform on [0, 7] (`MAX_AROUSAL_LEVEL`), mean 3.5, sd 2.02. With lambda = 0.5 the largest
single-feature gap contributes 0.9 (h block) and 1.0 (percept block) to a distance: the two blocks are on the same scale.
A key never changes with experience, so stored keys stay valid for the whole life. Unit tests pin each statistic's effect.

## Tests
Standardisation (mean maps to 0, one sd to 1), block normalisation (identical feature differences give a distance that scales
with 1/sqrt(n)), lambda scaling and lambda = 0, bearing sin/cos, contact flag, SELF has no contact, deterministic.
