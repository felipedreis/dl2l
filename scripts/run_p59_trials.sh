#!/usr/bin/env bash
# Run N sequential p59 trials, extracting data from each one.
# Each trial's raw extract goes to ml/data_p59/trial_N/.
# Usage: ./scripts/run_p59_trials.sh [N] [start_trial]   (default N=5, start=1)
set -euo pipefail

TRIALS=${1:-5}
START=${2:-1}
COMPOSE="docker/docker-compose-p59.yml"
OUT_BASE="ml/data_p59"

for i in $(seq "$START" "$TRIALS"); do
    TRIAL_DIR="${OUT_BASE}/trial_${i}"
    echo "=== Trial $i / $TRIALS → ${TRIAL_DIR} ==="

    echo "  Stopping any previous run and removing volumes …"
    docker compose -f "$COMPOSE" down -v --remove-orphans 2>/dev/null || true

    echo "  Starting containers …"
    docker compose -f "$COMPOSE" up -d

    echo "  Waiting for simulation to finish (MaxRuntimeExpired or Finish) …"
    until docker logs docker-dl2l-manager-1 2>&1 | grep -q "MaxRuntimeExpired\|Finish\|stopSimulation"; do
        sleep 15
    done
    echo "  Simulation finished."

    mkdir -p "$TRIAL_DIR"
    echo "  Extracting data to ${TRIAL_DIR} …"
    python3 scripts/pg_extract.py --out "$TRIAL_DIR" --container db

    echo "  Trial $i done."
    echo ""
done

echo "All trials complete. Data in ${OUT_BASE}/trial_N/."
