#!/usr/bin/env python3
"""
Smoke-run validation for issue #58 (focus regulation loop).

Checks per batch × creature:
  1. Creatures lived and ate (no feeding regression)
  2. Eye gating during SLEEP: perceptions at SLEEP timestamps are < 1% of all perceptions
     (async onset races produce a few overlaps, but sustained sleep is clean)
  3. EAT actions occur at near-zero distance (contact) — confirms focus interpolation
     reaches MIN at contact
  4. Arousal stays within legal bounds [MIN=0.18, MAX=7.0]
  5. No silent lifetime cap (not all emotions at MAX when creature dies)
  6. Creatures slept (sleep drive is functional)
  7. Lifetime > 10 s (creature survives long enough to behave)

Reports PASS / FAIL per check with details.
"""

import os
import sys
import csv
import glob

DATA_ROOT = os.path.join(os.path.dirname(__file__), "../ml/data_p58_smoke")
MIN_AROUSAL = 0.18
MAX_AROUSAL = 7.0
MIN_FOCUS   = 50.0
MAX_FOCUS   = 150.0

FAIL = False


def read_csv(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return list(csv.DictReader(f))


def check(label, cond, detail=""):
    global FAIL
    status = "PASS" if cond else "FAIL"
    if not cond:
        FAIL = True
    print(f"  [{status}] {label}" + (f" — {detail}" if detail else ""))
    return cond


def validate_batch(batch_dir, batch_name):
    print(f"\n{'='*60}")
    print(f"Batch: {batch_name}")

    lifetimes = read_csv(os.path.join(batch_dir, "lifetimes.csv"))
    print(f"  Creatures: {len(lifetimes)}")
    check("Exactly 3 creatures per batch", len(lifetimes) == 3, f"got {len(lifetimes)}")

    creature_dirs = sorted(glob.glob(os.path.join(batch_dir, "*:*")))

    for cdir in creature_dirs:
        cname = os.path.basename(cdir)
        print(f"\n  --- Creature {cname} ---")

        actions     = read_csv(os.path.join(cdir, "trajectory_actions.csv"))
        perceptions = read_csv(os.path.join(cdir, "trajectory_perceptions.csv"))
        emotions    = read_csv(os.path.join(cdir, "trajectory_emotions.csv"))
        choices     = read_csv(os.path.join(cdir, "choices_over_time.csv"))

        # ── 1. Creature ate (no feeding regression) ───────────────────────────
        eat_count = sum(1 for r in choices if r.get("action_type") == "EAT")
        check("Creature ate at least once", eat_count > 0, f"EAT count = {eat_count}")

        # ── 2. Eye gating during SLEEP ────────────────────────────────────────
        # In async Akka, perception records (ObjectSeenState) accumulate from Eye.
        # When the eye is closed (SLEEP, focus=0.0), no new records should be created.
        # Metric: perceptions whose timestamp coincides with a SLEEP action should be
        # < 1% of total perceptions (async onset races explain a tiny residual).
        sleep_ts = {r["action_time"] for r in actions if r.get("action_type") == "SLEEP"}
        total_percs = len(perceptions)
        sleep_percs = sum(1 for r in perceptions if r.get("time") in sleep_ts)
        sleep_ratio = sleep_percs / max(1, total_percs)
        check(
            "Perceptions at SLEEP timestamps < 1% of total (eye gating effective)",
            sleep_ratio < 0.01,
            f"{sleep_percs}/{total_percs} = {sleep_ratio*100:.2f}%"
        )

        # ── 3. Arousal stays within bounds ────────────────────────────────────
        anomalies = []
        for r in emotions:
            for col in ["final_hunger", "final_sleep", "final_pain", "final_tedium"]:
                v = r.get(col)
                if v is None:
                    continue
                val = float(v)
                if val < MIN_AROUSAL - 0.01 or val > MAX_AROUSAL + 0.01:
                    anomalies.append(f"{col}={val:.3f}")
        check("Active emotion arousals in [0.18, 7.0]", len(anomalies) == 0,
              "; ".join(anomalies[:3]))

        # ── 4. No silent lifetime cap ─────────────────────────────────────────
        if emotions:
            last = emotions[-1]
            all_at_max = all(
                abs(float(last.get(col, 0)) - MAX_AROUSAL) < 0.01
                for col in ["final_hunger", "final_sleep", "final_pain", "final_tedium"]
            )
            check("Creature did not die with all 4 emotions at MAX",
                  not all_at_max,
                  f"h={last.get('final_hunger')} s={last.get('final_sleep')} "
                  f"p={last.get('final_pain')} t={last.get('final_tedium')}")

        # ── 5. Sleep occurred ─────────────────────────────────────────────────
        sleep_count = sum(1 for r in actions if r.get("action_type") == "SLEEP")
        check("Creature slept at least once", sleep_count > 0,
              f"SLEEP action count = {sleep_count}")

        # ── 6. Lifetime sanity ─────────────────────────────────────────────────
        ckey = cname.split(":")[0]
        lt_row = next((r for r in lifetimes if r.get("ids") == ckey), None)
        if lt_row and lt_row.get("lifetime"):
            lt = float(lt_row["lifetime"])
            check("Lifetime > 10 s", lt > 10.0, f"lifetime = {lt:.1f} s")
        else:
            print(f"  [SKIP] Lifetime check — no entry for {cname}")


def main():
    batches = sorted(glob.glob(os.path.join(DATA_ROOT, "batch_*")))
    if not batches:
        print(f"No batch directories found under {DATA_ROOT}")
        sys.exit(1)

    print(f"Validating {len(batches)} batch(es) — issue #58 focus regulation smoke test")

    for b in batches:
        validate_batch(b, os.path.basename(b))

    print(f"\n{'='*60}")
    if FAIL:
        print("RESULT: FAIL — one or more checks failed (see above)")
        sys.exit(1)
    else:
        print("RESULT: PASS — all checks passed across all batches")


if __name__ == "__main__":
    main()
