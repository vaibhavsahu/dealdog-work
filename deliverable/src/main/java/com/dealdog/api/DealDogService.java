package com.dealdog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealdog.adapter.Adapters;
import com.dealdog.engine.Catalog;
import com.dealdog.engine.Model.*;
import com.dealdog.engine.Resolver;
import com.dealdog.export.Exporter;
import com.dealdog.ingest.Ingestor;
import com.dealdog.policy.Policies;
import com.dealdog.store.EventStore;
import com.dealdog.util.J;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;

/**
 * Application service: owns the durable event store, the in-memory projection, and the
 * public operations. A single writer lock keeps ingestion deterministic and lets /resolve
 * run concurrently without observing partial state.
 */
@Service
public class DealDogService {
    private final EventStore store;
    private Catalog catalog;
    private Ingestor ingestor;
    private Exporter exporter;
    private final Policies policies = Policies.load();
    private final Object lock = new Object();

    public DealDogService() {
        String dir = System.getenv().getOrDefault("DEALDOG_STATE_DIR",
                System.getProperty("dealdog.state.dir", "./.dealdog-state"));
        this.store = new EventStore(Path.of(dir));
        rebuildFromLog();
    }

    /** Replay the durable log into a fresh projection (startup + rebuild-equivalence checks). */
    public final void rebuildFromLog() {
        synchronized (lock) {
            catalog = new Catalog(policies);
            ingestor = new Ingestor(catalog);
            exporter = new Exporter(catalog);
            for (EventStore.StoredEvent e : store.replayAll()) applyStored(e);
        }
    }

    private void applyStored(EventStore.StoredEvent e) {
        Ingestor.Envelope env = envelopeFrom(e.envelope());
        env.source = e.source();
        ingestor.ingest(e.source(), e.batchId(), List.of(e.payload()), env);
    }

    private static Ingestor.Envelope envelopeFrom(JsonNode n) {
        Ingestor.Envelope env = new Ingestor.Envelope();
        if (n == null) return env;
        env.source = J.text(n, "source");
        env.batchId = J.text(n, "batch_id");
        env.operation = Optional.ofNullable(J.text(n, "operation")).orElse("upsert");
        env.updateMode = J.text(n, "update_mode");
        env.eventId = J.text(n, "event_id");
        env.idempotencyKey = J.text(n, "idempotency_key");
        env.sourceUpdatedAt = J.text(n, "source_updated_at");
        env.receivedAt = J.text(n, "received_at");
        env.correctsListingId = J.text(n, "corrects_listing_id");
        env.authority = n.get("authority");
        String ns = J.text(n, "null_semantics");
        if (ns != null) env.nullSemantics = ns;
        return env;
    }

    // ---------------------------------------------------------------- ingest
    public ObjectNode ingest(JsonNode req) {
        synchronized (lock) {
            String source = J.text(req, "source");
            String batchId = J.text(req, "batch_id");
            Ingestor.Counts counts = new Ingestor.Counts();
            List<JsonNode> records = unwrapRecords(req);

            // Source-shaped incremental events: each element carries its own envelope.
            boolean eventShaped = records.stream().allMatch(r -> r.has("payload") && (r.has("event_id") || r.has("operation")))
                    && !records.isEmpty();
            if (eventShaped) {
                for (JsonNode ev : records) {
                    ObjectNode envJson = eventEnvelopeJson(ev, source);
                    String evSource = J.text(ev, "source") != null ? J.text(ev, "source") : source;
                    JsonNode payload = ev.get("payload");
                    Ingestor.Envelope env = envelopeFrom(envJson);
                    store.append(evSource, batchId, envJson, payload,
                            ingestor.eventKeyFor(evSource, batchId, env, payload),
                            Ingestor.payloadHash(payload));
                    ingestor.ingestEvent(ev, counts);
                }
            } else {
                Ingestor.Envelope env = envelopeFrom(req);
                env.source = source;
                env.batchId = batchId;
                ObjectNode envJson = (ObjectNode) req.deepCopy();
                for (String k : List.of("records", "items", "products", "deals", "observations", "events"))
                    envJson.remove(k);
                for (JsonNode rec : records) {
                    store.append(source, batchId, envJson, rec,
                            ingestor.eventKeyFor(source, batchId, env, rec),
                            Ingestor.payloadHash(rec));
                    Ingestor.Counts one = ingestor.ingest(source, batchId, List.of(rec), env);
                    counts.accepted += one.accepted;
                    counts.quarantined += one.quarantined;
                    counts.duplicates += one.duplicates;
                    counts.corrected += one.corrected;
                    counts.rejected += one.rejected;
                }
            }
            catalog.prune();
            ObjectNode res = countsOf(counts, records.size());
            res.put("batch_id", batchId);
            res.put("source", source);
            return res;
        }
    }

    private ObjectNode countsOf(Ingestor.Counts c, int total) {
        ObjectNode o = c.json();
        o.put("received", total);
        return o;
    }

    private static ObjectNode eventEnvelopeJson(JsonNode ev, String fallbackSource) {
        ObjectNode o = J.obj();
        o.put("source", J.text(ev, "source") != null ? J.text(ev, "source") : fallbackSource);
        o.put("operation", J.text(ev, "operation"));
        o.put("update_mode", J.text(ev, "update_mode"));
        o.put("event_id", J.text(ev, "event_id"));
        o.put("idempotency_key", J.text(ev, "idempotency_key"));
        o.put("corrects_listing_id", J.text(ev, "corrects_listing_id"));
        o.put("source_updated_at", J.text(ev, "source_updated_at"));
        o.put("received_at", J.text(ev, "received_at"));
        return o;
    }

    /** Transport unwrapping: records/items/products/deals/observations/events arrays. */
    public static List<JsonNode> unwrapRecords(JsonNode req) {
        for (String key : List.of("records", "events", "items", "products", "deals", "observations")) {
            JsonNode n = req.get(key);
            if (n != null && n.isArray()) {
                List<JsonNode> out = new ArrayList<>();
                n.forEach(out::add);
                return out;
            }
        }
        if (req.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            req.forEach(out::add);
            return out;
        }
        return List.of();
    }

    // ---------------------------------------------------------------- resolve
    /**
     * Lookup-oriented: builds a transient listing, runs the same pipeline with commit=false,
     * and mutates nothing. Repeated calls in any order return the same logical result and
     * leave exported state byte-identical.
     */
    public ObjectNode resolve(JsonNode req) {
        synchronized (lock) {
            Adapters.SourceAdapter adapter = ingestor.registry().select("browser_resolve", req);
            ObjectNode res = J.obj();
            if (adapter == null) {
                res.put("decision", "NO_MATCH");
                res.putNull("universal_product_id");
                res.putNull("variant_id");
                res.put("confidence", 0.0);
                res.set("positive_signals", J.arr());
                res.set("negative_signals", J.arr());
                res.set("hypotheses", J.arr());
                res.set("offers", J.arr());
                res.put("comparability", "UNKNOWN");
                res.put("candidate_count", 0);
                res.put("scored_candidate_count", 0);
                res.set("scored_candidate_ids", J.arr());
                res.set("candidate_sources", J.obj());
                return res;
            }
            var x = adapter.extract(req);
            Listing tmp = new Listing();
            tmp.source = "browser_resolve";
            tmp.sourceRecordId = "resolve-" + J.sha1(J.canonical(req));
            tmp.internalId = "L:resolve:" + tmp.sourceRecordId + ":1";
            tmp.seller = x.seller;
            tmp.merchantSku = x.merchantSku;
            tmp.title = x.title;
            tmp.brand = x.brand;
            tmp.condition = x.condition;
            tmp.raw = req;
            x.identifiers.forEach(tmp.identifiers::add);
            for (var a : x.attrs) {
                var val = com.dealdog.normalize.Norm.canonValue(a.key, a.value);
                if (val == null) continue;
                if (!tmp.fields.containsKey(a.key))
                    tmp.fields.put(a.key, new FieldVal(val, "asserted", null, "resolve", a.derivation, a.sourceField, false));
            }
            tmp.familyTokens.addAll(com.dealdog.normalize.Norm.familyTokens(tmp.title));
            tmp.fullCodes.addAll(com.dealdog.normalize.Norm.fullCodes(tmp.title));
            for (var id : tmp.identifiers) if (id.raw != null && ("mpn".equals(id.ns) || "style_code".equals(id.ns))) {
                tmp.familyTokens.addAll(com.dealdog.normalize.Norm.familyTokens(id.raw));
                String sq = com.dealdog.normalize.Norm.squeeze(id.raw);
                if (sq != null) tmp.fullCodes.add(sq);
            }
            tmp.codeFamily.addAll(tmp.familyTokens);
            if (tmp.familyTokens.isEmpty())
                tmp.familyTokens.addAll(com.dealdog.normalize.Norm.fallbackTokens(tmp.title, tmp.brand));
            tmp.category = policies.inferCategory(tmp.assertedAttrs(), tmp.title, tmp.brand);

            Resolver.Result r = new Resolver(catalog).resolve(tmp, false, "resolve");

            res.put("decision", r.decision);
            res.put("universal_product_id", r.productId);
            res.put("variant_id", r.variantId);
            res.put("confidence", r.confidence);
            res.set("positive_signals", r.pos);
            res.set("negative_signals", r.neg);
            res.set("hypotheses", r.hypotheses);
            res.put("comparability", comparabilityFor(r.variantId));
            res.put("candidate_count", r.candidateCount());
            res.put("scored_candidate_count", r.scored.size());
            ArrayNode sc = res.putArray("scored_candidate_ids");
            r.scored.forEach(sc::add);
            ObjectNode cs = J.obj();
            r.candidateSources.forEach((k, ids) -> { ArrayNode a = cs.putArray(k); ids.forEach(a::add); });
            res.set("candidate_sources", cs);
            res.set("offers", offersFor(r.variantId));
            return res;
        }
    }

    private ArrayNode offersFor(String variantId) {
        ArrayNode out = J.arr();
        if (variantId == null) return out;
        List<Offer> list = new ArrayList<>();
        for (Offer o : catalog.offers.values()) if (variantId.equals(o.variantId)) list.add(o);
        list.sort(Comparator.comparing(a -> a.id));
        for (Offer o : list) {
            ObjectNode n = J.obj();
            n.put("id", o.id);
            n.put("seller", o.seller);
            n.put("condition", o.condition);
            if (o.price != null) n.put("price", o.price); else n.putNull("price");
            n.put("currency", o.currency);
            n.put("price_kind", o.priceKind);
            n.put("comparability", o.comparability);
            n.set("promotion_terms", o.terms == null ? J.obj() : o.terms);
            n.put("active", o.active);
            n.put("observed_at", o.observedAt);
            out.add(n);
        }
        return out;
    }

    private String comparabilityFor(String variantId) {
        if (variantId == null) return "UNKNOWN";
        boolean any = false, allComparable = true;
        for (Offer o : catalog.offers.values()) {
            if (!variantId.equals(o.variantId) || !o.active) continue;
            any = true;
            if (!"COMPARABLE".equals(o.comparability)) allComparable = false;
        }
        if (!any) return "UNKNOWN";
        return allComparable ? "COMPARABLE" : "CONDITIONAL";
    }

    // ---------------------------------------------------------------- export
    public ObjectNode export() { synchronized (lock) { return exporter.fullExport(); } }
    public ArrayNode normalizedListings() { synchronized (lock) { return exporter.normalizedListings(); } }
    public ObjectNode catalogDoc() { synchronized (lock) { return exporter.catalog(); } }
    public ArrayNode decisions() { synchronized (lock) { return exporter.decisions(); } }
    public Catalog catalog() { return catalog; }
    public EventStore store() { return store; }
}
