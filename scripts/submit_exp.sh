#!/bin/bash
# Submit a DL2L experiment sample as a SLURM array job on the Pi cluster.
#
# Usage:
#   ./scripts/submit_exp.sh <sample_name> <n_trials>
#
# Examples:
#   ./scripts/submit_exp.sh p7_1_baseline 5
#   ./scripts/submit_exp.sh p7_2_memory_only 10

set -e

SAMPLE=${1:?Usage: $0 <sample_name> <n_trials>}
N_TRIALS=${2:?Usage: $0 <sample_name> <n_trials>}
CONTROLLER="192.168.1.200"

case "$SAMPLE" in
  p7_0_jepa_train)         SIM="simulations/exp_p7_0_jepa_train.conf" ;;
  p7_1_baseline)           SIM="simulations/exp_p7_1_baseline.conf" ;;
  p7_2_memory_only)        SIM="simulations/exp_p7_2_memory_only.conf" ;;
  p7_3_memory_consolidation) SIM="simulations/exp_p7_3_memory_consolidation.conf" ;;
  p7_4_jepa_only)          SIM="simulations/exp_p7_4_jepa_only.conf" ;;
  p7_5_jepa_consolidation) SIM="simulations/exp_p7_5_jepa_consolidation.conf" ;;
  *) echo "Unknown sample: $SAMPLE"; echo "Valid: p7_0_jepa_train p7_1_baseline p7_2_memory_only p7_3_memory_consolidation p7_4_jepa_only p7_5_jepa_consolidation"; exit 1 ;;
esac

echo "Submitting ${N_TRIALS} trials of ${SAMPLE} → ${SIM}"
echo "Controller: ${CONTROLLER}"

JOB_ID=$(ssh "${CONTROLLER}" \
    "sbatch --array=1-${N_TRIALS} \
            --export=SAMPLE=${SAMPLE},SIMULATION=${SIM} \
            /mnt/dl2l-shared/jobs/dl2l_trial.sh" \
    | awk '{print $NF}')

echo "Submitted job array ${JOB_ID} (${N_TRIALS} trials)"
echo "Monitor: ssh ${CONTROLLER} squeue -j ${JOB_ID}"
echo "Logs:    ~/dl2l-shared/logs/slurm-${JOB_ID}_*.out"
echo "Data:    ~/dl2l-shared/data/${SAMPLE}/"
