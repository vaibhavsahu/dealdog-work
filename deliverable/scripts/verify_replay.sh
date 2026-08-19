#!/usr/bin/env bash
# Positive-evidence replay check.
#
# The public validator's --before-replay/--after-replay mode only asserts that five collection
# LENGTHS are unchanged, and says nothing on success. This script instead:
#   1. snapshots the full export,
#   2. re-POSTs every already-applied batch verbatim,
#   3. asserts the service reports them entirely as duplicates (0 accepted),
#   4. byte-diffs the whole export, including observations and resolution_history.
#
# Usage:  PORT=8080 ./scripts/verify_replay.sh
# Assumes the service is already running and has ingested the supplied data.
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
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -sf "$BASE/health" >/dev/null || { echo "FAIL: no service on $BASE"; exit 1; }

echo "[1/4] snapshotting export before replay"
curl -sf "$BASE/evaluation/export" > "$TMP/before.json"

echo "[2/4] rebuilding the exact batches that were already ingested"
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

echo "[3/4] re-POSTing every batch verbatim"
FAILED=0
for f in "$TMP"/0*.json "$TMP"/1*.json; do
  [ -e "$f" ] || continue
  RESP="$(curl -sf -X POST "$BASE/ingest" -H 'Content-Type: application/json' --data-binary @"$f")"
  ACC="$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accepted"])')"
  DUP="$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["duplicates"])')"
  printf '  %-34s accepted=%-4s duplicates=%s\n' "$(basename "$f")" "$ACC" "$DUP"
  [ "$ACC" != "0" ] && FAILED=1
done

echo "[4/4] diffing the full export"
curl -sf "$BASE/evaluation/export" > "$TMP/after.json"

python3 - "$TMP/before.json" "$TMP/after.json" "$FAILED" <<'PY'
import json, sys
before, after, failed = json.load(open(sys.argv[1])), json.load(open(sys.argv[2])), sys.argv[3]=="1"
bad = []
if failed: bad.append("a replayed batch reported accepted != 0")
for k in ("normalized_listings","universal_products","variants","offers","observations",
          "resolution_decisions","resolution_history","quarantine"):
    b, a = before.get(k, []), after.get(k, [])
    print(f"  {k:24} {len(b):>5} -> {len(a):>5}")
    if len(b) != len(a): bad.append(f"{k} count changed {len(b)} -> {len(a)}")
if json.dumps(before, sort_keys=True) != json.dumps(after, sort_keys=True):
    bad.append("export content differs (counts alone may still match)")
print()
if bad:
    print("REPLAY CHECK FAILED:"); [print("  -", x) for x in bad]; sys.exit(1)
print("REPLAY CHECK PASSED: every batch deduplicated, export byte-identical")
PY
