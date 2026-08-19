# Stage 2 — Normalization, provenance and unknown attributes

## Typed values, not columns

Every attribute is a `TypedValue` (`number | string | bool | struct`) held in a per-listing map.
There is no column per attribute anywhere in the system, so a source inventing
`battery_capacity_ah` or a hidden category inventing a lexically novel field costs zero schema
changes. Values keep their type: a numeric spec stays numeric, a nested object stays a `struct`.

Keys the system recognises as canonical (the union of every dimension named by
`IDENTITY_POLICY.json`) land in `normalized_attributes`. Everything else lands in
`unknown_attributes` **with full provenance** — retained, exported, never silently dropped and
never silently promoted to identity.

> Retention does not imply identity significance. An unfamiliar structured field is preserved and
> may push a decision to `REVIEW`, but it does not split or merge variants without a declared
> policy role.

## Provenance

Each assertion emits a provenance row:

```json
{"canonical_field":"color","source_field":"item.variant.color","raw_value":"Blk",
 "normalized_value":"black","derivation":"normalized","validity":"valid",
 "event_key":"affiliate_b|inc_011|ab_0029","provenance_ref":"ab_0029-prov-7"}
```

- `derivation` ∈ `explicit` (structured source field) | `normalized` (parsed/canonicalised) |
  `inferred` (derived, e.g. fl oz → ml)
- `validity` ∈ `valid` | `invalid` | `conflicting`

### Evidence authority

`explicit > normalized > inferred`. **Only equal-authority disagreement is a material conflict.**
A title-parsed value that contradicts a structured field is retained as `conflicting` provenance
and discarded as state — it never forces `REVIEW`. This matters constantly in the corpus:
`ab_0002` has structured `screen_in: 13.6` while its title says "13-inch"; `ab_0015` has
`color: black/white` while its title says only "black".

Two *explicit* fields that disagree do mark the field conflicted, which suppresses a variant
match (see stage 4).

## Identifiers and scope

`IdentifierEv` records the namespace, the raw value, a canonical form, the **declared scope**, and
a validity.

Scope is taken from the source (`identifierScopes` / `identifier_scopes` / `semantic_hints`) and
caps how much the identifier can prove:

| Scope | Proves |
|---|---|
| `exact_variant` | exact price-comparable configuration |
| `style_colorway`, `universal_product_family` | product only |
| `configurable_offer` | product only — a parent page carrying many configurations |
| `merchant_offer` | a seller listing, reusable across lifecycles |

### GTIN handling — a deliberate decision

Every GTIN in the supplied corpus **fails a real mod-10 check digit test** (they are synthetic).
Treating checksum failure as a disqualifier would have destroyed the primary blocking index and
collapsed recall to near zero.

So the system separates *existence* from *quality*:

- syntactically well-formed → canonical GTIN-14 (left zero-padded), usable as a block key
- check digit fails → still usable, but emits the negative signal
  `"GTIN check digit does not validate; identifier used as weaker evidence"`
- contains `?` or wrong length → `malformed`, **not** usable as a block key, retained as invalid evidence

Cross-length equivalence only occurs when the zero-padded forms are literally equal. This is what
keeps the deliberate trap apart: `"85000020011"` pads to `00085000020011`, which is **not**
`00850000200117`. Numeric resemblance alone never merges two codes.

## Measurement policy

Conversions are applied only where the data declares them, and each records its factor in
provenance:

- `1 TB = 1000 GB` (the corpus asserts `"1 TB"` alongside `storage_gb: 1000`); marketing `1024GB`
  and `2048GB` map to the nominal `1000`/`2000` tiers
- `1 fl oz = 29.5735 ml`, snapped to nominal 30/50/100 ml with `derivation: inferred`
- No undeclared mass/volume/length equivalence is ever assumed

## Untrusted text

`Norm.clean` strips HTML tags, non-breaking/zero-width/bidi marks and full-width characters, and
normalises dash variants — for matching only. The raw string is always preserved verbatim on the
evidence object and in `raw`. Instruction-like prose inside a title is product evidence, never an
instruction to the resolver.

Money parsing rejects anything that is not cleanly numeric: `"USD twelve??"` yields
`amount: null`, `validity: invalid`. A price is never invented, and a listing with unparseable
money still resolves at product level.

## Token extraction

Model-family tokens drive blocking (stage 3):

- code-shaped tokens: `AL-XM6-B → ALXM6`, `VVQ55-25 → VVQ55`, `AR41M-BW-11 → AR41`, `P16-256-BLK → P16`
- word+number tokens: `"Pegasus 41" → PEGASUS41`
- full codes (`VVQ5525` vs `VVQ5524`) are kept separately to discriminate generations

Guards learned from the corpus:

- single-letter heads stay valid (`P16`, `V12` are real models)
- spec-shaped tokens are excluded by name (`M2`, `PCIE4`, `USBC`, …) — a form factor is not a model
- a number followed by a unit is a measurement, not a model: `"Arc Series Two 41 mm"` must not
  yield `TWO41`
- when a family has no digits at all (`"Quanta NVX Pro"`), a brand-level fallback token is used so
  the listing can still be blocked. Fallback tokens are tracked separately from real code tokens
  and are excluded from model-contradiction checks, because they are weak by construction.

## Taxonomy

`Policies.inferCategory` classifies by attribute shape first (robust to lexically novel brands),
then by keyword. Unknown categories are not an error: they receive a conservative default policy
in which every configuration-like key is treated as potentially price-critical, which biases the
resolver toward abstention rather than toward an unsafe merge.

A source-declared category that looks wrong is kept as evidence, not obeyed — `xo_0004` claims
`source_category: computer_monitors` for a television.
