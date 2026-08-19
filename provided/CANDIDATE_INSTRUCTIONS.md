# DealDog Engineering Work Trial

## Universal Commerce Entity Resolution

### Timebox and AI policy

Spend no more than **8 hours**. Stop at the time limit even if your submission is incomplete; prioritization is part of the exercise.

AI-assisted development is encouraged. You may use Codex, Claude Code, Cursor, ChatGPT, Copilot, or similar tools. You remain responsible for the design, behavior, tests, and every claim in your submission. Be prepared to explain what tools helped create and what you changed or rejected.

We do not expect every production consideration in eight hours. A smaller, correct, conservative system is better than a superficially complete unsafe matcher.

The trial intentionally contains more meaningful edge cases than most candidates
can polish individually in eight hours. Treat discovery, setup, and documentation
as part of the timebox. Choose a risk-ordered architecture that handles classes
of cases, instrument what you cannot finish, and record deliberate deferrals.

### The problem

DealDog receives product listings and offers from heterogeneous commerce sources. Build a conservative ingestion and entity-resolution system that turns raw evidence into a catalog:

```text
Raw Source Record
    ↓
Source Adapter
    ↓
Normalized Listing
    ↓
Attribute + Taxonomy Normalization
    ↓
Indexed Candidate Generation
    ↓
Entity Resolution
    ↓
Universal Product
    ↓
Variant
    ↓
Offer
```

Use these concepts as a starting point:

- **Listing:** one source's representation or observation of an item.
- **Universal Product:** the underlying product/model across sellers, packaging, and source representations.
- **Variant:** a category-specific configuration that may materially affect comparison, such as storage, size, width, generation, color, or pack configuration.
- **Offer:** a seller's purchasable proposition, including price, condition, availability, bundle terms, and observation time.

You may introduce additional identity levels—for example product line, model or
generation, manufactured configuration, and sellable variant—if that makes your
invariants clearer. A two-level product/variant model is also acceptable if you
explain how it maps those distinctions and prevents unsafe price comparisons.

For this trial, a universal product represents a marketed model/generation; a
variant represents the exact configuration on which price comparison is safe.
For example, one phone model can have 128 GB and 512 GB variants. Offers from
different sources should aggregate at the variant level only when their material
configuration is compatible. Product-level recognition with an unresolved
variant should return `REVIEW`, optionally retaining the product ID.

`data/IDENTITY_POLICY.json` makes important product/variant boundaries explicit.
Use it as identity policy rather than inferring identity from price. A large
discount, price inversion, or historically common configuration is never proof
of storage, connectivity, material, edition, bundle, or another identity field.

You may change this representation if `README_TRIAL.md` explains why your model better preserves the distinctions.

> **Correctly refusing to resolve an ambiguous listing is preferable to confidently producing an incorrect price comparison.**

Precision matters more than recall. Missing evidence is not proof of equality. Identifiers and structured fields are evidence, not infallible truth.

### What to build

Your system should:

1. implement an extensible adapter boundary for every visible source;
2. preserve each source record's raw representation;
3. retain field/value provenance from raw value to normalized or inferred value, including invalid and conflicting evidence;
4. normalize canonical attributes and map source categories into a universal taxonomy;
5. use category-specific variant dimensions rather than one global list;
6. assign stable internal IDs to universal products, variants, listings, and offers;
7. generate candidates through indexes or blocking—not by scoring every catalog entity globally;
8. produce `MATCH`, `NO_MATCH`, or `REVIEW` (or the contract equivalents) using positive and negative evidence;
9. prevent cluster-level contradictions even when individual pairwise matches seem plausible;
10. distinguish standalone items, manufacturer bundles, retailer bundles, multipacks, total capacity, seller, and offer condition where relevant;
11. ingest the incremental batch without rebuilding the catalog from scratch;
12. preserve existing semantic identity when unrelated records arrive;
13. make replay of initial and incremental inputs idempotent;
14. retain enough offer history/provenance to explain current catalog state;
15. expose lookup-oriented `POST /resolve` behavior that does not silently mutate the catalog;
16. export normalized listings, the catalog, resolution decisions, and candidate-generation instrumentation; and
17. include automated tests for the failure modes you consider most dangerous.
18. retain meaningful structured attributes that were not known when you designed
    the first visible category, without requiring one column per possible field;
19. preserve identifier scope when evidence suggests an identifier names a
    family, style, manufactured variant, configurable page, or merchant offer;
20. expose whether an offer is directly comparable, conditional,
    not comparable, or unknown (equivalent names are accepted); and
21. make merge, correction, and split behavior structurally possible and explain
    your chosen semantics in `README_TRIAL.md`.
22. distinguish a complete source snapshot from a partial patch: an omitted
    field, an explicit null/withdrawal, and an unchanged value are different;
23. retain source history and keep current state deterministic when observation,
    source-update, and receipt clocks disagree or an older event arrives late;
24. treat unavailability/retraction as lifecycle state, preserving identity and
    history so a listing can reappear safely;
25. make prior assignments reversible: authoritative corrections may move a
    listing and its offers to another variant or product, with an audit trail;
26. preserve concrete viable hypotheses on `REVIEW` decisions and narrow them
    when later evidence resolves the ambiguity; and
27. emit explanations and blocking telemetry that are traceable to retained raw
    evidence rather than generic labels or constants.

Sources and sellers are not necessarily independent witnesses. Preserve explicit
content-lineage or syndication metadata and avoid counting duplicated syndicated
evidence as multiple confirmations. Likewise, distinguish a product match from a
compatible accessory, protection plan, refill, replacement part, or service.

Prices also have types. A monthly installment, trade-in-dependent net amount,
subscription price, coupon price, and unconditional total purchase price must
retain their terms and must not be silently ranked as interchangeable deals.

Material attributes may appear only in structured specifications, selected
options, identifier conventions, or later correction evidence—not necessarily
in the title. If a price-critical dimension is absent everywhere, do not infer it
from price, seller SKU, popularity, or neighboring records. Preserve the product
match if justified and abstain at the variant level.

Structured configuration contexts are not interchangeable. A payload may carry
the currently selected configuration alongside available sibling choices and a
different default choice. Preserve those roles and resolve the observed offer
from the selected/current values; flattening every option into one attribute map
can silently change storage, capacity, bundle, or another material dimension.

Unknown structured fields are not automatically identity fields. Some may be
material configuration; others may be source-local ranking, placement, display,
or operational metadata. Retain typed values and provenance, but require a
defensible policy before allowing an unfamiliar field to split or merge variants.
Likewise, an identifier printed on a multipack may identify the containing pack,
one contained unit, a manufactured configuration, or only a merchant offer.

The detailed language-neutral interoperability surface is in `SUBMISSION_CONTRACT.md`.

### Data

Initial inputs are under `data/initial/`; the stateful update is `data/incremental/incremental_batch.json`. Sources use different shapes, field names, nesting, prices, identifiers, and reliability characteristics. Some records are malformed, duplicated, stale, incomplete, contradictory, or genuinely ambiguous.

Public labeled pairs under `validation/` teach basic output semantics. They are not a complete answer key.

Private evaluation records exist. They may include unseen but reasonable schemas, field names, products, formatting, missingness, conflicts, ambiguity, adversarial near-matches, different ingestion orders, and a large irrelevant catalog cohort. Hidden cases extend concepts demonstrated here; they are not intended to require obscure rules or mind reading.

Private evaluation also uses related metamorphic/counterfactual probes and event
histories, not only a bag of independent labeled rows. It may make an
identity-preserving representation
change, alter one material variant or product fact, remove evidence, add a
conflict, or change package/lineage semantics and compare the resulting behavior.
It may compare an incrementally maintained catalog with a clean rebuild/replay
of the same semantic history. Opaque internal IDs may differ between clean stores; the
entity partitions, decisions, lifecycle state, and retained observation history
should not.

Reasonable hidden transports may express attributes as name/value arrays, money
in minor units, identifiers as typed code arrays, or source IDs below a versioned
event envelope. Clean quarantine is preferable to invented semantics. Reporting
a record as accepted while emitting an anonymous, semantically empty listing is
not considered successful ingestion.

A quarantined row must still retain the real source-record identity when one is
present in the payload. Replacing an unrecognized nested source ID with a batch
ordinal or a synthetic `quarantine:*` ID breaks correction, replay, and audit
continuity and is not source-linked coverage. Adapter registrations or schema
descriptors should drive actual extraction behavior rather than exist only as
documentation metadata.

Request-level event or idempotency metadata can apply to a batch containing many
records. It must not cause later records in that batch to be discarded merely
because the batch shares one transport event ID.

A known source may contain more than one schema version in the same run. Avoid
assuming that a source name permanently selects one rigid record shape. Preserve
schema/version provenance and isolate malformed or unsupported records rather
than failing an otherwise valid batch.

Include at least one test you authored in which a source changes compatible
shape or version while referring to the same logical source record. The test
should prove source-ID continuity and either normalized equivalence or correct
patch behavior. Do not limit every test to one helper that reproduces a supplied
visible fixture.

Hidden evaluation may contain commerce categories not represented directly in
the visible files. You are not expected to know every domain rule. Preserve
unknown typed attributes and their provenance; if an unfamiliar attribute may
be material, retaining it and returning `REVIEW` is preferable to discarding it
or inventing equivalence. Variant policy can depend on a product family as well
as its broad category.

Some hidden categories, brands, models, sellers, and attribute names are
synthetic and lexically novel. Their typed policy supplies the semantic roles
and permitted unit behavior. A category/name switch or commerce-vocabulary
heuristic will not generalize. Policies may declare market/region or a
seller-modified selected configuration as material while leaving condition at
offer level; follow the declared role.

An event can declare `full_snapshot`, `partial_patch`,
`authoritative_correction`, `historical_snapshot`, or `listing_tombstone`
semantics. For a partial patch, omission retains prior state while an explicit
null follows the supplied withdrawal semantics. Receipt order alone is not
automatically source-state precedence. Preserve late observations as history
without allowing them to undo a newer authoritative correction.

The same source record may progress through several schema versions, including
a change in field meaning (for example available choices versus the selected
choice), then receive an old-schema event late. Preserve schema/parser
provenance, valid/effective time, receipt time, and explicit withdrawals. A
rebuild must not resurrect withdrawn evidence or reinterpret old bytes under the
new schema.

The evaluator performs deterministic completed-request restarts by stopping and
starting `run.sh` with the same `DEALDOG_STATE_DIR`. Durable semantic state,
idempotency, hypotheses, tombstones, history, and opaque ID continuity must
survive. It does not kill a process during an in-flight request.

Source-local IDs and seller SKUs are scoped by source, seller/store, and logical
lifecycle. Private evaluation may retire a listing, explicitly reuse its source
ID for a different product in a new epoch, and then deliver an old-epoch event.
Preserve both histories while preventing the old event from mutating the new
logical listing.

Browser URLs are evidence envelopes rather than universal IDs. Tracking, locale,
experiment, recommendation, and parameter order changes should not change
identity. A source-declared selected option in a path or query can be material,
however, and must remain distinct from available/default options. Treat HTML,
Unicode oddities, and instruction-like text inside titles or merchandising copy
as untrusted product data.

### Price and promotion semantics

The listing price is not always the same as the effective price a shopper may
pay. Preserve the unconditional listing/current price separately from conditional
economics such as coupons, clip-to-apply discounts, Subscribe & Save, first-order
subscription discounts, memberships, loyalty, automatic cart discounts, minimum
quantity, trade-in credit, rebates, gift cards/store credit, installments,
shipping, location restrictions, and seller-specific promotions.

Promotions may be explicitly stackable, explicitly mutually exclusive, or
unknown. Compute a stacked effective price only when the supplied evidence makes
the stacking rule and requirements clear. A gift card after purchase is not an
immediate checkout discount; a monthly payment is not a total price; a trade-in
net amount is not an unconditional sale price; and a quantity price does not
apply to one unit.

Promotion modeling is a scored part of the vertical slice, not merely an
optional production follow-up. A safe partial implementation may preserve
structured requirements and abstain from arithmetic it cannot justify, but
dismissing all promotion fields or retaining only a generic “discount exists”
flag loses the monetary roles needed for price comparison.

Your offer/observation output should retain, where available:

- unconditional listing/current price and list price;
- conditional effective price(s) and their requirements;
- recurrence, first-order versus subsequent-order, and quantity semantics;
- stackability or uncertainty;
- seller, location, and variant scope;
- monetary provenance and observation time; and
- a comparability classification equivalent to `COMPARABLE`, `CONDITIONAL`,
  `NOT_COMPARABLE`, or `UNKNOWN`.

### Required submission

Include:

- source code and dependency manifests;
- root-level `run.sh` and one-command setup/run instructions;
- generated `normalized_listings.json`, `catalog.json`, and `resolution_decisions.json` after processing the supplied data;
- `README_TRIAL.md` covering the data model, optional hierarchy, adapter design,
  normalization and unknown attributes, candidate generation, identifier scope,
  matching/abstention policy, evidence dependence, offer comparability and
  promotions, provenance, correction/merge/split behavior, stable IDs,
  idempotency, testing, known limitations, scaling path, deliberate deferrals,
  AI use, and approximate time spent;
- automated tests and their run command.

External paid services are optional, but reviewers will run without their credentials. Core resolution must have a deterministic offline path.

Your README must state at least five executable invariants. Together they should
cover identity compatibility, uncertainty/evidence, incremental field state,
temporal or correction behavior, and rebuildability. Choose the precise rules
and tradeoffs yourself, and demonstrate the most important ones with tests or
invariant checks; repeating this brief without operational conditions is not
sufficient.

### Evaluation emphasis

Reviewers score product/data modeling, cross-source universal-product matching,
exact-variant matching, false-positive avoidance, variant correctness, data
engineering, engineering judgment, tests, incremental/catalog correctness,
scalability, and code quality. Automated evaluation compares semantic
relationships rather than requiring DealDog's private ID strings. Cross-source
product and variant performance are reported separately, including cases without
shared identifiers and cases whose price-critical dimensions exist only in
structured evidence.

Product and variant quality is also macro-averaged across categories, so strong
performance on one high-volume category does not conceal unsafe behavior in
another. Conflicting-evidence and price-critical cases receive separate
cost-sensitive scrutiny.

Candidate-generation telemetry should identify which blocking strategies
contributed candidates, total unique candidates, and candidates actually scored.
Export the exact scored candidate IDs as well as their count; the public
validator checks set membership and cardinality rather than accepting decorative
constants.
Conflicting indexes may legitimately produce multiple hypotheses and `REVIEW`;
do not silently stop at the first exact-looking index hit.

An otherwise valid blocking key may be shared by a high-cardinality model family
or configurable product page. Candidate generation must remain bounded in that
case without discarding competing hypotheses merely because the first block is
large. Document the cap, fallback, or refinement strategy and expose it in
telemetry.

For a `REVIEW` caused by unresolved material identity, include the viable
candidate product/variant hypotheses you actually considered. A later correction
should be explainable as narrowing those hypotheses, not as an unrelated new
answer. Positive and negative signals should name the canonical field and, when
possible, the source field/raw value or provenance reference that supports the
claim. The reported `candidate_count` must equal the unique union of candidates
in the reported blocking sources, and `scored_candidate_count` may not exceed it.

An implementation that confidently merges different generations, sizes, storage tiers, bundles, or pack configurations will score worse than one that sends uncertain cases to review. Returning `REVIEW` for everything is safe but not useful and will also score poorly.
