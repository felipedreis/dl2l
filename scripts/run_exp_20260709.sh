#!/bin/bash
# Experiment: 20260709_memory_vs_wm_v1
# Memory Filter vs. JEPA World Model — 5 conditions × 5 trials
#
# Conditions:
#   1_baseline             — TARGET_DISTANCE + AFFORDANCE + RANDOM only
#   2_memory_only          — adds MEMORY filter, no consolidation
#   3_memory_consolidation — adds MEMORY filter + sleep consolidation
#   4_jepa_only            — adds WORLD_MODEL filter, no consolidation
#   5_jepa_consolidation   — adds WORLD_MODEL filter + sleep consolidation
#
# Usage:
#   ./scripts/run_exp_20260709.sh [N_TRIALS]   (default: 5)
#
set -euo pipefail

TRIALS=${1:-5}
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
DC="docker compose"
EXP="20260709_memory_vs_wm_v1"
DATA_DIR="$ROOT_DIR/ml/data_${EXP}"

CONDITION_KEYS=(
  "1_baseline"
  "2_memory_only"
  "3_memory_consolidation"
  "4_jepa_only"
  "5_jepa_consolidation"
)

COMPOSE_FILES=(
  "docker-compose-20260709-memory-vs-wm-v1-1.yml"
  "docker-compose-20260709-memory-vs-wm-v1-2.yml"
  "docker-compose-20260709-memory-vs-wm-v1-3.yml"
  "docker-compose-20260709-memory-vs-wm-v1-4.yml"
  "docker-compose-20260709-memory-vs-wm-v1-5.yml"
)

echo "========================================================"
echo " EXP: $EXP"
echo " Trials per condition : $TRIALS"
echo " Conditions           : ${#CONDITION_KEYS[@]}"
echo " Total runs           : $((TRIALS * ${#CONDITION_KEYS[@]}))"
echo " Output               : $DATA_DIR"
echo "========================================================"

# ── Step 0: Build jar + Docker image ─────────────────────────────────────────
echo ""
echo ">>> Building fat jar (includes SimulationRenderer.js fix) <<<"
(cd "$ROOT_DIR" && mvn package -q -DskipTests)
echo "  Jar built: target/l2l-2.0.0-SNAPSHOT-wd.jar"

echo ">>> Building Docker image <<<"
(cd "$ROOT_DIR" && docker build -f docker/Dockerfile -t dl2l . -q)
echo "  Image built."

mkdir -p "$DATA_DIR"

# ── Step 1: Run all trials ───────────────────────────────────────────────────
for trial in $(seq 1 "$TRIALS"); do
  for idx in "${!CONDITION_KEYS[@]}"; do
    COND="${CONDITION_KEYS[$idx]}"
    COMPOSE="${COMPOSE_FILES[$idx]}"
    PROJ="exp20260709_${COND}_t${trial}"

    echo ""
    echo "────────────────────────────────────────────────────────"
    echo " Condition: $COND  |  Trial: $trial/$TRIALS"
    echo "────────────────────────────────────────────────────────"

    # Clean up any previous run with this project name
    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE" down -v --remove-orphans 2>/dev/null || true)

    # Start simulation
    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE" up -d)

    # Wait for the holder to exit (simulation finished or maxRuntimeMinutes hit)
    HOLDER_ID=$(cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE" ps -q dl2l-holder)
    echo "  Holder container: $HOLDER_ID"
    echo "  Waiting for simulation to finish ..."
    docker wait "$HOLDER_ID"
    echo "  Simulation done."

    # Extract data before tearing down DB
    # (DB container_name is hardcoded as 'db' in the compose file)
    echo "  Extracting data → $DATA_DIR/$COND/trial_$trial/ ..."
    python3 "$ROOT_DIR/scripts/exp_extract.py" \
        --experiment "$EXP" \
        --condition  "$COND" \
        --trial      "$trial" \
        --out        "$DATA_DIR" \
        --container  "db"

    # Tear down (volumes too so next trial starts with a clean DB)
    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE" down -v)
    echo "  Trial $trial/$TRIALS for $COND complete."
  done
done

echo ""
echo "========================================================"
echo " ALL DONE"
echo " Data: $DATA_DIR"
echo "========================================================"
