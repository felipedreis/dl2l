#!/bin/bash
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=12
#SBATCH --qos=part6h
#SBATCH --partition=lsi-1

module load jdk8

config=$1
role=$2
LD="-Djava.library.path=../natives"
save_dir=${SLURM_JOB_ID}

if [ "$role" = "holder" ]; then
    mkdir -p ${save_dir}/data ${save_dir}/backup
fi

java -jar ${LD} l2l.jar --host `hostname -I` --port 2550 --roles ${role} --simulation ${config} --save "${save_dir}/data"


if [ "$role" = "holder" ]; then
    pg_dump -d "l2l" -U felipe -W --role=felipe -f ${save_dir}/backup/`hostname`.backup -F c <<< "123456"
fi
