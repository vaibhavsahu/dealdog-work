# curl cookbook

Copy-paste commands for every endpoint. Assumes the service is running:

```bash
export PORT=8080 DEALDOG_STATE_DIR=/tmp/dealdog-state
./run.sh &
./scripts/load_corpus.sh          # ingest the supplied corpus into THIS instance
```

`run.sh` rebuilds automatically when `target/dealdog.jar` is missing or older than anything in
`src/`, so it never serves a stale jar. Force a rebuild with `DEALDOG_FORCE_BUILD=1 ./run.sh`.

> **`load_corpus.sh` vs `generate_outputs.sh`** — `generate_outputs.sh` starts its *own* throwaway
> service on a temporary state dir in order to produce `outputs/` reproducibly; it does not touch
> the instance you are running by hand. Use `load_corpus.sh` to populate the service you started
> yourself, which is what the `/health`, `/resolve` and replay examples below assume.

Endpoint names, exactly: **`/health`**, **`/ingest`** (alias `/v1/ingestions`), **`/resolve`**,
**`/evaluation/export`** (alias `/v1/evaluation/export`). There is no `/evaluation/report` — that
path returns 404.

All examples below were verified against the supplied corpus.

---

## `GET /health`

```bash
curl -sf localhost:8080/health | python3 -m json.tool
```

```json
{
    "status": "ok",
    "state_dir": "/tmp/dealdog-state",
    "events_stored": 147,
    "catalog": {"listings": 140, "universal_products": 24, "variants": 56, "offers": 98}
}
```

Returns 200 once the durable store is open and the adapters are registered.

`events_stored` counts **distinct durable events**, keyed by `(delivery identity, payload hash)`.
Replaying a batch verbatim does not increase it — a byte-identical redelivery is the same event.
A repeated delivery identity carrying *mutated* bytes is a different row on purpose, so that a
rebuild reproduces the conflict quarantine.

After ingesting the supplied corpus this reads `147` (120 initial + 27 incremental records), and
stays at `147` no matter how many times `./scripts/verify_replay.sh` runs.

---

## `POST /ingest`

Full envelope (every field except `source` and `records` is optional):

```bash
curl -sf -X POST localhost:8080/ingest -H 'Content-Type: application/json' -d '{
  "source": "affiliate_a",
  "batch_id": "manual-test",
  "operation": "upsert",
  "event_id": "optional-event-id",
  "idempotency_key": "optional-key",
  "update_mode": "full_snapshot",
  "source_updated_at": "2026-08-10T12:00:00Z",
  "records": [{"record_id":"aa_9001","merchant":"BestElectro","merchant_sku":"X1",
               "product_name":"Auralux SilencePro XM6 Wireless Headphones Black",
               "sale_price":"399.99","retail_price":"449.99","currency":"USD",
               "ean":"00850000100011","manufacturer_part_number":"AL-XM6-B",
               "availability":"in_stock","condition":"new","promotion_text":"",
               "deep_link":"x","last_updated":"2026-08-10T12:00:00Z",
               "price_kind":"total_purchase_price","comparability":"COMPARABLE",
               "upstream_origin":"","product_type":"primary_product","exclusions":"[]"}]
}' | python3 -m json.tool
```

```json
{"accepted": 1, "quarantined": 0, "duplicates": 0, "corrected": 0,
 "rejected": 0, "received": 1, "batch_id": "manual-test", "source": "affiliate_a"}
```

Re-running the identical command returns `accepted: 0, duplicates: 1`.

`update_mode` accepts `full_snapshot`, `partial_patch`, `authoritative_correction`,
`historical_snapshot`, `listing_tombstone`. Source-shaped incremental events (elements carrying
their own `payload` plus `operation` / `event_id` / `corrects_listing_id`) are detected
automatically and each applied with its own envelope:

```bash
curl -sf -X POST localhost:8080/ingest -H 'Content-Type: application/json' \
  --data-binary @<(python3 -c '
import json
doc = json.load(open("../provided/data/incremental/incremental_phase_1.json"))
print(json.dumps({"batch_id": doc["batch_id"], "records": doc["events"]}))') \
  | python3 -m json.tool
```

To load the whole supplied corpus into the instance you are running, use
`./scripts/load_corpus.sh` (147 records: 120 initial + 27 incremental).

---

## `POST /resolve`

Lookup-only: it never creates or alters an entity, and repeated calls in any order return the same
logical result. Accepts `url`/`page_url`, `price`/`observed_price`, `metadata`/`attributes`, and
`identifiers`.

### 1. MATCH — an exact-scope MPN pins the variant

```bash
curl -sf -X POST localhost:8080/resolve -H 'Content-Type: application/json' -d '{
  "url": "https://bestelectro.invalid/product/C7BF-XM6-5E8",
  "title": "Auralux SilencePro XM6 Wireless Headphones Black",
  "price": "$429.99",
  "metadata": {"brand": "Auralux", "model": "AL-XM6-B", "color": "black"}
}' | python3 -m json.tool
```

Returns `"decision": "MATCH"`, `"confidence": 0.95`, a `variant_id`, `"candidate_count": 2`, and
`positive_signals` citing the identifier that carried the match.

### 2. REVIEW — product is clear, the price-critical dimension is not

```bash
curl -sf -X POST localhost:8080/resolve -H 'Content-Type: application/json' -d '{
  "url": "https://valuemart.invalid/product/P16-CONFIGURABLE",
  "title": "PinePhone 16",
  "price": "$799.00",
  "metadata": {"brand": "PinePhone", "color": "black", "carrier": "unlocked"}
}' | python3 -m json.tool
```

Returns `"decision": "REVIEW"` with `universal_product_id` set, **`"variant_id": null`**, and
**3 hypotheses** — the 128 / 256 / 512 GB variants. Storage is price-critical for `phones` and the
request does not supply it, so the system refuses to guess. This is the abstention behaviour the
brief asks for, and the single most useful call for demonstrating it.

### 3. Synonymous top-level fields

```bash
curl -sf -X POST localhost:8080/resolve -H 'Content-Type: application/json' -d '{
  "page_url": "https://glow-market.invalid/product/7C98-LLBC15-D36",
  "title": "Lumina Labs Bright-C 15% Vitamin C Serum 30 mL",
  "observed_price": "$42.00",
  "identifiers": {"mpn": "LL-BC15-30"},
  "attributes": {"volume_ml": 30, "formulation": "standard"}
}' | python3 -m json.tool
```

Returns `"decision": "MATCH"`, `"confidence": 0.95`.

### 4. NO_MATCH — nothing blocks, and no entity is invented

```bash
curl -sf -X POST localhost:8080/resolve -H 'Content-Type: application/json' \
  -d '{"url":"https://unknown.invalid/p/1","title":"Zephyr QuantumBlade 9000 Hyperdrive","price":"$1.00"}' \
  | python3 -m json.tool
```

Returns `"decision": "NO_MATCH"`, `"candidate_count": 0`. Critically, the catalog is unchanged —
see the read-only proof below.

### Response shape

```json
{"decision": "MATCH | REVIEW | NO_MATCH",
 "universal_product_id": "up:... | null",
 "variant_id": "var:... | null",
 "confidence": 0.95,
 "positive_signals": [{"canonical_field": "...", "source_field": "...",
                       "raw_value": "...", "note": "...", "effect": "positive"}],
 "negative_signals": [],
 "hypotheses": [{"universal_product_id": "up:...", "variant_id": "var:...", "score": 0.33}],
 "offers": [{"seller": "...", "condition": "new", "price": 429.99,
             "price_kind": "total_purchase_price", "comparability": "COMPARABLE",
             "promotion_terms": {}, "active": true, "observed_at": "..."}],
 "comparability": "COMPARABLE | CONDITIONAL | NOT_COMPARABLE | UNKNOWN",
 "candidate_count": 2,
 "scored_candidate_count": 1,
 "scored_candidate_ids": ["var:..."],
 "candidate_sources": {"mpn_index": ["var:..."], "token_index": ["up:..."]}}
```

### Proving `/resolve` is read-only

```bash
curl -sf localhost:8080/evaluation/export > /tmp/x1.json
# ... run all four resolve calls above, in any order ...
curl -sf localhost:8080/evaluation/export > /tmp/x2.json

diff <(python3 -m json.tool /tmp/x1.json) <(python3 -m json.tool /tmp/x2.json) \
  && echo "RESOLVE IS READ-ONLY"
```

Expect an empty diff.

---

## `GET /evaluation/export`

The full document is ~1.3 MB, so filter it:

```bash
# stats
curl -sf localhost:8080/evaluation/export \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["stats"])'
```

```json
{"catalog_entity_count": 178, "universal_product_count": 24, "variant_count": 56,
 "offer_count": 98, "listing_count": 140, "quarantined_count": 0}
```

```bash
# collection sizes
curl -sf localhost:8080/evaluation/export | python3 -c '
import json,sys; d=json.load(sys.stdin)
[print(f"{k:22} {len(v)}") for k,v in d.items() if isinstance(v,list)]'

# decision mix
curl -sf localhost:8080/evaluation/export | python3 -c '
import json,sys,collections
d=json.load(sys.stdin)
print(collections.Counter(x["decision"] for x in d["resolution_decisions"]))'

# one listing decision, in full
curl -sf localhost:8080/evaluation/export | python3 -c '
import json,sys
d=json.load(sys.stdin)
print(json.dumps([x for x in d["resolution_decisions"] if x["listing_id"]=="ab_0028"][0], indent=2))'

# the correction audit trail
curl -sf localhost:8080/evaluation/export | python3 -c '
import json,sys
for a in json.load(sys.stdin)["resolution_history"]:
    print(a["listing_internal_id"], a["prior_variant_id"], "->", a["new_variant_id"], "|", a["reason"])'

# whole document to a file
curl -sf localhost:8080/evaluation/export -o /tmp/export.json
```

---

## Durable-state restart

The evaluator performs completed-request restarts against the same state dir:

```bash
export DEALDOG_STATE_DIR=/tmp/dealdog-state PORT=8080
./run.sh &
curl -sf localhost:8080/health | python3 -c 'import json,sys; print(json.load(sys.stdin)["events_stored"])'
curl -sf localhost:8080/evaluation/export | python3 -c 'import json,sys; print(json.load(sys.stdin)["stats"])'
kill %1

./run.sh &                       # same state dir
curl -sf localhost:8080/health | python3 -c 'import json,sys; print(json.load(sys.stdin)["events_stored"])'
curl -sf localhost:8080/evaluation/export | python3 -c 'import json,sys; print(json.load(sys.stdin)["stats"])'
```

Expect identical `events_stored` and identical `stats` across the restart. Clusters, assignments,
hypotheses, tombstones, source epochs, dedupe identities and observation history are all rebuilt by
replaying the durable event log.

---

## Replay / idempotency

```bash
PORT=8080 ./scripts/verify_replay.sh
```

Re-POSTs every already-applied batch and byte-diffs the whole export. See `VALIDATION.md` §4 for
what this proves that the supplied validator's `--before-replay/--after-replay` mode does not.
