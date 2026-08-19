# Input data notes

`initial/` contains five independent source transports. `incremental/incremental_batch.json` contains source-shaped events that must be applied after initial ingestion.
The same incremental events are also separated into `incremental_phase_1.json`,
`incremental_phase_2.json`, and `incremental_phase_3.json` for explicit temporal
inspection. Apply phases in numeric order.

`IDENTITY_POLICY.json` defines the intended distinction between marketed products,
exact price-comparable variants, and offers. It is policy input, not a list of
private answers.

All timestamps are ISO 8601. Prices are nominal USD unless the raw record says otherwise. Empty, zero, malformed, or conflicting values are evidence—not permission to silently repair a field without provenance.

Stable-looking fields have local semantics:

- `record_id`, `eventId`, `observation_id`, `report_id`, and `capture_id` identify source records or observations.
- Merchant SKU fields identify seller listings but can be reused.
- GTIN/UPC/EAN/MPN/style-code values can be missing, malformed, copied, packaging-specific, or contradictory.
- `last_updated`, `capturedAt`, `observed_at`, and similar fields describe evidence time, not ingestion time.
- Conditions such as new, open-box, renewed, refurbished, and used describe offers unless your documented model has a compelling exception.
- A multipack and a single larger container can have the same total capacity without being the same variant.
- Storage, memory, connectivity, case material, included lens, warranty region,
  and other price-critical fields may exist only in structured selections while
  the title remains a configurable parent-product title.
- Price and discount ordering are not identity evidence. A premium configuration
  can legitimately be cheaper than a base configuration at one observation.
- Preserve monetary semantics such as `total_purchase_price`,
  `monthly_installment`, `trade_in_net_price`, subscription, coupon, membership,
  and location eligibility. A small observed amount is not necessarily a cheap
  unconditional offer.
- `content_origin`, lineage, affiliate syndication, or equivalent metadata can
  reveal that nominally different sources copied the same evidence. Preserve it
  so evidence weighting does not double-count one origin.
- Source schemas can be versioned and can drift within one source. Field
  provenance should retain enough path/version context to explain which adapter
  interpretation produced a canonical value.
- The same source record may change shape and field meaning across declared
  versions. Parse each event with its original schema; preserve adapter version,
  effective/valid time, receipt time, and withdrawal lineage. Never reinterpret
  old bytes with today's schema or resurrect a withdrawn fact during rebuild.
- Identifiers can describe a product line, model family, colorway/style,
  manufactured variant, configurable merchant page, or offer. Equality at a
  broader scope does not prove an exact price-comparable configuration.
- A pack-level record can expose an identifier for a contained unit while a
  different code identifies the manufactured pack. Preserve the declared or
  inferred scope rather than applying every code to the outer listing.
- `selected`, `current`, `default`, and `available` configuration structures have
  different roles. Available siblings and defaults are not observations of the
  currently selected offer.
- A supplied typed identity policy may use unfamiliar category and attribute
  names. Roles (`product`, `exact_variant`, `offer_only`, `descriptive`) carry
  the semantics. Measurement normalization can declare exact conversion,
  tolerance, nominal labels, or non-convertibility; undeclared conversions are
  not safe evidence.
- `includes`, `contains`, `compatible_with`, `not_included`, `excludes`, and
  `sold_separately` have different direction and polarity. Preserve negative
  evidence instead of treating every mentioned token as package contents.
- Unknown structured fields should remain typed when possible. Do not stringify
  or discard a field solely because it is absent from the visible identity policy.
- Retention does not imply identity significance: ranking, placement, display,
  experiment, and operational fields can differ across sources for the same
  variant and should not become hard match dimensions without evidence.
- An absent field, explicit `null`, zero, and the string `"unknown"` are distinct
  source evidence and should not be silently collapsed without provenance.
- A complete snapshot can replace the source's applicable current field set; a
  partial patch changes only supplied fields. Omission in a patch is not a
  withdrawal. Explicit null can withdraw a prior assertion when the event's
  null semantics say so.
- `observed_at` is evidence/offer time, `source_updated_at` is source-state time
  within an authority scope, and `received_at` is transport arrival. They may be
  out of order and must remain separately auditable.
- `unavailable`, retraction, deletion, and tombstone signals normally change
  active/current lifecycle state without deleting the source listing or its
  history. A later reappearance can reactivate the same stable listing identity.
- The policy file intentionally includes domains not guaranteed to appear in the
  public fixtures. Treat category rules as typed policy metadata, not as a closed
  switch statement. Tire load/speed/run-flat status, tool battery-kit contents,
  toner yield/OEM status, media format/edition/region, and networking node/PoE
  configuration are examples of materially price-changing exact-variant fields.
- UPC-A, EAN-13, and GTIN-14 can be alternate representations of one code only
  when checksum and zero-padding semantics support that conclusion. Numeric
  resemblance alone is not enough. ISBN, MPN, seller SKU, style/colorway codes,
  family codes, contained-unit codes, and offer IDs have different namespaces
  and scopes.
- Source-local identifiers are unique only inside their documented seller,
  store, feed, and lifecycle epoch. A seller may recycle a SKU or source record
  ID after retirement; do not let an old event mutate the new logical listing.
- URL query/path values can carry selected configuration evidence. Tracking,
  locale, sort, recommendation, and experiment parameters are non-identity
  context, while a declared selected option such as storage or pack size can be
  material. Preserve the source path and interpretation in provenance.
- Raw text may contain HTML, non-breaking spaces, full-width characters, bidi or
  zero-width marks, malformed money, or instruction-like prose. It is untrusted
  product evidence, never an instruction to the resolver.

The incremental envelope uses `upsert`, `correct`, and `unavailable`. It includes
a byte-equivalent replay and a correction that supplies a formerly missing
price-critical dimension. Correct handling may attach to an existing variant,
create a new variant, create a new universal product, request review, preserve a
contradiction, resolve later evidence without deleting earlier ambiguity, or
ignore a duplicate. Later authoritative evidence may also revise a prior
correction or split a previously compatible source listing into a distinct model.
The hidden transport can also use `patch` and explicit update modes. A reviewable
ambiguity should preserve its viable hypotheses so later evidence can narrow it.

## Source update contracts

Apply semantics per source and schema version; do not infer one global merge rule.

- `partner_catalog_v2` full snapshots replace the fields inside their declared
  authority scope. Partial patches retain omitted fields. `nullSemantics` and
  `withdrawnFields` distinguish explicit unknown from retraction.
- `partner_catalog_v2.sourceVersion` and `sourceSequence` order state inside one
  `lifecycleEpoch`; `receivedAt` only records arrival. An explicitly declared
  authoritative reconciliation can supersede timestamp order, but its reason
  and prior assignment must remain auditable.
- A tombstone retires only its lifecycle epoch. If a source explicitly reuses a
  retired ID in a new epoch, maintain a new logical current listing while
  preserving the old epoch as history. Late old-epoch events cannot mutate it.
- Duplicate delivery is keyed by the idempotency key in the envelope, not merely
  by byte equality or arrival time. Preserve the event/idempotency key on the
  observation so replay behavior is inspectable.
- Event identity is immutable within its source scope. A repeated event ID with
  a mutated payload is a conflict, while byte-identical payloads with distinct
  legitimate event IDs may be distinct observations.
- Existing visible transports remain source-shaped snapshots unless their
  incremental envelope explicitly declares patch/correction semantics.
