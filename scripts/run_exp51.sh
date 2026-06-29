#!/bin/bash
# EXP-51 full experiment runner.
#
# Phase 1: Collect new training data (plants now collidable after PR #50 fix).
# Phase 2: Prepare dataset, train DualSpeciesModel with internal-aware Critic.
# Phase 3: Export model, rebuild jar, run validation experiment.
# Phase 4: Extract validation data, run analysis.
#
# Usage: ./scripts/run_exp51.sh [N_TRAIN_TRIALS]  (default 3)
set -e

TRIALS=${1:-3}
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
JAR="$ROOT_DIR/target/l2l-2.0.0-SNAPSHOT-wd.jar"
CONFIG_FILE="$ROOT_DIR/config/docker-config.conf"
NETWORK="docker_dl2l-network"

RAW_P8_DIR="$ROOT_DIR/ml/data/raw_p8"
ML_DATA_P8="$ROOT_DIR/ml/data_p8"
CKPT_DIR="$ROOT_DIR/ml/checkpoints/exp_b2"
VAL_DIR="$ROOT_DIR/data/exp_51"

echo "======================================================"
echo " EXP-51: Internal-Aware Critic — full pipeline"
echo "======================================================"
echo " Trials           : $TRIALS"
echo " Training raw data: $RAW_P8_DIR"
echo " ML data dir      : $ML_DATA_P8"
echo " Checkpoint dir   : $CKPT_DIR"
echo " Validation data  : $VAL_DIR"
echo "======================================================"

# ── Phase 1: Collect training data ──────────────────────────────────────────

echo ""
echo ">>> PHASE 1: Collecting training data ($TRIALS trials) <<<"
mkdir -p "$RAW_P8_DIR"

for i in $(seq 1 "$TRIALS"); do
    TRIAL_DIR="$RAW_P8_DIR/trial_$i"
    mkdir -p "$TRIAL_DIR"

    echo ""
    echo "--- Training trial $i / $TRIALS ---"
    (cd "$COMPOSE_DIR" && docker-compose -f docker-compose-train-p7.yml down -v --remove-orphans 2>/dev/null || true)

    echo "  Starting training simulation..."
    (cd "$COMPOSE_DIR" && docker-compose -f docker-compose-train-p7.yml up -d)

    HOLDER_ID=$(cd "$COMPOSE_DIR" && docker-compose -f docker-compose-train-p7.yml ps -q dl2l-holder)
    echo "  Holder: $HOLDER_ID — waiting for simulation to finish..."
    docker wait "$HOLDER_ID"
    echo "  Simulation finished."

    echo "  Extracting to $TRIAL_DIR ..."
    docker run --rm \
        --network "$NETWORK" \
        --entrypoint java \
        -v "$JAR":/dl2l/run/dl2l.jar \
        -v "$CONFIG_FILE":/config/docker-config.conf \
        -v "$TRIAL_DIR":/output \
        dl2l \
        -Dconfig.file=/config/docker-config.conf \
        -jar dl2l.jar \
        --host localhost --port 2551 --roles holder --extractor --save /output

    echo "  Trial $i complete ($(ls "$TRIAL_DIR" | wc -l) creature dirs)."
    (cd "$COMPOSE_DIR" && docker-compose -f docker-compose-train-p7.yml down -v)
done

echo ""
echo ">>> PHASE 2: Prepare dataset & train <<<"

mkdir -p "$ML_DATA_P8"
echo "  Preparing dataset from $RAW_P8_DIR ..."
(cd "$ROOT_DIR/ml" && python3 -m scripts.prepare_dataset \
    --wd "$RAW_P8_DIR" \
    --out "$ML_DATA_P8" \
    --dual)

echo "  Training DualSpeciesModel with internal-aware Critic ..."
(cd "$ROOT_DIR/ml" && python3 -m scripts.train_species \
    --data "$ML_DATA_P8" \
    --ckpt "$CKPT_DIR" \
    --dual \
    --epochs 100)

echo "  Checking for representation collapse ..."
(cd "$ROOT_DIR/ml" && python3 -m scripts.check_collapse \
    --ckpt "$CKPT_DIR" \
    --dual 2>/dev/null || echo "  (check_collapse returned non-zero — inspect output above)")

echo "  Exporting model to src/main/resources/models/ ..."
(cd "$ROOT_DIR/ml" && python3 -m scripts.export_model \
    --dual \
    --ckpt "$CKPT_DIR" \
    --out "$ROOT_DIR/src/main/resources/models")

echo "  Rebuilding jar with new model ..."
(cd "$ROOT_DIR" && mvn package -q)

echo "  Rebuilding Docker image with trained model ..."
(cd "$ROOT_DIR" && docker build -f docker/Dockerfile -t dl2l . -q)

echo ""
echo ">>> PHASE 3: Validation experiment <<<"
mkdir -p "$VAL_DIR"

(cd "$COMPOSE_DIR" && docker-compose -f docker-compose-exp-51-val.yml down -v --remove-orphans 2>/dev/null || true)
echo "  Starting validation simulation..."
(cd "$COMPOSE_DIR" && docker-compose -f docker-compose-exp-51-val.yml up -d)

HOLDER_ID=$(cd "$COMPOSE_DIR" && docker-compose -f docker-compose-exp-51-val.yml ps -q dl2l-holder)
echo "  Holder: $HOLDER_ID — waiting for validation to finish..."
docker wait "$HOLDER_ID"
echo "  Validation simulation finished."

echo "  Extracting validation data to $VAL_DIR ..."
docker run --rm \
    --network "$NETWORK" \
    --entrypoint java \
    -v "$JAR":/dl2l/run/dl2l.jar \
    -v "$CONFIG_FILE":/config/docker-config.conf \
    -v "$VAL_DIR":/output \
    dl2l \
    -Dconfig.file=/config/docker-config.conf \
    -jar dl2l.jar \
    --host localhost --port 2551 --roles holder --extractor --save /output

(cd "$COMPOSE_DIR" && docker-compose -f docker-compose-exp-51-val.yml down -v)
echo "  Validation data extracted."

echo ""
echo ">>> PHASE 4: Analysis <<<"
python3 "$ROOT_DIR/analysis/exp_51_internal_critic.py"

echo ""
echo "======================================================"
echo " EXP-51 COMPLETE"
echo " Report : docs/reports/EXP_51_INTERNAL_CRITIC.md"
echo " Figures: docs/figures/exp_51/"
echo "======================================================"
