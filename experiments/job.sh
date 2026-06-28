#!/bin/bash
# Slurm job script for a single DL2L experiment trial.
# All #SBATCH directives are passed on the command line by the orchestrator;
# only the output/error paths are fixed here.
#SBATCH --output=/srv/dl2l/runs/slurm-%j.out
#SBATCH --error=/srv/dl2l/runs/slurm-%j.err

set -euo pipefail

IMAGE="$1"        # e.g. ghcr.io/felipedreis/dl2l:v2.1.0
SIM_CONF="$2"     # e.g. simulations/baseline_1node_5creature.conf
DATA_DIR="$3"     # e.g. /srv/dl2l/runs/<run_id>/<exp_id>/trial_2
REPO_ROOT="$4"    # path to the checked-out repo on the Pi

mkdir -p "${DATA_DIR}/data" "${DATA_DIR}/backup"

PROJECT="dl2l-${SLURM_JOB_ID}"

export IMAGE SIM_CONF DATA_DIR REPO_ROOT
export CONFIG_FILE="${REPO_ROOT}/config/docker-config.conf"

docker pull "${IMAGE}"

docker compose \
    -f "${REPO_ROOT}/experiments/compose/run.yml" \
    -p "${PROJECT}" \
    up \
    --abort-on-container-exit \
    --exit-code-from dl2l-holder

docker compose \
    -f "${REPO_ROOT}/experiments/compose/run.yml" \
    -p "${PROJECT}" \
    --profile extractor \
    run --rm dl2l-extractor

docker compose \
    -f "${REPO_ROOT}/experiments/compose/run.yml" \
    -p "${PROJECT}" \
    down -v --remove-orphans
