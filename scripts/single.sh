#!/usr/bin/env bash

PROJ=`pwd`
java -jar -Djava.library.path=${PROJ}/target/natives ${PROJ}/target/l2l-2.0.0-SNAPSHOT-wd.jar --host localhost --port 2550 --roles manager idProvider collisionDetector holder --simulation ${PROJ}/simulations/basic.conf
