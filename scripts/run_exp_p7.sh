#!/bin/bash
# EXP-P7: Memory Filter vs. World Model — full experiment pipeline.
#
# Six-sample comparison of Mapa symbolic memory filter vs. JEPA neural world model,
# with and without sleep consolidation.
#
# Phases:
#   0 — Collect JEPA training data (P7-0: dist+afford+rand, N=TRAIN_TRIALS)
#   1 — Train all four JEPA variants (single, dual, internal_critic, internal_predictor)
#   2 — Export critic-aware model, rebuild jar + Docker image
#   3 — Run samples P7-1 through P7-5 (N=VAL_TRIALS each)
#   4 — Extract data from all samples
#   5 — Statistical analysis + report
#
# Usage:
#   ./scripts/run_exp_p7.sh [TRAIN_TRIALS [VAL_TRIALS]]
#   Default: TRAIN_TRIALS=10  VAL_TRIALS=calculated from power analysis
#
set -e

DC="docker compose"
TRAIN_TRIALS=${1:-10}
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
JAR="$ROOT_DIR/target/l2l-2.0.0-SNAPSHOT-wd.jar"
CONFIG_FILE="$ROOT_DIR/config/docker-config.conf"
NETWORK="docker_dl2l-network"

TRAIN_COMPOSE="docker-compose-exp-p7-0.yml"

RAW_P7_DIR="$ROOT_DIR/ml/data/raw_p7"
ML_DATA_P7="$ROOT_DIR/ml/data_p7"
CKPT_DIR="$ROOT_DIR/ml/checkpoints/exp_p7"

DATA_DIR="$ROOT_DIR/data/exp_p7"

SAMPLE_NAMES=(
  "p7_1_baseline"
  "p7_2_memory_only"
  "p7_3_memory_consolidation"
  "p7_4_jepa_only"
  "p7_5_jepa_consolidation"
)

SAMPLE_COMPOSES=(
  "docker-compose-exp-p7-1.yml"
  "docker-compose-exp-p7-2.yml"
  "docker-compose-exp-p7-3.yml"
  "docker-compose-exp-p7-4.yml"
  "docker-compose-exp-p7-5.yml"
)

echo "========================================================"
echo " EXP-P7: Memory Filter vs. World Model"
echo "========================================================"
echo " Training trials : $TRAIN_TRIALS"
echo " Training raw    : $RAW_P7_DIR"
echo " ML data dir     : $ML_DATA_P7"
echo " Checkpoint dir  : $CKPT_DIR"
echo " Data dir        : $DATA_DIR"
echo "========================================================"

# ── Phase 0: Collect JEPA training data ─────────────────────────────────────

echo ""
echo ">>> PHASE 0: Collecting JEPA training data ($TRAIN_TRIALS trials) <<<"
mkdir -p "$RAW_P7_DIR"

for i in $(seq 1 "$TRAIN_TRIALS"); do
    TRIAL_DIR="$RAW_P7_DIR/trial_$i"
    mkdir -p "$TRIAL_DIR"

    echo "--- Training trial $i / $TRAIN_TRIALS ---"
    (cd "$COMPOSE_DIR" && $DC -f "$TRAIN_COMPOSE" down -v --remove-orphans 2>/dev/null || true)
    (cd "$COMPOSE_DIR" && $DC -f "$TRAIN_COMPOSE" up -d)

    HOLDER_ID=$(cd "$COMPOSE_DIR" && $DC -f "$TRAIN_COMPOSE" ps -q dl2l-holder)
    echo "  Waiting for simulation to finish..."
    docker wait "$HOLDER_ID"

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

    echo "  Trial $i done ($(ls "$TRIAL_DIR" | wc -l | tr -d ' ') creature dirs)."
    (cd "$COMPOSE_DIR" && $DC -f "$TRAIN_COMPOSE" down -v)
done

# ── Phase 1: Train all four JEPA variants ────────────────────────────────────

echo ""
echo ">>> PHASE 1: Preparing dataset and training JEPA variants <<<"
mkdir -p "$ML_DATA_P7"

echo "  Preparing dataset (dual) ..."
(cd "$ROOT_DIR/ml" && python3 -m scripts.prepare_dataset \
    --wd "$RAW_P7_DIR" \
    --out "$ML_DATA_P7" \
    --dual)

# Four-variant ablation (see docs/plans/phase-2-species-model.md for design).
# Best performer selects as the live model for P7-4 / P7-5.
for LABEL in single dual internal_critic internal_predictor; do
    CKPT_V="$CKPT_DIR/$LABEL"
    mkdir -p "$CKPT_V"
    echo "  Training variant: $LABEL ..."
    (cd "$ROOT_DIR/ml" && python3 -m scripts.train_species \
        --data "$ML_DATA_P7" \
        --ckpt "$CKPT_V" \
        --variant "$LABEL" \
        --epochs 200) || echo "  WARNING: variant $LABEL training failed, continuing."
done

# ── Phase 2: Export best variant (internal_critic), rebuild ──────────────────

echo ""
echo ">>> PHASE 2: Exporting internal_critic model <<<"

CKPT_FINAL="$CKPT_DIR/internal_critic"

echo "  Checking for representation collapse ..."
(cd "$ROOT_DIR/ml" && python3 -m scripts.check_collapse \
    --ckpt "$CKPT_FINAL" \
    --variant internal_critic 2>/dev/null || echo "  (check_collapse returned non-zero — inspect output above)")

echo "  Exporting model to src/main/resources/models/ ..."
(cd "$ROOT_DIR/ml" && python3 -m scripts.export_model \
    --variant internal_critic \
    --ckpt "$CKPT_FINAL" \
    --out "$ROOT_DIR/src/main/resources/models")

echo "  Rebuilding jar ..."
(cd "$ROOT_DIR" && mvn package -q)

echo "  Rebuilding Docker image ..."
(cd "$ROOT_DIR" && docker build -f docker/Dockerfile -t dl2l . -q)

# ── Phase 3: Calculate sample size from P7-0 data ───────────────────────────

echo ""
echo ">>> PHASE 3: Calculating required sample size from P7-0 data <<<"

VAL_TRIALS=$(python3 "$ROOT_DIR/analysis/exp_p7_sample_size.py" --wd "$RAW_P7_DIR" 2>/dev/null || echo "10")
echo "  Required validation trials (power analysis): $VAL_TRIALS"

# Allow override from environment or second CLI argument
VAL_TRIALS=${2:-$VAL_TRIALS}
echo "  Using: $VAL_TRIALS trials per sample"

# ── Phase 4: Run validation samples P7-1 through P7-5 ───────────────────────

echo ""
echo ">>> PHASE 4: Running validation samples <<<"
mkdir -p "$DATA_DIR"

for IDX in "${!SAMPLE_NAMES[@]}"; do
    NAME="${SAMPLE_NAMES[$IDX]}"
    COMPOSE="${SAMPLE_COMPOSES[$IDX]}"
    SAMPLE_DIR="$DATA_DIR/$NAME"
    mkdir -p "$SAMPLE_DIR"

    echo ""
    echo "--- Sample $NAME ($VAL_TRIALS trials) ---"

    for i in $(seq 1 "$VAL_TRIALS"); do
        TRIAL_DIR="$SAMPLE_DIR/trial_$i"
        mkdir -p "$TRIAL_DIR"

        echo "  Trial $i / $VAL_TRIALS ..."
        (cd "$COMPOSE_DIR" && $DC -f "$COMPOSE" down -v --remove-orphans 2>/dev/null || true)
        (cd "$COMPOSE_DIR" && $DC -f "$COMPOSE" up -d)

        HOLDER_ID=$(cd "$COMPOSE_DIR" && $DC -f "$COMPOSE" ps -q dl2l-holder)
        docker wait "$HOLDER_ID"

        # Backup first — stream pg_dump to local trial dir before touching anything
        echo "  Backing up database..."
        docker exec db pg_dump -U postgres -d l2l -Fc > "$TRIAL_DIR/db_backup.dump"
        echo "  Backup saved ($(du -sh "$TRIAL_DIR/db_backup.dump" | cut -f1))"

        # Extract CSVs
        python3 "$ROOT_DIR/scripts/pg_extract.py" --container db --out "$TRIAL_DIR"

        (cd "$COMPOSE_DIR" && $DC -f "$COMPOSE" down -v)
        echo "  Trial $i done."
    done
done

# ── Phase 5: Analysis ────────────────────────────────────────────────────────

echo ""
echo ">>> PHASE 5: Analysis <<<"
python3 "$ROOT_DIR/analysis/exp_p7_memory_vs_wm.py" --wd "$DATA_DIR" --baseline-wd "$RAW_P7_DIR"

echo ""
echo "========================================================"
echo " EXP-P7 COMPLETE"
echo " Report : docs/reports/EXP_P7_MEMORY_FILTER_VS_WORLD_MODEL.md"
echo " Figures: docs/figures/exp_p7/"
echo "========================================================"
