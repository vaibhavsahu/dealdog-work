package com.dealdog.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealdog.normalize.Norm;
import com.dealdog.normalize.Norm.*;
import com.dealdog.util.J;

import java.util.*;

/**
 * Adapter boundary. Every raw source payload enters the pipeline through exactly one
 * SourceAdapter selected by AdapterRegistry (source name + structural schema fingerprint).
 *
 * Adding a new affiliate/retailer source = implementing SourceAdapter and registering it
 * in AdapterRegistry.defaultRegistry(). Nothing else in the pipeline changes.
 * A known source may carry several coexisting schema versions: selection is per record,
 * so one malformed or new-shaped record never fails a batch (it quarantines alone).
 */
public final class Adapters {
    private Adapters() {}

    /** Everything an adapter can extract from one raw record. */
    public static final class RawExtraction {
        public String recordId;
        public String seller;
        public String merchantSku;
        public String title;
        public String brand;
        public String condition = "new";
        public String availability;
        public String productType = "primary_product";
        public String contentOrigin;
        public String observedAt;
        public String sourceUpdatedAt;
        public String url;
        public List<AttrEv> attrs = new ArrayList<>();
        public List<IdentifierEv> identifiers = new ArrayList<>();
        public List<MoneyEv> money = new ArrayList<>();
        public ObjectNode declaredScopes = J.obj();
    }

    public interface SourceAdapter {
        String name();
        String schemaVersion();
        boolean supports(String source, JsonNode payload);
        RawExtraction extract(JsonNode payload);
    }

    public static final class AdapterRegistry {
        private final List<SourceAdapter> adapters = new ArrayList<>();
        public void register(SourceAdapter a) { adapters.add(a); }
        public SourceAdapter select(String source, JsonNode payload) {
            for (SourceAdapter a : adapters) if (a.supports(source, payload)) return a;
            return null;
        }
        public static AdapterRegistry defaultRegistry() {
            AdapterRegistry r = new AdapterRegistry();
            r.register(new RetailerApiCompactAdapter());  // more specific fingerprint first
            r.register(new RetailerApiV1Adapter());
            r.register(new AffiliateACsvAdapter());
            r.register(new AffiliateBAdapter());
            r.register(new CommunityDealsAdapter());
            r.register(new ExtensionObservationsAdapter());
            r.register(new BrowserObservationAdapter());
            return r;
        }
    }

    // ---------- shared helpers ----------
    private static String scopeOf(JsonNode scopes, String ns, String dflt) {
        if (scopes != null && scopes.hasNonNull(ns)) return scopes.get(ns).asText();
        return dflt;
    }

    private static void addGtin(RawExtraction x, String raw, JsonNode scopes, String field) {
        if (raw == null || raw.isBlank()) return;
        String validity = Norm.gtinValidity(raw);
        String canonical = Norm.gtinCanonical(raw);
        x.identifiers.add(new IdentifierEv("gtin", raw, canonical,
                scopeOf(scopes, "gtin", "exact_variant"), validity, field));
    }

    private static void addMpn(RawExtraction x, String raw, JsonNode scopes, String field) {
        if (raw == null || raw.isBlank()) return;
        String sq = Norm.squeeze(raw);
        boolean malformed = raw.contains("?");
        x.identifiers.add(new IdentifierEv("mpn", raw, malformed ? null : sq,
                scopeOf(scopes, "mpn", "exact_variant"), malformed ? "malformed" : "valid", field));
    }

    private static void addSku(RawExtraction x, String raw, JsonNode scopes, String field) {
        if (raw == null || raw.isBlank()) return;
        x.merchantSku = raw;
        x.identifiers.add(new IdentifierEv("merchant_sku", raw, Norm.squeeze(raw),
                scopeOf(scopes, "merchant_sku", "merchant_offer"), "valid", field));
    }

    /** Map a structured attribute node into evidence; condition stays offer-level. */
    private static void attrsFromObject(RawExtraction x, JsonNode obj, String prefix) {
        if (obj == null) return;
        obj.fields().forEachRemaining(e -> {
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) return;
            if ("condition".equals(k)) { x.condition = v.asText(); return; }
            TypedValue tv = TypedValue.of(v);
            if (tv == null) return;
            if ("style_code".equals(k) && v.isTextual())
                x.identifiers.add(new IdentifierEv("style_code", v.asText(), Norm.squeeze(v.asText()), "style_colorway", "valid", prefix + "." + k));
            x.attrs.add(new AttrEv(k, tv, prefix + "." + k, v.asText(), "explicit", false));
        });
    }

    private static MoneyEv money(Double amount, Double list, String currency, String kind, String comp,
                                 JsonNode terms, String availability, String observedAt, String field, String raw) {
        MoneyEv m = new MoneyEv();
        m.amount = amount; m.listPrice = list;
        if (currency != null) m.currency = currency;
        if (kind != null) m.priceKind = kind;
        m.comparability = comp;
        if (terms != null && terms.isObject() && !terms.isEmpty()) m.terms = (ObjectNode) terms.deepCopy();
        m.availability = availability; m.observedAt = observedAt; m.sourceField = field; m.rawValue = raw;
        if (amount == null && raw != null && !raw.isBlank()) m.validity = "invalid";
        return m;
    }

    // =========================================================================
    // affiliate_a — flat CSV rows (delivered as JSON objects, strings unchanged)
    // =========================================================================
    public static final class AffiliateACsvAdapter implements SourceAdapter {
        public String name() { return "affiliate_a"; }
        public String schemaVersion() { return "csv_v1"; }
        public boolean supports(String source, JsonNode p) {
            return "affiliate_a".equals(source) && p.has("record_id") && p.has("product_name");
        }
        public RawExtraction extract(JsonNode p) {
            RawExtraction x = new RawExtraction();
            x.recordId = J.text(p, "record_id");
            x.seller = J.text(p, "merchant");
            x.title = Norm.clean(J.text(p, "product_name"));
            x.condition = Optional.ofNullable(J.text(p, "condition")).orElse("new");
            x.availability = J.text(p, "availability");
            x.productType = Optional.ofNullable(J.text(p, "product_type")).orElse("primary_product");
            String origin = J.text(p, "upstream_origin");
            x.contentOrigin = (origin == null || origin.isBlank()) ? null : origin;
            x.sourceUpdatedAt = J.text(p, "last_updated");
            x.observedAt = x.sourceUpdatedAt;
            x.url = J.text(p, "deep_link");
            addSku(x, J.text(p, "merchant_sku"), null, "merchant_sku");
            addGtin(x, J.text(p, "ean"), null, "ean");
            addMpn(x, J.text(p, "manufacturer_part_number"), null, "manufacturer_part_number");
            x.attrs.addAll(Norm.fromTitle(x.title, "product_name"));

            String promo = J.text(p, "promotion_text");
            ObjectNode terms = J.obj();
            if (promo != null && !promo.isBlank()) {
                if (promo.trim().startsWith("{")) {
                    try { terms.setAll((ObjectNode) J.parse(promo)); }
                    catch (Exception e) { terms.put("promotion_text", promo); }
                } else terms.put("promotion_text", promo);
            }
            Double sale = Norm.parseMoney(J.text(p, "sale_price"));
            Double retail = Norm.parseMoney(J.text(p, "retail_price"));
            x.money.add(money(sale, retail, J.text(p, "currency"), J.text(p, "price_kind"),
                    J.text(p, "comparability"), terms, x.availability, x.observedAt, "sale_price", J.text(p, "sale_price")));
            return x;
        }
    }

    // =========================================================================
    // affiliate_b — nested feed with declared semantics
    // =========================================================================
    public static final class AffiliateBAdapter implements SourceAdapter {
        public String name() { return "affiliate_b"; }
        public String schemaVersion() { return "feed_v3.7"; }
        public boolean supports(String source, JsonNode p) {
            return "affiliate_b".equals(source) && p.has("eventId") && p.has("item");
        }
        public RawExtraction extract(JsonNode p) {
            RawExtraction x = new RawExtraction();
            JsonNode scopes = J.at(p, "semantics", "identifierScopes");
            x.declaredScopes = scopes != null ? (ObjectNode) scopes.deepCopy() : J.obj();
            x.recordId = J.text(p, "eventId");
            x.seller = J.text(p, "advertiser", "name");
            x.title = Norm.clean(J.text(p, "item", "title"));
            x.productType = Optional.ofNullable(J.text(p, "semantics", "productType")).orElse("primary_product");
            x.contentOrigin = firstNonNull(J.text(p, "semantics", "contentOrigin"), J.text(p, "contentLineage"));
            x.observedAt = J.text(p, "stock", "capturedAt");
            x.sourceUpdatedAt = x.observedAt;
            x.url = J.text(p, "trackingUrl");
            x.availability = J.text(p, "stock", "status");
            addSku(x, J.text(p, "item", "merchantItemId"), scopes, "item.merchantItemId");
            addGtin(x, J.text(p, "item", "identifiers", "globalTradeItemNumber"), scopes, "item.identifiers.globalTradeItemNumber");
            addMpn(x, J.text(p, "item", "identifiers", "manufacturerCode"), scopes, "item.identifiers.manufacturerCode");
            attrsFromObject(x, J.at(p, "item", "variant"), "item.variant");
            x.attrs.addAll(Norm.fromTitle(x.title, "item.title"));
            x.money.add(money(
                    J.at(p, "pricing", "current") != null ? p.get("pricing").get("current").asDouble() : null,
                    J.at(p, "pricing", "original") != null ? p.get("pricing").get("original").asDouble() : null,
                    J.text(p, "pricing", "currencyCode"),
                    J.text(p, "semantics", "priceKind"),
                    J.text(p, "semantics", "comparabilityHint"),
                    J.at(p, "pricing", "promotion"), x.availability, x.observedAt, "pricing.current",
                    J.text(p, "pricing", "current")));
            return x;
        }
    }

    // =========================================================================
    // retailer_api — classic shape
    // =========================================================================
    public static final class RetailerApiV1Adapter implements SourceAdapter {
        public String name() { return "retailer_api"; }
        public String schemaVersion() { return "v1"; }
        public boolean supports(String source, JsonNode p) {
            return "retailer_api".equals(source) && p.has("observation_id")
                    && J.at(p, "product", "specifications") != null;
        }
        public RawExtraction extract(JsonNode p) {
            RawExtraction x = new RawExtraction();
            JsonNode scopes = J.at(p, "semantics", "identifier_scopes");
            x.declaredScopes = scopes != null ? (ObjectNode) scopes.deepCopy() : J.obj();
            x.recordId = J.text(p, "observation_id");
            x.seller = J.text(p, "store", "display_name");
            x.title = Norm.clean(J.text(p, "product", "name"));
            x.brand = J.text(p, "product", "brand");
            x.productType = Optional.ofNullable(J.text(p, "semantics", "product_type")).orElse("primary_product");
            x.contentOrigin = J.text(p, "semantics", "content_origin");
            x.observedAt = J.text(p, "observed_at");
            x.sourceUpdatedAt = x.observedAt;
            x.url = J.text(p, "product_url");
            x.availability = J.text(p, "offer", "availabilityCode");
            addSku(x, J.text(p, "sku"), scopes, "sku");
            addGtin(x, J.text(p, "product", "barcode"), scopes, "product.barcode");
            addMpn(x, J.text(p, "product", "model"), scopes, "product.model");
            attrsFromObject(x, J.at(p, "product", "specifications"), "product.specifications");
            x.attrs.addAll(Norm.fromTitle(x.title, "product.name"));
            x.money.add(money(
                    J.at(p, "offer", "price", "amount") != null ? p.get("offer").get("price").get("amount").asDouble() : null,
                    J.at(p, "offer", "listPrice") != null ? p.get("offer").get("listPrice").asDouble() : null,
                    J.text(p, "offer", "price", "currency"),
                    J.text(p, "semantics", "price_kind"),
                    J.text(p, "semantics", "comparability_hint"),
                    J.at(p, "offer", "terms"), x.availability, x.observedAt, "offer.price.amount",
                    J.text(p, "offer", "price", "amount")));
            return x;
        }
    }

    // =========================================================================
    // retailer_api — "2026-08-compact": name/value configuration array + minor units
    // =========================================================================
    public static final class RetailerApiCompactAdapter implements SourceAdapter {
        public String name() { return "retailer_api"; }
        public String schemaVersion() { return "2026-08-compact"; }
        public boolean supports(String source, JsonNode p) {
            if (!"retailer_api".equals(source)) return false;
            if ("2026-08-compact".equals(J.text(p, "schema_version"))) return true;
            JsonNode cfg = J.at(p, "product", "configuration");
            return cfg != null && cfg.isArray();
        }
        public RawExtraction extract(JsonNode p) {
            RawExtraction x = new RawExtraction();
            x.recordId = J.text(p, "observation_id");
            x.seller = J.text(p, "store", "display_name");
            x.title = Norm.clean(firstNonNull(J.text(p, "product", "displayName"), J.text(p, "product", "name")));
            x.brand = J.text(p, "product", "identity", "brand");
            x.productType = Optional.ofNullable(J.text(p, "semantics", "productType")).orElse("primary_product");
            x.contentOrigin = J.text(p, "semantics", "contentOrigin");
            x.observedAt = J.text(p, "observed_at");
            x.sourceUpdatedAt = x.observedAt;
            x.url = J.text(p, "product_url");
            x.availability = J.text(p, "offer", "availability");
            addSku(x, J.text(p, "sku"), null, "sku");
            addMpn(x, J.text(p, "product", "identity", "model"), null, "product.identity.model");
            JsonNode codes = J.at(p, "product", "identity", "codes");
            if (codes != null) codes.fields().forEachRemaining(e -> {
                if ("gtin".equalsIgnoreCase(e.getKey())) addGtin(x, e.getValue().asText(), null, "product.identity.codes.gtin");
            });
            JsonNode cfg = J.at(p, "product", "configuration");
            if (cfg != null) for (JsonNode kv : cfg) {
                String k = J.text(kv, "key");
                JsonNode v = kv.get("value");
                if (k == null || v == null || v.isNull()) continue;
                if ("condition".equals(k)) { x.condition = v.asText(); continue; }
                x.attrs.add(new AttrEv(k, TypedValue.of(v), "product.configuration[" + k + "]", v.asText(), "explicit", false));
            }
            x.attrs.addAll(Norm.fromTitle(x.title, "product.displayName"));
            Double amount = null, list = null;
            JsonNode minor = J.at(p, "offer", "money", "minorAmount");
            if (minor != null) amount = minor.asDouble() / 100.0;
            JsonNode cmp = J.at(p, "offer", "compareAtMinorAmount");
            if (cmp != null) list = cmp.asDouble() / 100.0;
            x.money.add(money(amount, list, J.text(p, "offer", "money", "currency"),
                    J.text(p, "semantics", "priceKind"), null, J.at(p, "offer", "context"),
                    x.availability, x.observedAt, "offer.money.minorAmount",
                    minor == null ? null : minor.asText()));
            return x;
        }
    }

    // =========================================================================
    // community_deals — seller-entered reports (low-trust text, partial ids)
    // =========================================================================
    public static final class CommunityDealsAdapter implements SourceAdapter {
        public String name() { return "community_deals"; }
        public String schemaVersion() { return "v1"; }
        public boolean supports(String source, JsonNode p) {
            return "community_deals".equals(source) && p.has("report_id");
        }
        public RawExtraction extract(JsonNode p) {
            RawExtraction x = new RawExtraction();
            x.recordId = J.text(p, "report_id");
            x.seller = J.text(p, "merchant");
            x.title = Norm.clean(J.text(p, "title"));
            x.productType = Optional.ofNullable(J.text(p, "product_type")).orElse("primary_product");
            x.contentOrigin = J.text(p, "content_origin");
            x.observedAt = J.text(p, "posted_at");
            x.sourceUpdatedAt = x.observedAt;
            x.url = J.text(p, "url");
            x.availability = J.text(p, "availability_claim");
            addSku(x, J.text(p, "merchant_hint_sku"), null, "merchant_hint_sku");
            JsonNode pid = p.get("partial_identifiers");
            if (pid != null) {
                addGtin(x, J.text(pid, "gtin"), null, "partial_identifiers.gtin");
                addMpn(x, J.text(pid, "mpn"), null, "partial_identifiers.mpn");
            }
            x.attrs.addAll(Norm.fromTitle(x.title, "title"));
            JsonNode conf = p.get("user_confidence");
            if (conf != null && conf.isNumber())
                x.attrs.add(new AttrEv("user_confidence", TypedValue.num(conf.asDouble()), "user_confidence", conf.asText(), "explicit", true));

            ObjectNode terms = J.obj();
            JsonNode ctx = p.get("offer_context");
            if (ctx != null && ctx.isObject() && !ctx.isEmpty()) terms.setAll((ObjectNode) ctx.deepCopy());
            String req = J.text(p, "requirements");
            if (req != null && !req.isBlank()) {
                if (req.trim().startsWith("{")) { try { terms.set("requirements", J.parse(req)); } catch (Exception e) { terms.put("requirements", req); } }
                else terms.put("requirements", req);
            }
            String coupon = J.text(p, "coupon_code");
            if (coupon != null && !coupon.isBlank()) terms.put("coupon_code", coupon);
            JsonNode rp = p.get("reported_price");
            JsonNode up = p.get("usual_price");
            x.money.add(money(rp != null && rp.isNumber() ? rp.asDouble() : null,
                    up != null && up.isNumber() ? up.asDouble() : null, "USD",
                    J.text(p, "price_kind"), J.text(p, "comparability_hint"),
                    terms, x.availability, x.observedAt, "reported_price", rp == null ? null : rp.asText()));
            return x;
        }
    }

    // =========================================================================
    // extension_observations — browser DOM captures (selected configuration role)
    // =========================================================================
    public static final class ExtensionObservationsAdapter implements SourceAdapter {
        public String name() { return "extension_observations"; }
        public String schemaVersion() { return "0.9.x"; }
        public boolean supports(String source, JsonNode p) {
            return "extension_observations".equals(source) && p.has("capture_id");
        }
        public RawExtraction extract(JsonNode p) {
            RawExtraction x = new RawExtraction();
            JsonNode scopes = J.at(p, "semantic_hints", "identifierScopes");
            x.declaredScopes = scopes != null ? (ObjectNode) scopes.deepCopy() : J.obj();
            x.recordId = J.text(p, "capture_id");
            x.seller = J.text(p, "merchant");
            x.title = Norm.clean(J.text(p, "dom", "title"));
            x.productType = Optional.ofNullable(J.text(p, "semantic_hints", "productType")).orElse("primary_product");
            x.contentOrigin = J.text(p, "content_origin");
            x.observedAt = J.text(p, "observed_at");
            x.sourceUpdatedAt = x.observedAt;
            x.url = J.text(p, "page_url");
            x.availability = J.text(p, "dom", "stockText");
            addSku(x, Norm.skuFromUrl(x.url), scopes, "page_url");
            JsonNode pid = p.get("partial_identifiers");
            if (pid != null) {
                addGtin(x, J.text(pid, "gtin"), scopes, "partial_identifiers.gtin");
                addMpn(x, J.text(pid, "mpn"), scopes, "partial_identifiers.mpn");
            }
            // selectedOptions carry the SELECTED configuration role
            attrsFromObject(x, J.at(p, "dom", "selectedOptions"), "dom.selectedOptions");
            x.attrs.addAll(Norm.fromTitle(x.title, "dom.title"));
            ObjectNode terms = J.obj();
            JsonNode ctx = p.get("offer_context");
            if (ctx != null && ctx.isObject() && !ctx.isEmpty()) terms.setAll((ObjectNode) ctx.deepCopy());
            String priceText = J.text(p, "dom", "priceText");
            x.money.add(money(Norm.parseMoney(priceText), null, "USD",
                    J.text(p, "dom", "priceKind"), J.text(p, "semantic_hints", "comparability"),
                    terms, x.availability, x.observedAt, "dom.priceText", priceText));
            return x;
        }
    }

    // =========================================================================
    // browser resolve requests (POST /resolve) — synonymous top-level fields accepted
    // =========================================================================
    public static final class BrowserObservationAdapter implements SourceAdapter {
        public String name() { return "browser_resolve"; }
        public String schemaVersion() { return "v1"; }
        public boolean supports(String source, JsonNode p) {
            return "browser_resolve".equals(source);
        }
        public RawExtraction extract(JsonNode p) {
            RawExtraction x = new RawExtraction();
            x.recordId = "resolve-request";
            x.title = Norm.clean(firstNonNull(J.text(p, "title"), J.text(p, "page_title")));
            x.url = firstNonNull(J.text(p, "url"), J.text(p, "page_url"));
            x.observedAt = java.time.Instant.now().toString();
            addSku(x, Norm.skuFromUrl(x.url), null, "url");
            JsonNode meta = firstNode(p, "metadata", "attributes");
            if (meta != null) meta.fields().forEachRemaining(e -> {
                String k = e.getKey();
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) return;
                if ("brand".equals(k)) { x.brand = v.asText(); return; }
                if ("model".equals(k)) { addMpn(x, v.asText(), null, "metadata.model"); return; }
                if ("condition".equals(k)) { x.condition = v.asText(); return; }
                x.attrs.add(new AttrEv(k, Norm.canonValue(k, TypedValue.of(v)), "metadata." + k, v.asText(), "explicit", false));
            });
            JsonNode ids = p.get("identifiers");
            if (ids != null) {
                addGtin(x, J.text(ids, "gtin"), null, "identifiers.gtin");
                addMpn(x, J.text(ids, "mpn"), null, "identifiers.mpn");
            }
            x.attrs.addAll(Norm.fromTitle(x.title, "title"));
            String priceRaw = firstNonNull(J.text(p, "price"), J.text(p, "observed_price"));
            x.money.add(money(Norm.parseMoney(priceRaw), null, "USD", "total_purchase_price", null,
                    null, null, x.observedAt, "price", priceRaw));
            return x;
        }
        private static JsonNode firstNode(JsonNode p, String... keys) {
            for (String k : keys) if (p.has(k) && p.get(k).isObject()) return p.get(k);
            return null;
        }
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
