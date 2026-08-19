#!/usr/bin/env bash
# Boot the service against a FRESH state dir, ingest the supplied initial data and the three
# incremental phases in order, then write the three required artifacts into outputs/.
#
#   ./scripts/generate_outputs.sh
#
# Requires: python3 (used only to reshape the supplied fixtures into ingest envelopes).
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

PORT="${PORT:-8081}"
STATE="$(mktemp -d)"
export PORT DEALDOG_STATE_DIR="$STATE"

echo "[gen] state dir: $STATE"
./run.sh & SVC=$!
trap 'kill $SVC 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  curl -sf "http://localhost:$PORT/health" >/dev/null 2>&1 && break
  sleep 1
done

post() { curl -sf -X POST "http://localhost:$PORT/ingest" -H 'Content-Type: application/json' --data-binary @"$1" >/dev/null; }

TMP="$(mktemp -d)"
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

for f in "$TMP"/*.json; do echo "[gen] POST $(basename "$f")"; post "$f"; done

curl -sf "http://localhost:$PORT/evaluation/export" > "$TMP/export.json"
python3 - "$TMP/export.json" "$OUTPUT_DIR" <<'PY'
import json, sys
from pathlib import Path
doc = json.load(open(sys.argv[1])); out = Path(sys.argv[2] if len(sys.argv)>2 else "outputs")
json.dump(doc["normalized_listings"], open(out/"normalized_listings.json","w"), indent=2)
json.dump({k: doc[k] for k in ("universal_products","variants","offers","observations",
                               "resolution_history","quarantine","stats")},
          open(out/"catalog.json","w"), indent=2)
json.dump(doc["resolution_decisions"], open(out/"resolution_decisions.json","w"), indent=2)
print(json.dumps(doc["stats"], indent=2))
PY

echo "[gen] wrote outputs/{normalized_listings,catalog,resolution_decisions}.json"
python3 "$VALIDATOR" \
  --normalized "$OUTPUT_DIR"/normalized_listings.json \
  --catalog "$OUTPUT_DIR"/catalog.json \
  --decisions "$OUTPUT_DIR"/resolution_decisions.json
