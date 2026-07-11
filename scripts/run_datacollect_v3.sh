#!/bin/bash
# Data collection for JEPA v2 retrain (v3 config).
# AFFORDANCE + RANDOM filter, reduced CACTUS (10), reposition=true, full subsystem stack.
# 5 creatures per trial, maxRuntimeMinutes=120.
#
# Usage:
#   ./scripts/run_datacollect_v3.sh [N_TRIALS] [START_TRIAL]   (defaults: 10, 1)
#
set -euo pipefail

TRIALS=${1:-10}
START=${2:-1}
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
DC="docker compose"
COMPOSE="docker-compose-datacollect-v3.yml"
COND="datacollect_v3"
DATA_DIR="$ROOT_DIR/ml/data_datacollect_v3"

echo "========================================================"
echo " Data collection: JEPA v2 retrain (v3)"
echo " Trials           : $TRIALS (starting at $START)"
echo " Condition        : $COND"
echo " Output           : $DATA_DIR"
echo "========================================================"

# ── Step 0: Build jar + Docker image ─────────────────────────────────────────
echo ""
echo ">>> Building fat jar <<<"
(cd "$ROOT_DIR" && mvn package -q -DskipTests)
echo "  Jar built: target/l2l-2.0.0-SNAPSHOT-wd.jar"

echo ">>> Building Docker image <<<"
(cd "$ROOT_DIR" && docker build -f docker/Dockerfile -t dl2l . -q)
echo "  Image built."

mkdir -p "$DATA_DIR"

# ── Step 1: Run all trials ───────────────────────────────────────────────────
for trial in $(seq "$START" $((START + TRIALS - 1))); do
    PROJ="exp_datacollect_v3_t${trial}"

    echo ""
    echo "────────────────────────────────────────────────────────"
    echo " Trial: $trial/$((START + TRIALS - 1))"
    echo "────────────────────────────────────────────────────────"

    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE" down -v --remove-orphans 2>/dev/null || true)

    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE" up -d)

    HOLDER_ID=$(cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE" ps -q dl2l-holder)
    echo "  Holder container: $HOLDER_ID"
    echo "  Waiting for simulation to finish ..."
    docker wait "$HOLDER_ID"
    echo "  Simulation done."

    echo "  Extracting data → $DATA_DIR/$COND/trial_$trial/ ..."
    python3 "$ROOT_DIR/scripts/exp_extract.py" \
        --experiment "datacollect_v3" \
        --condition  "$COND" \
        --trial      "$trial" \
        --out        "$DATA_DIR" \
        --container  "db"

    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE" down -v)
    echo "  Trial $trial/$((START + TRIALS - 1)) complete."
done

echo ""
echo "========================================================"
echo " ALL DONE"
echo " Data: $DATA_DIR"
echo " Next: cd ml && python3 -m scripts.prepare_dataset --data data_datacollect_v3 --out data_prepared_v3"
echo "========================================================"
