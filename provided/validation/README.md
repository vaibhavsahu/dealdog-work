# Public validation

`public_validation_cases.json` contains a small set of labeled listing pairs for
both universal-product and exact-variant identity. Cases include the same
product/same variant, the same product/materially different variant, and
different products. It teaches contract semantics without exposing the full
clustering.

Run structural, pair, and optional replay checks with:

```bash
python3 validation/validate_outputs.py \
  --normalized outputs/normalized_listings.json \
  --catalog outputs/catalog.json \
  --decisions outputs/resolution_decisions.json
```

The validator also checks raw/provenance retention, traceable evidence for
automatic matches, finite-review hypotheses, and exact reconciliation between
the unique IDs reported by candidate blocks and candidate/scored counts.

Passing public validation is necessary, not sufficient. Private evaluation emphasizes unseen contradictions and categories, relational/counterfactual consistency, ambiguity and hypothesis narrowing, typed-attribute retention, partial/null/stale/tombstone behavior, temporal correction/split history, clean-rebuild equivalence, explanation fidelity, offer comparability and promotion semantics, order sensitivity, `/resolve`, and multi-strategy candidate-generation behavior.
