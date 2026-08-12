#!/bin/bash
# Submit one p84 arm to CCAD and collect it incrementally, as a single operation.
#
#   scripts/ccad_run_arm.sh <arm> <image-tag> [max_concurrent] [expected_trials]
#
# Submitting and collecting are one command because separating them is a live failure mode,
# not a hypothetical: on 2026-08-11 an arm was submitted without ccad_collect.sh attached, so
# completed trials accumulated with nothing clearing them and all 16 were lost to the disk
# quota — exactly what the collector exists to prevent. There is no supported way to run half
# of this.
#
# Trials in these arms do not terminate on their own (memory extends life past the runtime
# cap), so each writes its full maxRuntimeMinutes worth of data. Peak remote usage is
# roughly max_concurrent x 2 x per-trial size — the 2x is the raw_backup copy — plus whatever
# has completed but not yet been collected.
set -euo pipefail
ARM="${1:?arm}"; IMAGE="${2:?image tag, e.g. ghcr.io/felipedreis/dl2l:sha-abc1234}"
CONC="${3:-6}"; EXPECTED="${4:-16}"
ROOT=/Users/felipeduarte/IdeaProjects/dl2l
LOG="/private/tmp/claude-501/-Users-felipeduarte-IdeaProjects-dl2l/871cb7b3-a2c2-4f7c-9d76-3a707c292814/scratchpad/arm_${ARM}.log"

echo "=== submitting $ARM (image=$IMAGE, concurrency=$CONC) ==="
cd "$ROOT/ansible"
if ! ansible-playbook -i inventories/ccad run-experiment.yml \
      -e "experiment=p84_rerun_${ARM}" \
      -e "dl2l_image=${IMAGE}" \
      -e "ccad_max_concurrent_trials=${CONC}" > "$LOG" 2>&1; then
  echo "SUBMIT FAILED for $ARM — see $LOG"
  grep -E "^fatal" -A 4 "$LOG" | head -10
  exit 1
fi
grep -qE "failed=[1-9]" "$LOG" && { echo "SUBMIT REPORTED FAILURES for $ARM"; exit 1; }
echo "submitted $ARM"

# Collect from here on; this is the part that must not be skipped.
exec "$ROOT/scripts/ccad_collect.sh" "$ARM" "$EXPECTED" "$ROOT/ml/data_p84_rerun"
