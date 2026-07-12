#!/bin/bash
# Rerun conditions 1-3 with 5 creatures (matching conditions 4-5).
# Output goes to ml/data_20260709_memory_vs_wm_v2/ so the old 3-creature
# data in ml/data_20260709_memory_vs_wm_v1/ is preserved for reference.
#
# Usage:
#   ./scripts/run_exp_20260709_v2.sh [N_TRIALS]   (default: 5)
#
set -euo pipefail

TRIALS=${1:-5}
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
DC="docker compose"
EXP="20260709_memory_vs_wm_v1"
DATA_DIR="$ROOT_DIR/ml/data_20260709_memory_vs_wm_v2"

CONDITION_KEYS=(
  "1_baseline"
  "2_memory_only"
  "3_memory_consolidation"
)

COMPOSE_FILES=(
  "docker-compose-20260709-memory-vs-wm-v1-1.yml"
  "docker-compose-20260709-memory-vs-wm-v1-2.yml"
  "docker-compose-20260709-memory-vs-wm-v1-3.yml"
)

echo "========================================================"
echo " EXP rerun (v2, 5 creatures): $EXP"
echo " Trials per condition : $TRIALS"
echo " Conditions           : ${#CONDITION_KEYS[@]}"
echo " Total runs           : $((TRIALS * ${#CONDITION_KEYS[@]}))"
echo " Output               : $DATA_DIR"
echo "========================================================"

echo ""
echo ">>> Building fat jar <<<"
(cd "$ROOT_DIR" && mvn package -q -DskipTests)
echo "  Jar built: target/l2l-2.0.0-SNAPSHOT-wd.jar"

echo ">>> Building Docker image <<<"
(cd "$ROOT_DIR" && docker build -f docker/Dockerfile -t dl2l . -q)
echo "  Image built."

mkdir -p "$DATA_DIR"

for trial in $(seq 1 "$TRIALS"); do
  for idx in "${!CONDITION_KEYS[@]}"; do
    COND="${CONDITION_KEYS[$idx]}"
    COMPOSE="${COMPOSE_FILES[$idx]}"
    PROJ="exp20260709v2_${COND}_t${trial}"

    echo ""
    echo "────────────────────────────────────────────────────────"
    echo " Condition: $COND  |  Trial: $trial/$TRIALS"
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
        --experiment "$EXP" \
        --condition  "$COND" \
        --trial      "$trial" \
        --out        "$DATA_DIR" \
        --container  "db"

    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE" down -v)
    echo "  Trial $trial/$TRIALS for $COND complete."
  done
done

echo ""
echo "========================================================"
echo " ALL DONE"
echo " Data: $DATA_DIR"
echo "========================================================"
