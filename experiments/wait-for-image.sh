#!/bin/bash
# Poll GHCR until the image manifest is available, then pull it.
# Usage: wait-for-image.sh <image:tag>
# Waits up to 15 minutes, checking every 30 seconds.

set -euo pipefail

IMAGE="$1"
MAX_WAIT=900
INTERVAL=30
ELAPSED=0

echo "Waiting for image: ${IMAGE}"

while ! docker manifest inspect "${IMAGE}" > /dev/null 2>&1; do
    if [ "${ELAPSED}" -ge "${MAX_WAIT}" ]; then
        echo "Timed out after ${MAX_WAIT}s waiting for ${IMAGE}" >&2
        exit 1
    fi
    echo "  not yet available (${ELAPSED}s elapsed), retrying in ${INTERVAL}s..."
    sleep "${INTERVAL}"
    ELAPSED=$(( ELAPSED + INTERVAL ))
done

echo "Image available after ${ELAPSED}s. Pulling..."
docker pull "${IMAGE}"
echo "Pull complete."
