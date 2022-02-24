#!/bin/bash

project_dir=`dirname $0`
version=2.0.0-SNAPSHOT
bin_dir="target"
config_dir="simulations"
scripts_dir="scripts"

if [ "$#" != "1" ]; then
 echo "usage: ${0} user"
 exit 1
fi

port=2200
user=$1

l2l_bin=${bin_dir}/l2l-${version}-wd.jar

cluster=${user}@cluster.decom.cefetmg.br

cd ${project_dir}/../

ssh -p${port} ${cluster} "rm -rf l2l"
ssh -p${port} ${cluster} "mkdir -p  l2l/config"

mvn clean package

scp -P${port} ${l2l_bin} ${cluster}:~/l2l/l2l.jar
scp -P${port} ${scripts_dir}/deploy.sh ${cluster}:~/l2l/
scp -P${port} ${scripts_dir}/cancelJobs.sh ${cluster}:~/l2l/
scp -P${port} ${scripts_dir}/l2l_job.sh ${cluster}:~/l2l/
scp -P${port} ${config_dir}/*.conf ${cluster}:~/l2l/config/

ssh -p${port} ${cluster} "chmod +x l2l/deploy.sh l2l/cancelJobs.sh"
