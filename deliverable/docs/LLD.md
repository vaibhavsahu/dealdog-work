# Low-Level Design — DealDog Entity Resolution Service

**Stack:** Java 17, Spring Boot 3.3 (web, no JPA — plain JDBC), SQLite via `org.xerial:sqlite-jdbc`, Jackson, JUnit 5.
**Version:** 1.0 (for approval). Companion to `HLD.md`.

---

## 1. Module / package layout

```text
dealdog-work-trial/
├── run.sh                        # root shim -> deliverable/run.sh (contract requires root-level)
├── outputs/                      # generated artifacts (contract names this path)
├── provided/                     # unmodified trial package: brief, contract, data/, validation/
└── deliverable/
    ├── run.sh                    # builds (if needed) and runs fat jar on $PORT, foreground
    ├── pom.xml
    ├── docs/                     # stage docs (see HLD §9)
    ├── scripts/                  # acceptance, load, generate, replay, checks
    ├── README_TRIAL.md
    └── src/main/java/com/dealdog/
    ├── Application.java
    ├── api/                      # controllers + DTOs
    │   ├── HealthController.java
    │   ├── IngestController.java        # POST /ingest, /v1/ingestions
    │   ├── ResolveController.java       # POST /resolve
    │   └── ExportController.java        # GET /evaluation/export
    ├── ingest/
    │   ├── IngestionService.java        # orchestrates envelope→adapter→normalize→resolve→store
    │   ├── EventEnvelope.java           # operation, event_id, idempotency_key, update_mode, clocks
    │   ├── UpdateModeApplier.java       # snapshot/patch/correction/historical/tombstone semantics
    │   └── QuarantineService.java
    ├── adapter/
    │   ├── SourceAdapter.java           # interface
    │   ├── AdapterRegistry.java
    │   ├── RawExtraction.java
    │   └── impl/
    │       ├── AffiliateACsvAdapter.java
    │       ├── AffiliateBAdapter.java
    │       ├── RetailerApiV1Adapter.java
    │       ├── RetailerApiCompactAdapter.java   # "2026-08-compact" name/value + minor units
    │       ├── CommunityDealsAdapter.java
    │       └── ExtensionObservationsAdapter.java
    ├── normalize/
    │   ├── NormalizedListing.java
    │   ├── TypedValue.java              # NUMBER | STRING | BOOL | STRUCT (+ raw json)
    │   ├── Provenance.java
    │   ├── AttributeNormalizer.java     # color, storage/capacity units, size, dept, model tokens
    │   ├── IdentifierService.java       # gtin checksum/zero-pad, namespaces, scopes
    │   ├── MoneyParser.java             # "$1,142.49", minor units, malformed → invalid provenance
    │   └── TaxonomyMapper.java
    ├── policy/
    │   ├── IdentityPolicy.java          # loaded from ../provided/data/IDENTITY_POLICY.json (packaged copy)
    │   └── CategoryPolicy.java          # + conservative DEFAULT policy for unknown categories
    ├── resolve/
    │   ├── CandidateGenerator.java      # runs all BlockingIndex impls, merges + telemetry
    │   ├── BlockingIndex.java           # interface: name(), candidatesFor(listing)
    │   ├── indexes/ GtinIndex, MpnIndex, BrandModelIndex, MerchantSkuIndex, ModelTokenIndex
    │   ├── EvidenceScorer.java          # positive/negative structured signals
    │   ├── ClusterGuard.java            # cluster-level contradiction check pre-merge
    │   └── DecisionEngine.java          # MATCH/REVIEW/NO_MATCH + hypotheses
    ├── catalog/
    │   ├── CatalogStore.java            # all SQLite access (single writer, serialized)
    │   ├── Ids.java                     # deterministic ID derivation
    │   └── model/ UniversalProduct, Variant, Offer, Observation, Decision, AssignmentAudit
    └── export/
        └── Exporter.java                # three artifacts + combined export document
```

## 2. SQLite schema (`$DEALDOG_STATE_DIR/dealdog.db`, WAL)

```sql
-- transport & idempotency
event_log(event_key TEXT PRIMARY KEY,        -- source|event_id (or batch synthetic for initial rows)
          idempotency_key TEXT, payload_hash TEXT, status TEXT,   -- applied|duplicate|conflict
          received_at TEXT, applied_at TEXT);

-- listings: one row per (source, source_record_id, epoch)
listing(internal_id TEXT PRIMARY KEY,        -- L:<source>:<record_id>:<epoch>
        source TEXT, source_record_id TEXT, epoch INTEGER DEFAULT 1,
        seller TEXT, lifecycle TEXT,          -- active|inactive(tombstoned)
        schema_version TEXT, adapter TEXT,
        raw_json TEXT NOT NULL,               -- latest full raw; earlier raws in listing_history
        current_attrs_json TEXT,              -- field-state map (see §5) after patches/corrections
        taxonomy_json TEXT, unknown_attrs_json TEXT,
        created_at TEXT, updated_at TEXT);
listing_history(id INTEGER PK, internal_id TEXT, event_key TEXT, raw_json TEXT,
                update_mode TEXT, observed_at TEXT, source_updated_at TEXT, received_at TEXT,
                schema_version TEXT, withdrawn_fields_json TEXT);
provenance(id INTEGER PK, internal_id TEXT, canonical_field TEXT, source_field TEXT,
           raw_value TEXT, normalized_value TEXT, derivation TEXT, validity TEXT,
           adapter TEXT, event_key TEXT);
quarantine(id INTEGER PK, source TEXT, source_record_id TEXT,  -- real ID whenever present
           reason TEXT, raw_json TEXT, event_key TEXT, created_at TEXT);

-- catalog
product(id TEXT PRIMARY KEY, taxonomy_json TEXT, attrs_json TEXT);
variant(id TEXT PRIMARY KEY, product_id TEXT, attrs_json TEXT);
assignment(internal_id TEXT PRIMARY KEY,      -- current listing→(product,variant) + decision
           product_id TEXT, variant_id TEXT, decision TEXT, confidence REAL,
           signals_json TEXT, hypotheses_json TEXT, candidates_json TEXT);
assignment_history(id INTEGER PK, internal_id TEXT, event_key TEXT,
                   prior_product_id TEXT, prior_variant_id TEXT,
                   new_product_id TEXT, new_variant_id TEXT,
                   reason TEXT, authority TEXT, changed_at TEXT);
offer(id TEXT PRIMARY KEY,                    -- O:<listing_internal_id>
      variant_id TEXT, product_id TEXT, seller TEXT, condition TEXT,
      price REAL, currency TEXT, price_kind TEXT, comparability TEXT,
      terms_json TEXT, active INTEGER, source_listing_ids_json TEXT);
observation(id INTEGER PK, offer_id TEXT, internal_id TEXT, event_key TEXT,
            idempotency_key TEXT, price REAL, price_kind TEXT, comparability TEXT,
            terms_json TEXT, availability TEXT, observed_at TEXT,
            variant_id_at_observation TEXT, product_id_at_observation TEXT);

-- blocking indexes (rebuilt rows maintained transactionally with listings)
block_key(index_name TEXT, key TEXT, entity_id TEXT, PRIMARY KEY(index_name,key,entity_id));
```

Everything the export needs is derivable from these tables; no semantic state lives only in RAM. The single-writer pattern (synchronized store) keeps SQLite happy and the evaluator is sequential anyway.

## 3. Deterministic IDs (`Ids.java`)

- `product_id = "up:" + sha1(category + "|" + canonical(product_dimension_values))` — e.g., `up:sha1(headphones|auralux|silencepro xm6|…)`.
- `variant_id = "var:" + sha1(product_id + "|" + canonical(variant_dimension_values))` (only price-critical + declared variant dims participate; absent dims excluded — a variant exists only when its price-critical dims are complete).
- `listing internal_id = "L:" + source + ":" + source_record_id + ":" + epoch`.
- Merges/corrections can re-point listings to different product/variant IDs; deterministic derivation means a clean rebuild reproduces the same partitions (opaque IDs may differ where evidence order matters, but partitions won't — the property the private evaluator compares).

## 4. Adapter contract

```java
public interface SourceAdapter {
    String name();                               // "affiliate_b"
    String schemaVersion();                      // "v1", "2026-08-compact", …
    boolean supports(String source, JsonNode payload);   // name + structural fingerprint
    RawExtraction extract(JsonNode payload, EventEnvelope env) throws AdapterException;
}
```

`RawExtraction` carries: `sourceRecordId` (from the transport's own ID field — mandatory; adapters that can find a nested/versioned ID must surface it even for otherwise-malformed rows so quarantine keeps real identity), `seller`, `List<AttributeEvidence>` (canonical or unknown key, TypedValue, source path, derivation), `List<IdentifierEvidence>` (namespace, value, declared scope from `identifierScopes`/`identifier_scopes`/`semantic_hints`, validity), `List<MoneyEvidence>` (amount, currency, price_kind, terms incl. structured promotions, comparability hint), availability, condition, `contentOrigin`, timestamps.

Selection: `AdapterRegistry.select(source, payload)` returns the highest-specificity adapter whose `supports()` matches (e.g., `RetailerApiCompactAdapter` requires `schema_version=="2026-08-compact"` or the `product.configuration[]` name/value shape; `RetailerApiV1Adapter` claims the classic shape). No match → quarantine with reason `no_adapter`, retaining any recognizable record ID. `configuration[]` arrays map to *selected* values; available/default sibling structures (if present) are retained as `unknown_attributes` context, never flattened into selected state.

CSV rows arrive as JSON objects (per contract) → `AffiliateACsvAdapter` handles string coercion (`sale_price: "USD twelve??"` → MoneyEvidence validity=`invalid`, listing keeps provenance and resolves at product level only; a price is never invented). HTML/unicode oddities in titles are sanitized for matching but raw text preserved untouched.

## 5. Field-state & update-mode semantics (`UpdateModeApplier`)

`current_attrs_json` is a map `field → {value, state, source_updated_at, event_key}` where `state ∈ {asserted, withdrawn, unknown_explicit}`.

| update_mode / operation | Behavior |
|---|---|
| `upsert` (no mode) / `full_snapshot` | replace fields within the event's authority scope; unmentioned fields outside scope untouched |
| `partial_patch` | only supplied fields change; omission keeps prior state; explicit null applies source `nullSemantics` → `withdrawn` |
| `authoritative_correction` / `correct` | as snapshot but flagged authoritative; supersedes timestamp order; always writes `assignment_history`; may narrow prior REVIEW hypotheses (recorded as narrowing, not a new unrelated answer) |
| `historical_snapshot` (or older `source_updated_at` than current within scope) | appended to `listing_history` + `observation`; does not regress current state |
| `listing_tombstone` / `unavailable` | lifecycle→inactive; offers deactivated; identity/history retained; later upsert on same epoch reactivates |

Clock precedence (documented + tested): within an epoch and authority scope, `sourceVersion/sourceSequence` > `source_updated_at` > (tie) keep-current; `received_at` is never precedence, only audit. Authoritative corrections beat all of the above; a *later* correction may revise them, again audited.

Idempotency: `event_key = source|event_id`. Duplicate key + same `payload_hash` → `duplicate`, no state change (observation table keeps the original event_id/idempotency_key so replay is inspectable). Same key + different hash → `conflict`: quarantine the second payload, keep the first applied, both retained. Distinct event IDs + identical bytes → two observations. Initial (non-enveloped) rows get `event_key = batch_id|source_record_id` so batch replay is also idempotent.

Epochs: tombstone closes epoch N. A later event for the same source ID that is *not* a reactivation-compatible upsert of the retired listing (explicit new-epoch marker, e.g. `lifecycleEpoch`, or an authoritative correction that redefines the product — like `PINE-CORRECTION-LOT-7` → PinePhone 17) opens epoch N+1 as a new logical listing. Old-epoch events route to epoch N's history and cannot mutate epoch N+1.

## 6. Normalization details

- **Category inference:** explicit source category if present, else keyword/attribute-shape classifier over brand/model/attrs (headphones, laptops, phones, consoles…), else `unknown` → DEFAULT policy. `xo_0004`'s wrong `source_category: computer_monitors` stays as evidence; GTIN match into the TV cluster generates a conflicting-taxonomy negative signal → conservative handling.
- **Units:** storage/capacity normalized to GB using declared policy only (1 TB = 1000 GB for these fixtures per `ab_0003` "1 TB"/`storage_gb:1000` evidence; conversion factor recorded in provenance; undeclared conversions in hidden policies are not assumed). Volume → ml (1 fl oz = 29.57 ml declared; `aa_0009` "1 fl oz" → 30 ml *nominal* with derivation=inferred). Money via `MoneyParser` (symbols, commas, minor units).
- **Identifiers:** GTIN checksum validation + zero-padding canonicalization (UPC-A→GTIN-14 only when checksum/zero-pad supports it); values with `?` → validity=`malformed` (negative-capable evidence, never a block key). Scope taken from source-declared `identifierScopes` (default: gtin/mpn=exact_variant, merchant_sku=merchant_offer). Scope caps the evidence level: `style_colorway` MPN or `universal_product_family` MPN or `configurable_offer` SKU support product-level identity only.
- **Multipack/bundle:** `bundle`, pack counts (`2_pack`, cartridge_count, quantity) are price-critical wherever policy says so; a contained-unit code on a pack keeps its declared scope.
- **Syndication:** `content_origin` recorded on listing + observation; EvidenceScorer collapses same-origin confirmations to one.

## 7. Candidate generation

Each `BlockingIndex` returns `(entity_id, key)` hits from `block_key`:

| Index | Key | Notes |
|---|---|---|
| `gtin_index` | canonical GTIN (valid only) | scope-aware |
| `mpn_index` | namespace-normalized MPN/style code | e.g. `ALXM6B` ≈ `AL-XM6-B` via alphanumeric squeeze |
| `merchant_sku_index` | seller + SKU | ties re-observations of the same page |
| `brand_model_index` | brand + normalized model token ("xm6", "peg41", "np5 slim") | product-level |
| `model_token_index` | fallback token n-grams | bounded |

Merged union → `candidate_count` (unique). Per-index caps (default 50) with refinement: an over-cap block re-blocks on an additional dimension before truncating, telemetry records `capped:true`. `scored_candidate_ids` = candidates surviving cheap compatibility pre-filter (category-compatible, not hard-contradicted); `scored_candidate_count = |scored_candidate_ids| ≤ candidate_count`. All decisions export `candidate_sources: {index_name: [ids…]}` — the validator's set-membership and cardinality checks pass by construction. Empty generation reports 0.

## 8. Resolution algorithm

```text
resolve(listing):
  cands = CandidateGenerator.generate(listing)          # products + variants w/ telemetry
  for c in cands.scored: score = EvidenceScorer.score(listing, c)
      positive: exact-scope identifier equality (validated), product-dim agreement,
                price-critical-dim agreement, seller+sku continuity
      negative: price-critical conflict (HARD), scoped-id conflict (HARD),
                bundle/pack mismatch (HARD), checksum-invalid id (soft),
                taxonomy conflict (soft), title-model-generation mismatch (HARD, e.g. XM5≠XM6, P15≠P16)
  productWinner = best product-level candidate without hard conflicts
  if none and no soft support → create new product+variant (ingest) / NO_MATCH (resolve API)
  if multiple un-dominated product hypotheses → REVIEW(product=null, hypotheses=all viable)
  else product = winner
     variantDims = policy(category).priceCritical ∪ policy.variantDims present
     if all price-critical dims known & consistent → variant = derive/match → MATCH
     if a price-critical dim absent/conflicting  → REVIEW(product_id, variant=null,
                                                    hypotheses = variants compatible with known dims,
                                                    complete set, never popularity-truncated)
  ClusterGuard: simulate merge; if any hard contradiction inside resulting cluster → REVIEW instead
```

Confidence: bounded evidence-weight ratio (documented formula, not ML). `MATCH` requires ≥1 structured positive signal (validator requirement) and zero hard negatives.

Public-case sanity anchors (used as fixtures): `aa_0002` vs `ab_0002` differ on storage 256/512 ⇒ same product, different variant; `ab_0011` (configurable SKU, 128 selected) vs `ra_0013` (512 selected) ⇒ same product, different variants; `aa_0001`(XM6 black) vs `ra_0003`(AirBuds) never share a block; `ab_0001` GTIN=black-XM6 GTIN but color=silver ⇒ GTIN-vs-attribute conflict → REVIEW until the `inc_011` correction supplies GTIN `…028`, which narrows/repairs via authoritative correction + audit trail.

`/resolve` runs this pipeline against a transient listing built by a `BrowserObservationAdapter` (title/url/metadata/synonymous fields; `context` is not identity evidence unless a semantic selected-configuration field is declared). No writes; response includes decision, ids, confidence, structured signals, hypotheses, offers on the matched variant, comparability, and full candidate telemetry.

## 9. Executable invariants (README_TRIAL.md; each has a test)

| # | Invariant | Test |
|---|---|---|
| I1 | **Variant safety:** two listings share a `variant_id` only if no price-critical dimension (per category policy) conflicts between their evidence | `VariantSafetyInvariantTest` scans final catalog |
| I2 | **Abstention:** a listing missing any price-critical dimension never receives a variant_id; decision is REVIEW with product ID + complete viable-variant hypotheses | `AbstentionInvariantTest` (fixtures: xo_0012 missing storage) |
| I3 | **Idempotent replay:** re-ingesting any applied batch byte-identically changes no exported state (counts and partitions equal) | `ReplayInvariantTest` (full export diff) |
| I4 | **Patch/null/omission distinction:** omitted field retains prior value; explicit null withdraws per nullSemantics; tombstone preserves identity+history and reactivation reuses the same listing | `FieldStateInvariantTest` |
| I5 | **Correction auditability:** every assignment change has an `assignment_history` row linking prior→new with triggering event; old observations keep `variant_id_at_observation` (no history rewrite) | `CorrectionAuditInvariantTest` (inc_018/019/020/021 chain) |
| I6 | **Rebuild equivalence:** replaying the full semantic history into a fresh state dir reproduces identical entity partitions, decisions, lifecycle states | `RebuildEquivalenceTest` |
| I7 | **Telemetry honesty:** for every decision, candidate_count == unique union of candidate_sources; scored ⊆ generated | `TelemetryInvariantTest` |

Plus the brief-mandated **schema-evolution continuity test**: the same logical `retailer_api` record delivered as v1 then compact shape (same `observation_id`+sku) keeps one listing identity, with parser provenance per event and normalized equivalence.

## 10. API notes

- `POST /ingest`: accepts contract envelope; also `/v1/ingestions`. Response: `{accepted, rejected, quarantined, duplicates, corrected}` per batch. Record-level failures never fail the batch.
- `GET /health`: 200 once store is migrated and adapters registered.
- `GET /evaluation/export`: combined document (normalized_listings, universal_products, variants, offers, observations, resolution_decisions, resolution_history, stats).
- `run.sh`: `mvn -q package` if jar absent (registry access is available at build time; jar committed too so reviewers can skip the build), then `exec java -jar target/dealdog.jar` with `PORT`/`DEALDOG_STATE_DIR` respected, foreground.
- Output artifacts written to `outputs/` by a small driver script (`scripts/generate_outputs.sh`) that boots the service against a fresh state dir, POSTs `data/initial/*` then the three incremental phases in order, and saves the three JSON files.

## 11. Test plan summary

Unit: adapters (each source + compact variant + malformed rows), MoneyParser, IdentifierService (checksums, `?` values), UpdateModeApplier matrix, epoch reuse, AdapterRegistry selection. Integration: full pipeline on supplied data → public validator passes; phase-ordered vs aggregate-batch ingestion equivalence; `/resolve` read-only property (export hash before/after). Invariants I1–I7. Command: `mvn test`.
