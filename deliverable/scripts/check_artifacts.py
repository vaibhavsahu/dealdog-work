#!/usr/bin/env python3
"""
Executable acceptance checks for the generated artifacts.

Every check maps to a requirement in SUBMISSION_CONTRACT.md or CANDIDATE_INSTRUCTIONS.md, so an
evaluator can run this instead of taking VALIDATION.md Part 2 on trust. Exits non-zero if any
check fails.

  python3 scripts/check_artifacts.py \
      --normalized outputs/normalized_listings.json \
      --catalog    outputs/catalog.json \
      --decisions  outputs/resolution_decisions.json \
      --policy     data/IDENTITY_POLICY.json
"""
from __future__ import annotations
import argparse, json, sys
from pathlib import Path

RESULTS: list[tuple[str, str, bool, str]] = []


def check(cid: str, requirement: str, ok: bool, detail: str = "") -> None:
    RESULTS.append((cid, requirement, bool(ok), detail))


def load(p: Path):
    return json.loads(p.read_text(encoding="utf-8"))


def rows(doc, key):
    v = doc.get(key) if isinstance(doc, dict) else doc
    return v if isinstance(v, list) else []


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--normalized", type=Path, required=True)
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--decisions", type=Path, required=True)
    ap.add_argument("--policy", type=Path, default=None)
    a = ap.parse_args()

    nl = rows(load(a.normalized), "normalized_listings")
    cat = load(a.catalog)
    dec = rows(load(a.decisions), "resolution_decisions")
    products, variants = rows(cat, "universal_products"), rows(cat, "variants")
    offers, obs = rows(cat, "offers"), rows(cat, "observations")
    history = rows(cat, "resolution_history")
    if a.policy is None:
        for c in ("../provided/data", "provided/data", "../data", "data"):
            if (Path(c) / "IDENTITY_POLICY.json").exists():
                a.policy = Path(c) / "IDENTITY_POLICY.json"
                break
    policy = load(a.policy)["categories"] if a.policy and a.policy.exists() else {}

    # ---------------- normalized_listings ----------------
    check("C1", "normalized_listings is a non-empty array", len(nl) > 0, f"{len(nl)} rows")
    check("C2", "listing_id is the source record id",
          all(r.get("listing_id") == r.get("source_record_id") for r in nl))
    check("C3", "every listing retains raw or quarantined evidence",
          all(("raw" in r) or ("raw_payload" in r) or r.get("quarantined_evidence") for r in nl))
    check("C4", "every listing exposes a provenance array",
          all(isinstance(r.get("provenance"), list) for r in nl))
    check("C5", "normalized attributes are backed by provenance",
          all(r.get("provenance") for r in nl if r.get("normalized_attributes")))
    check("C6", "every listing carries a taxonomy category",
          all((r.get("taxonomy") or {}).get("category") for r in nl))
    check("C7", "unknown structured attributes are retained (not discarded)",
          all("unknown_attributes" in r for r in nl),
          f"{sum(1 for r in nl if r.get('unknown_attributes'))} rows carry unknown attributes")

    derivations = {p.get("derivation") for r in nl for p in r["provenance"]}
    validities = {p.get("validity") for r in nl for p in r["provenance"]}
    check("C8", "provenance distinguishes explicit / normalized / inferred",
          {"explicit", "normalized", "inferred"} <= derivations, f"seen: {sorted(x for x in derivations if x)}")
    check("C9", "provenance records invalid and conflicting evidence",
          {"invalid", "conflicting"} <= validities, f"seen: {sorted(x for x in validities if x)}")

    # ---------------- catalog ----------------
    pid_set = {p["id"] for p in products}
    vid_set = {v["id"] for v in variants}
    check("C10", "catalog has products, variants and offers",
          bool(products and variants and offers), f"{len(products)}/{len(variants)}/{len(offers)}")
    check("C11", "every variant references a real universal product",
          all(v.get("universal_product_id") in pid_set for v in variants))
    check("C12", "every offer variant reference is real",
          all((not o.get("variant_id")) or o["variant_id"] in vid_set for o in offers))

    priced = [o for o in offers if o.get("price") is not None]
    check("C13", "priced offers expose price_kind",
          all(o.get("price_kind") or o.get("price_type") for o in priced), f"{len(priced)} priced")
    ENUM = {"COMPARABLE", "CONDITIONAL", "NOT_COMPARABLE", "UNKNOWN"}
    check("C14", "priced offers expose a valid comparability",
          all(str(o.get("comparability") or o.get("comparison_status") or "").upper() in ENUM for o in priced))
    check("C15", "seller and condition remain offer-level",
          all("seller" in o and "condition" in o for o in offers))
    check("C16", "offers link back to source listings",
          all(o.get("source_listing_ids") for o in offers))
    check("C17", "observation history is retained",
          len(obs) > 0, f"{len(obs)} observations")

    kinds = {o.get("price_kind") for o in obs}
    check("C18", "distinct monetary roles stay distinguishable",
          len(kinds) > 1, f"price kinds: {sorted(k for k in kinds if k)}")
    check("C19", "conditional pricing retains structured requirements",
          all(o.get("promotion_terms") for o in obs if o.get("comparability") == "CONDITIONAL"),
          f"{sum(1 for o in obs if o.get('promotion_terms'))} observations carry terms")
    check("C20", "observations retain the source event identity",
          all(o.get("event_key") or o.get("event_id") for o in obs))
    check("C21", "observations retain the assignment known at observation time",
          all("variant_id_at_observation" in o for o in obs))

    # ---------------- decisions ----------------
    ids_nl = {r["listing_id"] for r in nl}
    ids_dec = {d["listing_id"] for d in dec}
    check("C22", "one decision row per unique source listing",
          len(dec) == len(ids_dec) == len(nl), f"{len(dec)} rows / {len(ids_dec)} unique")
    check("C23", "every delivered record is auditable in the export", ids_nl == ids_dec)

    def norm_decision(d):
        v = str(d.get("decision") or d.get("status") or "").upper()
        return {"MATCHED": "MATCH", "AMBIGUOUS": "REVIEW", "UNRESOLVED": "REVIEW"}.get(v, v)

    check("C24", "decisions use MATCH / REVIEW / NO_MATCH",
          all(norm_decision(d) in {"MATCH", "REVIEW", "NO_MATCH"} for d in dec))

    def cand_ids(v):
        if isinstance(v, dict):
            return {i for n in v.values() for i in cand_ids(n)}
        if isinstance(v, list):
            return {i for n in v for i in cand_ids(n)}
        return {str(v)} if v not in (None, "") else set()

    bad_count, bad_subset, bad_scored = [], [], []
    for d in dec:
        gen = cand_ids(d.get("candidate_sources") if d.get("candidate_sources") is not None
                       else d.get("blocking_strategies"))
        sc = cand_ids(d.get("scored_candidate_ids"))
        if d.get("candidate_count") != len(gen):
            bad_count.append(d["listing_id"])
        if d.get("scored_candidate_count") != len(sc):
            bad_scored.append(d["listing_id"])
        if not sc <= gen:
            bad_subset.append(d["listing_id"])
    check("C25", "candidate_count equals the unique candidate union", not bad_count, str(bad_count[:5]))
    check("C26", "scored_candidate_count equals scored_candidate_ids", not bad_scored, str(bad_scored[:5]))
    check("C27", "scored candidates are a subset of generated", not bad_subset, str(bad_subset[:5]))

    matches = [d for d in dec if norm_decision(d) == "MATCH"]
    check("C28", "every MATCH carries traceable positive evidence",
          all(d.get("positive_signals") or d.get("evidence") for d in matches), f"{len(matches)} matches")
    reviews = [d for d in dec if norm_decision(d) == "REVIEW"]
    finite = [d for d in reviews if (d.get("candidate_count") or 0) > 1]
    check("C29", "finite REVIEW ambiguity preserves viable hypotheses",
          all(d.get("hypotheses") or d.get("candidate_hypotheses") or d.get("viable_candidates") for d in finite),
          f"{len(finite)} finite-ambiguity reviews")
    check("C30", "a REVIEW never claims an exact variant",
          all(not d.get("variant_id") for d in reviews), f"{len(reviews)} reviews")
    check("C31", "REVIEW may retain a product id with a null variant id",
          any(d.get("universal_product_id") and not d.get("variant_id") for d in reviews),
          f"{sum(1 for d in reviews if d.get('universal_product_id') and not d.get('variant_id'))} such rows")

    sigs = [s for d in dec for s in (d.get("positive_signals") or []) + (d.get("negative_signals") or [])]
    check("C32", "signals are structured assertions naming a canonical field",
          all(isinstance(s, dict) and s.get("canonical_field") for s in sigs), f"{len(sigs)} signals")

    check("C33", "the system does not abstain indiscriminately",
          len(matches) > len(dec) * 0.5, f"{len(matches)}/{len(dec)} MATCH")

    # ---------------- corrections ----------------
    check("C34", "assignment changes are auditable prior -> new",
          all(h.get("listing_internal_id") and h.get("reason") and h.get("changed_at") for h in history),
          f"{len(history)} audit rows")

    # ---------------- I1: variant safety ----------------
    attrs_by_listing = {r["listing_id"]: (r.get("normalized_attributes") or {}) for r in nl}
    cat_by_product = {p["id"]: (p.get("taxonomy") or {}).get("category") for p in products}
    unsafe = []
    for v in variants:
        crit = policy.get(cat_by_product.get(v.get("universal_product_id")) or "", {}) \
                     .get("price_critical_dimensions", [])
        for dim in crit:
            seen = set()
            for lid in v.get("source_listing_ids", []):
                val = attrs_by_listing.get(lid, {}).get(dim)
                if val is not None:
                    seen.add(str(val).strip().lower())
            if len(seen) > 1:
                unsafe.append(f"{v['id']}:{dim}={sorted(seen)}")
    check("C35", "I1 - no variant mixes conflicting price-critical dimensions",
          not unsafe, str(unsafe[:3]))

    # ---------------- report ----------------
    width = max(len(r[1]) for r in RESULTS)
    failed = 0
    print()
    for cid, req, ok, detail in RESULTS:
        status = "PASS" if ok else "FAIL"
        if not ok:
            failed += 1
        line = f"  [{status}] {cid:<4} {req:<{width}}"
        print(f"{line}  {detail}" if detail else line)
    print()
    print(f"  {len(RESULTS) - failed}/{len(RESULTS)} artifact acceptance checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
