#!/bin/bash
# EXP-P7 on Raspberry Pi cluster — 4 parallel trials per batch.
#
# Each node runs a complete isolated simulation (all 4 containers on one machine).
# Trials are dispatched in batches of 4 (one per node); each batch runs in parallel.
# After each batch, data is rsynced back to this machine for analysis.
#
# Usage:
#   ./scripts/run_exp_p7_pi.sh [TRAIN_TRIALS [VAL_TRIALS]]
#   Default: TRAIN_TRIALS=10  VAL_TRIALS=calculated from power analysis
set -e

NODES=(192.168.1.200 192.168.1.201 192.168.1.202 192.168.1.203)
N_NODES=${#NODES[@]}

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker/docker-compose-pi.yml"
JAR="$ROOT_DIR/target/l2l-2.0.0-SNAPSHOT-wd.jar"

IMAGE="127.0.0.1:5000/dl2l:latest"
REMOTE_CONFIG="/home/felipeduarte/dl2l-config"
REMOTE_COMPOSE="/home/felipeduarte/docker-compose-pi.yml"

TRAIN_TRIALS=${1:-10}

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
SAMPLE_SIMS=(
  "simulations/exp_p7_1_baseline.conf"
  "simulations/exp_p7_2_memory_only.conf"
  "simulations/exp_p7_3_memory_consolidation.conf"
  "simulations/exp_p7_4_jepa_only.conf"
  "simulations/exp_p7_5_jepa_consolidation.conf"
)

echo "========================================================"
echo " EXP-P7 on Pi cluster (${N_NODES} nodes)"
echo " Training trials : $TRAIN_TRIALS"
echo " Nodes           : ${NODES[*]}"
echo "========================================================"

# ── Deploy compose file to all nodes ────────────────────────────────────────
echo "Deploying compose file to nodes..."
for NODE in "${NODES[@]}"; do
    scp -q "$COMPOSE_FILE" "$NODE:~/docker-compose-pi.yml" &
done
wait

# ── Helper: run one trial on one node ───────────────────────────────────────
# run_trial NODE SIMULATION LOCAL_OUTPUT_DIR
run_trial() {
    local NODE=$1
    local SIM=$2
    local LOCAL_OUT=$3
    local PROJECT="dl2l"

    echo "  [$NODE] Starting: $SIM"

    # Tear down any leftover state
    ssh "$NODE" "cd ~ && DL2L_IMAGE=$IMAGE SIMULATION=$SIM CONFIG_DIR=$REMOTE_CONFIG \
        sudo env DL2L_IMAGE=$IMAGE SIMULATION=$SIM CONFIG_DIR=$REMOTE_CONFIG \
            docker compose -p $PROJECT -f $REMOTE_COMPOSE down -v --remove-orphans 2>/dev/null || true"

    # Start the simulation
    ssh "$NODE" "cd ~ && DL2L_IMAGE=$IMAGE SIMULATION=$SIM CONFIG_DIR=$REMOTE_CONFIG \
        sudo env DL2L_IMAGE=$IMAGE SIMULATION=$SIM CONFIG_DIR=$REMOTE_CONFIG \
            docker compose -p $PROJECT -f $REMOTE_COMPOSE up -d"

    # Wait for the holder container to exit
    local HOLDER_ID
    HOLDER_ID=$(ssh "$NODE" "sudo docker ps -aq --filter name=${PROJECT}-dl2l-holder-1")
    if [ -z "$HOLDER_ID" ]; then
        HOLDER_ID=$(ssh "$NODE" "sudo docker ps -aq --filter name=${PROJECT}_dl2l-holder_1")
    fi
    echo "  [$NODE] Waiting for holder ($HOLDER_ID) to finish..."
    ssh "$NODE" "sudo docker wait $HOLDER_ID"

    # Extract data
    echo "  [$NODE] Extracting data..."
    local REMOTE_OUT="/tmp/dl2l_extract_$$"
    ssh "$NODE" "mkdir -p $REMOTE_OUT && \
        sudo docker run --rm \
            --network ${PROJECT}_dl2l-network \
            -v $REMOTE_CONFIG/docker-config.conf:/config/docker-config.conf \
            -v $REMOTE_OUT:/output \
            -e HOST=localhost -e PORT=2551 -e ROLE=holder \
            -e DATA_DIR=/output -e SIMULATION='' \
            $IMAGE \
            --host localhost --port 2551 --roles holder --extractor --save /output"

    # Rsync results to Mac
    mkdir -p "$LOCAL_OUT"
    rsync -a --remove-source-files "$NODE:$REMOTE_OUT/" "$LOCAL_OUT/"
    ssh "$NODE" "rm -rf $REMOTE_OUT"

    # Tear down
    ssh "$NODE" "cd ~ && DL2L_IMAGE=$IMAGE SIMULATION=$SIM CONFIG_DIR=$REMOTE_CONFIG \
        sudo env DL2L_IMAGE=$IMAGE SIMULATION=$SIM CONFIG_DIR=$REMOTE_CONFIG \
            docker compose -p $PROJECT -f $REMOTE_COMPOSE down -v 2>/dev/null || true"

    echo "  [$NODE] Done → $(ls "$LOCAL_OUT" | wc -l | tr -d ' ') creature dirs"
}

# ── Helper: run N trials in parallel batches across nodes ───────────────────
run_trials_parallel() {
    local SIM=$1
    local N=$2
    local OUTPUT_BASE=$3

    mkdir -p "$OUTPUT_BASE"
    local i=1
    while [ "$i" -le "$N" ]; do
        local BATCH_PIDS=()
        local BATCH_END=$(( i + N_NODES - 1 ))
        [ "$BATCH_END" -gt "$N" ] && BATCH_END=$N

        echo ""
        echo "--- Trials $i–$BATCH_END of $N (batch of $(( BATCH_END - i + 1 ))) ---"

        for j in $(seq "$i" "$BATCH_END"); do
            local NODE_IDX=$(( (j - i) % N_NODES ))
            local NODE="${NODES[$NODE_IDX]}"
            local TRIAL_DIR="$OUTPUT_BASE/trial_$j"
            run_trial "$NODE" "$SIM" "$TRIAL_DIR" &
            BATCH_PIDS+=($!)
        done

        # Wait for the whole batch
        for PID in "${BATCH_PIDS[@]}"; do
            wait "$PID"
        done

        i=$(( BATCH_END + 1 ))
    done
}

# ── Phase 0: Collect JEPA training data ─────────────────────────────────────
echo ""
echo ">>> PHASE 0: Collecting JEPA training data ($TRAIN_TRIALS trials) <<<"
mkdir -p "$RAW_P7_DIR"
run_trials_parallel "simulations/exp_p7_0_jepa_train.conf" "$TRAIN_TRIALS" "$RAW_P7_DIR"

# ── Phase 1: Train all three JEPA variants (on this Mac) ────────────────────
echo ""
echo ">>> PHASE 1: Training JEPA variants (local) <<<"
mkdir -p "$ML_DATA_P7"

echo "  Preparing dataset..."
(cd "$ROOT_DIR/ml" && python3 -m scripts.prepare_dataset \
    --wd "$RAW_P7_DIR" --out "$ML_DATA_P7" --dual)

declare -A VARIANT_FLAGS
VARIANT_FLAGS[single]=""
VARIANT_FLAGS[dual]="--dual --crit 0.0"
VARIANT_FLAGS[dual_critic]="--dual"

for LABEL in single dual dual_critic; do
    FLAGS="${VARIANT_FLAGS[$LABEL]}"
    CKPT_V="$CKPT_DIR/$LABEL"
    mkdir -p "$CKPT_V"
    echo "  Training $LABEL ..."
    # shellcheck disable=SC2086
    (cd "$ROOT_DIR/ml" && python3 -m scripts.train_species \
        --data "$ML_DATA_P7" --ckpt "$CKPT_V" $FLAGS --epochs 200) \
        || echo "  WARNING: $LABEL failed, continuing."
done

# ── Phase 2: Export, rebuild ARM64 image, push to registry ──────────────────
echo ""
echo ">>> PHASE 2: Export model → rebuild ARM64 image → push <<<"

CKPT_FINAL="$CKPT_DIR/dual_critic"
(cd "$ROOT_DIR/ml" && python3 -m scripts.check_collapse --ckpt "$CKPT_FINAL" --dual \
    2>/dev/null || echo "  (check_collapse non-zero — inspect above)")
(cd "$ROOT_DIR/ml" && python3 -m scripts.export_model --dual \
    --ckpt "$CKPT_FINAL" --out "$ROOT_DIR/src/main/resources/models")

echo "  Rebuilding jar..."
(cd "$ROOT_DIR" && mvn package -q)

echo "  Building ARM64 image and pushing..."
docker buildx build \
    --platform linux/arm64 \
    -f "$ROOT_DIR/docker/Dockerfile" \
    -t node-0:5000/dl2l:latest \
    --push \
    "$ROOT_DIR"
# Retag with IP so workers can pull
ssh 192.168.1.200 "sudo docker pull node-0:5000/dl2l:latest -q && \
    sudo docker tag node-0:5000/dl2l:latest 192.168.1.200:5000/dl2l:latest && \
    sudo docker push 192.168.1.200:5000/dl2l:latest -q"

echo "  Pulling updated image on all nodes..."
for NODE in "${NODES[@]}"; do
    ssh "$NODE" "sudo docker pull 192.168.1.200:5000/dl2l:latest -q" &
done
wait

# ── Phase 3: Sample size from P7-0 data ─────────────────────────────────────
echo ""
echo ">>> PHASE 3: Power analysis <<<"
VAL_TRIALS=$(python3 "$ROOT_DIR/analysis/exp_p7_sample_size.py" \
    --wd "$RAW_P7_DIR" 2>/dev/null || echo "10")
VAL_TRIALS=${2:-$VAL_TRIALS}
echo "  Using $VAL_TRIALS validation trials per sample"

# ── Phase 4: Validation samples ─────────────────────────────────────────────
echo ""
echo ">>> PHASE 4: Validation samples <<<"
mkdir -p "$DATA_DIR"

for IDX in "${!SAMPLE_NAMES[@]}"; do
    NAME="${SAMPLE_NAMES[$IDX]}"
    SIM="${SAMPLE_SIMS[$IDX]}"
    echo ""
    echo "=== Sample: $NAME ==="
    run_trials_parallel "$SIM" "$VAL_TRIALS" "$DATA_DIR/$NAME"
done

# ── Phase 5: Analysis ────────────────────────────────────────────────────────
echo ""
echo ">>> PHASE 5: Analysis <<<"
python3 "$ROOT_DIR/analysis/exp_p7_memory_vs_wm.py" \
    --wd "$DATA_DIR" --baseline-wd "$RAW_P7_DIR"

echo ""
echo "========================================================"
echo " EXP-P7 COMPLETE"
echo " Report : docs/reports/EXP_P7_MEMORY_FILTER_VS_WORLD_MODEL.md"
echo "========================================================"
