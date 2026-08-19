# Stage 4 — Resolution and abstention

Two stages, in order: choose the **universal product** (marketed model/generation), then choose
the **variant** (exact configuration on which price comparison is safe). Either stage may abstain.

Price is never identity evidence anywhere in this stage — not price level, not discount size, not
which configuration is cheaper.

## Product stage

1. Candidates are grouped by evidence strength (stage 3). Only the strongest stratum competes.
2. A candidate whose `generation` contradicts the listing's is dropped with a negative signal —
   this is what keeps `VVQ55-24` (2024) and `VVQ55-25` (2025) apart.
3. Category incompatibility drops the candidate.

### Contradicted identifiers do not merge models

If the winning candidate is identifier-backed but its model family is **entirely disjoint** from
the listing's own model codes, the identifier is treated as copied or mis-scoped rather than as
proof of identity. The listing abstains with the negative signal
*"identifier matches a product whose model family contradicts this listing's own model code"*.

This is the corpus's most dangerous trap: `ab_0028`, `ab_0030` and `ra_0036` are PinePhone **15**
listings carrying PinePhone **16** GTINs. All three go to `REVIEW` rather than silently merging
two phone generations. Brand-level fallback tokens are excluded from this comparison, since they
are too weak to establish a contradiction.

### Merging duplicate clusters

When several candidates are each independently supported (strength ≥ 2), compatible in category
and non-contradictory in generation, they describe the same marketed model reached by different
evidence, and are merged — with an audit row per relocated listing. No inter-product token overlap
is required, because the listing under resolution is itself the bridge.

If nothing matches and the listing is a committed primary product with real evidence, a new
product is created. A non-primary `product_type` (accessory, unknown) never merges into a primary
cluster; it abstains instead — which is how `cd_0012` (replacement ear cushions) is kept out of
the XM6 headphone cluster.

## Variant stage

Let `dims` = the price-critical and variant dimensions the listing actually asserts, per the
category policy.

| Situation | Outcome |
|---|---|
| a price-critical dim is *conflicted* (two explicit sources disagree) | `REVIEW`, product id kept, variant null, hypotheses = variants agreeing on the unconflicted dims |
| exactly one identifier-pinned variant, no attribute conflict | `MATCH` (confidence 0.95) |
| identifier-pinned variant **conflicts** with explicit attributes | fall back to attribute-based resolution within the product; unique → `MATCH`, otherwise `REVIEW` |
| one viable variant by attributes | `MATCH` (confidence 0.90) |
| several viable variants | `REVIEW` with **all** of them as hypotheses |
| none viable, and dims are known | create the variant, `MATCH` |

### Why a contradicted pin falls back instead of vetoing

An identifier can legitimately name something broader than the offer: a contained unit inside a
retailer-added bundle, a pack, or simply a copied code. `ra_0004` (standalone NovaPlay 5 Slim
Disc) shares GTIN `00850000400319` with `ab_0004` (the same console in a retailer bundle with a
second controller). Letting the pin veto good structured evidence would send a cleanly-specified
listing to review; letting it win would merge a bundle with a standalone unit. Falling back to
attribute resolution *within the product* does neither — and the negative signal records that the
identifier's scope was treated as broader than the offer.

**Abstention rule:** a listing never receives a `variant_id` while a price-critical dimension is
missing or conflicting. `REVIEW` with a product id and a null variant id is the preferred
representation, exactly as the contract asks.

## Signals

Signals are assertions, not decoration. Each names the canonical field and, where available, the
source field and raw value:

```json
{"canonical_field":"storage_gb","source_field":"product.specifications.storage_gb",
 "raw_value":"512","note":"price-critical dimension agrees with variant","effect":"positive"}
```

Because every signal is derived from retained evidence, a counterfactual that removes or
contradicts a field stops the corresponding claim from being emitted. A `MATCH` always carries at
least one positive signal.

## Hypotheses

A `REVIEW` caused by unresolved material identity returns the viable product/variant hypotheses
actually considered — the complete set, never truncated to a popularity-based top-N. A later
correction is then explainable as *narrowing* that set rather than as an unrelated new answer.

## Observed distribution on the supplied corpus

```
MATCH 125   REVIEW 14   NO_MATCH 1     (140 listings)
24 universal products · 56 variants · 98 offers
```

Both failure modes are avoided: the system neither merges aggressively nor reviews everything.
