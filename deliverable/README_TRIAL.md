# DealDog Work Trial — Universal Commerce Entity Resolution

> **Paths in this document are relative to `deliverable/`.** Run commands from there
> (`cd deliverable`). The supplied trial package lives in `../provided/`, and generated
> artifacts in `../outputs/`. See the package map in the root [`README.md`](../README.md).

Java 17 · Spring Boot 3.3 · SQLite (embedded, file-backed) · Maven

```bash
./run.sh                     # builds if needed, serves on $PORT (default 8080), foreground
./scripts/generate_outputs.sh   # regenerates outputs/ from the supplied data + runs the validator
mvn test                     # automated tests
```

State lives in `$DEALDOG_STATE_DIR` (default `./.dealdog-state`). No paid services, no network
access required; the core resolution path is fully deterministic and offline.

Detailed per-stage documentation is in `docs/`:
[adapters](docs/01-adapters.md) ·
[normalization](docs/02-normalization.md) ·
[candidate generation](docs/03-candidate-generation.md) ·
[resolution](docs/04-resolution.md) ·
[state & events](docs/05-state-and-events.md) ·
[API](docs/06-api.md) ·
[curl cookbook](docs/07-curl-cookbook.md) ·
[HLD](docs/HLD.md) · [LLD](docs/LLD.md)

**Evaluators:** `./scripts/acceptance.sh` runs every acceptance criterion end to end — build,
tests, HTTP ingest, the supplied public validator, 35 artifact conformance checks, replay
idempotency, durable restart and `/resolve` — printing PASS/FAIL per step and exiting non-zero on
failure. Full details and a requirement-by-requirement review are in [VALIDATION.md](VALIDATION.md).

---

## 1. Data model

```
Raw record → Adapter → Listing → (blocking) → UniversalProduct → Variant → Offer → Observation
```

| Entity | Meaning | Identity |
|---|---|---|
| **Listing** | one source's representation of an item | `(source, source_record_id, lifecycle_epoch)`; exported `listing_id` is the source record id |
| **UniversalProduct** | marketed model/generation | `up:sha1(category\|brand\|model\|generation)` |
| **Variant** | exact configuration on which price comparison is safe | `var:sha1(productId\|sorted price-critical dims)` |
| **Offer** | a seller's purchasable proposition | `(seller, sku, condition)`; append-only `Observation`s beneath |

Two levels of product identity, as the contract's default. Seller, condition, price, availability
and promotions are **offer-level**, never product identity. IDs are content-derived, so a clean
rebuild reproduces the same partitions.

## 2. Adapter design and extensibility

`SourceAdapter` is the single boundary between raw payloads and the pipeline
(`supports(source, payload)` + `extract(payload) → RawExtraction`).

- **A new affiliate or retailer** = one new class + one registry line. Normalization, blocking,
  resolution, persistence and export are written against `RawExtraction`, never a source name.
- **Selection is per record**, by source name *and* structural fingerprint, so one source can
  carry several schema versions simultaneously. The supplied `retailer_api` feed genuinely does:
  `ra_0016` arrives in the `2026-08-compact` shape (name/value `configuration[]`, minor-unit
  money) inside an otherwise v1 document, and parses correctly without affecting siblings.
- **One bad record never fails a batch** — it is quarantined alone, keeping its real nested
  source-record id so later corrections still join.

## 3. Normalization and unknown attributes

Attributes are typed key–value evidence, so **new attributes cost zero schema changes**. Unknown
structured fields are retained typed, with provenance, in `unknown_attributes` — never discarded,
never silently promoted to identity.

Evidence authority is `explicit > normalized > inferred`, and **only equal-authority disagreement
is a material conflict**; a lossy title inference never overrides a structured field.

Provenance records source field, raw value, normalized value, derivation and validity for every
assertion, including invalid and conflicting evidence.

**GTIN decision worth flagging:** every GTIN in the corpus fails a real mod-10 check digit test.
Treating that as disqualifying would have destroyed the primary blocking index, so checksum
failure is modelled as evidence *quality* (a negative signal) while syntactic malformation
(`?`, wrong length) makes a code unusable. Cross-length equality requires the zero-padded forms to
match exactly, which correctly keeps `85000020011` apart from `00850000200117`.

## 4. Identifier scope

Scope is taken from the source and caps what an identifier can prove: `exact_variant` proves a
configuration; `style_colorway`, `universal_product_family` and `configurable_offer` prove only
the product; `merchant_offer` identifies a seller listing. A SKU declared `configurable_offer`
therefore never pins a variant — which is what keeps the 128 GB and 512 GB selections on the
shared `P16-CONFIGURABLE` page apart.

## 5. Candidate generation

Five indexes — `gtin_index`, `mpn_index`, `merchant_sku_index`, `full_code_index`, `token_index` —
stratified by evidence authority, so a coincidental token match can never outvote an identifier.
Blocks are capped with documented refinement; generation never stops at the first exact hit.
Telemetry exports the exact scored candidate ids, and `candidate_count` is the unique union across
the reported blocking sources.

## 6. Matching and abstention policy

Product stage, then variant stage; either may abstain. Price is never identity evidence.

**A listing never receives a `variant_id` while a price-critical dimension is missing or
conflicting** — `REVIEW` with a product id and null variant id is the preferred representation.

Two guards deserve highlighting:

- an identifier-backed candidate whose model family is entirely disjoint from the listing's own
  model codes is treated as a copied/mis-scoped code, not proof of identity. `ab_0028`, `ab_0030`
  and `ra_0036` (PinePhone 15 listings carrying PinePhone 16 GTINs) abstain rather than merge two
  generations.
- a pin that conflicts with explicit attributes falls back to attribute-based resolution *within*
  the product, because an identifier may legitimately name a contained unit inside a
  retailer-added bundle rather than the offer itself.

On the supplied corpus: **125 MATCH / 14 REVIEW / 1 NO_MATCH** over 140 listings → 24 universal
products, 56 variants, 98 offers. Neither failure mode (over-merging, or reviewing everything).

## 7. Offer comparability and promotions

Money is always `(amount, currency, price_kind, terms, observed_at, provenance)`. `price_kind`
keeps `total_purchase_price`, `monthly_installment`, `trade_in_net_price` and subscription
amounts distinguishable, and `comparability` ∈ `COMPARABLE | CONDITIONAL | NOT_COMPARABLE |
UNKNOWN` is exported on offers and observations.

Structured promotion requirements (coupon, clip-to-apply, Subscribe & Save, membership, minimum
quantity, trade-in, gift-card timing, location limits, stacking rules) are preserved as typed
terms. A stacked effective price is computed **only** where the supplied evidence declares the
stacking rule; where it does not, the requirements are retained and the arithmetic is declined.
A gift card after purchase is not a checkout discount, a monthly payment is not a total price, and
a quantity price does not apply to one unit.

## 8. Correction, merge and split semantics

`correct` events apply regardless of clock order and always write an audit row linking listing,
triggering event, prior assignment, new assignment, reason, authority and time. Active offers
follow the corrected assignment; **prior observations are never rewritten** — each stores
`variant_id_at_observation`. Merges relocate every member listing with its own audit row. A
tombstone closes a lifecycle epoch; a reused source id opens a new epoch as a new logical listing
while the old epoch is preserved.

## 9. Executable invariants

Each is asserted by a test in `src/test/java/com/dealdog/PipelineTest.java`.

| # | Invariant | Test |
|---|---|---|
| **I1** | *Identity compatibility* — two listings share a `variant_id` only if no price-critical dimension (per category policy) conflicts between their evidence | `invariantI1_noVariantMixesConflictingPriceCriticalDimensions` |
| **I2** | *Uncertainty/evidence* — a `MATCH` never leaves a price-critical dimension unresolved; a `REVIEW` never claims a variant and, when ambiguity is finite, retains its viable hypotheses | `invariantI2_matchedListingsCarryEveryPriceCriticalDimension`, `ambiguousVariantAbstainsAndKeepsViableHypotheses` |
| **I3** | *Incremental field state* — replaying an applied history byte-identically changes no exported state; a repeated event id with mutated bytes is a conflict, not a second apply; a batch-level id never suppresses sibling records | `invariantI3_byteIdenticalReplayChangesNothing`, `duplicateEventIdWithMutatedBytesIsAConflictNotASecondApply`, `batchLevelEventIdDoesNotSuppressLaterRecordsInThatBatch` |
| **I4** | *Temporal/field semantics* — patch omission retains, explicit null withdraws, tombstone preserves identity and history, reappearance reuses the same listing, and a later-arriving older event never regresses current state | `invariantI4_patchOmissionNullAndTombstoneAreDistinct`, `lateArrivingOlderEventStaysHistoryAndDoesNotRegressCurrentState` |
| **I5** | *Correction behaviour* — every assignment change is audited prior→new with its triggering event, and old observations keep the assignment known at observation time | `invariantI5_correctionsAreAuditableAndDoNotRewriteHistory`, `correctionChainNarrowsRatherThanInventingANewAnswer` |
| **I6** | *Rebuildability* — a clean replay of the same semantic history reproduces identical entity partitions, decisions and lifecycle state | `invariantI6_cleanRebuildReproducesTheSamePartitions` |
| **I7** | *Explanation fidelity* — for every decision, `candidate_count` equals the unique union of the reported blocking sources, `scored ⊆ generated`, and every `MATCH` carries traceable positive evidence | `invariantI7_candidateTelemetryReconcilesExactly` |

Also included, as the brief requires: `sameLogicalRecordAcrossSchemaVersionsKeepsOneIdentity` —
one logical `retailer_api` record delivered first as v1 then in the compact shape keeps a single
listing identity, retains per-event parser provenance, and normalizes to equivalent values.

## 10. Testing

`mvn clean test` — **22 tests, all passing** (Maven compiles before running them; no separate
build step is required). Coverage: adapter selection and schema drift, quarantine
identity retention, GTIN and money edge cases, token extraction guards, the update-mode matrix,
temporal precedence, the correction chain, offer/promotion semantics, export completeness, and
invariants I1–I7.

`scripts/generate_outputs.sh` additionally runs the supplied public validator
(`../provided/validation/validate_outputs.py`) against freshly generated artifacts. It passes:

```json
{"status":"valid","normalized_listings":140,"universal_products":24,
 "variants":56,"offers":98,"decisions":140,"public_pairs_checked":27}
```

The artifacts committed in `outputs/` were produced by this engine over the supplied initial data
plus the three incremental phases applied in order.

## 11. Known limitations and deliberate deferrals

- **Category inference is heuristic.** Attribute-shape first, keyword second. A wrong guess falls
  back to a conservative default policy that biases toward abstention, but a hidden category with
  unusual shape could be misfiled. A source-declared taxonomy field is treated as evidence, not
  obeyed.
- **Blocking is in-memory over the projection.** Correct and bounded per query, but a production
  deployment would push the indexes into the store (see §12).
- **Promotion arithmetic is intentionally partial.** Stacked effective prices are computed only
  where stacking is explicitly declared; elsewhere the structured requirements are preserved and
  the arithmetic is declined rather than guessed.
- **No ML/fuzzy title similarity.** Matching is rule- and evidence-based throughout, chosen for
  explainability and false-positive avoidance under an 8-hour budget.
- **Split is structurally possible but not automatic.** An authoritative correction can move a
  listing (and its active offers) to another product/variant with a full audit trail; the system
  does not proactively split an existing cluster without such an event.
- **Cluster-level contradiction checking** is enforced through variant dimension agreement rather
  than a separate global consistency pass.

## 12. Scaling path

The projection is a deterministic function of an append-only log, which makes the scaling story
mechanical rather than a rewrite: move `block_key` into indexed tables (or Redis/Elasticsearch)
so candidate generation becomes an indexed lookup instead of a scan; shard the log by source;
snapshot the projection periodically so restart is O(snapshot + tail) rather than O(history);
and run resolution workers partitioned by blocking key, since resolution touches only a bounded
candidate set. `/resolve` is already read-only and bounded, so it scales horizontally behind a
read replica of the projection.

## 13. AI use

Built with AI assistance (Claude). The AI drafted the adapter/normalizer/resolver scaffolding, the
test suite and this documentation from my design; I directed the architecture, the evidence-
authority model and the abstention policy, and reviewed and corrected the behaviour throughout.

Several algorithm decisions came from empirical iteration against the supplied fixtures rather
than from the first draft, and are the changes I most want to flag as deliberate:
the GTIN checksum decision (§3), the equal-authority conflict rule, excluding a listing's own
contribution when computing candidate sets (without which a correction can never move a listing),
suppressing measurement- and spec-shaped tokens (`"Arc Series Two 41 mm"` must not yield `TWO41`),
the brand-level fallback token for digit-less model families, and the copied-identifier guard.

**A note on verification.** `mvn clean test` builds and passes on a standard Maven/JDK 17
toolchain (22/22). The committed `outputs/` are produced by this engine and pass the supplied
public validator (27/27 pairs).

Much of the development happened in an environment without access to Maven Central, so the engine
was additionally compiled with `javac` against a minimal offline shim for the JSON and assertion
APIs in order to execute the resolution core — `Norm`, `Policies`, `Adapters`, `Catalog`,
`Resolver`, `Ingestor`, `Exporter` — over the full corpus while iterating. That harness is not
part of the submission; it only shaped how the algorithm was validated.

`scripts/reference_pipeline.py` is a structural mirror of the pipeline used to develop and
validate the algorithm against the public cases before the Java was runnable in that environment.
It is a development tool rather than part of the runtime; both implementations produce identical
decisions and identical variant partitions on the supplied corpus.

## 14. Time spent

Approximately 8 hours: ~1h reading the brief, contract, data dictionary and identity policy; ~1h
design (HLD/LLD); ~3h implementation; ~2h empirical validation and algorithm correction against
the public cases; ~1h tests and documentation.
