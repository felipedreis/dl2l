#!/usr/bin/env python3
"""
Regression trip-wire for the ci_regression_benchmark experiment
(experiments/ci_regression_benchmark.yml), run once per push to main by
.github/workflows/regression-benchmark.yml.

Computes three concrete metrics from the freshly-extracted Parquet data -
cognitive-cycle rate, perception flip rate, mean lifespan - the same
properties SimulationCycleRateIntegrationTest and PerceptionFlickerIntegrationTest
assert live in Java (issue #85), re-derived here from extracted data instead of
live counters. Compares them against a rolling history (one JSON object per
line) and exits non-zero if any metric drifts outside its tolerance band.

Not a scientific analysis (no figures, no Purpose/Hypothesis/Results report) -
deliberately not under analysis/, which is for that. Reuses
analysis.dl2l_analysis's config/loading helpers rather than reimplementing
Parquet-globbing.

Usage:
    python3 scripts/ci_regression_benchmark.py \
        --experiment ci_regression_benchmark \
        --history /path/to/benchmark-data/metrics.jsonl \
        --commit "$GITHUB_SHA"

Always appends a new record to --history before deciding pass/fail, so a
failing run still contributes a real data point - the workflow commits
--history back to the benchmark-data branch as an `if: always()` step
regardless of this script's exit code.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

from analysis.dl2l_analysis.config import ExperimentAnalysis, from_spec  # noqa: E402
from analysis.dl2l_analysis.loading import load_all  # noqa: E402

# Minimum prior history entries before a band is enforced - a fresh
# benchmark-data branch's first few runs just seed history; there's nothing to
# regress against yet.
MIN_HISTORY_FOR_COMPARISON = 3

# How many of the most recent history entries the baseline (median) is computed
# over. Deliberately not "all of history" - an old, superseded baseline (e.g.
# from before a deliberate, intentional behavior change) shouldn't keep
# flagging every run after it forever.
BASELINE_WINDOW = 10

# Starting tolerance bands (relative to the rolling median baseline). First
# guesses, not derived from real variance data that doesn't exist yet -
# expect to retune these once a few weeks of real history accumulates.
# cycle_rate_hz is pinned to TARGET_CYCLE_HZ by construction since issue #85's
# fix, so it should be the tightest; perception_flip_rate_per_s is the
# noisiest (depends on movement/sleep stochastics).
TOLERANCES = {
    "cycle_rate_hz": 0.20,
    "perception_flip_rate_per_s": 0.35,
    "mean_lifespan_s": 0.25,
}


def compute_metrics(cfg: ExperimentAnalysis) -> dict:
    """Computes the three benchmark metrics from this run's extracted Parquet.

    Per-creature values are computed first, then averaged across every
    creature in every trial - a single noisy creature/trial doesn't dominate
    the run's headline number the way a straight row-count ratio would.
    """
    behavioural = load_all(cfg, "behavioural_efficiency.parquet")
    creatures = load_all(cfg, "creatures.parquet")

    if behavioural.empty or creatures.empty:
        raise RuntimeError(
            "no behavioural_efficiency.parquet / creatures.parquet rows found under "
            f"{cfg.data_dir} - did the ansible run actually produce data?"
        )

    creatures = creatures.copy()
    creatures["lifetime_s"] = creatures["lifetime_s"].astype(float)

    key_cols = ["creature_key", "condition", "trial"]
    cycles_per_creature = (
        behavioural.groupby(key_cols).size().reset_index(name="n_cycles")
    )
    merged = cycles_per_creature.merge(creatures, on=key_cols, how="inner")

    # cycle_rate_hz needs a real, positive lifetime; a creature still alive at
    # trial end has lifetime_s = NaN (dl2l_data.extract's existing semantics -
    # see tables.py's `CASE WHEN deadtime > 0 ...`) and is excluded here, same
    # as mean_lifespan_s below.
    died = merged[merged["lifetime_s"].notna() & (merged["lifetime_s"] > 0)]
    if died.empty:
        raise RuntimeError(
            "no creature died in any trial (all hit maxRuntimeMinutes?) - cycle_rate_hz "
            "and mean_lifespan_s are both undefined for this run. Check "
            "simulations/ci_regression_benchmark.conf's maxRuntimeMinutes/world size."
        )
    cycle_rate_per_creature = died["n_cycles"] / died["lifetime_s"]
    cycle_rate_hz = float(cycle_rate_per_creature.mean())

    flip_rates = []
    for _, group in behavioural.groupby(key_cols):
        # Column is n_perceived in the extracted Parquet (scripts/dl2l_data/tables.py's
        # `bes.perceivedobjects AS n_perceived`) - the Java field name and the extracted
        # column name deliberately differ, same as every other *_state -> table.py mapping.
        perceived = group["n_perceived"].astype(int).to_numpy()
        if len(perceived) < 2:
            continue
        had_something = perceived > 0
        flips = (had_something[1:] != had_something[:-1]).sum()
        flip_fraction = flips / (len(perceived) - 1)
        # Convert to flips/second using this same creature's own cycle rate -
        # see PerceptionFlickerIntegrationTest's javadoc for why the fraction
        # alone is the wrong metric once the cycle rate itself can differ
        # between runs (it undercounts a rate regression by exactly the
        # factor the rate changed).
        creature_rows = merged[
            (merged["creature_key"] == group["creature_key"].iloc[0])
            & (merged["condition"] == group["condition"].iloc[0])
            & (merged["trial"] == group["trial"].iloc[0])
        ]
        if creature_rows.empty or not (creature_rows["lifetime_s"] > 0).all():
            continue
        creature_hz = float(creature_rows["n_cycles"].iloc[0] / creature_rows["lifetime_s"].iloc[0])
        flip_rates.append(flip_fraction * creature_hz)

    if not flip_rates:
        raise RuntimeError("no creature had >= 2 cognitive cycles - can't compute a flip rate")
    perception_flip_rate_per_s = float(statistics.mean(flip_rates))

    mean_lifespan_s = float(died["lifetime_s"].mean())

    return {
        "cycle_rate_hz": cycle_rate_hz,
        "perception_flip_rate_per_s": perception_flip_rate_per_s,
        "mean_lifespan_s": mean_lifespan_s,
    }


def load_history(history_path: Path) -> list[dict]:
    if not history_path.exists():
        return []
    records = []
    with history_path.open() as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def append_history(history_path: Path, record: dict) -> None:
    history_path.parent.mkdir(parents=True, exist_ok=True)
    with history_path.open("a") as f:
        f.write(json.dumps(record) + "\n")


def check_regressions(metrics: dict, history: list[dict]) -> list[str]:
    """Returns a list of human-readable breach descriptions - empty if none,
    or if there isn't yet enough history to compare against."""
    if len(history) < MIN_HISTORY_FOR_COMPARISON:
        return []

    recent = history[-BASELINE_WINDOW:]
    breaches = []
    for metric_name, tolerance in TOLERANCES.items():
        baseline_values = [r["metrics"][metric_name] for r in recent if metric_name in r.get("metrics", {})]
        if not baseline_values:
            continue
        baseline = statistics.median(baseline_values)
        if baseline == 0:
            continue
        current = metrics[metric_name]
        relative_delta = (current - baseline) / baseline
        if abs(relative_delta) > tolerance:
            breaches.append(
                f"{metric_name}: {current:.4g} vs baseline {baseline:.4g} "
                f"(median of last {len(baseline_values)}) - {relative_delta:+.1%}, "
                f"tolerance is ±{tolerance:.0%}"
            )
    return breaches


def write_summary(summary_path: str | None, metrics: dict, history: list[dict], breaches: list[str]) -> None:
    lines = ["## CI regression benchmark", ""]
    lines.append(f"History entries (including this run): {len(history)}")
    lines.append("")
    lines.append("| metric | this run |")
    lines.append("|---|---|")
    for name, value in metrics.items():
        lines.append(f"| `{name}` | {value:.4g} |")
    lines.append("")
    if len(history) - 1 < MIN_HISTORY_FOR_COMPARISON:
        lines.append(
            f"_Not enough history yet to compare (need {MIN_HISTORY_FOR_COMPARISON}, "
            f"have {len(history) - 1} prior entries) - this run just seeds the baseline._"
        )
    elif breaches:
        lines.append("### :x: Regression detected")
        for b in breaches:
            lines.append(f"- {b}")
    else:
        lines.append(":white_check_mark: All metrics within tolerance.")

    text = "\n".join(lines) + "\n"
    print(text, file=sys.stderr)
    if summary_path:
        with open(summary_path, "a") as f:
            f.write(text)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--experiment", default="ci_regression_benchmark",
                    help="Experiment name, matches experiments/<name>.yml")
    p.add_argument("--history", required=True,
                    help="Path to the JSON-lines history file (on the benchmark-data "
                         "branch checkout)")
    p.add_argument("--commit", default=None,
                    help="Commit SHA to record with this run (defaults to 'unknown')")
    p.add_argument("--summary-file", default=None,
                    help="Append a human-readable summary here (e.g. $GITHUB_STEP_SUMMARY); "
                         "omit to skip")
    args = p.parse_args()

    cfg = from_spec(args.experiment)
    metrics = compute_metrics(cfg)

    history_path = Path(args.history)
    history = load_history(history_path)

    record = {
        "commit": args.commit or "unknown",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "metrics": metrics,
    }
    # Appended BEFORE the pass/fail decision below, deliberately - a failing run still
    # becomes a real history point (see this module's docstring and the workflow's
    # `if: always()` commit-back step).
    append_history(history_path, record)

    breaches = check_regressions(metrics, history)
    write_summary(args.summary_file, metrics, history + [record], breaches)

    if breaches:
        print("REGRESSION DETECTED:", file=sys.stderr)
        for b in breaches:
            print(f"  - {b}", file=sys.stderr)
        return 1

    print("OK - no regression detected.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
