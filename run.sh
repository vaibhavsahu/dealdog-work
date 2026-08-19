#!/usr/bin/env bash
# Root-level entry point required by SUBMISSION_CONTRACT.md.
# The implementation lives in deliverable/; this delegates to it so the contract's
# "root-level executable run.sh" requirement is satisfied from the package root.
#
#   PORT=8080 DEALDOG_STATE_DIR=/tmp/dealdog-state ./run.sh
exec "$(dirname "$0")/deliverable/run.sh" "$@"
