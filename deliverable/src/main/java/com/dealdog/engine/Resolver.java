package com.dealdog.engine;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealdog.engine.Model.*;
import com.dealdog.normalize.Norm.IdentifierEv;
import com.dealdog.policy.Policies;
import com.dealdog.util.J;

import java.util.*;

/**
 * Evidence-based conservative resolution.
 *
 * Precision over recall: a listing gets a variant only when its evidence uniquely
 * selects one variant of the chosen product; missing discriminating dimensions or
 * conflicting exact-scope evidence produce REVIEW with the full viable hypothesis set.
 * Price is never identity evidence anywhere in this class.
 */
public final class Resolver {
    private final Catalog cat;
    private final Policies policies;

    public Resolver(Catalog cat) { this.cat = cat; this.policies = cat.policies; }

    public static final class Result {
        public String decision = "REVIEW";
        public String productId;
        public String variantId;
        public double confidence = 0.5;
        public ArrayNode pos = J.arr();
        public ArrayNode neg = J.arr();
        public ArrayNode hypotheses = J.arr();
        public LinkedHashMap<String, TreeSet<String>> candidateSources = new LinkedHashMap<>();
        public TreeSet<String> scored = new TreeSet<>();
        public int candidateCount() {
            TreeSet<String> all = new TreeSet<>();
            candidateSources.values().forEach(all::addAll);
            return all.size();
        }
    }

    // ---------------------------------------------------------------- candidates
    private static final class Cands {
        LinkedHashMap<String, TreeSet<String>> sources = new LinkedHashMap<>();
        TreeMap<String, Integer> productStrength = new TreeMap<>();   // pid -> 1..3
        TreeMap<String, TreeSet<String>> pinVariantsByProduct = new TreeMap<>(); // pid -> variant ids pinned by exact ids
        TreeSet<String> idBacked = new TreeSet<>();                   // pinned by real identifier equality
        void add(String index, String entityId) {
            sources.computeIfAbsent(index, k -> new TreeSet<>()).add(entityId);
        }
        void strength(String pid, int s) {
            productStrength.merge(pid, s, Math::max);
        }
    }

    private Cands generate(Listing l) {
        Cands c = new Cands();
        TreeSet<String> myGtins = new TreeSet<>();
        l.validIds("gtin").forEach(id -> myGtins.add(id.canonical));
        TreeSet<String> myMpnsExact = new TreeSet<>();
        TreeSet<String> myMpnsBroad = new TreeSet<>();
        for (IdentifierEv id : l.identifiers) {
            if (id.canonical == null) continue;
            if ("mpn".equals(id.ns) || "style_code".equals(id.ns)) {
                if ("exact_variant".equals(id.scope)) myMpnsExact.add(id.canonical);
                else myMpnsBroad.add(id.canonical);
            }
        }
        // gtin / mpn indexes -> variants (excluding this listing's own contribution)
        for (Variant v : cat.variants.values()) {
            TreeSet<String> vg = cat.variantGtins(v, l.internalId);
            TreeSet<String> vm = cat.variantMpns(v, l.internalId);
            boolean gtinHit = myGtins.stream().anyMatch(vg::contains);
            boolean mpnHit = myMpnsExact.stream().anyMatch(vm::contains);
            if (gtinHit) { c.add("gtin_index", v.id); }
            if (mpnHit) { c.add("mpn_index", v.id); }
            if (gtinHit || mpnHit) {
                c.strength(v.productId, 3);
                c.pinVariantsByProduct.computeIfAbsent(v.productId, k -> new TreeSet<>()).add(v.id);
                c.idBacked.add(v.productId);
            }
        }
        // merchant sku index -> co-listings of the same seller page.
        // A SKU declared as a CONFIGURABLE offer addresses a parent page carrying many
        // configurations: product-level evidence only, never an exact-variant pin.
        if (l.seller != null && l.merchantSku != null) {
            boolean configurable = isConfigurable(l);
            String key = norm(l.seller) + "|" + l.merchantSku;
            for (Listing o : cat.listings.values()) {
                if (o == l || o.seller == null || o.merchantSku == null) continue;
                if (!key.equals(norm(o.seller) + "|" + o.merchantSku)) continue;
                if (o.variantId != null && !configurable && !isConfigurable(o)) {
                    c.add("merchant_sku_index", o.variantId);
                    c.strength(o.productId, 3);
                    c.pinVariantsByProduct.computeIfAbsent(o.productId, k -> new TreeSet<>()).add(o.variantId);
                } else if (o.productId != null) { c.add("merchant_sku_index", o.productId); c.strength(o.productId, 2); }
            }
        }
        // full-code index -> products (VVQ5524 vs VVQ5525 discrimination)
        TreeSet<String> myCodes = new TreeSet<>(l.fullCodes);
        myMpnsExact.forEach(myCodes::add);
        myMpnsBroad.forEach(myCodes::add);
        TreeSet<String> myRoots = cat.roots(l.familyTokens);
        for (Product p : cat.products.values()) {
            TreeSet<String> pCodes = cat.productFullCodes(p, l.internalId);
            if (myCodes.stream().anyMatch(pCodes::contains)) {
                c.add("full_code_index", p.id);
                c.strength(p.id, 2);
            }
            TreeSet<String> pRoots = cat.productTokens(p, l.internalId);
            if (myRoots.stream().anyMatch(pRoots::contains)) {
                c.add("token_index", p.id);
                c.strength(p.id, 1);
            }
        }
        return c;
    }

    private static boolean isConfigurable(Listing l) {
        for (IdentifierEv id : l.identifiers)
            if ("merchant_sku".equals(id.ns) && "configurable_offer".equals(id.scope)) return true;
        return false;
    }

    // ---------------------------------------------------------------- main entry
    /**
     * @param commit  when true, may create/extend catalog entities and attach the listing.
     * @param eventKey context for audit entries.
     */
    public Result resolve(Listing l, boolean commit, String eventKey) {
        Result r = new Result();
        if (l.quarantined) {
            r.decision = "REVIEW";
            r.confidence = 0.1;
            neg(r, "record", null, l.quarantineReason, "quarantined record: no adapter could safely interpret payload");
            finish(l, r, commit, eventKey);
            return r;
        }
        Cands c = generate(l);
        r.candidateSources = c.sources;

        // scored = category-compatible candidate entities
        TreeSet<String> union = new TreeSet<>();
        c.sources.values().forEach(union::addAll);
        for (String id : union) {
            String pid = id.startsWith("var:") ? (cat.variants.containsKey(id) ? cat.variants.get(id).productId : null) : id;
            Product p = pid == null ? null : cat.products.get(pid);
            if (p == null) continue;
            if (compatibleCategory(l.category, p.category)) r.scored.add(id);
            else neg(r, "category", null, l.category, "candidate " + id + " dropped: category " + p.category + " incompatible");
        }

        // ---- product selection
        List<String> best = new ArrayList<>();
        int bestS = 0;
        for (var e : c.productStrength.entrySet()) {
            Product p = cat.products.get(e.getKey());
            if (p == null) continue;
            if (!compatibleCategory(l.category, p.category)) continue;
            if (generationConflict(l, p)) {
                neg(r, "generation", fieldSrc(l, "generation"), String.valueOf(l.asserted("generation")),
                        "candidate product " + p.id + " dropped: generation conflict");
                continue;
            }
            int s = e.getValue();
            if (s > bestS) { bestS = s; best.clear(); best.add(e.getKey()); }
            else if (s == bestS) best.add(e.getKey());
        }

        Product product = null;
        if (best.size() == 1) {
            product = cat.products.get(best.get(0));
            // Identifier evidence pointing at a product whose model family is entirely disjoint
            // from this listing's own model codes is a copied/mis-scoped identifier, not proof of
            // identity (e.g. a P15 listing carrying P16's GTIN). Refuse to merge; abstain instead.
            TreeSet<String> myCodeRoots = l.codeFamily.isEmpty() ? new TreeSet<>() : cat.roots(l.codeFamily);
            TreeSet<String> pCodeRoots = cat.productCodeTokens(product, l.internalId);
            if (c.idBacked.contains(best.get(0)) && !myCodeRoots.isEmpty() && !pCodeRoots.isEmpty()
                    && myCodeRoots.stream().noneMatch(pCodeRoots::contains)) {
                neg(r, "identifier", idSrc(l), firstId(l),
                        "identifier matches a product whose model family contradicts this listing's own model code");
                hyp(r, product.id, null, 0.5);
                r.decision = "REVIEW";
                finish(l, r, commit, eventKey);
                return r;
            }
            // Other independently-supported candidates that are compatible describe the same
            // marketed model reached by different evidence: unify them rather than leaving
            // duplicate clusters behind.
            if (commit) {
                List<String> others = new ArrayList<>();
                for (var e : c.productStrength.entrySet()) {
                    if (e.getKey().equals(best.get(0)) || e.getValue() < 2) continue;
                    Product op = cat.products.get(e.getKey());
                    if (op != null && compatibleCategory(l.category, op.category)) others.add(e.getKey());
                }
                if (!others.isEmpty()) {
                    List<String> all = new ArrayList<>();
                    all.add(best.get(0));
                    all.addAll(others);
                    if (mutuallyMergeable(all)) product = mergeAll(all, eventKey);
                }
            }
        }
        else if (best.size() > 1) {
            if (bestS >= 2 && commit && mutuallyMergeable(best)) {
                product = mergeAll(best, eventKey);
            } else if (bestS >= 2) {
                // conflicting strong evidence towards distinct products -> REVIEW with product hypotheses
                for (String pid : best) hyp(r, pid, null, 0.5);
                r.decision = "REVIEW";
                neg(r, "identity", null, null, "exact-scope evidence points at incompatible products");
                finish(l, r, commit, eventKey);
                return r;
            } else {
                // several weak token candidates: prefer one whose full codes overlap, else ambiguous
                List<String> refined = new ArrayList<>();
                for (String pid : best) {
                    TreeSet<String> codes = cat.productFullCodes(cat.products.get(pid), l.internalId);
                    TreeSet<String> mine = new TreeSet<>(l.fullCodes);
                    if (mine.stream().anyMatch(codes::contains)) refined.add(pid);
                }
                if (refined.size() == 1) product = cat.products.get(refined.get(0));
                else {
                    for (String pid : best) hyp(r, pid, null, 0.4);
                    r.decision = "REVIEW";
                    finish(l, r, commit, eventKey);
                    return r;
                }
            }
        }

        boolean primary = "primary_product".equals(l.productType);
        if (product == null) {
            // nothing matched: create when this is a committed primary product with real evidence
            boolean hasEvidence = !l.familyTokens.isEmpty() || !l.validIds("gtin").isEmpty()
                    || !l.validIds("mpn").isEmpty() || !priceCriticalDims(l).isEmpty();
            if (commit && primary && hasEvidence) {
                product = cat.getOrCreateProduct(l.category, l.brand, primaryRoot(l), plain(l, "generation"));
                pos(r, "model", fieldSrc(l, "model"), primaryRoot(l), "new universal product established from evidence");
            } else {
                r.decision = union.isEmpty() ? "NO_MATCH" : "REVIEW";
                r.confidence = union.isEmpty() ? 0.2 : 0.4;
                for (String id : r.scored) if (id.startsWith("up:")) hyp(r, id, null, 0.3);
                finish(l, r, commit, eventKey);
                return r;
            }
        } else if (!primary) {
            // accessory / unknown product type must not merge into a primary cluster
            r.decision = "REVIEW";
            hyp(r, product.id, null, 0.4);
            neg(r, "product_type", "product_type", l.productType,
                    "source declares non-primary product type; related-product match suppressed");
            finish(l, r, commit, eventKey);
            return r;
        } else {
            pos(r, "model", fieldSrc(l, "model"), String.join(",", cat.roots(l.familyTokens)),
                    "product-level evidence agrees with " + product.id);
        }
        r.productId = product.id;
        if (commit) upgradeProduct(product, l);

        // ---- variant stage
        LinkedHashMap<String, Object> dims = priceCriticalDims(l);
        TreeSet<String> conflicted = new TreeSet<>(l.conflictKeys);
        conflicted.retainAll(dims.keySet());

        // pins restricted to the chosen product
        TreeSet<String> pins = c.pinVariantsByProduct.getOrDefault(product.id, new TreeSet<>());
        pins.removeIf(vid -> !cat.variants.containsKey(vid));

        if (!conflicted.isEmpty()) {
            r.decision = "REVIEW";
            for (String k : conflicted)
                neg(r, k, fieldSrc(l, k), String.valueOf(dims.get(k)), "conflicting evidence for price-critical dimension " + k);
            for (Variant v : productVariants(product)) if (agreesOnShared(v, dims, conflicted)) hyp(r, product.id, v.id, 0.5);
            finish(l, r, commit, eventKey);
            return r;
        }

        // check-digit failures are recorded as evidence quality, never as a disqualifier
        if (l.hasChecksumInvalidId())
            neg(r, "gtin", idSrc(l), firstId(l), "GTIN check digit does not validate; identifier used as weaker evidence");

        if (pins.size() == 1) {
            Variant v = cat.variants.get(pins.first());
            if (conflictsWith(v, dims)) {
                // An identifier can legitimately name a broader thing than the offer: a contained
                // unit inside a retailer-added bundle, a pack, or a copied code. Rather than let a
                // contradicted pin veto good structured evidence, fall back to attribute-based
                // resolution WITHIN the same product. Ambiguity there still yields REVIEW.
                neg(r, "identifier", idSrc(l), firstId(l), "exact-scope identifier pins " + v.id
                        + " but explicit attributes conflict; identifier scope treated as broader than the offer");
                List<Variant> viableHere = new ArrayList<>();
                for (Variant cand : productVariants(product)) if (viable(cand, dims)) viableHere.add(cand);
                if (viableHere.size() == 1) {
                    matchVariant(l, r, product, viableHere.get(0), dims, commit, false, eventKey);
                    return r;
                }
                if (viableHere.isEmpty() && commit && !dims.isEmpty()) {
                    matchVariant(l, r, product, cat.getOrCreateVariant(product, dims), dims, commit, false, eventKey);
                    return r;
                }
                r.decision = "REVIEW";
                hyp(r, product.id, v.id, 0.5);
                for (Variant cand : viableHere) hyp(r, product.id, cand.id, 0.5);
                finish(l, r, commit, eventKey);
                return r;
            }
            matchVariant(l, r, product, v, dims, commit, true, eventKey);
            return r;
        }
        if (pins.size() > 1) {
            // one identifier claims several variants (copied / packaging-scoped codes)
            List<Variant> compat = new ArrayList<>();
            for (String vid : pins) { Variant v = cat.variants.get(vid); if (!conflictsWith(v, dims)) compat.add(v); }
            if (compat.size() == 1) { matchVariant(l, r, product, compat.get(0), dims, commit, true, eventKey); return r; }
            r.decision = "REVIEW";
            for (String vid : pins) hyp(r, product.id, vid, 0.5);
            neg(r, "identifier", idSrc(l), firstId(l), "identifier is shared by multiple variants; scope insufficient for exact-variant proof");
            finish(l, r, commit, eventKey);
            return r;
        }

        // attribute-based
        List<Variant> viable = new ArrayList<>();
        for (Variant v : productVariants(product)) if (viable(v, dims)) viable.add(v);

        if (viable.size() == 1) { matchVariant(l, r, product, viable.get(0), dims, commit, false, eventKey); return r; }
        if (viable.size() > 1) {
            r.decision = "REVIEW";
            r.confidence = 0.5;
            for (Variant v : viable) hyp(r, product.id, v.id, round(1.0 / viable.size()));
            neg(r, "variant", null, null, "listing lacks dimensions that distinguish " + viable.size() + " viable variants");
            finish(l, r, commit, eventKey);
            return r;
        }
        // none viable: create a new variant when we actually know a configuration
        if (commit && (!dims.isEmpty() || productVariants(product).isEmpty())) {
            Variant v = cat.getOrCreateVariant(product, dims);
            matchVariant(l, r, product, v, dims, commit, false, eventKey);
            return r;
        }
        r.decision = "REVIEW";
        for (Variant v : productVariants(product)) hyp(r, product.id, v.id, 0.4);
        finish(l, r, commit, eventKey);
        return r;
    }

    // ---------------------------------------------------------------- helpers
    private void matchVariant(Listing l, Result r, Product p, Variant v, Map<String, Object> dims,
                              boolean commit, boolean pinned, String eventKey) {
        r.decision = "MATCH";
        r.variantId = v.id;
        r.confidence = pinned ? 0.95 : 0.9;
        if (pinned) pos(r, "identifier", idSrc(l), firstId(l), "valid exact-scope identifier equals variant identifier set");
        for (var e : dims.entrySet()) {
            Object vv = v.attrs.get(e.getKey());
            if (vv != null && canon(vv).equals(canon(e.getValue())))
                pos(r, e.getKey(), fieldSrc(l, e.getKey()), String.valueOf(e.getValue()),
                        "price-critical dimension agrees with variant");
        }
        if (r.pos.isEmpty())
            pos(r, "model", fieldSrc(l, "model"), String.join(",", cat.roots(l.familyTokens)),
                    "single compatible variant of matched product");
        if (commit) {
            // identifier-backed extension: the id asserts exact-variant equality, so extra dims refine the variant
            for (var e : dims.entrySet()) if (!v.attrs.containsKey(e.getKey())) v.attrs.put(e.getKey(), e.getValue());
        }
        finish(l, r, commit, eventKey);
    }

    private void finish(Listing l, Result r, boolean commit, String eventKey) {
        if (!commit) return;
        String priorP = l.productId, priorV = l.variantId;
        cat.detach(l);
        Product p = r.productId == null ? null : cat.products.get(r.productId);
        Variant v = r.variantId == null ? null : cat.variants.get(r.variantId);
        cat.attach(l, p, v);
        l.decision = r.decision;
        l.confidence = r.confidence;
        l.positiveSignals = r.pos;
        l.negativeSignals = r.neg;
        l.hypotheses = r.hypotheses;
        l.candidateCount = r.candidateCount();
        l.scoredCandidates = r.scored;
        ObjectNode cs = J.obj();
        r.candidateSources.forEach((k, ids) -> { ArrayNode a = cs.putArray(k); ids.forEach(a::add); });
        l.candidateSources = cs;
        if (!Objects.equals(priorP, l.productId) || !Objects.equals(priorV, l.variantId)) {
            if (priorP != null || priorV != null) {
                AuditEvent a = new AuditEvent();
                a.listingInternalId = l.internalId;
                a.eventKey = eventKey;
                a.priorProduct = priorP; a.priorVariant = priorV;
                a.newProduct = l.productId; a.newVariant = l.variantId;
                a.reason = "re-resolution under new evidence";
                a.authority = eventKey != null && eventKey.contains("correct") ? "authoritative_correction" : "evidence";
                a.changedAt = java.time.Instant.now().toString();
                cat.audits.add(a);
            }
        }
    }

    private List<Variant> productVariants(Product p) {
        List<Variant> out = new ArrayList<>();
        for (String vid : p.variantIds) { Variant v = cat.variants.get(vid); if (v != null) out.add(v); }
        return out;
    }

    /** viable: all shared dims agree AND the listing's dims are a subset of the variant's dims. */
    private boolean viable(Variant v, Map<String, Object> dims) {
        for (var e : dims.entrySet()) {
            Object vv = v.attrs.get(e.getKey());
            if (vv == null) return false;                       // listing more specific than variant
            if (!canon(vv).equals(canon(e.getValue()))) return false;
        }
        return true;
    }
    private boolean conflictsWith(Variant v, Map<String, Object> dims) {
        for (var e : dims.entrySet()) {
            Object vv = v.attrs.get(e.getKey());
            if (vv != null && !canon(vv).equals(canon(e.getValue()))) return true;
        }
        return false;
    }
    private boolean agreesOnShared(Variant v, Map<String, Object> dims, Set<String> ignore) {
        for (var e : dims.entrySet()) {
            if (ignore.contains(e.getKey())) continue;
            Object vv = v.attrs.get(e.getKey());
            if (vv != null && !canon(vv).equals(canon(e.getValue()))) return false;
        }
        return true;
    }
    private Variant findByDims(Product p, Map<String, Object> dims) {
        for (Variant v : productVariants(p)) if (viable(v, dims)) return v;
        return null;
    }

    /** price-critical / variant dimensions asserted by the listing, per category policy. */
    public LinkedHashMap<String, Object> priceCriticalDims(Listing l) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        Policies.CategoryPolicy cp = policies.forCategory(l.category);
        LinkedHashSet<String> keys = new LinkedHashSet<>(cp.variantDims);
        keys.addAll(cp.priceCritical);
        for (String k : keys) {
            var v = l.asserted(k);
            if (v != null) out.put(k, v.plain());
        }
        return out;
    }

    private boolean generationConflict(Listing l, Product p) {
        var lg = l.asserted("generation");
        Object pg = p.attrs.get("generation");
        return lg != null && pg != null && !canon(lg.plain()).equals(canon(pg));
    }
    private static boolean compatibleCategory(String a, String b) {
        if (a == null || b == null || "unknown".equals(a) || "unknown".equals(b)) return true;
        return a.equals(b);
    }

    private boolean mutuallyMergeable(List<String> pids) {
        for (int i = 0; i < pids.size(); i++)
            for (int j = i + 1; j < pids.size(); j++) {
                Product a = cat.products.get(pids.get(i)), b = cat.products.get(pids.get(j));
                if (a == null || b == null) return false;
                if (!compatibleCategory(a.category, b.category)) return false;
                Object ga = a.attrs.get("generation"), gb = b.attrs.get("generation");
                if (ga != null && gb != null && !canon(ga).equals(canon(gb))) return false;
                // No inter-product token overlap is required here: the listing under resolution
                // is itself the bridge, and every candidate reaching this point is independently
                // supported (strength >= 2) rather than linked by token coincidence alone.
            }
        return true;
    }

    private Product mergeAll(List<String> pids, String eventKey) {
        List<String> sorted = new ArrayList<>(pids);
        Collections.sort(sorted);
        Product target = cat.products.get(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            Product src = cat.products.get(sorted.get(i));
            if (src == null || src == target) continue;
            for (String vid : new TreeSet<>(src.variantIds)) {
                Variant sv = cat.variants.get(vid);
                if (sv == null) continue;
                Variant tv = cat.getOrCreateVariant(target, sv.attrs);
                for (String lid : new TreeSet<>(sv.listingIds)) {
                    Listing member = cat.listings.get(lid);
                    if (member == null) continue;
                    AuditEvent a = new AuditEvent();
                    a.listingInternalId = lid;
                    a.eventKey = eventKey;
                    a.priorProduct = src.id; a.priorVariant = sv.id;
                    a.newProduct = target.id; a.newVariant = tv.id;
                    a.reason = "product merge: exact-scope evidence links product clusters";
                    a.authority = "evidence";
                    a.changedAt = java.time.Instant.now().toString();
                    cat.audits.add(a);
                    cat.detach(member);
                    cat.attach(member, target, tv);
                }
                sv.listingIds.clear();
            }
            target.familyTokens.addAll(src.familyTokens);
            src.variantIds.clear();
            src.listingIds.clear();
        }
        cat.prune();
        return target;
    }

    private void upgradeProduct(Product p, Listing l) {
        if (p.brand == null && l.brand != null) { p.brand = l.brand; p.attrs.put("brand", l.brand); }
        var g = l.asserted("generation");
        if (g != null && !p.attrs.containsKey("generation")) p.attrs.put("generation", g.plain());
        p.familyTokens.addAll(cat.roots(l.familyTokens));
    }

    private String primaryRoot(Listing l) {
        for (IdentifierEv id : l.identifiers)
            if (("mpn".equals(id.ns) || "style_code".equals(id.ns)) && id.canonical != null) {
                String fam = com.dealdog.normalize.Norm.family(id.raw);
                if (fam != null) return cat.find(fam);
            }
        for (String t : l.familyTokens) return cat.find(t);
        return null;
    }

    // ---- signal builders (structured, traceable) ----
    private void pos(Result r, String field, String src, String raw, String note) {
        r.pos.add(signal(field, src, raw, note, "positive"));
    }
    private void neg(Result r, String field, String src, String raw, String note) {
        r.neg.add(signal(field, src, raw, note, "negative"));
    }
    private static ObjectNode signal(String field, String src, String raw, String note, String effect) {
        ObjectNode s = J.obj();
        s.put("canonical_field", field);
        if (src != null) s.put("source_field", src);
        if (raw != null) s.put("raw_value", raw);
        s.put("note", note);
        s.put("effect", effect);
        return s;
    }
    private void hyp(Result r, String pid, String vid, double score) {
        ObjectNode h = J.obj();
        h.put("universal_product_id", pid);
        if (vid != null) h.put("variant_id", vid); else h.putNull("variant_id");
        h.put("score", score);
        r.hypotheses.add(h);
    }

    private String fieldSrc(Listing l, String key) {
        FieldVal f = l.fields.get(key);
        return f == null ? null : f.sourceField;
    }
    private String idSrc(Listing l) {
        for (IdentifierEv id : l.identifiers) if (id.canonical != null) return id.sourceField;
        return null;
    }
    private String firstId(Listing l) {
        for (IdentifierEv id : l.identifiers) if (id.canonical != null) return id.raw;
        return null;
    }
    private Object plain(Listing l, String key) {
        var v = l.asserted(key);
        return v == null ? null : v.plain();
    }
    private static String canon(Object o) { return String.valueOf(o).trim().toLowerCase(Locale.ROOT); }
    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }
}
