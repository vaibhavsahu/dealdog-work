#!/usr/bin/env python3
"""Public structural and basic-semantic checks for candidate output files."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def rows(document: Any, key: str) -> list[dict[str, Any]]:
    value = document.get(key) if isinstance(document, dict) else document
    assert isinstance(value, list), f"{key} must be an array"
    assert all(isinstance(row, dict) for row in value), f"{key} rows must be objects"
    return value


def normalized_decision(row: dict[str, Any]) -> str:
    value = str(row.get("decision") or row.get("status") or "").upper()
    return {"MATCHED":"MATCH","AMBIGUOUS":"REVIEW","UNRESOLVED":"REVIEW"}.get(value, value)


def candidate_ids(value: Any) -> set[str]:
    if isinstance(value, dict):
        return {item for nested in value.values() for item in candidate_ids(nested)}
    if isinstance(value, list):
        return {item for nested in value for item in candidate_ids(nested)}
    return {str(value)} if value not in (None, "") else set()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--normalized", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--decisions", type=Path, required=True)
    parser.add_argument("--before-replay", type=Path)
    parser.add_argument("--after-replay", type=Path)
    args = parser.parse_args()

    normalized = rows(load(args.normalized), "normalized_listings")
    catalog_doc = load(args.catalog)
    products = rows(catalog_doc, "universal_products")
    variants = rows(catalog_doc, "variants")
    offers = rows(catalog_doc, "offers")
    decisions = rows(load(args.decisions), "resolution_decisions")
    assert normalized and products and variants and offers and decisions, "required outputs must not be empty"
    assert all(row.get("listing_id") and row.get("source") for row in normalized)
    for row in normalized:
        assert "raw" in row or "raw_payload" in row or row.get("quarantined_evidence"), "each listing must retain raw or quarantined evidence"
        provenance = row.get("provenance")
        assert isinstance(provenance, list), "each normalized listing must expose provenance"
        attributes = row.get("normalized_attributes") or row.get("attributes") or {}
        if attributes:
            assert provenance, "normalized attributes require at least one provenance entry"
    assert all(row.get("id") for row in products)
    product_ids = {str(row["id"]) for row in products}
    assert all(row.get("id") and str(row.get("universal_product_id")) in product_ids for row in variants)
    variant_ids = {str(row["id"]) for row in variants}
    assert all(not row.get("variant_id") or str(row["variant_id"]) in variant_ids for row in offers)
    for row in offers:
        if row.get("price") is not None:
            assert row.get("price_kind") or row.get("price_type"), "priced offers must preserve monetary price kind"
            assert str(row.get("comparability") or row.get("comparison_status") or "").upper() in {
                "COMPARABLE", "CONDITIONAL", "NOT_COMPARABLE", "UNKNOWN",
            }, "priced offers must expose comparability"
    by_listing = {str(row.get("listing_id")):row for row in decisions if row.get("listing_id")}
    assert set(by_listing) >= {str(row["listing_id"]) for row in normalized}
    assert all(normalized_decision(row) in {"MATCH","REVIEW","NO_MATCH"} for row in decisions)
    assert all(isinstance(row.get("candidate_count"), int) and isinstance(row.get("scored_candidate_count"), int) for row in decisions), "candidate-generation integer counts are required"
    for row in decisions:
        sources = row.get("candidate_sources") if row.get("candidate_sources") is not None else row.get("blocking_strategies")
        assert isinstance(sources, (dict, list)), "candidate-source instrumentation is required"
        generated = candidate_ids(sources)
        scored = candidate_ids(row.get("scored_candidate_ids"))
        assert row["candidate_count"] == len(generated), "candidate_count must equal the reported unique candidate union"
        assert row["scored_candidate_count"] == len(scored), "scored_candidate_count must equal scored_candidate_ids"
        assert scored <= generated, "scored_candidate_ids must be a subset of generated candidates"
        signals = row.get("positive_signals") or row.get("evidence") or []
        if normalized_decision(row) == "MATCH":
            assert isinstance(signals, list) and signals, "MATCH decisions require traceable positive evidence"
        if normalized_decision(row) == "REVIEW" and row["candidate_count"] > 1:
            hypotheses = row.get("hypotheses") or row.get("candidate_hypotheses") or row.get("viable_candidates")
            assert isinstance(hypotheses, (list, dict)) and hypotheses, "finite REVIEW ambiguity must preserve viable hypotheses"

    cases = load(HERE / "public_validation_cases.json")["cases"]
    checked = 0
    for case in cases:
        left, right = by_listing.get(case["listing_a"]), by_listing.get(case["listing_b"])
        assert left and right, f"public case {case['case_id']} listings must be present"
        assert normalized_decision(left) == "MATCH" and normalized_decision(right) == "MATCH", f"public case {case['case_id']} contains two intentionally resolvable listings"
        left_product, right_product = left.get("universal_product_id"), right.get("universal_product_id")
        left_variant, right_variant = left.get("variant_id"), right.get("variant_id")
        assert left_product and right_product, f"public case {case['case_id']} requires universal-product IDs"
        assert left_variant and right_variant, f"public case {case['case_id']} requires exact-variant IDs"
        same_product = left_product == right_product
        same = left_variant == right_variant
        assert same_product == case["same_universal_product"], f"public case {case['case_id']} failed product identity: {case['note']}"
        assert same == case["same_variant"], f"public case {case['case_id']} failed: {case['note']}"
        checked += 1

    if bool(args.before_replay) != bool(args.after_replay):
        parser.error("provide both --before-replay and --after-replay")
    if args.before_replay:
        before, after = load(args.before_replay), load(args.after_replay)
        for key in ("normalized_listings","universal_products","variants","offers","resolution_decisions"):
            assert len(rows(before, key)) == len(rows(after, key)), f"replay changed {key} count"
    print(json.dumps({
        "status":"valid",
        "normalized_listings":len(normalized),
        "universal_products":len(products),
        "variants":len(variants),
        "offers":len(offers),
        "decisions":len(decisions),
        "public_pairs_checked":checked,
    }, indent=2))


if __name__ == "__main__":
    main()
