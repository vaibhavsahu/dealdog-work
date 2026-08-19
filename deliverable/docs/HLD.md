# High-Level Design — DealDog Universal Commerce Entity Resolution

**Stack:** Java 17 + Spring Boot 3 · SQLite (durable state) · Maven
**Version:** 1.0 (for approval)
**Timebox context:** 8h work trial; design favors a smaller, correct, conservative system.

---

## 1. Goals and non-goals

### Goals
1. Ingest heterogeneous commerce sources (CSV, nested JSON, DOM captures, community reports) into a normalized catalog of **Universal Products → Variants → Offers**, with full raw + provenance retention.
2. **Precision over recall.** Ambiguity produces `REVIEW` with preserved hypotheses; a wrong price comparison is the worst failure mode.
3. **Open-world extensibility** — the explicit requirement for this design:
   - Adding a **new affiliate/retailer source** = registering one new adapter. Zero changes to normalization, resolution, storage, or export.
   - Adding **new attributes** = nothing to change. Attributes are stored as typed key–value evidence, not columns.
   - **New categories / unknown attributes** are governed by data-driven policy (IDENTITY_POLICY.json), not code switches.
   - **Schema drift inside a known source** (e.g., `retailer_api`'s `2026-08-compact` record sitting inside a v1 feed) selects a different adapter version per record; one bad record never fails a batch.
4. Stateful incremental ingestion: idempotent replay, update modes (`full_snapshot`, `partial_patch`, `authoritative_correction`, `historical_snapshot`, `listing_tombstone`), corrections with audit trail, lifecycle tombstone/reappearance, source-ID epochs.
5. Durable state in `DEALDOG_STATE_DIR` that survives process restarts.

### Non-goals (deliberate deferrals, documented in README_TRIAL.md)
- ML-based matching, fuzzy title embeddings — rule/evidence-based scoring only.
- Distributed operation, async ingestion, external services.
- Full promotion arithmetic in every case — we preserve structured promotion terms and abstain from arithmetic we cannot justify (contract explicitly allows this).

---

## 2. Architecture overview

```text
                          ┌──────────────────────────────────────────────┐
                          │              Spring Boot HTTP                │
                          │  GET /health   POST /ingest   POST /resolve  │
                          │           GET /evaluation/export             │
                          └───────────────┬──────────────────────────────┘
                                          │
                    ┌─────────────────────▼────────────────────────┐
                    │              IngestionService                │
                    │  envelope parsing · idempotency · update-    │
                    │  mode semantics · quarantine · event log     │
                    └─────────────────────┬────────────────────────┘
                                          │ raw record + envelope
                    ┌─────────────────────▼────────────────────────┐
                    │              AdapterRegistry                 │
                    │  per-record adapter selection by source name │
                    │  + schema fingerprint (versioned adapters)   │
                    │  AffiliateA, AffiliateB, RetailerApiV1,      │
                    │  RetailerApiCompact, CommunityDeals,         │
                    │  ExtensionObs, … (pluggable)                 │
                    └─────────────────────┬────────────────────────┘
                                          │ NormalizedListing (typed attrs + provenance)
                    ┌─────────────────────▼────────────────────────┐
                    │        Normalization & Taxonomy layer        │
                    │  canonical attribute normalizers · category  │
                    │  mapping · unknown attrs retained typed ·    │
                    │  identifier scoping · price/promotion parse  │
                    └─────────────────────┬────────────────────────┘
                                          │
                    ┌─────────────────────▼────────────────────────┐
                    │           CandidateGeneration                │
                    │  blocking indexes: gtin · mpn · brand+model  │
                    │  · merchant+sku · model-token — bounded,     │
                    │  multi-strategy, full telemetry              │
                    └─────────────────────┬────────────────────────┘
                                          │ candidates + per-index provenance
                    ┌─────────────────────▼────────────────────────┐
                    │            ResolutionEngine                  │
                    │  policy-driven evidence scoring →            │
                    │  MATCH / REVIEW(+hypotheses) / NO_MATCH      │
                    │  cluster-consistency guard · variant policy  │
                    └─────────────────────┬────────────────────────┘
                                          │
                    ┌─────────────────────▼────────────────────────┐
                    │            CatalogStore (SQLite)             │
                    │  products · variants · offers · observations │
                    │  listings · provenance · decisions ·         │
                    │  assignment_history · quarantine · epochs ·  │
                    │  event_log (idempotency)                     │
                    └─────────────────────┬────────────────────────┘
                                          │
                    ┌─────────────────────▼────────────────────────┐
                    │                Exporters                     │
                    │  normalized_listings.json · catalog.json ·   │
                    │  resolution_decisions.json · export endpoint │
                    └──────────────────────────────────────────────┘
```

## 3. Core entity model

Four levels (the contract's two-level product/variant model, plus listings and offers):

| Entity | Meaning | Identity |
|---|---|---|
| **Listing** | One source's representation of an item, scoped by `(source, source_record_id, lifecycle_epoch)` | source record ID from the transport (`record_id`/`eventId`/`observation_id`/`report_id`/`capture_id`); internal surrogate kept alongside |
| **UniversalProduct** | Marketed model/generation (e.g., "Auralux SilencePro XM6") | deterministic ID = hash of category + canonical product-dimension values |
| **Variant** | Exact configuration safe for price comparison (e.g., XM6 *black*) | deterministic ID = product ID + hash of canonical variant-dimension values |
| **Offer / Observation** | Seller's purchasable proposition at a time, with price kind, terms, comparability | per listing+seller+condition; observations append-only |

**Key rule:** listings resolve to a product and (only when every price-critical dimension for that category is known and un-conflicted) to a variant. Product known + variant unresolved → `REVIEW` with product ID, null variant, and the viable variant hypotheses preserved.

Deterministic content-hash IDs make clean rebuild vs incremental catalogs converge to identical partitions — directly serving the rebuild-equivalence probe.

## 4. Extensibility design (the heart of this trial)

### 4.1 New source ⇒ one adapter
`SourceAdapter` is the single boundary between raw payloads and the pipeline:

- `supports(sourceName, payload)` — adapters claim records via source name **and schema fingerprint** (required/known field shape), so one source can carry several coexisting schema versions and each record picks the right parser.
- `extract(payload, envelope)` → `RawExtraction` (source record ID, seller, typed attribute evidence with source-field paths, identifiers with declared scopes, money values with price kinds, timestamps, lineage/`content_origin`).
- Registration is a Spring bean — a new affiliate is a new class + registration, nothing else. A registered adapter that doesn't actually drive extraction is explicitly *not* an adapter (contract calls this out), so the registry is the only extraction path, used by both `/ingest` and `/resolve`.
- No adapter claims a record → **quarantine**, preserving any recognizable nested source-record ID (never `quarantine:*` synthetics), with a `REVIEW`/`NO_MATCH` decision row so coverage stays complete.

### 4.2 New attributes ⇒ no schema change
Normalized listings carry `Map<String, TypedValue>`: number/string/bool/structured, each linked to provenance (source field path, raw value, derivation: explicit | normalized | inferred | invalid | conflicting). Unknown-but-typed fields are retained in `unknown_attributes` with provenance. Storage is EAV-style in SQLite — no `ALTER TABLE` when a source invents a field. Unknown fields **do not** become identity dimensions without policy (they are retained, exported, and can trigger `REVIEW` if plausibly material).

### 4.3 New categories ⇒ policy, not code
`IDENTITY_POLICY.json` is loaded at startup into a `CategoryPolicy` registry: product dimensions, variant dimensions, price-critical dimensions per category. Resolution logic reads policy; there is no per-category `switch`. A category absent from policy gets a conservative default policy (product dims = brand+model-ish evidence; all structured config treated as potentially material ⇒ variant abstention unless single-candidate agreement). Hidden typed policies with novel names slot in because roles (`product` / `exact_variant` / `offer_only` / `descriptive`), not names, drive behavior.

### 4.4 Schema evolution within a source
Adapter selection is per record. Parser version + schema fingerprint are recorded in provenance. Old-schema events arriving late are parsed with their declared original schema (never reinterpreted), and versioned assignment references prevent stale evidence from becoming current.

## 5. Incremental & temporal semantics

- **Idempotency:** `(source, event_id)` and `idempotency_key` are recorded in an event log. Replayed identical batch = duplicate counts, zero state change. Same event ID + different bytes = conflict (quarantined, both retained as evidence). Identical bytes under distinct event IDs = two observations. Request-level event IDs never suppress later records within the same batch.
- **Update modes:** full snapshot replaces fields in its authority scope; partial patch changes only supplied fields (omission ≠ withdrawal; explicit null follows declared `nullSemantics`); historical snapshot lands in history without regressing newer state; tombstone flips lifecycle to inactive, preserving identity and history (reappearance reactivates the same listing).
- **Clocks:** `observed_at` (evidence time), `source_updated_at`/`sourceSequence` (source-state order within an epoch), `received_at` (transport). Deterministic precedence: source order wins within scope; receipt order never wins alone; late-arriving older events append to history but do not overwrite newer applicable state or undo authoritative corrections.
- **Corrections (`correct` + `corrects_listing_id`):** applied as authoritative evidence for the referenced listing; may re-assign listing + active offers to another variant/product. Every reassignment writes an `assignment_history` audit row (listing, trigger event, prior → new assignment, reason, time). Old observations keep `variant_id_at_observation` — history is never rewritten.
- **Epochs:** tombstone closes a listing's lifecycle epoch; a reused source ID opens a new epoch as a new logical listing; late old-epoch events attach to the old epoch only.

## 6. Candidate generation & resolution

- **Blocking indexes** (each contributes with telemetry): GTIN (checksum-validated, zero-pad-aware), MPN/style code (namespace-scoped), brand+category+model-token, merchant+SKU, and normalized model n-gram fallback. Bounded: per-index cap with documented refinement (never "first exact hit wins" — conflicting indexes yield multiple hypotheses).
- **Scoring** is evidence arithmetic per policy: positive (scoped identifier equality, agreeing product dims, agreeing price-critical dims) and negative (conflicting price-critical dims, checksum-invalid IDs, bundle/pack mismatch, condition/product-type mismatch). Syndicated evidence (`content_origin` lineage) counts once. Price is never identity evidence.
- **Decisions:** hard positive at variant scope + no conflicts ⇒ `MATCH`; product-level certainty with variant ambiguity ⇒ `REVIEW` (product ID + hypotheses); material conflict ⇒ `REVIEW` preserving the conflict; no viable candidate ⇒ new product/variant (initial ingest) or `NO_MATCH` (`/resolve`).
- **Cluster consistency:** before commit, a merge is checked against the target cluster's aggregate — any hard price-critical contradiction blocks the merge even if pairwise plausible.
- **Signals** are structured objects naming canonical field, source field, raw value, and provenance ref — assertions traceable to retained evidence, never constants.

## 7. Price & promotion semantics

Money is always stored as `(amount, currency, price_kind, terms, observed_at, provenance)`. `price_kind` (total_purchase_price, monthly_installment, trade_in_net_price, …) and `comparability` (COMPARABLE / CONDITIONAL / NOT_COMPARABLE / UNKNOWN) flow through to offers and export. Structured promotion requirements (coupon, subscribe & save, membership, min-quantity, stacking rules, gift-card timing) are preserved as typed terms; stacked effective prices computed **only** when stacking is explicitly declared (e.g., `explicitly_stackable` with supplied requirements).

## 8. Persistence & lifecycle

Single SQLite file at `$DEALDOG_STATE_DIR/dealdog.db` (WAL mode). All semantic state — clusters, assignments, hypotheses, tombstones, epochs, dedupe identities, observation history, quarantine — is durable and survives completed-request restarts. `run.sh` starts the Spring Boot fat jar on `$PORT` (default 8080) in the foreground; repeated starts against the same state dir resume cleanly.

`/resolve` is strictly read-only over catalog state (it runs the same adapter → normalize → candidates → score path but commits nothing; export state before/after is byte-identical; bounded candidate generation keeps lookups from scanning the catalog).

## 9. Documentation plan (per stage)

`docs/` will contain one focused document per stage, written alongside the code:

| Doc | Covers |
|---|---|
| `docs/01-adapters.md` | Adapter contract, registration, schema fingerprints, quarantine rules, how to add a source |
| `docs/02-normalization.md` | Typed attributes, provenance model, unknown attributes, identifier scopes, measurement policy |
| `docs/03-candidate-generation.md` | Index designs, caps/fallbacks, telemetry fields |
| `docs/04-resolution.md` | Evidence scoring, decision thresholds, cluster guard, hypotheses & narrowing |
| `docs/05-state-and-events.md` | SQLite schema, update modes, clocks, epochs, corrections, idempotency |
| `docs/06-api.md` | Endpoint contracts and examples |
| `README_TRIAL.md` | Required submission doc incl. ≥5 executable invariants, AI use, time spent, deferrals |

## 10. Testing & validation strategy

- Unit tests per stage (adapters incl. schema-version continuity test required by the brief; normalizers; GTIN checksum; update-mode semantics; epoch reuse; correction audit).
- Invariant tests (the ≥5 executable invariants — enumerated in the LLD §9).
- End-to-end: ingest initial + 3 incremental phases → export → run `../provided/validation/validate_outputs.py` (must pass); replay the incremental batch → export → replay-equality check.

## 11. Risk-ordered build plan (within the 8h timebox)

1. Skeleton + store + adapters for 5 visible sources + normalization/provenance (highest scoring leverage).
2. Candidate generation + resolution + policy engine + exports → validator green.
3. Incremental semantics: idempotency, corrections, tombstone, epochs, audit history.
4. `/resolve`, promotion terms polish, invariant tests, documentation.
5. Instrument-and-defer anything left, recorded in README_TRIAL.md.
