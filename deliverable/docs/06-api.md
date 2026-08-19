# Stage 6 — HTTP surface

> Ready-to-run commands for every endpoint, with verified expected output, are in
> [`07-curl-cookbook.md`](07-curl-cookbook.md).

`run.sh` starts the service on `PORT` (default 8080) in the foreground, with durable state in
`DEALDOG_STATE_DIR`. No paid credentials, no network dependency: the resolution path is fully
deterministic and offline.

## `GET /health`

`200` once the state store is open and adapters are registered.

```json
{"status":"ok","state_dir":"/tmp/xyz","events_stored":145}
```

## `POST /ingest` (also `/v1/ingestions`)

```json
{
  "source": "affiliate_a",
  "batch_id": "initial-affiliate-a",
  "records": [ { "...": "source-shaped objects" } ],
  "operation": "upsert",
  "event_id": "optional",
  "idempotency_key": "optional",
  "update_mode": "full_snapshot | partial_patch | authoritative_correction | historical_snapshot | listing_tombstone",
  "source_updated_at": "ISO-8601",
  "received_at": "ISO-8601"
}
```

Transport unwrapping accepts `records`, `events`, `items`, `products`, `deals`, `observations`, or
a bare top-level array. Source-shaped incremental events (elements carrying their own `payload`
plus `operation`/`event_id`/`corrects_listing_id`) are detected and each applied with its own
envelope.

Response:

```json
{"accepted":25,"quarantined":0,"duplicates":0,"corrected":0,"rejected":0,
 "received":25,"batch_id":"...","source":"..."}
```

Ingestion is synchronous: completion is signalled by the response returning, as the default
harness assumes. A malformed or unsupported record is quarantined individually and never fails
the batch.

## `POST /resolve`

```json
{"url":"https://shop.example/item/42",
 "title":"Auralux XM6 headphones black",
 "price":"$429.99",
 "metadata":{"brand":"Auralux","model":"XM6","color":"Blk"}}
```

Synonymous fields are accepted: `page_url`, `observed_price`, `identifiers`, `attributes`. An
optional `context` object is treated as operational context, **not** identity evidence, unless it
explicitly declares a semantic selected-configuration field.

```json
{"decision":"MATCH","universal_product_id":"up:...","variant_id":"var:...","confidence":0.95,
 "positive_signals":[{"canonical_field":"identifier","source_field":"identifiers.gtin",
                      "raw_value":"00850000100011","note":"...","effect":"positive"}],
 "negative_signals":[],
 "hypotheses":[],
 "offers":[{"seller":"BestElectro","condition":"new","price":429.99,
            "price_kind":"total_purchase_price","comparability":"COMPARABLE","promotion_terms":{}}],
 "comparability":"COMPARABLE",
 "candidate_count":2,"scored_candidate_count":1,"scored_candidate_ids":["var:..."],
 "candidate_sources":{"gtin_index":["var:..."],"token_index":["up:..."]}}
```

**Strictly lookup-oriented.** It runs the identical adapter → normalize → block → score pipeline
with `commit = false`: no entity is created or altered, nothing is written to the event log, and
repeated calls in any order return the same logical result and leave every exported collection —
audit, quarantine, promotion, assignment history — byte-identical. Candidate generation is bounded
by the same indexes, so a lookup never scans or serialises the catalog.

## `GET /evaluation/export` (also `/v1/evaluation/export`)

Returns the combined document backing the three artifact files:

```json
{"normalized_listings":[], "universal_products":[], "variants":[], "offers":[],
 "observations":[], "resolution_decisions":[], "resolution_history":[],
 "quarantine":[], "stats":{}}
```

## Generating the required artifacts

```bash
./scripts/generate_outputs.sh
```

Boots the service against a fresh state dir, POSTs the five initial sources and then the three
incremental phases **in order**, writes `../outputs/normalized_listings.json`, `../outputs/catalog.json`
and `../outputs/resolution_decisions.json`, and runs the public validator against them.
