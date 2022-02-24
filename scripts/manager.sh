#!/bin/bash

PROJ=`pwd`/

java -jar ${PROJ}/target/l2l-2.0.0-SNAPSHOT-wd.jar --host localhost --port 2551 --roles manager --simulation ${PROJ}/simulations/basic.conf
