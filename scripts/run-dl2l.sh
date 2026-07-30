#!/bin/bash

HOST=$1
PORT=$2
ROLE=$3
DATA_DIR=$4
SIMULATION=$5
CONFIG=$6

# Diagnostic-tooling gap identified during the issue #77 BDActor OOM investigation: a heap
# dump / GC log wasn't available for the crash, so all analysis had to be inferred from thread
# counts and container RSS. Kept even after the OOM fix so a future incident yields a heap dump
# by default - see docs/plans/issue-77-bdactor-oom-fix.md.
HEAPDUMP_DIR=${HEAPDUMP_DIR:-/dl2l/heapdumps}
mkdir -p "$HEAPDUMP_DIR"

java -Xmx2g \
    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$HEAPDUMP_DIR \
    -Xlog:gc*:file=$HEAPDUMP_DIR/gc.log:time,uptime,level,tags:filecount=5,filesize=50M \
    -Dconfig.file=$CONFIG -jar dl2l.jar --host $HOST --port $PORT --roles "$ROLE" --save $DATA_DIR --simulation $SIMULATION