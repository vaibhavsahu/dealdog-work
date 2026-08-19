# Stage 1 — Source adapters

## Contract

```java
public interface SourceAdapter {
    String name();                                       // "affiliate_b"
    String schemaVersion();                              // "v1", "2026-08-compact", ...
    boolean supports(String source, JsonNode payload);   // source name + structural fingerprint
    RawExtraction extract(JsonNode payload);
}
```

`RawExtraction` is the only vocabulary the rest of the pipeline understands:

| Field | Meaning |
|---|---|
| `recordId` | the source's own record id (`record_id`/`eventId`/`observation_id`/`report_id`/`capture_id`) |
| `seller`, `merchantSku`, `title`, `brand`, `condition`, `availability` | offer + descriptive context |
| `attrs` | `AttrEv(key, TypedValue, sourceField, rawValue, derivation, unknown)` |
| `identifiers` | `IdentifierEv(ns, raw, canonical, scope, validity, sourceField)` |
| `money` | `MoneyEv(amount, listPrice, currency, priceKind, comparability, terms, availability, observedAt, validity)` |
| `contentOrigin` | syndication lineage, so copied evidence is not double-counted |
| `observedAt` / `sourceUpdatedAt` | evidence time and source-state time, kept separate |

## Selection is per record, not per source

`AdapterRegistry.select(source, payload)` returns the first adapter whose `supports()` matches,
most-specific first. This is what makes schema drift safe:

- `RetailerApiCompactAdapter` claims a record when `schema_version == "2026-08-compact"` **or** when
  `product.configuration` is a name/value array. It decodes minor-unit money (`minorAmount/100`).
- `RetailerApiV1Adapter` claims the classic `product.specifications` shape.

Both live under the source name `retailer_api`, and the supplied feed genuinely contains one
compact record (`ra_0016`) inside an otherwise v1 document. Because selection happens per record,
that record parses correctly and **no sibling record is affected**.

If no adapter matches, only that record is quarantined — a batch is never failed wholesale.

## Adding a new affiliate or retailer

1. Implement `SourceAdapter`.
2. Register it in `AdapterRegistry.defaultRegistry()`.

Nothing else changes: normalization, candidate generation, resolution, persistence and export are
all written against `RawExtraction`, never against a source name. A registered adapter that does
not actually drive source-id / field / money / provenance extraction is explicitly *not* an
adapter boundary; the registry is the only extraction path, used by both `/ingest` and `/resolve`.

## Quarantine keeps real identity

An unclaimed payload still yields a listing row. `Ingestor.sniffRecordId` walks the payload —
including nested/versioned envelopes — looking for a plausible source-record id, so a later
correction or patch for that record still joins. Synthetic `quarantine:*` ids are never
substituted for a real id; only when no id exists anywhere does the row fall back to a
content-addressed `unidentified:<hash>`.

Quarantined rows are exported with `quarantined_evidence` and an explicit `REVIEW` decision, so
every delivered record remains auditable.

## Implemented adapters

| Source | Schema | Notes |
|---|---|---|
| `affiliate_a` | `csv_v1` | CSV rows arrive as JSON objects with strings unchanged; `sale_price` may be junk (`"USD twelve??"`) → money marked `invalid`, never invented |
| `affiliate_b` | `feed_v3.7` | nested catalog, declared `identifierScopes`, structured `promotion` |
| `retailer_api` | `v1` | `product.specifications`, `offer.terms` |
| `retailer_api` | `2026-08-compact` | name/value `configuration[]`, minor-unit money |
| `community_deals` | `v1` | seller-entered text, partial identifiers, `requirements` as JSON-in-string |
| `extension_observations` | `0.9.x` | DOM capture; `selectedOptions` carries the **selected** configuration role |
| `browser_resolve` | `v1` | `/resolve` requests; accepts synonymous fields (`page_url`, `observed_price`, `identifiers`, `attributes`) |
