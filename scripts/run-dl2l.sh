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

# docs/plans/arrow-ipc-write-path.md, W5: was hardcoded -Xmx2g, which is why CCAD (6+ CPUs, no
# per-role memory accounting) OOM'd at 10-creature scale even after the Arrow write path fixed
# the local OOM. JAVA_XMX lets each environment/role size its own heap (see
# ansible/inventories/ccad/group_vars/all.yml's ccad_holder_xmx); unset (the default everywhere
# else) keeps today's 2g behavior unchanged.
JAVA_XMX=${JAVA_XMX:-2g}

# Mandatory for ArrowIpcBackend's arrow-memory-unsafe allocator on JDK 16+ (arrow-memory-core's
# MemoryUtil reflectively pokes java.nio's internals) - without it, ArrowIpcBackend's
# constructor self-check fails loudly at holder preStart() instead of this flag being missing
# silently. See ArrowIpcBackend's javadoc. Target is ALL-UNNAMED (not also naming
# org.apache.arrow.memory.core as a module) because this project runs entirely off the
# classpath (no module-info.java anywhere, no --module-path) - arrow-memory-core's classes load
# into the unnamed module here, and naming it separately as a target module produces a harmless
# but noisy "Unknown module" JVM warning on every startup for no effect.
ARROW_ADD_OPENS="--add-opens=java.base/java.nio=ALL-UNNAMED"

# docs/plans/arrow-ipc-write-path.md, W5b: -XX:ActiveProcessorCount rescales every
# availableProcessors()-derived pool at once (G1 workers, fork-join dispatcher sizing, JIT,
# netty, libtorch intra-op threads) - all four role JVMs otherwise size themselves as if they
# owned CCAD's whole cpuset even though they share it. Unset (the default everywhere else)
# leaves JVM auto-detection unchanged.
JAVA_CPUS_FLAG=""
if [ -n "${JAVA_CPUS:-}" ]; then
    JAVA_CPUS_FLAG="-XX:ActiveProcessorCount=${JAVA_CPUS}"
fi

java -Xmx${JAVA_XMX} $ARROW_ADD_OPENS $JAVA_CPUS_FLAG \
    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$HEAPDUMP_DIR \
    -Xlog:gc*:file=$HEAPDUMP_DIR/gc.log:time,uptime,level,tags:filecount=5,filesize=50M \
    -Dconfig.file=$CONFIG ${JAVA_OPTS:-} -jar dl2l.jar \
    --host $HOST --port $PORT --roles "$ROLE" --save $DATA_DIR --simulation $SIMULATION