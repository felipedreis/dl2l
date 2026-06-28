#!/bin/bash
# Open a PR with the experiment report for this version.
# Usage: open-report-pr.sh <version>   (e.g. v2.1.0)
# Requires GH_TOKEN in env (injected by the workflow).

set -euo pipefail

VERSION="$1"
REPORT_DIR="reports/${VERSION}"
BRANCH="experiment-report/${VERSION}"

if [ ! -d "${REPORT_DIR}" ]; then
    echo "Report directory ${REPORT_DIR} not found." >&2
    exit 1
fi

git config user.name  "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"

git checkout -b "${BRANCH}"
git add "${REPORT_DIR}"
git commit -m "experiments: ${VERSION} report"

git push -u origin "${BRANCH}"

# Summarise regressions for the PR body
REGRESSIONS=$(grep -c "↓ regression" "${REPORT_DIR}/report.md" || true)
IMPROVEMENTS=$(grep -c "↑ improvement" "${REPORT_DIR}/report.md" || true)

gh pr create \
    --title "experiments: ${VERSION} report" \
    --base main \
    --head "${BRANCH}" \
    --label "experiment-report" \
    --body "$(cat <<EOF
## Experiment report for \`${VERSION}\`

Automated results from the self-hosted Raspberry Pi Slurm cluster.

- **Improvements**: ${IMPROVEMENTS}
- **Regressions**: ${REGRESSIONS}

See \`${REPORT_DIR}/report.md\` for the full comparison vs. the previous release.
EOF
)"
