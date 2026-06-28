#!/usr/bin/env python3
"""
DL2L experiment orchestrator.

Reads experiments/manifest.yml, submits Slurm jobs for each
experiment × trial, waits for completion, runs analysis scripts,
extracts metrics, and writes the comparison report.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import jsonschema
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
SCHEMA_PATH = REPO_ROOT / "experiments" / "schema.json"
JOB_SCRIPT = REPO_ROOT / "experiments" / "job.sh"
REPORTS_DIR = REPO_ROOT / "reports"
RUN_BASE = Path(os.environ.get("DL2L_RUN_BASE", "/srv/dl2l/runs"))

SACCT_TERMINAL = {"COMPLETED", "FAILED", "TIMEOUT", "CANCELLED", "NODE_FAIL", "OUT_OF_MEMORY"}
SACCT_POLL_INTERVAL = 60  # seconds


# ---------------------------------------------------------------------------
# Manifest loading + validation
# ---------------------------------------------------------------------------

def load_manifest(path: Path) -> dict:
    with open(path) as f:
        manifest = yaml.safe_load(f)
    with open(SCHEMA_PATH) as f:
        schema = json.load(f)
    jsonschema.validate(manifest, schema)
    return manifest


def resolve_experiment(exp: dict, defaults: dict) -> dict:
    """Fill in defaults for fields the experiment didn't override."""
    merged = {**defaults, **exp}
    return merged


# ---------------------------------------------------------------------------
# Slurm submission
# ---------------------------------------------------------------------------

def sbatch(exp: dict, trial: int, image: str, run_id: str) -> str:
    """Submit a single trial job; return the Slurm job id."""
    data_dir = RUN_BASE / run_id / exp["id"] / f"trial_{trial}"
    data_dir.mkdir(parents=True, exist_ok=True)

    cmd = [
        "sbatch",
        "--parsable",
        f"--job-name=dl2l-{exp['id']}-t{trial}",
        f"--partition={exp['partition']}",
        f"--time={exp['time']}",
        f"--cpus-per-task={exp['cpus_per_task']}",
        f"--mem={exp['mem']}",
        str(JOB_SCRIPT),
        image,
        exp["simulation"],
        str(data_dir),
        str(REPO_ROOT),
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    job_id = result.stdout.strip().split(";")[0]
    print(f"  submitted trial {trial} → job {job_id} (output: {data_dir})")
    return job_id


# ---------------------------------------------------------------------------
# Job monitoring
# ---------------------------------------------------------------------------

def sacct_state(job_ids: list[str]) -> dict[str, str]:
    """Return {job_id: state} for the given job ids."""
    cmd = [
        "sacct",
        "--jobs", ",".join(job_ids),
        "--format=JobID,State",
        "--parsable2",
        "--noheader",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, check=False)
    states: dict[str, str] = {}
    for line in result.stdout.splitlines():
        parts = line.split("|")
        if len(parts) >= 2:
            jid = parts[0].strip()
            state = parts[1].strip().split(" ")[0]  # strip reason suffix
            # Only track the top-level job (no .batch / .extern steps)
            if re.match(r"^\d+$", jid):
                states[jid] = state
    return states


def wait_for_jobs(job_map: dict[str, tuple[str, int]], timeout_factor: float = 1.2) -> None:
    """
    Block until all jobs in job_map reach a terminal state.
    job_map: {job_id: (exp_id, trial)}
    Raises RuntimeError if any job fails.
    """
    pending = set(job_map.keys())
    failed: list[str] = []

    print(f"\nWaiting for {len(pending)} Slurm jobs...")

    while pending:
        time.sleep(SACCT_POLL_INTERVAL)
        states = sacct_state(list(pending))
        done_this_round: list[str] = []
        for jid in list(pending):
            state = states.get(jid, "PENDING")
            if state in SACCT_TERMINAL:
                exp_id, trial = job_map[jid]
                status = "OK" if state == "COMPLETED" else "FAIL"
                print(f"  [{status}] job {jid} ({exp_id}/trial_{trial}): {state}")
                done_this_round.append(jid)
                if state != "COMPLETED":
                    failed.append(f"job {jid} ({exp_id}/trial_{trial}): {state}")
        for jid in done_this_round:
            pending.discard(jid)

    if failed:
        raise RuntimeError("The following Slurm jobs did not complete successfully:\n" +
                           "\n".join(f"  - {f}" for f in failed))


# ---------------------------------------------------------------------------
# Analysis
# ---------------------------------------------------------------------------

def run_analysis(exp: dict, run_id: str) -> None:
    """Run each configured analysis script for all trials of this experiment."""
    exp_base = RUN_BASE / run_id / exp["id"]
    for script_entry in exp["analysis"]:
        script = REPO_ROOT / script_entry["script"]
        if not script.exists():
            print(f"  [WARN] analysis script not found, skipping: {script}")
            continue
        print(f"  running {script.name} over {exp_base}...")
        env = {**os.environ, "wd": str(exp_base)}
        subprocess.run(
            [sys.executable, str(script)],
            env=env,
            cwd=str(REPO_ROOT),
            check=False,
        )


# ---------------------------------------------------------------------------
# Metric extraction
# ---------------------------------------------------------------------------

def extract_metrics(exp: dict, run_id: str) -> dict:
    """
    For each metric defined in the experiment, aggregate across all trials.
    Returns {metric_name: {mean, std, n}}.
    """
    import pandas as pd
    import numpy as np

    results: dict[str, dict] = {}
    exp_base = RUN_BASE / run_id / exp["id"]

    for metric in exp["metrics"]:
        values: list[float] = []
        for trial_dir in sorted(exp_base.glob("trial_*")):
            csv_path = trial_dir / "data" / metric["source"]
            if not csv_path.exists():
                print(f"  [WARN] metric source not found: {csv_path}")
                continue
            df = pd.read_csv(csv_path)
            if metric["column"] not in df.columns:
                print(f"  [WARN] column '{metric['column']}' not in {csv_path}")
                continue
            col = df[metric["column"]].dropna()
            if metric["aggregate"] == "mean":
                values.append(float(col.mean()))
            elif metric["aggregate"] == "median":
                values.append(float(col.median()))
            elif metric["aggregate"] == "std":
                values.append(float(col.std()))

        if values:
            results[metric["name"]] = {
                "mean": float(np.mean(values)),
                "std": float(np.std(values, ddof=1)) if len(values) > 1 else 0.0,
                "n": len(values),
                "higher_is_better": metric["higher_is_better"],
            }
        else:
            results[metric["name"]] = {"mean": None, "std": None, "n": 0,
                                        "higher_is_better": metric["higher_is_better"]}
    return results


# ---------------------------------------------------------------------------
# Report generation
# ---------------------------------------------------------------------------

def load_previous_metrics(version: str) -> dict | None:
    """Scan reports/ for the most recent successful run older than `version`."""
    if not REPORTS_DIR.exists():
        return None
    candidates = []
    for p in REPORTS_DIR.glob("*/metrics.json"):
        with open(p) as f:
            data = json.load(f)
        if data.get("version") != version and data.get("released_at"):
            candidates.append((data["released_at"], data))
    if not candidates:
        return None
    candidates.sort(key=lambda x: x[0])
    return candidates[-1][1]


def welch_t_pvalue(mean1, std1, n1, mean2, std2, n2) -> float | None:
    """Two-sided Welch's t-test p-value; None if inputs are invalid."""
    if any(v is None for v in [mean1, std1, n1, mean2, std2, n2]):
        return None
    if n1 < 2 or n2 < 2 or std1 == 0 and std2 == 0:
        return None
    import math
    se = math.sqrt(std1**2 / n1 + std2**2 / n2)
    if se == 0:
        return None
    t = (mean1 - mean2) / se
    df_num = (std1**2 / n1 + std2**2 / n2) ** 2
    df_den = (std1**2 / n1) ** 2 / (n1 - 1) + (std2**2 / n2) ** 2 / (n2 - 1)
    df = df_num / df_den if df_den > 0 else 1
    from scipy.stats import t as t_dist
    return float(2 * t_dist.sf(abs(t), df))


def render_report(version: str, run_id: str, image: str,
                  current: dict, previous: dict | None,
                  manifest: dict) -> str:
    """Render a markdown report from the experiment results."""
    released_at = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    lines = [
        f"# DL2L Experiment Report — {version}",
        "",
        f"| Field | Value |",
        f"|---|---|",
        f"| Version | `{version}` |",
        f"| Image | `{image}` |",
        f"| Released | {released_at} |",
        f"| Runner | `{os.uname().nodename}` |",
        "",
    ]

    prev_version = previous.get("version", "—") if previous else "—"
    lines += [
        f"## Comparison: {version} vs {prev_version}",
        "",
        "Significance: Welch's t-test, α = 0.05 (only reported when both versions "
        "have n ≥ 3 trials).",
        "",
    ]

    for exp in manifest["experiments"]:
        exp_id = exp["id"]
        lines += [f"### {exp_id}", "", f"*{exp['description']}*", ""]

        cur_exp = current.get(exp_id, {})
        prev_exp = (previous.get("experiments", {}).get(exp_id, {}) if previous else {})

        headers = ["Metric", f"{version} mean ± std", f"{prev_version} mean ± std",
                   "Delta", "Sig.", "Direction"]
        lines.append("| " + " | ".join(headers) + " |")
        lines.append("| " + " | ".join(["---"] * len(headers)) + " |")

        for metric in exp["metrics"]:
            name = metric["name"]
            cur = cur_exp.get(name, {})
            prev = prev_exp.get(name, {}) if prev_exp else {}

            c_mean, c_std, c_n = cur.get("mean"), cur.get("std"), cur.get("n", 0)
            p_mean, p_std, p_n = prev.get("mean"), prev.get("std"), prev.get("n", 0)

            cur_str = f"{c_mean:.4f} ± {c_std:.4f}" if c_mean is not None else "—"
            prev_str = f"{p_mean:.4f} ± {p_std:.4f}" if p_mean is not None else "—"

            delta_str = "—"
            sig_str = "—"
            direction = "—"

            if c_mean is not None and p_mean is not None:
                delta = c_mean - p_mean
                pct = (delta / abs(p_mean) * 100) if p_mean != 0 else 0
                delta_str = f"{delta:+.4f} ({pct:+.1f}%)"

                hib = metric.get("higher_is_better", True)
                if abs(pct) < 1.0:
                    direction = "≈ neutral"
                elif (delta > 0) == hib:
                    direction = "↑ improvement"
                else:
                    direction = "↓ regression"

                pval = welch_t_pvalue(c_mean, c_std or 0, c_n, p_mean, p_std or 0, p_n)
                if pval is not None:
                    sig_str = f"p={pval:.3f}" + (" ✓" if pval < 0.05 else "")
                else:
                    sig_str = "n/a"

            lines.append(f"| {name} | {cur_str} | {prev_str} | {delta_str} | {sig_str} | {direction} |")

        lines.append("")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="DL2L experiment orchestrator")
    parser.add_argument("--manifest", default="experiments/manifest.yml")
    parser.add_argument("--version", required=True, help="Image version tag (e.g. v2.1.0)")
    args = parser.parse_args()

    manifest_path = REPO_ROOT / args.manifest
    manifest = load_manifest(manifest_path)
    defaults = manifest["defaults"]

    # Strip leading 'v' for the tag if present; keep full tag for the image ref
    version = args.version
    image_tag = version if not version.startswith("v") else version
    image = f"ghcr.io/{os.environ.get('GITHUB_REPOSITORY', 'felipedreis/dl2l')}:{image_tag}"

    run_id = f"{version}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')}"
    print(f"\n=== DL2L experiments: {version} (run {run_id}) ===")
    print(f"Image: {image}")
    print(f"Run base: {RUN_BASE / run_id}")

    # Submit all trials
    job_map: dict[str, tuple[str, int]] = {}
    for exp_raw in manifest["experiments"]:
        exp = resolve_experiment(exp_raw, defaults)
        trials = exp["trials"]
        print(f"\n[{exp['id']}] submitting {trials} trial(s)...")
        for trial in range(1, trials + 1):
            jid = sbatch(exp, trial, image, run_id)
            job_map[jid] = (exp["id"], trial)

    # Wait
    wait_for_jobs(job_map)

    # Analyse + extract metrics
    current_metrics: dict[str, dict] = {}
    for exp_raw in manifest["experiments"]:
        exp = resolve_experiment(exp_raw, defaults)
        print(f"\n[{exp['id']}] running analysis...")
        run_analysis(exp, run_id)
        print(f"[{exp['id']}] extracting metrics...")
        current_metrics[exp["id"]] = extract_metrics(exp, run_id)

    # Build metrics.json
    metrics_doc = {
        "version": version,
        "image": image,
        "run_id": run_id,
        "released_at": datetime.now(timezone.utc).isoformat(),
        "experiments": current_metrics,
    }

    report_dir = REPORTS_DIR / version
    report_dir.mkdir(parents=True, exist_ok=True)
    metrics_path = report_dir / "metrics.json"
    with open(metrics_path, "w") as f:
        json.dump(metrics_doc, f, indent=2)
    print(f"\nMetrics written to {metrics_path}")

    # Compare with previous
    previous = load_previous_metrics(version)
    if previous:
        print(f"Comparing against previous version: {previous.get('version')}")
    else:
        print("No previous metrics found; report will show current values only.")

    # Render report
    report_md = render_report(version, run_id, image, current_metrics, previous, manifest)
    report_path = report_dir / "report.md"
    with open(report_path, "w") as f:
        f.write(report_md)
    print(f"Report written to {report_path}")

    print("\n=== All experiments complete ===")


if __name__ == "__main__":
    main()
