#!/usr/bin/env bash
# DealDog entity-resolution service.
#   PORT              listen port (default 8080)
#   DEALDOG_STATE_DIR durable state directory (default ./.dealdog-state)
# Runs in the foreground; repeated starts against the same state dir resume cleanly.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8080}"
export DEALDOG_STATE_DIR="${DEALDOG_STATE_DIR:-$PWD/.dealdog-state}"
mkdir -p "$DEALDOG_STATE_DIR"

JAR="target/dealdog.jar"

# Rebuild when the jar is missing OR any source is newer than it, so editing code and
# re-running never silently serves a stale jar. Set DEALDOG_FORCE_BUILD=1 to always rebuild.
NEEDS_BUILD=0
if [ ! -f "$JAR" ]; then
  NEEDS_BUILD=1
  REASON="no jar"
elif [ "${DEALDOG_FORCE_BUILD:-0}" = "1" ]; then
  NEEDS_BUILD=1
  REASON="DEALDOG_FORCE_BUILD=1"
elif [ -n "$(find src pom.xml -newer "$JAR" 2>/dev/null | head -1)" ]; then
  NEEDS_BUILD=1
  REASON="sources newer than jar"
fi

if [ "$NEEDS_BUILD" = "1" ]; then
  echo "[run.sh] building $JAR ($REASON) ..." >&2
  if [ -x ./mvnw ]; then ./mvnw -q -DskipTests package
  else mvn -q -DskipTests package
  fi
else
  echo "[run.sh] reusing up-to-date $JAR" >&2
fi

echo "[run.sh] PORT=$PORT DEALDOG_STATE_DIR=$DEALDOG_STATE_DIR" >&2
exec java -jar "$JAR"
