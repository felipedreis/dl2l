# Plan: GitHub Self-Hosted Runner on Raspberry Pi Slurm + Release-Driven Experiment Pipeline

## 1. Goal

When a new release of the container is published, automatically:

1. Run a pre-defined, YAML-configured set of experiments on the local
   4-node Raspberry Pi 4 Slurm cluster.
2. Collect simulation outputs after the Slurm jobs finish.
3. Run the existing Python analysis scripts to produce graphs and
   summary metrics.
4. Compare this release's metrics to the previous release and produce a
   markdown report flagging regressions / improvements.
5. Commit the report into `reports/` on a new branch and open a PR
   against `main`.

## 2. Decisions (confirmed with user)

| Topic | Decision |
|---|---|
| Slurm topology | 4× Raspberry Pi 4 (8 GB). Node 0 = controller + compute. Treat as single cluster, ARM64. |
| Trigger | GitHub Release `published` event. Versioning driven by a Maven plugin. |
| Report scope | Current version vs. previous successful version. |
| Publishing | Commit `reports/<version>/report.md` to `main` via PR. No wiki. |

## 3. SDLC / Versioning Strategy

Goal: developer creates a release in GitHub UI (or via `gh release create`)
and the rest happens automatically.

### 3.1 Maven semver

Adopt the **CI-friendly `${revision}` pattern** + the
`flatten-maven-plugin`:

- Change `pom.xml`:
  ```xml
  <version>${revision}</version>
  <properties>
    <revision>2.0.0-SNAPSHOT</revision>
  </properties>
  ```
- Add `flatten-maven-plugin` (`process-resources` phase) so the
  installed/deployed POM has the resolved version. This is the official
  Maven-recommended pattern.
- Builds use `-Drevision=<X.Y.Z>` to set the version per build.

This is lighter than `maven-release-plugin` (which makes its own commits and
tags) and plays cleanly with GitHub Releases as the trigger.

### 3.2 Release flow

1. Developer (or automation) creates a GitHub Release with tag `vX.Y.Z`
   (semver enforced by GitHub Release UI / `gh release create`).
2. `cd.yml` triggers on both `push: main` (as today) **and**
   `release: { types: [published] }`.
3. On a release event, `cd.yml`:
   - Strips the leading `v` from the tag → `X.Y.Z`.
   - Builds with `mvn -Drevision=X.Y.Z package`.
   - Uses `docker buildx` with `--platform linux/amd64,linux/arm64` so
     the image runs on the Pi. Sets up QEMU via
     `docker/setup-qemu-action@v3` + `docker/setup-buildx-action@v3`.
   - Pushes tags `:vX.Y.Z`, `:X.Y.Z`, `:latest`.
4. `experiments.yml` triggers on `release.published` and runs on the
   self-hosted runner. It waits for the image to appear in GHCR (poll
   `docker manifest inspect` with timeout) before launching jobs.

Why not `workflow_run`? Direct triggering on `release.published` is more
explicit, has the version available in `github.event.release.tag_name`,
and the GHCR poll handles the race.

## 4. Self-Hosted Runner Setup (on Pi node 0)

A standalone setup doc — `docs/runner-setup.md` — will spell out:

1. Create dedicated user `gh-runner` on node 0 (no sudo).
2. Install runner from
   `https://github.com/actions/runner/releases/latest` (linux-arm64).
3. Register with labels: `self-hosted, linux, ARM64, raspberry-pi, slurm`.
4. Install as a systemd service (`./svc.sh install gh-runner && ./svc.sh start`).
5. Pre-reqs the runner needs on the Pi:
   - `docker` + `docker-compose` (already used by the project locally).
   - `slurm-client` (`sbatch`, `squeue`, `scancel`, `sacct`) — already
     present per the user's setup.
   - `python3 >= 3.11`, `pip install pandas scikit-learn matplotlib pyyaml`
     for the analysis scripts and the orchestrator.
   - `gh` CLI for opening the report PR. Auth via the workflow's
     `GITHUB_TOKEN` (passed in env).
6. Mount a shared dir at `/srv/dl2l/runs/<job-id>/` (NFS across the 4
   Pis if any experiment uses `layout: distributed`; otherwise local fs
   is fine — see §5).

## 5. Experiment YAML Schema

File: `experiments/manifest.yml`. Schema is intentionally small.

```yaml
defaults:
  partition: rpi
  time: "06:00:00"
  trials: 3
  cpus_per_task: 4
  mem: 6G

experiments:
  - id: baseline_5creature
    description: "Baseline 1-node simulation, 5 creatures"
    simulation: simulations/baseline_1node_5creature.conf
    layout: single_node          # docker-compose on one Pi
    trials: 5
    analysis:
      - script: analysis/exp1.py
      - script: analysis/avgBehaviouralEfficiency.py
    metrics:
      - name: avg_behavioural_efficiency
        source: behavioural_efficiency.csv     # produced by the analysis script
        column: efficiency
        aggregate: mean
      - name: avg_accumulated_nutrients
        source: nutrients.csv
        column: total
        aggregate: mean

  - id: phase5_sleep_consolidation
    description: "Phase 5 sleep consolidation; one holder, 10 creatures"
    simulation: simulations/exp_p5_1_sleep_consolidation.conf
    layout: single_node
    trials: 3
    analysis:
      - script: analysis/exp_p5_1_analysis.py
    metrics:
      - name: p5_consolidation_score
        source: p5_summary.csv
        column: score
        aggregate: mean
```

`layout` is `single_node` for v1. The plan keeps `distributed` (4 Pis,
one Akka role per node via `srun --nodes=4 --ntasks=4`) as a follow-up,
explicitly **out of scope for the first iteration** to keep the surface
small.

`metrics` is the contract for the comparison report. Each named metric is
extracted post-analysis from a CSV; the per-trial values get aggregated
(mean / median / std) and stored in `reports/<version>/metrics.json`.

### 5.1 Schema validation

Add `experiments/schema.json` (JSON Schema). The orchestrator validates
the manifest on startup; an invalid manifest fails the workflow loudly.

## 6. SBATCH Job Template

File: `experiments/job.sh`. One sbatch invocation per trial.

```bash
#!/bin/bash
#SBATCH --job-name=dl2l-exp
#SBATCH --output=%j.out
#SBATCH --error=%j.err
# All other directives (--partition, --time, --cpus-per-task, --mem)
# are passed on the command line by the orchestrator.

set -euo pipefail

IMAGE="$1"          # e.g. ghcr.io/felipedreis/dl2l:v1.2.3
SIM_CONF="$2"       # path inside repo, e.g. simulations/baseline_1node_5creature.conf
RUN_DIR="$3"        # output dir, e.g. /srv/dl2l/runs/<sha>/<exp_id>/trial_2

mkdir -p "$RUN_DIR/data" "$RUN_DIR/backup"

# Pull (Docker on the Pi caches across runs).
docker pull "$IMAGE"

# Run the existing 4-role topology via docker-compose. The compose file
# references the image tag via $IMAGE env var.
export IMAGE
COMPOSE_PROJECT_NAME="dl2l-${SLURM_JOB_ID}"
docker compose -f experiments/compose/run.yml -p "$COMPOSE_PROJECT_NAME" up \
    --abort-on-container-exit \
    --exit-code-from dl2l-holder \
    || true

# Extract data
docker compose -p "$COMPOSE_PROJECT_NAME" run --rm \
    -v "$RUN_DIR":/output \
    dl2l-extractor

docker compose -p "$COMPOSE_PROJECT_NAME" down -v
```

`experiments/compose/run.yml` is a simplified version of the existing
`docker/docker-compose.yml` that:

- References `$IMAGE` instead of the local `dl2l` tag.
- Binds the simulation file via `$SIM_CONF`.
- Uses a unique compose project name so trials can run in parallel
  (Slurm decides parallelism via partition / `--ntasks` budget).

## 7. Orchestrator

File: `experiments/orchestrator.py` (Python 3).

Responsibilities:

1. Parse `manifest.yml`, validate against schema.
2. For each experiment × trial, write the sbatch invocation and capture
   the job id. Submit jobs with `sbatch --parsable`.
3. Wait for completion. Use `sacct -j <id> --format=State --parsable2`
   in a poll loop (60 s interval). Fail the workflow if any job ends in
   `FAILED` / `TIMEOUT` / `CANCELLED`.
4. After all trials complete, run each experiment's analysis scripts in
   a Python venv. Working directory convention: the existing analysis
   scripts expect `wd` to point at the data dir.
5. Extract `metrics.json` per experiment using the YAML `metrics`
   contract.
6. Build the comparison report (§8).
7. Commit and open the PR (§9).

Why Python over bash: schema validation, sacct parsing, metric
extraction (pandas), and report rendering (Jinja2) are clearer in
Python — and the rest of `analysis/` is already Python.

## 8. Report Generation

File: `reports/<version>/report.md`. Companion: `reports/<version>/metrics.json`.

Sections (rendered from a Jinja template):

1. **Header**: version, commit SHA, image digest, runner host, total
   wall-clock.
2. **Experiments table**: id, trials, status, mean/std for each metric.
3. **Comparison vs. previous**: for each metric, show
   `current ± std` vs. `previous ± std`, the delta, and a flag:
   - `↑ improvement` / `↓ regression` based on the metric's `direction`
     (added to YAML: `higher_is_better: true/false`).
   - Significance: Welch's t-test, two-sided, `α = 0.05`. Marked only
     when both versions had `trials >= 3`.
4. **Graphs**: embed PNGs produced by analysis scripts via relative
   paths.
5. **Raw artifacts**: link to the run directory tarball on the Pi
   (referenced but not committed).

"Previous version" lookup: scan `reports/*/metrics.json`, sort by
embedded `released_at` timestamp, take the most recent strictly older
entry. If none exists, the report just shows current values with a note.

## 9. PR Submission

The runner uses `gh` CLI to:

1. Create branch `experiment-report/v<version>`.
2. Commit `reports/v<version>/report.md`, `metrics.json`, and any PNGs.
3. Open a PR against `main` titled `experiments: v<version> report`
   with a short body summarizing regressions/improvements above a 5%
   delta threshold.
4. Add label `experiment-report`.

The PR is not auto-merged — the user reviews the regression flags
manually.

## 10. GitHub Actions Workflow

File: `.github/workflows/experiments.yml`.

```yaml
name: experiments

on:
  release:
    types: [published]
  workflow_dispatch:
    inputs:
      tag:
        description: "Image tag to test (e.g. v1.2.3 or latest)"
        required: true

concurrency:
  group: experiments
  cancel-in-progress: false

jobs:
  run:
    runs-on: [self-hosted, linux, ARM64, raspberry-pi, slurm]
    timeout-minutes: 720
    permissions:
      contents: write
      pull-requests: write
    env:
      VERSION: ${{ github.event.release.tag_name || github.event.inputs.tag }}
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Wait for image
        run: experiments/wait-for-image.sh "ghcr.io/${{ github.repository }}:${VERSION}"
      - name: Run experiments
        run: python3 experiments/orchestrator.py --manifest experiments/manifest.yml --version "$VERSION"
      - name: Open report PR
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: experiments/open-report-pr.sh "$VERSION"
```

`concurrency.group: experiments` ensures two releases queue instead of
racing for Slurm.

## 11. Changes to Existing Files

| File | Change |
|---|---|
| `pom.xml` | Switch to `${revision}` pattern; add `flatten-maven-plugin`. |
| `.github/workflows/cd.yml` | Add `release.published` trigger; setup QEMU + buildx; multi-arch build; pass `-Drevision=${TAG#v}`; tag image with `:vX.Y.Z`, `:X.Y.Z`, `:latest`. |
| `docker/Dockerfile` | No code change; ensure the base image supports arm64 (`eclipse-temurin:23-jdk` does). |

## 12. New Files

```
experiments/
├── manifest.yml
├── schema.json
├── orchestrator.py
├── job.sh
├── wait-for-image.sh
├── open-report-pr.sh
├── compose/
│   └── run.yml
└── templates/
    └── report.md.j2
.github/workflows/experiments.yml
docs/runner-setup.md
reports/.gitkeep
```

## 13. Out of Scope (for this iteration)

- `layout: distributed` (multi-node Akka via `srun`). Single-node only
  for v1; the YAML key reserves the namespace.
- Native lib handling (`-Djava.library.path=../natives`). The docker
  flow doesn't use it; if a future experiment needs it, package the
  natives in the image and add an `arm64/` subdir.
- Auto-merging the report PR.
- A dashboard view of historical metrics (`reports/index.html`). The
  per-version markdown is enough for v1; a roll-up can come later.
- `release-please` / conventional-commits automation. Manual `gh release create`
  is fine for v1; we can layer release-please on top later without changing
  any of the above.

## 14. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Pi runs out of memory under the full 4-role stack. | Manifest constrains JVM heap via env (`JAVA_OPTS=-Xmx512m` in `compose/run.yml`); experiment authors must size for ~6 GB total. Document in the manifest schema. |
| Slurm jobs hang. | Per-job `--time` from manifest defaults; orchestrator polls `sacct` and scancels stragglers past 1.2 × the configured time. |
| GHCR image not yet available when experiments fires. | `wait-for-image.sh` polls `docker manifest inspect` for up to 15 min before failing. |
| Comparison breaks for the first release ever. | Orchestrator handles "no previous" path: report says `(no baseline yet)`. |
| Two releases back-to-back. | `concurrency.group: experiments` serializes; second release queues. |
| Self-hosted runner gets compromised. | Runner registered as repository-scoped (not org-scoped). Workflow uses minimum permissions. Document in `runner-setup.md` to NEVER allow this runner to execute workflows from forks. |

## 15. Phased Implementation Order

1. **Phase A — versioning & multi-arch image.** Modify `pom.xml`, update
   `cd.yml` for buildx + arm64 + release trigger. Verify by cutting a
   throwaway pre-release tag and confirming the arm64 image runs on a Pi
   with `docker pull && docker compose up`.
2. **Phase B — runner setup.** Write `docs/runner-setup.md`, register
   the runner on Pi node 0.
3. **Phase C — experiment substrate.** Write `experiments/manifest.yml`
   (one experiment to start: `baseline_5creature` with 2 trials), the
   compose template, `job.sh`, and the orchestrator's submit/wait loop.
   Run end-to-end manually via `workflow_dispatch` on a test tag.
4. **Phase D — analysis + report.** Wire up the metric extraction,
   Jinja template, and `open-report-pr.sh`.
5. **Phase E — expand the manifest** with the other experiments
   (`phase5_sleep_consolidation`, etc.) once the pipeline is proven.

Each phase ends with `mvn package` green and a manual smoke test.

## 16. Acceptance Criteria

- Creating a GitHub Release `vX.Y.Z` triggers a multi-arch image
  publish, then an experiments run, and finally a PR titled
  `experiments: vX.Y.Z report` is opened against `main`.
- The PR contains a markdown report with at least one comparison row
  vs. the previous release.
- Failing or hung Slurm jobs cause the workflow to fail (no silent
  passes); the run dir tarball is preserved on the Pi for debugging.
- `docs/runner-setup.md` is sufficient for someone with shell access to
  the Pi to reproduce the runner install from scratch.
