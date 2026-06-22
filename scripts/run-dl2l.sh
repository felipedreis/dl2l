#!/bin/bash

HOST=$1
PORT=$2
ROLE=$3
DATA_DIR=$4
SIMULATION=$5
CONFIG=$6

java -Dconfig.file=$CONFIG -jar dl2l.jar --host $HOST --port $PORT --roles "$ROLE" --save $DATA_DIR --simulation $SIMULATION