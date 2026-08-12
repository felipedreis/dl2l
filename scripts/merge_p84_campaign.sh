#!/bin/bash
# Assemble the p84 campaign's six arms into one data dir for the analysis module.
#
# The campaign was collected in three pieces because it failed and was recovered piecemeal:
#
#   ml/data_p84_ccad_partial     legacy_nomem, legacy_mem   — the only arms the original
#                                                             submission completed
#   ml/data_p84_rerun            the four arms re-run one at a time afterwards
#   ml/data_p84_ccad_recovered   current_nomem trials salvaged from raw dumps whose extraction
#                                had died; superseded by the rerun, so NOT merged by default
#
# The pre-rework v3 campaign at ml/data_p84_behaviour_parity is moved aside rather than
# overwritten: it is the evidence base for issue #90's pre/post comparison and for the
# statement that the no-memory arms are unchanged across builds.
#
#   scripts/merge_p84_campaign.sh [--include-recovered]
set -euo pipefail
cd /Users/felipeduarte/IdeaProjects/dl2l
DEST=ml/data_p84_behaviour_parity
OLD=ml/data_p84_behaviour_parity_v3_prerework
INCLUDE_RECOVERED="${1:-}"

if [ -d "$DEST" ] && [ ! -d "$OLD" ]; then
  echo "moving the pre-rework v3 campaign aside -> $OLD"
  mv "$DEST" "$OLD"
fi
mkdir -p "$DEST"

copy_arm() {   # src_root arm
  local src="$1/$2"
  [ -d "$src" ] || return 0
  local n
  n=$(ls -d "$src"/trial_* 2>/dev/null | wc -l | tr -d ' ')
  [ "$n" = "0" ] && return 0
  mkdir -p "$DEST/$2"
  cp -R "$src"/trial_* "$DEST/$2/" 2>/dev/null || true
  echo "  $2: $n trials from $(basename "$1")"
}

echo "assembling $DEST"
for arm in legacy_nomem legacy_mem; do copy_arm ml/data_p84_ccad_partial "$arm"; done
for arm in legacy_nomem_simple legacy_mem_simple current_nomem current_mem; do
  copy_arm ml/data_p84_rerun "$arm"
done
if [ "$INCLUDE_RECOVERED" = "--include-recovered" ]; then
  # Recovered trials carry recovered=True and a reconstructed creature registry (born_time is
  # first-activity, died is False by construction). Mixing them with cleanly extracted trials
  # is only defensible for rate measures, never for anything about birth or death.
  echo "  (including recovered current_nomem trials — rate measures only)"
  copy_arm ml/data_p84_ccad_recovered current_nomem
fi

echo
for a in "$DEST"/*/; do
  [ -d "$a" ] && echo "  $(basename "$a"): $(ls -d "$a"trial_* 2>/dev/null | wc -l | tr -d ' ') trials"
done
echo
echo "next: python3 scripts/check_experiment_gates.py $DEST"
echo "      PYTHONPATH=analysis python3 -m dl2l_analysis --experiment p84_behaviour_parity"
