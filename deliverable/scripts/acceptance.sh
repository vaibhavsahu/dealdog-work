#!/usr/bin/env bash
# One-command acceptance run for an evaluator.
#
#   ./scripts/acceptance.sh
#
# Executes every acceptance criterion end to end and prints PASS/FAIL per step. Exits non-zero if
# any step fails, so it is CI-usable. Uses a throwaway state dir and a free port; leaves the
# repository untouched except for outputs/ (regenerated).
#
# Steps
#   1  build + automated tests (22)
#   2  service starts on $PORT, /health returns 200
#   3  ingest the corpus over HTTP (5 initial sources + 3 ordered phases)
#   4  export artifacts, run the SUPPLIED public validator (27 labelled pairs)
#   5  artifact acceptance checks (scripts/check_artifacts.py, 35 checks)
#   6  replay idempotency: re-POST everything, export must be byte-identical
#   7  durable restart: stop/start against the same state dir, state must survive
#   8  /resolve is lookup-only and returns MATCH / REVIEW / NO_MATCH appropriately
set -uo pipefail
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

PORT="${PORT:-8099}"
STATE="$(mktemp -d)"
WORK="$(mktemp -d)"
BASE="http://localhost:$PORT"
SVC=""
PASS=0; FAIL=0

cleanup() { [ -n "$SVC" ] && kill "$SVC" 2>/dev/null; rm -rf "$STATE" "$WORK"; }
trap cleanup EXIT

step()  { printf '\n=== %s\n' "$*"; }
ok()    { PASS=$((PASS+1)); printf '  [PASS] %s\n' "$*"; }
bad()   { FAIL=$((FAIL+1)); printf '  [FAIL] %s\n' "$*"; }
verdict(){ if [ "$1" -eq 0 ]; then ok "$2"; else bad "$2"; fi; }

start_service() {
  DEALDOG_STATE_DIR="$STATE" PORT="$PORT" ./run.sh >"$WORK/service.log" 2>&1 &
  SVC=$!
  for _ in $(seq 1 90); do
    curl -sf "$BASE/health" >/dev/null 2>&1 && return 0
    kill -0 "$SVC" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}
stop_service() { [ -n "$SVC" ] && kill "$SVC" 2>/dev/null; wait "$SVC" 2>/dev/null; SVC=""; }

# ---------------------------------------------------------------- 1. build + tests
step "1. Build and automated tests"
if mvn -q clean test >"$WORK/mvn.log" 2>&1; then
  ok "mvn clean test ($(grep -Eo 'Tests run: [0-9]+' "$WORK/mvn.log" | tail -1 || echo 'passed'))"
else
  bad "mvn clean test — see $WORK/mvn.log"; tail -25 "$WORK/mvn.log"
fi

# ---------------------------------------------------------------- 2. service up
step "2. Service lifecycle"
if start_service; then
  ok "run.sh serves on PORT=$PORT and /health returns 200"
  curl -sf "$BASE/health" | python3 -m json.tool | sed 's/^/      /'
else
  bad "service did not become ready — see $WORK/service.log"; tail -25 "$WORK/service.log"
  printf '\n  %d passed, %d failed\n' "$PASS" "$FAIL"; exit 1
fi

# ---------------------------------------------------------------- 3. ingest
step "3. Ingest the supplied corpus over HTTP"
python3 - "$WORK" "$DATA_DIR" <<'PY'
import csv, json, sys
from pathlib import Path
tmp = Path(sys.argv[1]); data = Path(sys.argv[2] if len(sys.argv)>2 else "data")
def dump(n, o): (tmp/n).write_text(json.dumps(o))
dump("b01.json", {"source":"affiliate_a","batch_id":"initial-affiliate-a","operation":"upsert",
    "records":list(csv.DictReader(open(data/"initial/affiliate_a.csv")))})
dump("b02.json", {"source":"affiliate_b","batch_id":"initial-affiliate-b","operation":"upsert",
    "records":json.load(open(data/"initial/affiliate_b.json"))["products"]})
dump("b03.json", {"source":"retailer_api","batch_id":"initial-retailer-api","operation":"upsert",
    "records":json.load(open(data/"initial/retailer_api.json"))["items"]})
dump("b04.json", {"source":"community_deals","batch_id":"initial-community-deals","operation":"upsert",
    "records":json.load(open(data/"initial/community_deals.json"))})
dump("b05.json", {"source":"extension_observations","batch_id":"initial-extension","operation":"upsert",
    "records":json.load(open(data/"initial/extension_observations.json"))["observations"]})
for p in (1,2,3):
    doc = json.load(open(data/f"incremental/incremental_phase_{p}.json"))
    dump(f"b1{p}.json", {"batch_id": doc.get("batch_id", f"phase-{p}"), "records": doc["events"]})
PY
INGEST_OK=0
for f in "$WORK"/b0*.json "$WORK"/b1*.json; do
  curl -sf -X POST "$BASE/ingest" -H 'Content-Type: application/json' --data-binary @"$f" \
    >"$WORK/resp.json" 2>/dev/null || INGEST_OK=1
done
verdict "$INGEST_OK" "all 8 batches accepted (5 initial sources + 3 ordered phases)"

# ---------------------------------------------------------------- 4. artifacts + public validator
step "4. Generated artifacts and the SUPPLIED public validator"
curl -sf "$BASE/evaluation/export" -o "$WORK/export.json"
python3 - "$WORK/export.json" "$OUTPUT_DIR" <<'PY'
import json, sys
from pathlib import Path
d = json.load(open(sys.argv[1])); out = Path(sys.argv[2] if len(sys.argv)>2 else "outputs")
json.dump(d["normalized_listings"], open(out/"normalized_listings.json","w"), indent=2)
json.dump({k: d[k] for k in ("universal_products","variants","offers","observations",
                             "resolution_history","quarantine","stats")},
          open(out/"catalog.json","w"), indent=2)
json.dump(d["resolution_decisions"], open(out/"resolution_decisions.json","w"), indent=2)
PY
verdict $? "outputs/{normalized_listings,catalog,resolution_decisions}.json written"

if python3 "$VALIDATOR" \
     --normalized "$OUTPUT_DIR"/normalized_listings.json \
     --catalog "$OUTPUT_DIR"/catalog.json \
     --decisions "$OUTPUT_DIR"/resolution_decisions.json >"$WORK/validator.json" 2>&1; then
  SUMMARY="$(python3 - "$WORK/validator.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print(f'{d["public_pairs_checked"]}/27 labelled pairs, {d["normalized_listings"]} listings, '
      f'{d["universal_products"]} products, {d["variants"]} variants, {d["offers"]} offers')
PY
)"
  ok "$VALIDATOR — $SUMMARY"
else
  bad "$VALIDATOR"; cat "$WORK/validator.json"
fi

# ---------------------------------------------------------------- 5. artifact acceptance checks
step "5. Artifact acceptance checks (SUBMISSION_CONTRACT requirements)"
python3 scripts/check_artifacts.py \
  --normalized "$OUTPUT_DIR"/normalized_listings.json \
  --catalog "$OUTPUT_DIR"/catalog.json \
  --decisions "$OUTPUT_DIR"/resolution_decisions.json \
  --policy "$PROVIDED_DIR"/data/IDENTITY_POLICY.json
verdict $? "all artifact conformance checks"

# ---------------------------------------------------------------- 6. replay idempotency
step "6. Replay idempotency"
REPLAY_OK=0
for f in "$WORK"/b0*.json "$WORK"/b1*.json; do
  ACC="$(curl -sf -X POST "$BASE/ingest" -H 'Content-Type: application/json' --data-binary @"$f" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["accepted"])' 2>/dev/null)"
  [ "$ACC" = "0" ] || { REPLAY_OK=1; echo "      $(basename "$f") reported accepted=$ACC"; }
done
verdict "$REPLAY_OK" "re-POSTing every batch reports accepted=0"
curl -sf "$BASE/evaluation/export" -o "$WORK/export2.json"
python3 -c '
import json,sys
a=json.load(open(sys.argv[1])); b=json.load(open(sys.argv[2]))
sys.exit(0 if json.dumps(a,sort_keys=True)==json.dumps(b,sort_keys=True) else 1)' \
  "$WORK/export.json" "$WORK/export2.json"
verdict $? "export is byte-identical after replay (incl. observations and history)"

# ---------------------------------------------------------------- 7. durable restart
step "7. Durable state across a completed-request restart"
BEFORE="$(curl -sf "$BASE/health")"
stop_service
if start_service; then
  AFTER="$(curl -sf "$BASE/health")"
  python3 -c '
import json,sys
b=json.loads(sys.argv[1]); a=json.loads(sys.argv[2])
sys.exit(0 if (b["events_stored"],b.get("catalog"))==(a["events_stored"],a.get("catalog")) else 1)' \
    "$BEFORE" "$AFTER"
  verdict $? "events_stored and catalog counts survive restart"
  curl -sf "$BASE/evaluation/export" -o "$WORK/export3.json"
  python3 -c '
import json,sys
a=json.load(open(sys.argv[1])); b=json.load(open(sys.argv[2]))
sys.exit(0 if a["stats"]==b["stats"] else 1)' "$WORK/export.json" "$WORK/export3.json"
  verdict $? "catalog rebuilt identically from the durable event log"
else
  bad "service did not restart against the same state dir"
fi

# ---------------------------------------------------------------- 8. /resolve
step "8. /resolve is lookup-oriented"
curl -sf "$BASE/evaluation/export" -o "$WORK/pre_resolve.json"
r_decision() {
  curl -sf -X POST "$BASE/resolve" -H 'Content-Type: application/json' -d "$1" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["decision"])' 2>/dev/null
}
D1="$(r_decision '{"url":"https://bestelectro.invalid/product/C7BF-XM6-5E8","title":"Auralux SilencePro XM6 Wireless Headphones Black","price":"$429.99","metadata":{"brand":"Auralux","model":"AL-XM6-B","color":"black"}}')"
D2="$(r_decision '{"url":"https://valuemart.invalid/product/P16-CONFIGURABLE","title":"PinePhone 16","price":"$799.00","metadata":{"brand":"PinePhone","color":"black","carrier":"unlocked"}}')"
D3="$(r_decision '{"url":"https://unknown.invalid/p/1","title":"Zephyr QuantumBlade 9000 Hyperdrive","price":"$1.00"}')"
[ "$D1" = "MATCH" ]    && ok "identified listing resolves to MATCH"            || bad "expected MATCH, got '$D1'"
[ "$D2" = "REVIEW" ]   && ok "missing price-critical dimension yields REVIEW"  || bad "expected REVIEW, got '$D2'"
[ "$D3" = "NO_MATCH" ] && ok "unknown product yields NO_MATCH"                 || bad "expected NO_MATCH, got '$D3'"
curl -sf "$BASE/evaluation/export" -o "$WORK/post_resolve.json"
python3 -c '
import json,sys
a=json.load(open(sys.argv[1])); b=json.load(open(sys.argv[2]))
sys.exit(0 if json.dumps(a,sort_keys=True)==json.dumps(b,sort_keys=True) else 1)' \
  "$WORK/pre_resolve.json" "$WORK/post_resolve.json"
verdict $? "/resolve mutated no exported state"

# ---------------------------------------------------------------- summary
printf '\n==========================================================\n'
printf ' ACCEPTANCE: %d passed, %d failed\n' "$PASS" "$FAIL"
printf '==========================================================\n'
[ "$FAIL" -eq 0 ] || exit 1
