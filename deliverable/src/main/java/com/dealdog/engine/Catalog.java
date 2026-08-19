package com.dealdog.engine;

import com.dealdog.engine.Model.*;
import com.dealdog.policy.Policies;
import com.dealdog.util.J;

import java.util.*;

/**
 * In-memory catalog projection. Durability comes from the append-only event log in the
 * SQLite store: the projection is rebuilt deterministically by replaying applied events,
 * so no semantic state is lost across restarts and clean rebuilds converge to the same
 * entity partitions.
 *
 * Determinism notes: the token union-find always keeps the lexicographically smallest
 * member as root, and all id collections are TreeSets, so iteration order never depends
 * on hash seeds.
 */
public final class Catalog {
    public final Policies policies;

    public final LinkedHashMap<String, Listing> listings = new LinkedHashMap<>();
    /** source|recordId -> internalId (includes correction-record aliases) */
    public final LinkedHashMap<String, String> recordIndex = new LinkedHashMap<>();
    /** aliases created by correction events: source|recordId -> target internalId */
    public final LinkedHashMap<String, String> aliasRecords = new LinkedHashMap<>();

    public final LinkedHashMap<String, Product> products = new LinkedHashMap<>();
    public final LinkedHashMap<String, Variant> variants = new LinkedHashMap<>();
    public final LinkedHashMap<String, Offer> offers = new LinkedHashMap<>();
    public final List<AuditEvent> audits = new ArrayList<>();
    public final List<com.fasterxml.jackson.databind.node.ObjectNode> quarantineRows = new ArrayList<>();

    // token union-find (root = smallest member, deterministic)
    private final TreeMap<String, String> parent = new TreeMap<>();

    public Catalog(Policies policies) { this.policies = policies; }

    // ---------- token union-find ----------
    public String find(String t) {
        String p = parent.get(t);
        if (p == null) { parent.put(t, t); return t; }
        if (p.equals(t)) return t;
        String r = find(p);
        parent.put(t, r);
        return r;
    }
    public void union(String a, String b) {
        String ra = find(a), rb = find(b);
        if (ra.equals(rb)) return;
        String root = ra.compareTo(rb) < 0 ? ra : rb;
        String child = ra.compareTo(rb) < 0 ? rb : ra;
        parent.put(child, root);
    }
    public void unionAll(Collection<String> tokens) {
        String first = null;
        for (String t : tokens) {
            if (first == null) { first = t; find(t); }
            else union(first, t);
        }
    }
    public TreeSet<String> roots(Collection<String> tokens) {
        TreeSet<String> out = new TreeSet<>();
        for (String t : tokens) out.add(find(t));
        return out;
    }

    // ---------- listing registry ----------
    public void register(Listing l) {
        listings.put(l.internalId, l);
        recordIndex.put(l.source + "|" + l.sourceRecordId, l.internalId);
    }
    public Listing byRecord(String source, String recordId) {
        String iid = recordIndex.get(source + "|" + recordId);
        return iid == null ? null : listings.get(iid);
    }
    public void alias(String source, String recordId, String targetInternalId) {
        recordIndex.put(source + "|" + recordId, targetInternalId);
        aliasRecords.put(source + "|" + recordId, targetInternalId);
    }

    // ---------- product / variant management ----------
    public Product getOrCreateProduct(String category, String brand, String rootToken, Object generation) {
        String key = (category == null ? "unknown" : category) + "|" + n(brand) + "|" + n(rootToken) + "|" + n(generation);
        String id = "up:" + J.sha1(key);
        Product p = products.get(id);
        if (p == null) {
            p = new Product();
            p.id = id;
            p.category = category == null ? "unknown" : category;
            p.brand = brand;
            if (brand != null) p.attrs.put("brand", brand);
            if (rootToken != null) { p.attrs.put("model", rootToken); p.familyTokens.add(rootToken); }
            if (generation != null) p.attrs.put("generation", generation);
            products.put(id, p);
        }
        return p;
    }

    public Variant getOrCreateVariant(Product p, Map<String, Object> dims) {
        TreeMap<String, Object> sorted = new TreeMap<>(dims);
        StringBuilder key = new StringBuilder(p.id);
        sorted.forEach((k, v) -> key.append("|").append(k).append("=").append(String.valueOf(v).toLowerCase(Locale.ROOT)));
        String id = "var:" + J.sha1(key.toString());
        Variant v = variants.get(id);
        if (v == null) {
            v = new Variant();
            v.id = id;
            v.productId = p.id;
            v.attrs.putAll(sorted);
            variants.put(id, v);
            p.variantIds.add(id);
        }
        return v;
    }

    /** variant identifier sets are derived from member listings, optionally excluding one listing (self). */
    public TreeSet<String> variantGtins(Variant v, String excludeListing) {
        TreeSet<String> out = new TreeSet<>();
        for (String lid : v.listingIds) {
            if (lid.equals(excludeListing)) continue;
            Listing l = listings.get(lid);
            if (l != null) l.validIds("gtin").forEach(id -> out.add(id.canonical));
        }
        return out;
    }
    public TreeSet<String> variantMpns(Variant v, String excludeListing) {
        TreeSet<String> out = new TreeSet<>();
        for (String lid : v.listingIds) {
            if (lid.equals(excludeListing)) continue;
            Listing l = listings.get(lid);
            for (var id : l.validIds("mpn")) if ("exact_variant".equals(id.scope)) out.add(id.canonical);
        }
        return out;
    }

    /**
     * All family-token roots known for a product.
     *
     * `exclude` keeps a re-resolving listing from matching its own product through the very
     * tokens it just changed — without it an authoritative correction could never move a
     * listing to a different product.
     */
    public TreeSet<String> productTokens(Product p, String exclude) {
        TreeSet<String> out = new TreeSet<>();
        for (String lid : p.listingIds) {
            if (lid.equals(exclude)) continue;
            Listing l = listings.get(lid);
            if (l != null) out.addAll(roots(l.familyTokens));
        }
        if (out.isEmpty() && exclude == null) out.addAll(roots(p.familyTokens));
        return out;
    }
    public TreeSet<String> productTokens(Product p) { return productTokens(p, null); }

    /** Model-family roots derived from real codes only (brand fallback tokens excluded). */
    public TreeSet<String> productCodeTokens(Product p, String exclude) {
        TreeSet<String> out = new TreeSet<>();
        for (String lid : p.listingIds) {
            if (lid.equals(exclude)) continue;
            Listing l = listings.get(lid);
            if (l != null && !l.codeFamily.isEmpty()) out.addAll(roots(l.codeFamily));
        }
        return out;
    }

    /** full squeezed code strings (e.g. VVQ5524) known for a product — discriminates VVQ55-24 vs VVQ55-25. */
    public TreeSet<String> productFullCodes(Product p, String exclude) {
        TreeSet<String> out = new TreeSet<>();
        for (String lid : p.listingIds) {
            if (lid.equals(exclude)) continue;
            Listing l = listings.get(lid);
            if (l == null) continue;
            for (var id : l.identifiers)
                if (id.canonical != null && ("mpn".equals(id.ns) || "style_code".equals(id.ns))) out.add(id.canonical);
            out.addAll(l.fullCodes);
        }
        return out;
    }
    public TreeSet<String> productFullCodes(Product p) { return productFullCodes(p, null); }

    public void detach(Listing l) {
        if (l.variantId != null) {
            Variant v = variants.get(l.variantId);
            if (v != null) v.listingIds.remove(l.internalId);
        }
        if (l.productId != null) {
            Product p = products.get(l.productId);
            if (p != null) p.listingIds.remove(l.internalId);
        }
        l.variantId = null;
        l.productId = null;
    }

    public void attach(Listing l, Product p, Variant v) {
        if (p != null) {
            l.productId = p.id;
            p.listingIds.add(l.internalId);
            if (l.category != null && !"unknown".equals(l.category) && "unknown".equals(p.category)) p.category = l.category;
            p.familyTokens.addAll(roots(l.familyTokens));
        }
        if (v != null) {
            l.variantId = v.id;
            v.listingIds.add(l.internalId);
        }
    }

    /** drop variants/products that no longer have members (created then abandoned during re-resolution). */
    public void prune() {
        List<String> deadVars = new ArrayList<>();
        for (Variant v : variants.values()) if (v.listingIds.isEmpty()) deadVars.add(v.id);
        for (String id : deadVars) {
            Variant v = variants.remove(id);
            Product p = products.get(v.productId);
            if (p != null) p.variantIds.remove(id);
        }
        List<String> deadProds = new ArrayList<>();
        for (Product p : products.values()) if (p.listingIds.isEmpty() && p.variantIds.isEmpty()) deadProds.add(p.id);
        deadProds.forEach(products::remove);
        // offers referencing pruned variants lose the link
        for (Offer o : offers.values()) {
            if (o.variantId != null && !variants.containsKey(o.variantId)) o.variantId = null;
            if (o.productId != null && !products.containsKey(o.productId)) o.productId = null;
        }
    }

    private static String n(Object o) { return o == null ? "" : String.valueOf(o).trim().toLowerCase(Locale.ROOT); }
}
