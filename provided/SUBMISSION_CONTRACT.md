# Submission interoperability contract

The evaluator is language- and database-agnostic. Field names below are the small required surface; you may add fields and APIs.

## Service lifecycle

Include a root-level executable `run.sh`. It must start the service on `PORT` (default `8080`), remain in the foreground, and become ready without paid credentials.

The evaluator sets `DEALDOG_STATE_DIR` to a writable directory. Durable
application state must live there (or in a candidate-configured store rooted
there) and survive a completed-request process stop/start. The evaluator starts
`run.sh` repeatedly against the same directory; it does not prescribe a database.
Essential clusters, assignments, hypotheses, tombstones, source epochs, dedupe
identities, and observation history must not exist only in RAM.

### `GET /health`

Return HTTP 200 when ingestion can begin.

### `POST /ingest` (or `/v1/ingestions`)

Accept:

```json
{
  "source": "affiliate_a",
  "batch_id": "initial-affiliate-a",
  "records": [{"source_specific": "raw objects"}],
  "operation": "upsert",
  "event_id": "optional-event-id",
  "update_mode": "full_snapshot | partial_patch | authoritative_correction | historical_snapshot | listing_tombstone",
  "source_updated_at": "optional ISO-8601 source clock",
  "received_at": "optional ISO-8601 transport clock",
  "authority": "optional field-scope metadata"
}
```

The harness converts CSV rows to JSON objects without changing strings. For incremental events it preserves `operation`, `event_id`, `idempotency_key`, and correction metadata in the request. Return accepted, rejected/quarantined, duplicate, and corrected counts. Replaying an identical batch must not create duplicate logical state.

Within one source/identity namespace, an `event_id` is an immutable delivery
identity. If that ID is later delivered with different bytes, reject, quarantine,
or retain an explicit conflict; do not apply both as independent state changes.
Conversely, identical payload bytes under two distinct legitimate event IDs can
represent two observations and must not be collapsed solely by payload hash.

An `event_id` or `idempotency_key` at request level may describe transport of a
multi-record batch. Idempotency must still preserve every distinct source record
inside that batch. A record counted as accepted must retain its source record ID
and meaningful normalized or quarantined evidence; anonymous empty acceptance is
not equivalent to successful ingestion.

When a payload contains a source-record ID in a reasonable nested or versioned
form, quarantine must preserve that ID. A generated batch ordinal, payload hash,
or `quarantine:*` identifier is not a substitute for source identity and will
not join later corrections or patches to the record. Internal IDs may be added
separately.

If `update_mode` is omitted on ordinary initial `upsert` data, treating the
record as a full source snapshot is acceptable. For `partial_patch`, omitted
fields retain the last applicable value. Explicit null is a supplied value and
must follow source `nullSemantics` (for example, withdraw a formerly asserted
attribute to explicit unknown); it must not be treated as omission. A
`historical_snapshot` or otherwise stale event remains in history but does not
regress newer applicable state. An `unavailable`/`listing_tombstone` event marks
the listing inactive without erasing its identity, evidence, or observations.

`observed_at` describes when an offer/evidence observation applied,
`source_updated_at` orders source state within its declared field/authority
scope, and `received_at` describes transport arrival. These clocks can disagree.
Document your deterministic precedence and tie-breaking policy. A later-arriving
older event must not automatically win merely because it was received last.

### `POST /resolve`

Accept a browser-style observation:

```json
{
  "url": "https://shop.example/item/42",
  "title": "Auralux XM6 headphones black",
  "price": "$429.99",
  "metadata": {
    "brand": "Auralux",
    "model": "XM6",
    "color": "Blk"
  }
}
```

The supplied evaluator may also use synonymous top-level fields such as `page_url`, `observed_price`, `identifiers`, and `attributes`. It may add an optional `context` object containing request IDs, evaluation pass, locale, or source hints. Operational context is not identity evidence unless the request explicitly declares a semantic selected-configuration field.

Return:

```json
{
  "decision": "MATCH | REVIEW | NO_MATCH",
  "universal_product_id": "candidate-defined id or null",
  "variant_id": "candidate-defined id or null",
  "confidence": 0.95,
  "positive_signals": [],
  "negative_signals": [],
  "hypotheses": [{"universal_product_id": "...", "variant_id": "...", "score": 0.72}],
  "offers": [],
  "comparability": "COMPARABLE | CONDITIONAL | NOT_COMPARABLE | UNKNOWN",
  "candidate_count": 2,
  "scored_candidate_count": 1,
  "scored_candidate_ids": ["candidate-1"],
  "candidate_sources": {"model_index": ["candidate-1", "candidate-2"]}
}
```

`status: matched | ambiguous | unresolved` is accepted as an alias for `decision`. A product-level ID with a null variant is allowed for a `REVIEW` response when product identity is clear but variant identity is not.

When `REVIEW` represents a finite ambiguity, return the viable hypotheses in
`hypotheses`, `candidate_hypotheses`, or `viable_candidates`. A hypothesis may
be an ID or an object with a product/variant ID, score, and evidence references.
Do not include candidates contradicted by known hard identity fields.
Do not arbitrarily truncate the viable set to a popularity-based top 3/5. The
private evaluator can leave dozens of variants viable. An explicit set is
acceptable; a documented exact constraint/count representation is also
acceptable if it proves completeness without a huge response.

This endpoint is lookup-oriented. It must not create or alter canonical entities merely because an unknown browser page was submitted. Repeated calls in a different order must return the same logical result and leave all exported application state—including audit, quarantine, promotion, and assignment-history collections—unchanged. Volatile request counters may be omitted from export.

Private evaluation records lookup latency after loading the large distractor
cohort. This is diagnostic rather than a machine-dependent pass/fail timeout,
but reviewers will inspect designs that clone, serialize, or export the complete
catalog on every lookup even when candidate generation is bounded.

### `GET /evaluation/export` (or `/v1/evaluation/export`)

Return the same document represented by the three required artifact files below. This is an evaluation hook, not a prescribed production endpoint.

## Required generated artifacts

After the supplied initial and incremental data are processed, include these JSON files in `outputs/`.

### `normalized_listings.json`

Top-level array. Each unique source listing should contain:

```json
{
  "listing_id": "source record id used by the input transport",
  "source": "affiliate_a",
  "source_record_id": "aa_0001",
  "raw": {},
  "taxonomy": {"category": "headphones"},
  "normalized_attributes": {"brand": "Auralux", "model": "XM6", "color": "black"},
  "unknown_attributes": {"source_specific_typed_field": 30},
  "provenance": [
    {
      "canonical_field": "color",
      "source_field": "product_name",
      "raw_value": "Blk",
      "normalized_value": "black",
      "derivation": "normalized",
      "validity": "valid"
    }
  ]
}
```

For interoperability, `listing_id` must be the source record ID (`record_id`, `eventId`, `observation_id`, `report_id`, `capture_id`, or the corresponding hidden-source ID). Add `internal_listing_id` if your storage uses another stable key. Provenance serialization may differ, but the evaluator must be able to determine source field, raw value, normalized/inferred value, and whether evidence is explicit, normalized, inferred, invalid, or conflicting.

### `catalog.json`

```json
{
  "universal_products": [{"id": "...", "taxonomy": {}, "attributes": {}}],
  "variants": [{"id": "...", "universal_product_id": "...", "attributes": {}}],
  "offers": [{"id": "...", "variant_id": "...", "seller": "...", "condition": "new", "price": 429.99, "price_kind": "total_purchase_price", "source_listing_ids": ["aa_0001"], "observations": []}],
  "stats": {"catalog_entity_count": 100}
}
```

Condition and seller should generally remain offer-level. Preserve source listing links and historical price/availability observations either nested under offers or in a top-level `observations` array. Each monetary observation should expose `price_kind` (or a documented equivalent) so total purchase, installment, trade-in, subscription, and conditional amounts remain distinguishable. Also expose `comparability` (or a documented equivalent) as `COMPARABLE`, `CONDITIONAL`, `NOT_COMPARABLE`, or `UNKNOWN`, plus structured promotion requirements when present. If your model differs, justify it.

### `resolution_decisions.json`

Top-level array with one row per unique source listing:

```json
{
  "listing_id": "aa_0001",
  "decision": "MATCH",
  "universal_product_id": "your-id",
  "variant_id": "your-id",
  "confidence": 0.94,
  "positive_signals": ["normalized model agrees"],
  "negative_signals": ["GTIN checksum invalid"],
  "candidate_count": 2,
  "scored_candidate_count": 2,
  "scored_candidate_ids": ["candidate-a", "candidate-b"],
  "candidate_sources": {
    "gtin_index": ["candidate-a"],
    "normalized_model_index": ["candidate-b"]
  }
}
```

Structured signal objects are preferred, for example:

```json
{
  "canonical_field": "storage_gb",
  "source_field": "product.specifications.storage",
  "raw_value": "512 GB",
  "normalized_value": 512,
  "provenance_ref": "prov-42",
  "effect": "positive"
}
```

A signal is an assertion, not decorative text: it must be supportable by the
retained source record and provenance. If a counterfactual removes or contradicts
that field, the explanation must stop claiming it as positive support.

Candidate counts are required instrumentation. They should describe entities generated/scored before the final decision, not a fabricated constant. `candidate_sources`, `blocking_strategies`, or an equivalent structure should show which indexes contributed hypotheses. Unknown or quarantined rows must still appear with `REVIEW` or `NO_MATCH`.

`candidate_count` must equal the unique union of candidate entity IDs reported
across `candidate_sources`/`blocking_strategies`; duplicate hits from two indexes
count once. `scored_candidate_ids` must name the exact subset actually evaluated
by the scorer, and `scored_candidate_count` must equal its unique size. Empty
candidate generation should report zero rather than a placeholder.

Every delivered source record must be auditable in the final export: retain its
raw or quarantined evidence and emit an explicit resolution decision. An ingest
response that reports a rejection without a source-record-linked evidence row
and decision is not complete coverage.

Observations created from incremental events must retain the source `event_id`
and `idempotency_key` (or a provenance reference resolving to both). This makes
duplicate suppression, source-version precedence, and repair review executable
rather than README-only claims.

The export endpoint may combine these as:

```json
{
  "normalized_listings": [],
  "universal_products": [],
  "variants": [],
  "offers": [],
  "observations": [],
  "resolution_decisions": [],
  "resolution_history": [],
  "stats": {}
}
```

Corrections and splits must be auditable either through top-level
`resolution_history`, `assignment_history`, `audit_events`, or `reassignments`,
or through equivalent fields on the listing decision. An audit entry should link
the listing, triggering event/evidence, prior product/variant assignment, new
assignment, reason/authority, and time. Active offers must follow the corrected
listing assignment; prior observations remain historical and must not become
offers on the wrong current variant.

For an observation recorded before a later reassignment, retain the assignment
known at observation/decision time (for example
`variant_id_at_observation`) or an equivalent versioned assignment reference.
Updating the listing's current foreign key must not rewrite every old price as
if it always belonged to the new variant. Preserve valid/effective time and
receipt time separately for retroactive corrections; a bounded older-window
correction must not replace a newer current fact.

Candidate IDs are opaque. Private evaluation compares partitions and stable relationships, never literal DealDog IDs.

`universal_product_id` should identify the marketed model/generation across
sources. `variant_id` should identify an exact configuration safe for offer-price
comparison. When product evidence is sufficient but a material variant dimension
is absent or conflicting, `REVIEW` with a product ID and null variant ID is the
preferred representation.

## Input transport

- CSV is sent one row per record with strings unchanged.
- JSON arrays are sent element by element.
- Documents containing `products`, `items`, `deals`, or `observations` are unwrapped at that array.
- Incremental events retain their source-shaped `payload` and operation metadata.
- Hidden sources may use new but reasonable structures. Explicit quarantine and a clean extension boundary receive credit; magical support for arbitrary JSON is not required.
- Multiple compatible schema versions can occur under one source name. One bad or unsupported record should not require discarding unrelated valid records from the same batch.
- The same logical source record can evolve v1→v2→v3 and an older-schema event
  can arrive after v3. Select the adapter from the event's declared original
  schema, preserve schema/parser provenance, and prevent stale or withdrawn
  evidence from becoming current during replay/rebuild.
- Schema evolution can change meaning, not only location. An `available` list is
  not a selected value merely because it appears first; respect selected,
  current, default, and available roles.
- Private typed policies can introduce lexically novel category, brand, family,
  model, merchant, and attribute names. Follow declared product/exact-variant/
  offer/descriptive roles rather than category-name conditionals.
- Measurement policy declares exact conversion factors, tolerance, nominal
  labels, or non-convertibility. Do not globally assume `1 TB == 1024 GB`,
  convert nominal labels as physical lengths, or infer undeclared mass/volume
  equivalence.
- Region, market, warranty, voltage, plug, radio-domain, condition, and
  seller-selected configuration fields are material only at the role declared
  by policy. Source authority is field/evidence-role specific; there is no one
  global manufacturer/retailer/marketplace priority for every fact.
- A declared adapter or registry entry that does not alter source-ID, field, money, or provenance extraction is not an implemented adapter boundary.

Asynchronous ingestion is allowed only if your documentation exposes a deterministic completion/polling mechanism. The default harness assumes completion when the response returns.
