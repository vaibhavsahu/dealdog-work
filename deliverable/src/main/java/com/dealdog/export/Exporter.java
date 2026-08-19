package com.dealdog.export;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealdog.engine.Catalog;
import com.dealdog.engine.Model.*;
import com.dealdog.util.J;

import java.util.*;

/** Builds the three required artifacts and the combined evaluation export document. */
public final class Exporter {
    private final Catalog cat;
    public Exporter(Catalog cat) { this.cat = cat; }

    public ArrayNode normalizedListings() {
        ArrayNode out = J.arr();
        for (Listing l : sortedListings()) {
            ObjectNode o = J.obj();
            o.put("listing_id", l.sourceRecordId);          // contract: source record id
            o.put("internal_listing_id", l.internalId);
            o.put("source", l.source);
            o.put("source_record_id", l.sourceRecordId);
            o.put("lifecycle_epoch", l.epoch);
            o.put("lifecycle", l.lifecycle);
            o.put("seller", l.seller);
            o.put("merchant_sku", l.merchantSku);
            o.put("condition", l.condition);
            o.put("adapter", l.adapter);
            o.put("schema_version", l.schemaVersion);
            o.put("content_origin", l.contentOrigin);
            o.put("product_type", l.productType);
            o.put("observed_at", l.observedAt);
            o.put("source_updated_at", l.sourceUpdatedAt);
            o.set("raw", l.raw == null ? J.obj() : l.raw);
            if (l.quarantined) {
                o.put("quarantined", true);
                o.put("quarantine_reason", l.quarantineReason);
                o.set("quarantined_evidence", l.raw == null ? J.obj() : l.raw);
            }
            ObjectNode tax = J.obj();
            tax.put("category", l.category);
            o.set("taxonomy", tax);

            ObjectNode attrs = J.obj();
            l.fields.forEach((k, f) -> {
                if (!"asserted".equals(f.state) || f.value == null) return;
                put(attrs, k, f.value.plain());
            });
            o.set("normalized_attributes", attrs);

            ObjectNode unknown = J.obj();
            l.unknownAttrs.forEach((k, v) -> put(unknown, k, v.plain()));
            o.set("unknown_attributes", unknown);

            ObjectNode fieldState = J.obj();
            l.fields.forEach((k, f) -> {
                ObjectNode fs = J.obj();
                fs.put("state", f.state);
                fs.put("source_updated_at", f.srcTime);
                fs.put("event_key", f.eventKey);
                fs.put("derivation", f.derivation);
                fs.put("authoritative", f.authoritative);
                fieldState.set(k, fs);
            });
            o.set("field_state", fieldState);

            ArrayNode prov = J.arr();
            int i = 0;
            for (ObjectNode p : l.provenance) {
                ObjectNode c = p.deepCopy();
                c.put("provenance_ref", l.sourceRecordId + "-prov-" + (i++));
                prov.add(c);
            }
            o.set("provenance", prov);

            ArrayNode ids = J.arr();
            l.identifiers.forEach(id -> {
                ObjectNode n = J.obj();
                n.put("namespace", id.ns);
                n.put("raw", id.raw);
                n.put("canonical", id.canonical);
                n.put("scope", id.scope);
                n.put("validity", id.validity);
                n.put("source_field", id.sourceField);
                ids.add(n);
            });
            o.set("identifiers", ids);

            ArrayNode hist = J.arr();
            l.history.forEach(hist::add);
            o.set("source_history", hist);
            out.add(o);
        }
        return out;
    }

    public ObjectNode catalog() {
        ObjectNode doc = J.obj();
        ArrayNode products = doc.putArray("universal_products");
        for (Product p : sorted(cat.products.values(), x -> x.id)) {
            ObjectNode o = J.obj();
            o.put("id", p.id);
            ObjectNode tax = J.obj();
            tax.put("category", p.category);
            o.set("taxonomy", tax);
            ObjectNode attrs = J.obj();
            p.attrs.forEach((k, v) -> put(attrs, k, v));
            o.set("attributes", attrs);
            ArrayNode lids = o.putArray("source_listing_ids");
            p.listingIds.forEach(id -> lids.add(recordId(id)));
            products.add(o);
        }
        ArrayNode variants = doc.putArray("variants");
        for (Variant v : sorted(cat.variants.values(), x -> x.id)) {
            ObjectNode o = J.obj();
            o.put("id", v.id);
            o.put("universal_product_id", v.productId);
            ObjectNode attrs = J.obj();
            v.attrs.forEach((k, val) -> put(attrs, k, val));
            o.set("attributes", attrs);
            ArrayNode lids = o.putArray("source_listing_ids");
            v.listingIds.forEach(id -> lids.add(recordId(id)));
            variants.add(o);
        }
        ArrayNode offers = doc.putArray("offers");
        ArrayNode observations = doc.putArray("observations");
        for (Offer of : sorted(cat.offers.values(), x -> x.id)) {
            ObjectNode o = J.obj();
            o.put("id", of.id);
            o.put("variant_id", of.variantId);
            o.put("universal_product_id", of.productId);
            o.put("seller", of.seller);
            o.put("condition", of.condition);
            o.put("active", of.active);
            if (of.price != null) o.put("price", of.price); else o.putNull("price");
            if (of.listPrice != null) o.put("list_price", of.listPrice);
            o.put("currency", of.currency);
            o.put("price_kind", of.priceKind);
            o.put("comparability", of.comparability);
            o.set("promotion_terms", of.terms == null ? J.obj() : of.terms);
            o.put("observed_at", of.observedAt);
            ArrayNode src = o.putArray("source_listing_ids");
            of.sourceListingIds.forEach(src::add);
            ArrayNode obs = o.putArray("observations");
            for (Observation ob : of.observations) {
                ObjectNode n = observation(ob, of.id);
                obs.add(n);
                observations.add(n.deepCopy());
            }
            offers.add(o);
        }
        ArrayNode history = doc.putArray("resolution_history");
        cat.audits.forEach(a -> history.add(a.json()));
        ArrayNode quarantine = doc.putArray("quarantine");
        cat.quarantineRows.forEach(quarantine::add);

        ObjectNode stats = J.obj();
        stats.put("catalog_entity_count", cat.products.size() + cat.variants.size() + cat.offers.size());
        stats.put("universal_product_count", cat.products.size());
        stats.put("variant_count", cat.variants.size());
        stats.put("offer_count", cat.offers.size());
        stats.put("listing_count", cat.listings.size());
        stats.put("quarantined_count", cat.quarantineRows.size());
        doc.set("stats", stats);
        return doc;
    }

    private ObjectNode observation(Observation ob, String offerId) {
        ObjectNode n = J.obj();
        n.put("offer_id", offerId);
        n.put("listing_id", recordId(ob.listingInternalId));
        n.put("event_key", ob.eventKey);
        n.put("idempotency_key", ob.idempotencyKey);
        if (ob.price != null) n.put("price", ob.price); else n.putNull("price");
        if (ob.listPrice != null) n.put("list_price", ob.listPrice);
        n.put("currency", ob.currency);
        n.put("price_kind", ob.priceKind);
        n.put("comparability", ob.comparability);
        n.set("promotion_terms", ob.terms == null ? J.obj() : ob.terms);
        n.put("availability", ob.availability);
        n.put("observed_at", ob.observedAt);
        n.put("variant_id_at_observation", ob.variantAtObservation);
        n.put("universal_product_id_at_observation", ob.productAtObservation);
        return n;
    }

    public ArrayNode decisions() {
        ArrayNode out = J.arr();
        for (Listing l : sortedListings()) {
            ObjectNode o = J.obj();
            o.put("listing_id", l.sourceRecordId);
            o.put("internal_listing_id", l.internalId);
            o.put("decision", l.decision == null ? "REVIEW" : l.decision);
            o.put("universal_product_id", l.productId);
            o.put("variant_id", l.variantId);
            o.put("confidence", l.confidence);
            o.set("positive_signals", l.positiveSignals);
            o.set("negative_signals", l.negativeSignals);
            o.set("hypotheses", l.hypotheses);
            o.put("candidate_count", l.candidateCount);
            o.put("scored_candidate_count", l.scoredCandidates.size());
            ArrayNode sc = o.putArray("scored_candidate_ids");
            l.scoredCandidates.forEach(sc::add);
            o.set("candidate_sources", l.candidateSources);
            o.put("lifecycle", l.lifecycle);
            if (l.quarantined) o.put("quarantined", true);
            out.add(o);
        }
        return out;
    }

    public ObjectNode fullExport() {
        ObjectNode doc = J.obj();
        doc.set("normalized_listings", normalizedListings());
        ObjectNode c = catalog();
        doc.set("universal_products", c.get("universal_products"));
        doc.set("variants", c.get("variants"));
        doc.set("offers", c.get("offers"));
        doc.set("observations", c.get("observations"));
        doc.set("resolution_decisions", decisions());
        doc.set("resolution_history", c.get("resolution_history"));
        doc.set("quarantine", c.get("quarantine"));
        doc.set("stats", c.get("stats"));
        return doc;
    }

    // helpers
    private List<Listing> sortedListings() {
        List<Listing> ls = new ArrayList<>(cat.listings.values());
        ls.sort(Comparator.comparing(a -> a.internalId));
        return ls;
    }
    private <T> List<T> sorted(Collection<T> c, java.util.function.Function<T, String> key) {
        List<T> l = new ArrayList<>(c);
        l.sort(Comparator.comparing(key));
        return l;
    }
    private String recordId(String internalId) {
        Listing l = cat.listings.get(internalId);
        return l == null ? internalId : l.sourceRecordId;
    }
    private static void put(ObjectNode o, String k, Object v) {
        if (v == null) { o.putNull(k); return; }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) o.put(k, (long) d); else o.put(k, d);
        }
        else if (v instanceof Boolean b) o.put(k, b);
        else if (v instanceof com.fasterxml.jackson.databind.JsonNode jn) o.set(k, jn);
        else o.put(k, String.valueOf(v));
    }
}
