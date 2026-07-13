#!/bin/bash
# Run the two missing rotten_fruit_v1 conditions:
#   2_memory_only         — MEMORY filter, no consolidation
#   4_jepa_rpe_only       — WORLD_MODEL + JEPA RPE, no consolidation
#
# Usage: bash scripts/run_rotten_fruit_v1_new_conds.sh [N_TRIALS]
#
set -euo pipefail

TRIALS=${1:-5}
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
DC="docker compose"
EXP="rotten_fruit_v1"
DATA_DIR="$ROOT_DIR/ml/data_${EXP}"

CONDITION_KEYS=(
  "2_memory_only"
  "4_jepa_rpe_only"
)

COMPOSE_FILES=(
  "docker-compose-rotten-fruit-v1-2.yml"
  "docker-compose-rotten-fruit-v1-4.yml"
)

echo "========================================================"
echo " EXP: $EXP  (new conditions only)"
echo " Trials per condition : $TRIALS"
echo " Conditions           : ${#CONDITION_KEYS[@]}"
echo " Total runs           : $((TRIALS * ${#CONDITION_KEYS[@]}))"
echo " Output               : $DATA_DIR"
echo "========================================================"

mkdir -p "$DATA_DIR"

for trial in $(seq 1 "$TRIALS"); do
  for idx in "${!CONDITION_KEYS[@]}"; do
    COND="${CONDITION_KEYS[$idx]}"
    COMPOSE="${COMPOSE_FILES[$idx]}"
    PROJ="exp_rotten_v1_${COND}_t${trial}"

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
