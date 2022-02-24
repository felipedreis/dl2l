#!/bin/bash

PROJ=`pwd`/

rm -rf ${PROJ}/data
java -jar ${PROJ}/target/l2l-2.0.0-SNAPSHOT-wd.jar --host localhost --port 2554 --roles holder --save data --simulation ${PROJ}/simulations/basic.conf #> ${PROJ}/logs/holder.log 2>&1



