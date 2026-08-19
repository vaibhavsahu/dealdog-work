# Stage 3 — Candidate generation (blocking)

No listing is ever scored against the whole catalog. Candidates come from five independent
indexes, each of which reports what it contributed.

| Index | Key | Yields | Strength |
|---|---|---|---|
| `gtin_index` | canonical GTIN-14 (not malformed) | variants | 3 (identifier-backed) |
| `mpn_index` | squeezed MPN/style code, `exact_variant` scope only | variants | 3 (identifier-backed) |
| `merchant_sku_index` | `seller + SKU` | variants, or products when the SKU is a configurable offer | 3 / 2 |
| `full_code_index` | full squeezed code (`VVQ5525`) | products | 2 |
| `token_index` | model-family root (`ALXM6`, `PEG41`) | products | 1 |

Strength is the *authority* of the evidence, not a similarity score. Product selection takes the
highest-strength stratum and only refines within it, so a coincidental token match can never
outvote an identifier.

## Identifier sets are computed excluding the listing itself

`variantGtins(v, exclude)` / `variantMpns(v, exclude)` / `productTokens(p, exclude)` /
`productCodeTokens(p, exclude)` all skip the listing being resolved.

This is essential, not an optimisation: a re-resolving listing would otherwise match its own
current product through the very tokens it just changed, and an authoritative correction could
never move a listing anywhere. It is what allows `ra_0021` to leave its PinePhone 16 product when
a correction rewrites it as a PinePhone 17.

## Configurable offers never pin a variant

A merchant SKU whose declared scope is `configurable_offer` addresses a parent page that carries
many configurations. Two listings sharing such a SKU are the same *page*, not the same
*configuration*. The index therefore contributes product-level candidates only.

Without this, `ab_0011` (128 GB selected) and `ra_0013` (512 GB selected) — both on
`P16-CONFIGURABLE` — would be forced onto one variant, which is exactly the unsafe price
comparison the brief warns about.

## Bounding

Each index is capped (default 50 hits). An over-cap block is refined on an additional dimension
before truncating, and the telemetry records that the cap was hit. Competing hypotheses are never
discarded merely because the first block was large, and generation never stops at the first exact
index hit — conflicting indexes legitimately produce several hypotheses and a `REVIEW`.

## Telemetry

Every decision exports:

```json
"candidate_count": 3,
"scored_candidate_count": 2,
"scored_candidate_ids": ["var:...","up:..."],
"candidate_sources": {"gtin_index":["var:..."],"token_index":["up:...","up:..."]}
```

By construction:

- `candidate_count` = size of the **unique union** across `candidate_sources` (a duplicate hit
  from two indexes counts once)
- `scored_candidate_ids` = the exact subset that reached the scorer (category-compatible, not
  hard-contradicted); `scored_candidate_count` = its unique size
- `scored ⊆ generated`
- empty generation reports `0`, never a placeholder

`PipelineTest.invariantI7_candidateTelemetryReconcilesExactly` asserts these relationships over
every decision in the corpus, so the numbers cannot drift into decoration.
