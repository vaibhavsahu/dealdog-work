# DealDog Engineering Work Trial — Universal Commerce Entity Resolution

Candidate package version: `2.0.0-hardening-pass2`.

This directory is the complete candidate package. No network access, DealDog production code, or paid service is required.

1. Read `CANDIDATE_INSTRUCTIONS.md`.
2. Read `SUBMISSION_CONTRACT.md`.
3. Read `data/DATA_DICTIONARY.md` and `data/IDENTITY_POLICY.json`.
4. Inspect the heterogeneous files under `data/initial/` and the three ordered stateful phases under `data/incremental/` (the aggregate batch contains the same events).
5. Use `validation/validate_outputs.py` and the labeled public cases to check your interoperability output.

Source characteristics are tendencies, not infallible trust rankings:

| Source | Typical strength | Known limitations |
|---|---|---|
| `affiliate_a` | identifiers are often useful | price/stock may be stale; strings are lossy |
| `affiliate_b` | nested catalog attributes | malformed or contradictory metadata occurs |
| `retailer_api` | current structured offer data | identifiers and categories can still be wrong |
| `community_deals` | fast promotion discovery | seller-entered text, conditional prices, missing fields |
| `extension_observations` | fresh browser evidence | partial DOM extraction and inferred attributes |

Only this directory is distributed to candidates.
