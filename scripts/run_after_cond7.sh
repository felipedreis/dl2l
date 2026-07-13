#!/bin/bash
# Orchestration: wait for condition 7 background script to finish,
# then run 20260709 analysis, then run rotten fruit experiment.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT_DIR/scripts/run_after_cond7.log"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }

log "=== Post-condition-7 orchestration started ==="

# ── 1. Wait for condition 7 background script (pid 89107) to finish ─────────
COND7_PID=89107
log "Waiting for condition 7 script (pid $COND7_PID) to finish ..."
while kill -0 "$COND7_PID" 2>/dev/null; do
    sleep 30
done
log "Condition 7 script finished."

# ── 2. Brief cooldown to let final containers be removed ─────────────────────
sleep 15

# ── 3. Run 20260709 analysis ─────────────────────────────────────────────────
log "Running 20260709 analysis ..."
cd "$ROOT_DIR"
python3 analysis/exp_20260709_memory_vs_wm_v1.py 2>&1 | tee -a "$LOG"
log "Analysis complete. Figures in docs/reports/figures/."

# ── 4. Run rotten fruit experiment (3 conditions × 5 trials) ─────────────────
log "Starting rotten fruit experiment ..."
bash "$ROOT_DIR/scripts/run_exp_rotten_fruit_v1.sh" 5 2>&1 | tee -a "$LOG"
log "Rotten fruit experiment complete."

# ── 5. Run rotten fruit analysis ─────────────────────────────────────────────
log "Running rotten fruit analysis ..."
python3 "$ROOT_DIR/analysis/exp_rotten_fruit_v1.py" 2>&1 | tee -a "$LOG"
log "Rotten fruit analysis complete."

log "=== All done. ==="
