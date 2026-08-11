#!/bin/bash
# Incrementally collect a CCAD arm's trials WHILE it runs, clearing each from the remote.
#
# The shared rescue flow syncs only once every trial of every condition has finished, so the
# whole arm's output sits on the remote until then. That is what exhausted the disk quota on
# 2026-08-11 and cost four of six arms: a single current_nomem arm is ~38 GB against ~23 GB of
# headroom, and capping SLURM array concurrency does not help because COMPLETED trials keep
# accumulating regardless of how many run at once.
#
# This polls for finished trials, syncs each down, verifies it locally, and only then deletes
# the remote copy — so peak remote usage is the trials in flight, not the arm. It is the
# wrapper docs/experiments/p84_behaviour_parity_recipe.md §9 describes and that was never
# folded into the shared ansible role.
#
#   scripts/ccad_collect.sh <condition> <expected_trials> <local_out_dir> [min_tables]
#
# A trial is collected only when it has a DONE sentinel AND at least min_tables parquet files.
# DONE alone is not sufficient: it is written unconditionally by the job script's EXIT trap, so
# a trial that died mid-extraction still carries one (recipe §9, confirmed live).
set -u
COND="${1:?condition}"; EXPECTED="${2:?expected trials}"; OUT="${3:?local out dir}"
MIN_TABLES="${4:-13}"
cd /Users/felipeduarte/IdeaProjects/dl2l || exit 1
CU=$(grep -o 'CCAD_USERNAME=.*' .env.local | cut -d= -f2 | tr -d '" ')
H="$CU@login.ccad.cefetmg.br"
SSH="ssh -o ConnectTimeout=20 -o BatchMode=yes -o ServerAliveInterval=15"
REMOTE_BASE="l2l/data/p84_rerun_${COND}/${COND}"

# Space-delimited set rather than an associative array: macOS ships bash 3.2, where
# `declare -A` does not exist, and this script has to run from the developer's Mac.
collected=0
have=" "
mkdir -p "$OUT/$COND"
for d in "$OUT/$COND"/trial_*; do
  [ -d "$d" ] || continue
  have="$have$(basename "$d") "
  collected=$((collected+1))
done
already() { case "$have" in *" $1 "*) return 0;; *) return 1;; esac; }

idle=0
while [ "$collected" -lt "$EXPECTED" ]; do
  ready=$($SSH "$H" "for t in ~/$REMOTE_BASE/trial_*; do [ -f \$t/DONE ] || continue; n=\$(ls \$t/*.parquet 2>/dev/null | wc -l); [ \$n -ge $MIN_TABLES ] && basename \$t; done" 2>/dev/null)
  if [ -z "$ready" ]; then
    # Nothing new. If the queue is empty too, the arm is finished and whatever is missing
    # failed — report rather than spin forever.
    q=$($SSH "$H" "squeue -u \$USER -h -o '%i' 2>/dev/null | wc -l" 2>/dev/null)
    if [ "${q:-1}" = "0" ]; then
      idle=$((idle+1))
      [ "$idle" -ge 2 ] && { echo "QUEUE EMPTY with $collected/$EXPECTED collected — stopping"; break; }
    fi
    sleep 120; continue
  fi
  idle=0
  for t in $ready; do
    already "$t" && continue
    if rsync -a --partial -e "$SSH" "$H:~/$REMOTE_BASE/$t/" "$OUT/$COND/$t/" 2>/dev/null; then
      n=$(ls "$OUT/$COND/$t"/*.parquet 2>/dev/null | wc -l | tr -d ' ')
      if [ "$n" -ge "$MIN_TABLES" ]; then
        $SSH "$H" "rm -rf ~/$REMOTE_BASE/$t" 2>/dev/null
        have="$have$t "; collected=$((collected+1))
        echo "collected $COND/$t ($n tables) — $collected/$EXPECTED, remote cleared"
      else
        echo "WARN $COND/$t synced only $n tables (<$MIN_TABLES) — left on remote for retry"
        rm -rf "$OUT/$COND/$t"
      fi
    else
      echo "sync of $t interrupted — will retry"
    fi
  done
  sleep 60
done
echo "COLLECTION DONE: $COND $collected/$EXPECTED"
