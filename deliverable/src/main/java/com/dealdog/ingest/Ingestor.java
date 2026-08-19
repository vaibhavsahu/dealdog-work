package com.dealdog.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealdog.adapter.Adapters;
import com.dealdog.adapter.Adapters.RawExtraction;
import com.dealdog.engine.Catalog;
import com.dealdog.engine.Model.*;
import com.dealdog.engine.Resolver;
import com.dealdog.normalize.Norm;
import com.dealdog.normalize.Norm.*;
import com.dealdog.util.J;

import java.util.*;

/**
 * Turns transport events into listings, applies update-mode/temporal semantics, and drives
 * resolution. Idempotency, quarantine, corrections, tombstones and epochs live here.
 */
public final class Ingestor {
    private final Catalog cat;
    private final Resolver resolver;
    private final Adapters.AdapterRegistry registry = Adapters.AdapterRegistry.defaultRegistry();

    /** event_key -> payload hash, for duplicate/conflict detection. */
    public final LinkedHashMap<String, String> eventLog = new LinkedHashMap<>();
    public final LinkedHashMap<String, String> idempotencyLog = new LinkedHashMap<>();

    public Ingestor(Catalog cat) { this.cat = cat; this.resolver = new Resolver(cat); }

    public static final class Counts {
        public int accepted, quarantined, duplicates, corrected, rejected;
        public ObjectNode json() {
            ObjectNode o = J.obj();
            o.put("accepted", accepted);
            o.put("quarantined", quarantined);
            o.put("duplicates", duplicates);
            o.put("corrected", corrected);
            o.put("rejected", rejected);
            return o;
        }
    }

    /** Envelope fields carried by an ingest request or an incremental event. */
    public static final class Envelope {
        public String source;
        public String batchId;
        public String operation = "upsert";       // upsert | correct | unavailable | patch | delete
        public String updateMode;                 // full_snapshot | partial_patch | authoritative_correction | historical_snapshot | listing_tombstone
        public String eventId;
        public String idempotencyKey;
        public String sourceUpdatedAt;
        public String receivedAt;
        public String correctsListingId;
        public JsonNode authority;
        public String nullSemantics = "withdraw";

        public String effectiveMode() {
            if (updateMode != null && !updateMode.isBlank()) return updateMode;
            return switch (operation == null ? "upsert" : operation) {
                case "correct" -> "authoritative_correction";
                case "unavailable", "delete" -> "listing_tombstone";
                case "patch" -> "partial_patch";
                default -> "full_snapshot";
            };
        }
        public boolean isCorrection() { return "authoritative_correction".equals(effectiveMode()); }
        public boolean isTombstone() { return "listing_tombstone".equals(effectiveMode()); }
        public boolean isPatch() { return "partial_patch".equals(effectiveMode()); }
        public boolean isHistorical() { return "historical_snapshot".equals(effectiveMode()); }
    }

    // ------------------------------------------------------------------ ingest
    public Counts ingest(String source, String batchId, List<JsonNode> records, Envelope base) {
        Counts c = new Counts();
        for (JsonNode rec : records) apply(source, batchId, rec, base, c);
        cat.prune();
        return c;
    }

    /** Ingest a source-shaped incremental event (its own envelope + nested payload). */
    public Counts ingestEvent(JsonNode event, Counts c) {
        Envelope env = new Envelope();
        env.source = J.text(event, "source");
        env.operation = Optional.ofNullable(J.text(event, "operation")).orElse("upsert");
        env.updateMode = J.text(event, "update_mode");
        env.eventId = J.text(event, "event_id");
        env.idempotencyKey = J.text(event, "idempotency_key");
        env.correctsListingId = J.text(event, "corrects_listing_id");
        env.sourceUpdatedAt = J.text(event, "source_updated_at");
        env.receivedAt = J.text(event, "received_at");
        JsonNode payload = event.get("payload");
        if (payload == null) { c.rejected++; return c; }
        apply(env.source, null, payload, env, c);
        return c;
    }

    /** Stable content hash of a payload, used as the second half of delivery identity. */
    public static String payloadHash(JsonNode payload) { return J.sha1(J.canonical(payload)); }

    /**
     * Delivery identity for one record.
     *
     * A request-level event_id/idempotency_key describes the TRANSPORT of a multi-record batch,
     * so the key is always record-scoped: replaying the same event+record is a duplicate, but
     * sibling records inside one batch are never suppressed by their shared id.
     */
    public String eventKeyFor(String source, String batchId, Envelope env, JsonNode payload) {
        Adapters.SourceAdapter adapter = registry.select(source, payload);
        String recordId = adapter != null ? safeRecordId(adapter, payload) : sniffRecordId(payload);
        String eventId = env == null ? null : env.eventId;
        String scope = (eventId != null && !eventId.isBlank())
                ? eventId
                : (batchId == null ? "batch" : batchId);
        return source + "|" + scope + "|" + (recordId == null ? payloadHash(payload) : recordId);
    }

    private void apply(String source, String batchId, JsonNode payload, Envelope base, Counts c) {
        Envelope env = base == null ? new Envelope() : base;
        String payloadHash = payloadHash(payload);

        Adapters.SourceAdapter adapter = registry.select(source, payload);
        String recordId = adapter != null ? safeRecordId(adapter, payload) : sniffRecordId(payload);
        String eventKey = eventKeyFor(source, batchId, env, payload);
        String prior = eventLog.get(eventKey);
        if (prior != null) {
            if (prior.equals(payloadHash)) { c.duplicates++; return; }
            // same delivery identity, mutated bytes -> conflict; keep first applied, quarantine second
            quarantine(source, recordId, "event_id_payload_conflict", payload, eventKey);
            c.quarantined++;
            return;
        }
        if (env.idempotencyKey != null && !env.idempotencyKey.isBlank()) {
            String idemScope = env.idempotencyKey + "|" + (recordId == null ? payloadHash : recordId);
            String prevHash = idempotencyLog.get(idemScope);
            if (prevHash != null && prevHash.equals(payloadHash)) { c.duplicates++; return; }
            idempotencyLog.put(idemScope, payloadHash);
        }
        eventLog.put(eventKey, payloadHash);

        if (adapter == null) {
            quarantine(source, recordId, "no_adapter_for_schema", payload, eventKey);
            c.quarantined++;
            return;
        }

        RawExtraction x;
        try {
            x = adapter.extract(payload);
        } catch (Exception e) {
            quarantine(source, recordId, "adapter_error:" + e.getClass().getSimpleName(), payload, eventKey);
            c.quarantined++;
            return;
        }
        if (x.recordId == null || x.recordId.isBlank()) {
            quarantine(source, recordId, "missing_source_record_id", payload, eventKey);
            c.quarantined++;
            return;
        }

        Listing target = resolveTargetListing(source, x, env);
        boolean isNew = target == null;
        if (isNew) {
            target = new Listing();
            target.source = source;
            target.sourceRecordId = x.recordId;
            target.epoch = nextEpoch(source, x.recordId, env);
            target.internalId = "L:" + source + ":" + x.recordId + ":" + target.epoch;
            cat.register(target);
        }

        // correction events carry their own record id: alias it to the corrected listing
        if (env.isCorrection() && env.correctsListingId != null)
            cat.alias(source, x.recordId, target.internalId);

        mergeExtraction(target, x, env, eventKey, adapter, payload);

        if (env.isTombstone()) {
            target.lifecycle = "inactive";
            c.accepted++;
        } else if (!"inactive".equals(target.lifecycle)) {
            c.accepted++;
        } else {
            target.lifecycle = "active";   // reappearance reactivates the same stable identity
            c.accepted++;
        }
        if (env.isCorrection()) c.corrected++;

        resolver.resolve(target, true, eventKey);
        updateOffer(target, x, env, eventKey);
    }

    /** Locate the listing this event applies to (correction target, existing record, or new). */
    private Listing resolveTargetListing(String source, RawExtraction x, Envelope env) {
        if (env.isCorrection() && env.correctsListingId != null) {
            Listing t = cat.byRecord(source, env.correctsListingId);
            if (t == null) {
                for (Listing l : cat.listings.values())
                    if (l.sourceRecordId.equals(env.correctsListingId)) { t = l; break; }
            }
            if (t != null) return t;
        }
        return cat.byRecord(source, x.recordId);
    }

    private int nextEpoch(String source, String recordId, Envelope env) {
        int max = 0;
        for (Listing l : cat.listings.values())
            if (l.source.equals(source) && l.sourceRecordId.equals(recordId)) max = Math.max(max, l.epoch);
        return max + 1;
    }

    // ------------------------------------------------------- merge one event's evidence
    private void mergeExtraction(Listing l, RawExtraction x, Envelope env, String eventKey,
                                 Adapters.SourceAdapter adapter, JsonNode rawPayload) {
        String srcTime = firstNonNull(env.sourceUpdatedAt, x.sourceUpdatedAt, x.observedAt);
        boolean authoritative = env.isCorrection();

        // history entry first, so late/stale events remain auditable even if they change nothing
        ObjectNode h = J.obj();
        h.put("event_key", eventKey);
        h.put("update_mode", env.effectiveMode());
        h.put("operation", env.operation);
        h.put("observed_at", x.observedAt);
        h.put("source_updated_at", srcTime);
        h.put("received_at", env.receivedAt);
        h.put("schema_version", adapter.schemaVersion());
        h.put("adapter", adapter.name() + ":" + adapter.schemaVersion());
        if (env.idempotencyKey != null) h.put("idempotency_key", env.idempotencyKey);
        l.history.add(h);

        boolean stale = isStale(l, srcTime, authoritative);
        h.put("applied", !stale);
        if (stale) return;                    // history retained, current state not regressed

        l.raw = rawPayload;
        l.condition = x.condition == null ? "new" : x.condition;
        l.money = new ArrayList<>(x.money);        // money of the currently applicable observation
        l.adapter = adapter.name() + ":" + adapter.schemaVersion();
        l.schemaVersion = adapter.schemaVersion();
        if (x.seller != null) l.seller = x.seller;
        if (x.merchantSku != null) l.merchantSku = x.merchantSku;
        if (x.title != null) l.title = x.title;
        if (x.brand != null) l.brand = x.brand;
        if (x.contentOrigin != null) l.contentOrigin = x.contentOrigin;
        if (x.productType != null) l.productType = x.productType;
        if (x.observedAt != null) l.observedAt = x.observedAt;
        l.sourceUpdatedAt = srcTime;

        // full snapshot / correction replace the field set; patch keeps omitted fields
        if (!env.isPatch()) {
            l.fields.clear();
            l.provenance.clear();
            l.identifiers.clear();
            l.conflictKeys.clear();
            l.unknownAttrs.clear();
            l.familyTokens.clear();
            l.codeFamily.clear();
            l.fullCodes.clear();
        }

        // identifiers
        for (IdentifierEv id : x.identifiers) {
            l.identifiers.add(id);
            ObjectNode p = J.obj();
            p.put("canonical_field", id.ns);
            p.put("source_field", id.sourceField);
            p.put("raw_value", id.raw);
            p.put("normalized_value", id.canonical);
            p.put("derivation", "normalized");
            p.put("validity", "valid".equals(id.validity) ? "valid" : "invalid");
            p.put("scope", id.scope);
            p.put("event_key", eventKey);
            l.provenance.add(p);
        }

        // attributes (canonical + unknown), with explicit > normalized/inferred precedence
        for (AttrEv a : x.attrs) {
            TypedValue val = Norm.canonValue(a.key, a.value);
            if (val == null) continue;
            boolean known = isCanonicalKey(a.key);
            ObjectNode p = J.obj();
            p.put("canonical_field", known ? a.key : null);
            p.put("source_field", a.sourceField);
            p.put("raw_value", a.rawValue);
            p.put("normalized_value", String.valueOf(val.plain()));
            p.put("derivation", a.derivation);
            p.put("event_key", eventKey);

            if (!known) {
                l.unknownAttrs.put(a.key, val);
                p.put("validity", "valid");
                p.put("retained_as", "unknown_attribute");
                l.provenance.add(p);
                continue;
            }
            FieldVal existing = l.fields.get(a.key);
            if (existing == null) {
                l.fields.put(a.key, new FieldVal(val, "asserted", srcTime, eventKey, a.derivation, a.sourceField, authoritative));
                p.put("validity", "valid");
            } else if (existing.value != null && existing.value.sameAs(val)) {
                p.put("validity", "valid");
            } else {
                // Only EQUAL-authority disagreement is a material conflict. Structured/explicit
                // evidence outranks text parsed out of a title, so a lossy title inference never
                // forces REVIEW: it is retained as conflicting provenance and discarded as state.
                int newRank = rank(a.derivation), oldRank = rank(existing.derivation);
                p.put("validity", "conflicting");
                if (newRank == oldRank) l.conflictKeys.add(a.key);
                if (newRank > oldRank)
                    l.fields.put(a.key, new FieldVal(val, "asserted", srcTime, eventKey, a.derivation, a.sourceField, authoritative));
            }
            l.provenance.add(p);
        }

        // explicit nulls in a patch withdraw prior assertions per declared null semantics
        if (env.isPatch()) applyExplicitNulls(l, x, env, eventKey);

        // tokens: title + identifier codes
        l.familyTokens.addAll(Norm.familyTokens(l.title));
        l.fullCodes.addAll(Norm.fullCodes(l.title));
        for (IdentifierEv id : l.identifiers) {
            if (("mpn".equals(id.ns) || "style_code".equals(id.ns)) && id.raw != null) {
                l.familyTokens.addAll(Norm.familyTokens(id.raw));
                String sq = Norm.squeeze(id.raw);
                if (sq != null) l.fullCodes.add(sq);
            }
        }
        l.codeFamily.clear();
        l.codeFamily.addAll(l.familyTokens);          // real code tokens, before any fallback
        if (l.familyTokens.isEmpty()) l.familyTokens.addAll(Norm.fallbackTokens(l.title, l.brand));
        cat.unionAll(l.familyTokens);

        // category inference last (needs attrs + title)
        l.category = cat.policies.inferCategory(l.assertedAttrs(), l.title, l.brand);
    }

    private void applyExplicitNulls(Listing l, RawExtraction x, Envelope env, String eventKey) {
        for (AttrEv a : x.attrs) {
            if (a.value != null) continue;
            FieldVal f = l.fields.get(a.key);
            if (f == null) continue;
            f.state = "withdraw".equals(env.nullSemantics) ? "withdrawn" : "unknown_explicit";
            f.eventKey = eventKey;
        }
    }

    /**
     * Temporal precedence: an authoritative correction always applies; otherwise a strictly
     * older source clock never regresses newer current state. Receipt order alone is never
     * precedence.
     */
    private boolean isStale(Listing l, String srcTime, boolean authoritative) {
        if (authoritative) return false;
        if (l.sourceUpdatedAt == null || srcTime == null) return false;
        boolean priorAuthoritative = l.fields.values().stream().anyMatch(f -> f.authoritative);
        if (priorAuthoritative) return true;     // late evidence cannot undo a correction
        return srcTime.compareTo(l.sourceUpdatedAt) < 0;
    }

    private static int rank(String derivation) {
        return switch (derivation == null ? "" : derivation) {
            case "explicit" -> 3;
            case "normalized" -> 2;
            case "inferred" -> 1;
            default -> 0;
        };
    }

    private static final Set<String> CANONICAL = Set.of(
            "brand", "model", "generation", "storage_gb", "color", "carrier", "screen_in", "ram_gb", "gpu",
            "processor_generation", "case_size_mm", "connectivity", "case_material", "bundle", "capacity_gb",
            "interface", "form_factor", "heatsink", "sensor_format", "included_lens", "warranty_region",
            "edition", "department", "size_us", "size", "width", "line", "formulation_family", "volume_ml",
            "formulation", "strength_pct", "style_code", "season", "width_mm", "aspect_ratio", "rim_in",
            "load_index", "speed_rating", "extra_load", "run_flat", "quantity", "voltage_platform", "tool_type",
            "voltage_v", "battery_count", "battery_capacity_ah", "charger_included", "compatible_printer_family",
            "yield_pages", "yield_class", "oem_status", "cartridge_count", "work", "language", "format", "region",
            "disc_count", "license", "wifi_generation", "band_count", "max_speed_mbps", "ethernet_ports", "poe",
            "node_count");
    private static boolean isCanonicalKey(String k) { return CANONICAL.contains(k); }

    // ------------------------------------------------------------------ offers
    private void updateOffer(Listing l, RawExtraction x, Envelope env, String eventKey) {
        if (x.money.isEmpty()) return;
        MoneyEv m = x.money.get(0);
        String cond = x.condition == null ? "new" : x.condition;
        String offerId = "of:" + J.sha1(norm(l.seller) + "|" + (l.merchantSku == null ? l.internalId : l.merchantSku) + "|" + cond);
        Offer o = cat.offers.get(offerId);
        if (o == null) {
            o = new Offer();
            o.id = offerId;
            o.seller = l.seller;
            o.condition = cond;
            cat.offers.put(offerId, o);
        }
        o.sourceListingIds.add(l.sourceRecordId);
        o.productId = l.productId;
        o.variantId = l.variantId;

        Observation ob = new Observation();
        ob.eventKey = eventKey;
        ob.idempotencyKey = env.idempotencyKey;
        ob.listingInternalId = l.internalId;
        ob.price = m.amount;
        ob.listPrice = m.listPrice;
        ob.currency = m.currency;
        ob.priceKind = m.priceKind;
        ob.comparability = comparability(m, l);
        ob.terms = m.terms;
        ob.availability = m.availability;
        ob.observedAt = m.observedAt;
        ob.variantAtObservation = l.variantId;
        ob.productAtObservation = l.productId;
        o.observations.add(ob);

        boolean newer = o.observedAt == null || (ob.observedAt != null && ob.observedAt.compareTo(o.observedAt) >= 0);
        if (newer) {
            o.price = m.amount;
            o.listPrice = m.listPrice;
            o.currency = m.currency;
            o.priceKind = m.priceKind;
            o.comparability = ob.comparability;
            o.terms = m.terms;
            o.observedAt = ob.observedAt;
        }
        o.active = !env.isTombstone() && !"inactive".equals(l.lifecycle)
                && !"out_of_stock".equalsIgnoreCase(String.valueOf(m.availability));
    }

    /**
     * Comparability: honor a source hint when present, else derive from monetary semantics.
     * Conditional terms (coupon, membership, trade-in, installment, quantity) are never
     * silently ranked as unconditional prices.
     */
    private String comparability(MoneyEv m, Listing l) {
        if (m.comparability != null && !m.comparability.isBlank()) return m.comparability.toUpperCase(Locale.ROOT);
        if (m.amount == null) return "UNKNOWN";
        if (!"total_purchase_price".equals(m.priceKind)) return "NOT_COMPARABLE";
        if (m.terms != null && !m.terms.isEmpty()) return "CONDITIONAL";
        if (l.condition != null && !"new".equalsIgnoreCase(l.condition)) return "NOT_COMPARABLE";
        return "COMPARABLE";
    }

    // ------------------------------------------------------------------ quarantine
    private void quarantine(String source, String recordId, String reason, JsonNode payload, String eventKey) {
        // A quarantined row keeps its REAL source-record identity whenever the payload has one.
        String rid = recordId != null ? recordId : ("unidentified:" + J.sha1(J.canonical(payload)));
        Listing l = cat.byRecord(source, rid);
        if (l == null) {
            l = new Listing();
            l.source = source;
            l.sourceRecordId = rid;
            l.internalId = "L:" + source + ":" + rid + ":1";
            cat.register(l);
        }
        l.quarantined = true;
        l.quarantineReason = reason;
        l.raw = payload;
        l.decision = "REVIEW";
        ObjectNode q = J.obj();
        q.put("source", source);
        q.put("source_record_id", rid);
        q.put("reason", reason);
        q.put("event_key", eventKey);
        q.set("raw", payload);
        cat.quarantineRows.add(q);
        resolver.resolve(l, true, eventKey);
    }

    /** Key names that denote a source record id, compared case- and separator-insensitively. */
    private static final Set<String> ID_KEYS = Set.of(
            "recordid", "eventid", "observationid", "reportid", "captureid",
            "sourcerecordid", "listingid", "id");

    /**
     * Best-effort source-record id sniffing for payloads no adapter claimed.
     *
     * A quarantined row must keep the REAL source identity so a later correction or patch still
     * joins it, so this walks nested/versioned envelopes and tolerates snake_case or camelCase.
     */
    public static String sniffRecordId(JsonNode p) {
        if (p == null || !p.isObject()) return null;
        for (Iterator<Map.Entry<String, JsonNode>> it = p.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            JsonNode v = e.getValue();
            if (ID_KEYS.contains(key) && v != null && v.isValueNode() && !v.isNull()) {
                String s = v.asText();
                if (s != null && !s.isBlank()) return s;
            }
        }
        // nested/versioned envelopes: {"envelope":{"data":{"recordId":...}}}
        for (Iterator<Map.Entry<String, JsonNode>> it = p.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue() != null && e.getValue().isObject()) {
                String nested = sniffRecordId(e.getValue());
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static String safeRecordId(Adapters.SourceAdapter a, JsonNode payload) {
        try {
            RawExtraction x = a.extract(payload);
            if (x.recordId != null && !x.recordId.isBlank()) return x.recordId;
        } catch (Exception ignored) { }
        return sniffRecordId(payload);
    }

    private static String firstNonNull(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s;
        return null;
    }
    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    public Resolver resolver() { return resolver; }
    public Adapters.AdapterRegistry registry() { return registry; }
}
