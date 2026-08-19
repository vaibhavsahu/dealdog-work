#!/usr/bin/env bash
# Load the supplied corpus into an ALREADY-RUNNING service.
#
#   ./run.sh &
#   PORT=8080 ./scripts/load_corpus.sh
#
# Posts the five initial sources, then the three incremental phases in order, to the service
# listening on $PORT. Unlike scripts/generate_outputs.sh -- which starts its own throwaway
# service on a temporary state dir -- this targets the instance you are already running, so
# /health, /resolve and scripts/verify_replay.sh then have data to work with.
set -euo pipefail
cd "$(dirname "$0")/.."

# --- locate the supplied package (provided/) and the artifact directory (outputs/) -----------
# Works from the split layout (deliverable/ beside provided/) or a flat package.
resolve_dirs() {
  if [ -z "${PROVIDED_DIR:-}" ]; then
    for c in "${DEALDOG_PROVIDED_DIR:-}" ../provided provided .. .; do
      [ -n "$c" ] && [ -d "$c/data" ] && PROVIDED_DIR="$c" && break
    done
  fi
  [ -n "${PROVIDED_DIR:-}" ] || { echo "cannot locate provided/ (set DEALDOG_PROVIDED_DIR)"; exit 1; }
  if [ -z "${OUTPUT_DIR:-}" ]; then
    if [ -d ../outputs ]; then OUTPUT_DIR=../outputs; else OUTPUT_DIR=outputs; fi
  fi
  mkdir -p "$OUTPUT_DIR"
  DATA_DIR="$PROVIDED_DIR/data"
  VALIDATOR="$PROVIDED_DIR/validation/validate_outputs.py"
}

resolve_dirs

PORT="${PORT:-8080}"
BASE="http://localhost:$PORT"

curl -sf "$BASE/health" >/dev/null || { echo "FAIL: no service on $BASE (start it with ./run.sh &)"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 - "$TMP" "$DATA_DIR" <<'PY'
import csv, json, sys
from pathlib import Path
tmp = Path(sys.argv[1]); data = Path(sys.argv[2] if len(sys.argv)>2 else "data")
def dump(name, obj): (tmp/name).write_text(json.dumps(obj))
dump("01_affiliate_a.json", {"source":"affiliate_a","batch_id":"initial-affiliate-a",
    "operation":"upsert","records":list(csv.DictReader(open(data/"initial/affiliate_a.csv")))})
dump("02_affiliate_b.json", {"source":"affiliate_b","batch_id":"initial-affiliate-b",
    "operation":"upsert","records":json.load(open(data/"initial/affiliate_b.json"))["products"]})
dump("03_retailer_api.json", {"source":"retailer_api","batch_id":"initial-retailer-api",
    "operation":"upsert","records":json.load(open(data/"initial/retailer_api.json"))["items"]})
dump("04_community_deals.json", {"source":"community_deals","batch_id":"initial-community-deals",
    "operation":"upsert","records":json.load(open(data/"initial/community_deals.json"))})
dump("05_extension.json", {"source":"extension_observations","batch_id":"initial-extension",
    "operation":"upsert","records":json.load(open(data/"initial/extension_observations.json"))["observations"]})
for phase in (1,2,3):
    doc = json.load(open(data/f"incremental/incremental_phase_{phase}.json"))
    dump(f"1{phase}_incremental_phase_{phase}.json",
         {"batch_id": doc.get("batch_id", f"incremental-phase-{phase}"), "records": doc["events"]})
PY

echo "Posting to $BASE/ingest"
for f in "$TMP"/0*.json "$TMP"/1*.json; do
  [ -e "$f" ] || continue
  RESP="$(curl -sf -X POST "$BASE/ingest" -H 'Content-Type: application/json' --data-binary @"$f")"
  printf '  %-34s %s\n' "$(basename "$f")" \
    "$(printf '%s' "$RESP" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(" ".join(f"{k}={d[k]}" for k in ("accepted","quarantined","duplicates","corrected","rejected")))')"
done

echo
curl -sf "$BASE/health" | python3 -m json.tool
