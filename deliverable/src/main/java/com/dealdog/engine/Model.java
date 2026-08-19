package com.dealdog.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealdog.normalize.Norm.*;
import com.dealdog.util.J;

import java.util.*;

/** In-memory domain model. All state is rebuilt deterministically from the durable event log. */
public final class Model {
    private Model() {}

    /** Per-field state after snapshot/patch/correction semantics. */
    public static final class FieldVal {
        public TypedValue value;
        public String state;         // asserted | withdrawn | unknown_explicit
        public String srcTime;       // source_updated_at (or observed_at) governing this value
        public String eventKey;
        public String derivation;
        public String sourceField;
        public boolean authoritative;
        public FieldVal(TypedValue v, String state, String srcTime, String eventKey, String derivation, String sourceField, boolean auth) {
            this.value = v; this.state = state; this.srcTime = srcTime; this.eventKey = eventKey;
            this.derivation = derivation; this.sourceField = sourceField; this.authoritative = auth;
        }
    }

    public static final class Listing {
        public String internalId;          // L:<source>:<recordId>:<epoch>
        public String source;
        public String sourceRecordId;
        public int epoch = 1;
        public String seller;
        public String merchantSku;
        public String lifecycle = "active";   // active | inactive
        public String adapter;
        public String schemaVersion;
        public JsonNode raw;               // latest raw payload
        public String category = "unknown";
        public String brand;
        public String title;
        public String contentOrigin;
        public String observedAt;
        public String sourceUpdatedAt;
        public String productType = "primary_product";
        public String condition = "new";      // offer-level, never product identity

        public LinkedHashMap<String, FieldVal> fields = new LinkedHashMap<>();      // canonical attrs
        public LinkedHashMap<String, TypedValue> unknownAttrs = new LinkedHashMap<>();
        public List<ObjectNode> provenance = new ArrayList<>();
        public List<IdentifierEv> identifiers = new ArrayList<>();
        public List<MoneyEv> money = new ArrayList<>();
        public LinkedHashSet<String> familyTokens = new LinkedHashSet<>();
        /** family tokens derived from real model codes only (brand fallbacks excluded) */
        public LinkedHashSet<String> codeFamily = new LinkedHashSet<>();
        public LinkedHashSet<String> fullCodes = new LinkedHashSet<>();   // squeezed full code tokens, e.g. VVQ5524
        public List<ObjectNode> history = new ArrayList<>();                        // per-event audit

        public boolean quarantined;
        public String quarantineReason;
        public LinkedHashSet<String> conflictKeys = new LinkedHashSet<>();  // intra-listing conflicting canonical fields

        // resolution state
        public String productId;
        public String variantId;
        public String decision;            // MATCH | REVIEW | NO_MATCH
        public double confidence;
        public ArrayNode positiveSignals = J.arr();
        public ArrayNode negativeSignals = J.arr();
        public ArrayNode hypotheses = J.arr();
        public ObjectNode candidateSources = J.obj();
        public TreeSet<String> scoredCandidates = new TreeSet<>();
        public int candidateCount;

        /** asserted canonical attribute map (plain values) */
        public LinkedHashMap<String, Object> assertedAttrs() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            fields.forEach((k, f) -> { if ("asserted".equals(f.state) && f.value != null) m.put(k, f.value.plain()); });
            return m;
        }
        public TypedValue asserted(String key) {
            FieldVal f = fields.get(key);
            return f != null && "asserted".equals(f.state) ? f.value : null;
        }
        public String assertedCanon(String key) {
            TypedValue v = asserted(key);
            return v == null ? null : v.canon();
        }
        /**
         * Identifiers usable as evidence. A failed check digit lowers evidence quality
         * (recorded as a negative signal) but does not erase the identifier; only
         * syntactically malformed values are excluded.
         */
        public List<IdentifierEv> validIds(String ns) {
            List<IdentifierEv> out = new ArrayList<>();
            for (IdentifierEv id : identifiers)
                if (id.ns.equals(ns) && id.canonical != null && !"malformed".equals(id.validity)) out.add(id);
            return out;
        }
        public boolean hasChecksumInvalidId() {
            for (IdentifierEv id : identifiers) if ("checksum_invalid".equals(id.validity)) return true;
            return false;
        }
    }

    public static final class Product {
        public String id;
        public String category;
        public String brand;
        public LinkedHashMap<String, Object> attrs = new LinkedHashMap<>();  // product dims
        public TreeSet<String> familyTokens = new TreeSet<>();
        public TreeSet<String> listingIds = new TreeSet<>();
        public TreeSet<String> variantIds = new TreeSet<>();
    }

    public static final class Variant {
        public String id;
        public String productId;
        public LinkedHashMap<String, Object> attrs = new LinkedHashMap<>();  // price-critical/variant dims
        public TreeSet<String> gtins = new TreeSet<>();
        public TreeSet<String> mpns = new TreeSet<>();                       // squeezed, exact_variant scope only
        public TreeSet<String> listingIds = new TreeSet<>();
        public String canon(String key) {
            Object v = attrs.get(key);
            return v == null ? null : String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        }
    }

    public static final class Observation {
        public String eventKey;
        public String idempotencyKey;
        public String listingInternalId;
        public Double price;
        public Double listPrice;
        public String currency;
        public String priceKind;
        public String comparability;
        public ObjectNode terms;
        public String availability;
        public String observedAt;
        public String variantAtObservation;
        public String productAtObservation;
    }

    public static final class Offer {
        public String id;                   // O:<seller>|<sku or listing>|<condition>
        public String seller;
        public String condition = "new";
        public String variantId;            // may be null (REVIEW listings)
        public String productId;
        public boolean active = true;
        public Double price;                // current unconditional price (latest total_purchase_price obs)
        public Double listPrice;
        public String currency = "USD";
        public String priceKind = "total_purchase_price";
        public String comparability = "UNKNOWN";
        public ObjectNode terms = J.obj();
        public String observedAt;
        public TreeSet<String> sourceListingIds = new TreeSet<>();
        public List<Observation> observations = new ArrayList<>();
    }

    public static final class AuditEvent {
        public String listingInternalId;
        public String eventKey;
        public String priorProduct, priorVariant, newProduct, newVariant;
        public String reason, authority, changedAt;
        public ObjectNode json() {
            ObjectNode o = J.obj();
            o.put("listing_internal_id", listingInternalId);
            o.put("event_key", eventKey);
            o.put("prior_universal_product_id", priorProduct);
            o.put("prior_variant_id", priorVariant);
            o.put("new_universal_product_id", newProduct);
            o.put("new_variant_id", newVariant);
            o.put("reason", reason);
            o.put("authority", authority);
            o.put("changed_at", changedAt);
            return o;
        }
    }
}
