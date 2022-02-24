#!/bin/bash

exp=exp_1_1
exp_dir=/media/felipe/0c5b2979-59fc-4555-a2d9-88e99846e8f1/${exp}
target=experiments

counter=0
rm -rf ${target}/${exp}
mkdir ${target}/${exp}

for backup in `ls -R ${exp_dir}/**/backup/*.backup`; do
    echo ${backup}
    pg_restore -U postgres -d l2l --clean -j 4 ${backup}
    mkdir ${target}/${exp}/${counter}
    java -jar target/l2l-2.0.0-SNAPSHOT-wd.jar --host "localhost" --port 2551 --roles none --extractor --save ${target}/${exp}/${counter}

    let "counter++"
done
