#!/bin/bash

HOST=$1
PORT=$2
ROLE=$3
DATA_DIR=$4
SIMULATION=$5

java -jar dl2l.jar --host $HOST --port $PORT --roles $ROLE --save $DATA_DIR --simulation simulations/basic.conf