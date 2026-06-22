#!/bin/bash
# Run N simulation trials for Phase 2 data collection and extract trajectory CSVs.
# Usage: ./scripts/run_phase2_trials.sh [N]   (default N=5)
set -e

TRIALS=${1:-5}
COMPOSE_DIR="$(cd "$(dirname "$0")/../docker" && pwd)"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RAW_DIR="$ROOT_DIR/ml/data/raw"
JAR="$ROOT_DIR/target/l2l-2.0.0-SNAPSHOT-wd.jar"
NETWORK="docker_dl2l-network"
CONFIG_FILE="$ROOT_DIR/config/docker-config.conf"

echo "=== Phase 2 trial runner: $TRIALS trials ==="
echo "Output: $RAW_DIR"

for i in $(seq 1 "$TRIALS"); do
    TRIAL_DIR="$RAW_DIR/trial_$i"
    mkdir -p "$TRIAL_DIR"

    echo ""
    echo "--- Trial $i / $TRIALS ---"

    # Clean DB and containers from previous run
    (cd "$COMPOSE_DIR" && docker-compose -f docker-compose.yml -f docker-compose.phase2.yml down -v --remove-orphans 2>/dev/null || true)

    # Start simulation
    echo "  Starting simulation..."
    (cd "$COMPOSE_DIR" && docker-compose -f docker-compose.yml -f docker-compose.phase2.yml up -d)

    # Wait for holder to exit (simulation complete)
    HOLDER_ID=$(cd "$COMPOSE_DIR" && docker-compose ps -q dl2l-holder)
    echo "  Holder container: $HOLDER_ID"
    echo "  Waiting for simulation to finish..."
    docker wait "$HOLDER_ID"
    echo "  Simulation finished."

    # Run extractor
    echo "  Extracting data to $TRIAL_DIR ..."
    docker run --rm \
        --network "$NETWORK" \
        --entrypoint java \
        -v "$JAR":/dl2l/run/dl2l.jar \
        -v "$CONFIG_FILE":/config/docker-config.conf \
        -v "$TRIAL_DIR":/output \
        dl2l \
        -Dconfig.file=/config/docker-config.conf \
        -jar dl2l.jar \
        --host localhost --port 2551 --roles holder --extractor --save /output

    echo "  Trial $i complete. Files:"
    ls "$TRIAL_DIR"/ | head -5

    # Tear down
    (cd "$COMPOSE_DIR" && docker-compose -f docker-compose.yml -f docker-compose.phase2.yml down -v)
done

echo ""
echo "=== All $TRIALS trials complete ==="
echo "Raw data in: $RAW_DIR"
echo ""
echo "Next step: run prepare_dataset.py"
echo "  cd ml && python3 -m scripts.prepare_dataset --wd $RAW_DIR --out data"
