#!/bin/bash
# Master orchestration: finish condition 7 (7_jepa_rpe_only) and run rotten fruit experiment.
#
# 1. Wait for the already-running diagnostic trial (exp_diag_cond7) to finish
# 2. Extract its data as condition 7, trial 1
# 3. Tear down the diag containers
# 4. Run 4 more condition 7 trials (trial 2-5)
# 5. Run 20260709 analysis (conditions 1, 2, 3, 6, 7)
# 6. Run rotten fruit experiment (3 conditions × 5 trials)
# 7. Run rotten fruit analysis
#
# Usage: nohup bash scripts/run_full_cond7_and_rotten.sh &>scripts/full_run.log &
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_DIR="$ROOT_DIR/docker"
DC="docker compose"

EXP_MAIN="20260709_memory_vs_wm_v1"
DATA_DIR_MAIN="$ROOT_DIR/ml/data_${EXP_MAIN}"
COMPOSE_COND7="docker-compose-20260709-memory-vs-wm-v1-7.yml"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

log "=== Master orchestration started ==="

# ── Step 1: Wait for diagnostic trial (= condition 7 trial 1) to finish ────────
DIAG_HOLDER="exp_diag_cond7-dl2l-holder-1"
log "Waiting for diagnostic trial holder ($DIAG_HOLDER) to exit..."
docker wait "$DIAG_HOLDER" || true
log "Diagnostic trial finished."

# ── Step 2: Extract data from diagnostic trial as trial 1 ───────────────────────
log "Extracting condition 7 trial 1 data..."
python3 "$ROOT_DIR/scripts/exp_extract.py" \
    --experiment "$EXP_MAIN" \
    --condition  "7_jepa_rpe_only" \
    --trial      1 \
    --out        "$DATA_DIR_MAIN" \
    --container  "db"
log "Trial 1 data extracted."

# ── Step 3: Tear down diagnostic containers ─────────────────────────────────────
log "Tearing down diagnostic containers..."
(cd "$COMPOSE_DIR" && $DC -p "exp_diag_cond7" -f "$COMPOSE_COND7" down -v --remove-orphans 2>/dev/null || true)
log "Diagnostic containers removed."

# ── Step 4: Run condition 7 trials 2-5 ──────────────────────────────────────────
log "Running condition 7 trials 2-5..."
for trial in 2 3 4 5; do
    PROJ="exp20260709_7_jepa_rpe_only_t${trial}"
    log "  Condition 7 trial $trial starting..."

    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE_COND7" down -v --remove-orphans 2>/dev/null || true)
    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE_COND7" up -d)

    HOLDER_ID=$(cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE_COND7" ps -q dl2l-holder)
    log "  Holder container: $HOLDER_ID. Waiting..."
    docker wait "$HOLDER_ID"
    log "  Simulation done."

    log "  Extracting trial $trial data..."
    python3 "$ROOT_DIR/scripts/exp_extract.py" \
        --experiment "$EXP_MAIN" \
        --condition  "7_jepa_rpe_only" \
        --trial      "$trial" \
        --out        "$DATA_DIR_MAIN" \
        --container  "db"
    log "  Trial $trial data extracted."

    (cd "$COMPOSE_DIR" && $DC -p "$PROJ" -f "$COMPOSE_COND7" down -v)
    log "  Condition 7 trial $trial complete."
done
log "All condition 7 trials done."

# ── Step 5: Run 20260709 analysis (conditions 1, 2, 3, 6, 7) ────────────────────
log "Running 20260709 analysis..."
cd "$ROOT_DIR"
python3 analysis/exp_20260709_memory_vs_wm_v1.py
log "20260709 analysis complete."

# ── Step 6: Run rotten fruit experiment (3 conditions × 5 trials) ────────────────
log "Starting rotten fruit experiment..."
bash "$ROOT_DIR/scripts/run_exp_rotten_fruit_v1.sh" 5
log "Rotten fruit experiment complete."

# ── Step 7: Run rotten fruit analysis ────────────────────────────────────────────
log "Running rotten fruit analysis..."
python3 "$ROOT_DIR/analysis/exp_rotten_fruit_v1.py"
log "Rotten fruit analysis complete."

log "=== ALL DONE ==="
