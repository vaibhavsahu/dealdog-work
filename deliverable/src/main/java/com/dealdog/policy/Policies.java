package com.dealdog.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.dealdog.util.J;

import java.util.*;

/**
 * Identity policy: loaded from IDENTITY_POLICY.json (packaged copy of the supplied file).
 * Category rules are DATA, not code — resolution reads dimensions from here, so a new
 * category added to the policy file changes behavior with zero code changes.
 * Unknown categories fall back to a conservative DEFAULT policy.
 */
public final class Policies {

    public static final class CategoryPolicy {
        public final String name;
        public final List<String> productDims;
        public final List<String> variantDims;
        public final List<String> priceCritical;
        CategoryPolicy(String name, List<String> p, List<String> v, List<String> pc) {
            this.name = name; this.productDims = p; this.variantDims = v; this.priceCritical = pc;
        }
    }

    private final Map<String, CategoryPolicy> categories = new LinkedHashMap<>();
    /** all attribute keys any policy considers configuration-relevant (used by DEFAULT policy) */
    private final Set<String> configKeys = new LinkedHashSet<>();
    private final CategoryPolicy defaultPolicy;

    public Policies(JsonNode policyDoc) {
        JsonNode cats = policyDoc.get("categories");
        if (cats != null) cats.fields().forEachRemaining(e -> {
            JsonNode c = e.getValue();
            CategoryPolicy cp = new CategoryPolicy(e.getKey(),
                    list(c, "product_dimensions"), list(c, "variant_dimensions"), list(c, "price_critical_dimensions"));
            categories.put(e.getKey(), cp);
            configKeys.addAll(cp.variantDims);
        });
        // extra configuration-like keys seen in the wild but not in the visible policy.
        // Deliberately narrow: retained-but-unlisted structured fields (panel, band_configuration,
        // style_code, ranking/display metadata) stay typed evidence WITHOUT becoming identity
        // dimensions — an unfamiliar field needs a defensible policy before it may split variants.
        configKeys.addAll(List.of("color", "size", "department", "bundle", "case_connector"));
        List<String> cfg = new ArrayList<>(configKeys);
        defaultPolicy = new CategoryPolicy("__default__",
                List.of("brand", "model", "generation"), cfg, cfg);
    }

    private static List<String> list(JsonNode n, String key) {
        List<String> out = new ArrayList<>();
        JsonNode a = n.get(key);
        if (a != null) a.forEach(x -> out.add(x.asText()));
        return out;
    }

    public static Policies load() {
        try (var in = Policies.class.getResourceAsStream("/IDENTITY_POLICY.json")) {
            return new Policies(J.M.readTree(in));
        } catch (Exception e) { throw new RuntimeException("cannot load IDENTITY_POLICY.json", e); }
    }

    public CategoryPolicy forCategory(String category) {
        return categories.getOrDefault(category, defaultPolicy);
    }
    public boolean isKnownCategory(String category) { return categories.containsKey(category); }
    public Set<String> configKeys() { return configKeys; }

    // ---------- category inference ----------
    // Attribute-shape first (robust to lexically novel brands), then keyword hints.
    public String inferCategory(Map<String, Object> attrs, String title, String brand) {
        Set<String> k = attrs.keySet();
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        // shape-based
        if (k.contains("capacity_gb") || (k.contains("interface") && k.contains("form_factor"))) return "storage_devices";
        if (k.contains("case_size_mm") || k.contains("case_material") || t.contains("smartwatch")) return "smartwatches";
        if (k.contains("formulation") || k.contains("volume_ml")) return "beauty_and_fragrance";
        if (k.contains("size_us") || t.contains("running shoe") || t.contains("road shoe")) return "footwear";
        if (k.contains("edition") && (t.contains("console") || t.contains("novaplay"))) return "game_consoles";
        if (k.contains("ram_gb") || t.contains("laptop") || t.contains("airbook") || t.contains("air book")) return "laptops";
        if ((k.contains("carrier") || t.contains("phone")) && !t.contains("headphone")) return "phones";
        // keyword-based (categories outside the visible policy get descriptive names + DEFAULT policy)
        if (t.contains("headphone") || t.contains("earbud") || t.contains("airbud") || t.contains("buds")) return "headphones";
        if (t.contains("console") || t.contains("novaplay")) return "game_consoles";
        if (t.contains("television") || t.contains(" tv") || t.contains("qled")) return "tvs";
        if (t.contains("vacuum")) return "vacuums";
        if (t.contains("blender")) return "small_appliances";
        if (t.contains("tee") || t.contains("jacket") || t.contains("crew") || t.contains("shirt")) return "apparel";
        if (t.contains("serum") || t.contains("parfum") || t.contains("fragrance") || t.contains("toilette")) return "beauty_and_fragrance";
        if (t.contains("ssd") || t.contains("solid state") || t.contains("nvme") || t.contains("internal drive")) return "storage_devices";
        if (t.contains("watch")) return "smartwatches";
        return "unknown";
    }
}
