#!/bin/bash

if [ "$#" != "2" ]; then
    echo "usage: ${0} config runs"
    exit 1
fi

config=$1
runs=$2
holders=`cat ${config} | grep holders | sed -e 's/  holders = //g'`
dependency=""

for i in `seq 1 ${runs}`; do

    manager=`sbatch ${dependency} --job-name="manager" -w compute-0-34 l2l_job.sh ${config} "manager" | grep -o "[0-9]*"`
    provider=`sbatch ${dependency} --job-name="idProvider" l2l_job.sh ${config} "idProvider" | grep -o "[0-9]*"`
    detector=`sbatch ${dependency} --job-name="collisionDetector" l2l_job.sh ${config} "collisionDetector" | grep -o "[0-9]*"`
    ids="${manager}:${provider}:${detector}"
    sleep 10

    for i in `seq 1 ${holders}`; do
        id=`sbatch ${dependency} --job-name="holder-${i}" l2l_job.sh ${config} "holder" | grep -o "[0-9]*"`
        ids+=":${id}"
    done
    echo "Submitted ${ids}"

    dependency="--dependency=afterany:${ids}"
    ids=""
done
