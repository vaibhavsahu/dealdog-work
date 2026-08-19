package com.dealdog.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealdog.util.J;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalization primitives: typed values, evidence records, text cleaning, money parsing,
 * GTIN validation, model-token extraction and title attribute extraction.
 *
 * Everything here treats raw text as UNTRUSTED evidence: raw values are always preserved
 * on the evidence object; normalization failures produce validity=invalid rather than
 * silently repaired values.
 */
public final class Norm {
    private Norm() {}

    // ---------- typed value ----------
    public static final class TypedValue {
        public final String kind;     // number | string | bool | struct
        public final Double num;
        public final String str;
        public final Boolean bool;
        public final JsonNode node;

        private TypedValue(String kind, Double num, String str, Boolean bool, JsonNode node) {
            this.kind = kind; this.num = num; this.str = str; this.bool = bool; this.node = node;
        }
        public static TypedValue num(double d) { return new TypedValue("number", d, null, null, null); }
        public static TypedValue str(String s) { return new TypedValue("string", null, s, null, null); }
        public static TypedValue bool(boolean b) { return new TypedValue("bool", null, null, b, null); }
        public static TypedValue struct(JsonNode n) { return new TypedValue("struct", null, null, null, n); }
        public static TypedValue of(JsonNode n) {
            if (n == null || n.isNull()) return null;
            if (n.isNumber()) return num(n.asDouble());
            if (n.isBoolean()) return bool(n.asBoolean());
            if (n.isTextual()) return str(n.asText());
            return struct(n);
        }
        public Object plain() {
            if (num != null) { return num == Math.floor(num) && !num.isInfinite() ? (Object) num.longValue() : (Object) num; }
            if (str != null) return str;
            if (bool != null) return bool;
            return node;
        }
        /** canonical comparable string */
        public String canon() {
            Object p = plain();
            if (p instanceof JsonNode jn) return J.canonical(jn);
            return String.valueOf(p).trim().toLowerCase(Locale.ROOT);
        }
        public boolean sameAs(TypedValue o) { return o != null && canon().equals(o.canon()); }
        @Override public String toString() { return String.valueOf(plain()); }
    }

    // ---------- evidence records ----------
    /** One attribute assertion extracted from a source record. */
    public static final class AttrEv {
        public String key;            // canonical key or source-local key when unknown
        public TypedValue value;
        public String sourceField;    // raw path, e.g. item.variant.color
        public String rawValue;
        public String derivation;     // explicit | normalized | inferred
        public String validity = "valid"; // valid | invalid | conflicting | malformed
        public boolean unknown;       // true = not a canonical attribute; retained typed
        public AttrEv(String key, TypedValue v, String sourceField, String rawValue, String derivation, boolean unknown) {
            this.key = key; this.value = v; this.sourceField = sourceField; this.rawValue = rawValue;
            this.derivation = derivation; this.unknown = unknown;
        }
    }

    public static final class IdentifierEv {
        public String ns;             // gtin | mpn | merchant_sku | style_code
        public String raw;
        public String canonical;      // null when invalid
        public String scope;          // exact_variant | style_colorway | universal_product_family | configurable_offer | merchant_offer | unknown
        public String validity;       // valid | malformed | checksum_invalid
        public String sourceField;
        public IdentifierEv(String ns, String raw, String canonical, String scope, String validity, String sourceField) {
            this.ns = ns; this.raw = raw; this.canonical = canonical; this.scope = scope; this.validity = validity; this.sourceField = sourceField;
        }
    }

    public static final class MoneyEv {
        public Double amount;         // null when unparseable
        public Double listPrice;
        public String currency = "USD";
        public String priceKind = "total_purchase_price";
        public String comparability;  // COMPARABLE | CONDITIONAL | NOT_COMPARABLE | UNKNOWN
        public ObjectNode terms = J.obj();
        public String availability;
        public String observedAt;
        public String validity = "valid";
        public String sourceField;
        public String rawValue;
    }

    // ---------- text cleaning ----------
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]{1,80}>");
    public static String clean(String s) {
        if (s == null) return null;
        String t = HTML_TAG.matcher(s).replaceAll(" ");
        t = t.replace(' ', ' ').replace("​", "").replace("‎", "").replace("‏", "")
             .replace("‑", "-").replace("–", "-").replace("—", "-");
        // full-width -> ascii
        StringBuilder b = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) b.append((char) (c - 0xFEE0));
            else b.append(c);
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    // ---------- money ----------
    private static final Pattern MONEY = Pattern.compile("(-?\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?)");
    /** Returns amount or null if unparseable. Purely numeric extraction; caller records validity. */
    public static Double parseMoney(String raw) {
        if (raw == null) return null;
        String t = clean(raw).replace("$", "").replace("USD", "").trim();
        Matcher m = MONEY.matcher(t);
        if (!m.matches() && !m.lookingAt()) return null;
        // reject strings with residual alphabetic junk (e.g. "USD twelve??")
        if (t.replaceAll("[\\d.,\\s-]", "").length() > 0 && !m.matches()) return null;
        try {
            String g = m.group(1).replace(",", "");
            return Double.parseDouble(g);
        } catch (Exception e) { return null; }
    }

    // ---------- GTIN ----------
    /**
     * Canonical GTIN-14 form (left zero-padded) for a syntactically well-formed code, else null.
     *
     * Checksum failure does NOT void the identifier: a check digit is evidence quality, not
     * existence. Cross-length equivalence therefore only happens when the zero-padded forms are
     * literally equal — numeric resemblance alone never merges two codes (e.g. "85000020011"
     * pads to 00085000020011, which is deliberately NOT 00850000200117).
     */
    public static String gtinCanonical(String raw) {
        if (raw == null) return null;
        String d = raw.trim();
        if (!d.matches("\\d{8}|\\d{11,14}")) return null;   // '?' / wrong length -> unusable
        return "0".repeat(14 - d.length()) + d;
    }

    /** Standard GTIN mod-10 check digit test. */
    public static boolean gtinChecksumOk(String raw) {
        String p = gtinCanonical(raw);
        if (p == null) return false;
        int sum = 0;
        for (int i = 0; i < 13; i++) sum += (p.charAt(i) - '0') * ((i % 2 == 0) ? 3 : 1);
        return ((10 - (sum % 10)) % 10) == (p.charAt(13) - '0');
    }

    /** missing | malformed | checksum_invalid | valid. Only "malformed" is unusable as evidence. */
    public static String gtinValidity(String raw) {
        if (raw == null || raw.isBlank()) return "missing";
        if (gtinCanonical(raw) == null) return "malformed";
        return gtinChecksumOk(raw) ? "valid" : "checksum_invalid";
    }

    /** Alphanumeric-squeezed uppercase, used for MPN comparison (AL-XM6-B ~ ALXM6B). */
    public static String squeeze(String s) {
        if (s == null) return null;
        String t = clean(s).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    // ---------- model tokens ----------
    private static final Pattern CODE = Pattern.compile("\\b([A-Za-z]{1,8}-?\\d{1,4}[A-Za-z0-9]*(?:-[A-Za-z0-9]{1,6})*)\\b");
    private static final Pattern WORDNUM = Pattern.compile("\\b([A-Za-z]{3,15})[\\s-](\\d{1,4})\\b");
    private static final Set<String> STOP = Set.of(
            "inch", "gb", "tb", "ml", "oz", "mm", "us", "ram", "pack", "size", "months", "month", "fl",
            "the", "with", "for", "and", "pcs", "gen", "usb", "type", "series", "qled", "edp", "edt", "no",
            "unlocked", "phone", "black", "white", "silver", "midnight", "plus", "includes", "gift", "card",
            "payments", "save", "only", "total", "free", "up", "to", "x", "nvme", "pcie", "m", "lot", "pay",
            "members", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "set", "case");

    /** Tokens shaped like model codes that are really interface/form-factor specs. */
    private static final Set<String> SPEC_TOKENS = Set.of(
            "M2", "M22280", "PCIE4", "PCIE3", "PCIE5", "USB2", "USB3", "USBC",
            "WIFI5", "WIFI6", "WIFI7", "TYPE1", "TYPE2", "GEN1", "GEN2", "GEN3", "DDR4", "DDR5");

    /** A number followed by a unit is a measurement, not a model number ("Arc Series Two 41 mm"). */
    private static final Pattern UNIT_AFTER = Pattern.compile("^\\s?(mm|gb|tb|ml|oz|in\\b|inch|\"|percent|%|pack|x\\b|fl)", Pattern.CASE_INSENSITIVE);

    /** Generic nouns that must not become a brand-level fallback token. */
    private static final Set<String> GENERIC_HEAD = Set.of(
            "solid", "state", "drive", "internal", "wireless", "premium", "professional", "cordless",
            "countertop", "stick", "road", "running", "waterproof", "everyday", "quantum", "noise",
            "canceling", "last", "previous", "weekend", "special", "new", "with", "the", "and", "for",
            "plus", "best", "top");

    /**
     * Family base of a code token: leading letters + first digit-run
     * (AR41M-BW -> AR41, VVQ55-25 -> VVQ55, XM6 -> XM6).
     * Single-letter heads are legitimate model codes (P16, V12), so the head stays {1,8};
     * spec-shaped tokens are excluded by name instead.
     */
    public static String family(String token) {
        Matcher m = Pattern.compile("^([A-Za-z]{1,8})-?(\\d{1,4})").matcher(token);
        if (!m.find()) return null;
        String fam = (m.group(1) + m.group(2)).toUpperCase(Locale.ROOT);
        return SPEC_TOKENS.contains(fam) ? null : fam;
    }

    /**
     * Product families whose model code carries no digits (e.g. "Quanta NVX Pro") would otherwise
     * produce no blocking token at all. Fall back to brand-level tokens so such listings can still
     * be blocked together; the variant stage still requires full price-critical agreement, and
     * these weak tokens are excluded from model-contradiction checks.
     */
    public static LinkedHashSet<String> fallbackTokens(String title, String brand) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (brand != null) {
            String s = squeeze(brand);
            if (s != null && s.length() >= 3) out.add(s);
        }
        String t = clean(title);
        if (t != null) {
            Matcher m = Pattern.compile("[A-Za-z][A-Za-z\\-]{2,}").matcher(t);
            while (m.find()) {
                String w = m.group();
                String lw = w.toLowerCase(Locale.ROOT);
                if (GENERIC_HEAD.contains(lw) || STOP.contains(lw)) continue;
                String s = squeeze(w);
                if (s != null && s.length() >= 3) out.add(s);
                break;
            }
        }
        return out;
    }

    /**
     * Extract model-family tokens from free text (titles, MPNs, URL slugs).
     * Returns the set of family bases, e.g. "AeroRun Pegasus 41 ... AR41M-BW" -> {PEGASUS41, AR41}.
     */
    public static LinkedHashSet<String> familyTokens(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        String t = clean(text);
        Matcher c = CODE.matcher(t);
        while (c.find()) {
            String tok = c.group(1);
            String head = tok.replaceAll("[-\\d].*", "").toLowerCase(Locale.ROOT);
            if (STOP.contains(head)) continue;
            String fam = family(tok);
            if (fam != null && fam.length() >= 2 && !STOP.contains(fam.toLowerCase(Locale.ROOT))) out.add(fam);
        }
        Matcher w = WORDNUM.matcher(t);
        while (w.find()) {
            String word = w.group(1).toLowerCase(Locale.ROOT);
            if (STOP.contains(word)) continue;
            if (UNIT_AFTER.matcher(t.substring(w.end())).find()) continue;   // measurement, not a model
            out.add((w.group(1) + w.group(2)).toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** Full squeezed code tokens (e.g. "VVQ55-25" -> VVQ5525) — discriminate generation-suffixed codes. */
    public static LinkedHashSet<String> fullCodes(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher c = CODE.matcher(clean(text));
        while (c.find()) {
            String tok = c.group(1);
            String head = tok.replaceAll("[-\\d].*", "").toLowerCase(Locale.ROOT);
            if (STOP.contains(head)) continue;
            if (!tok.matches(".*\\d.*")) continue;
            String sq = squeeze(tok);
            if (sq != null && sq.length() >= 3) out.add(sq);
        }
        return out;
    }

    // ---------- title attribute extraction (derivation=normalized from title text) ----------
    private static final Set<String> COLORS = Set.of("black", "white", "silver", "midnight", "starlight", "blue",
            "ocean blue", "ultramarine", "red", "natural", "gold", "gray", "grey", "green", "pink", "blk");
    private static final Map<String, String> COLOR_ALIAS = Map.of("blk", "black");

    public static List<AttrEv> fromTitle(String title, String sourceField) {
        List<AttrEv> out = new ArrayList<>();
        if (title == null) return out;
        String t = clean(title);
        String lower = t.toLowerCase(Locale.ROOT);

        // color (longest phrase match wins)
        String colorFound = null;
        for (String c : COLORS) {
            if (Pattern.compile("\\b" + Pattern.quote(c) + "\\b").matcher(lower).find()) {
                if (colorFound == null || c.length() > colorFound.length()) colorFound = c;
            }
        }
        if (lower.contains("black/white") || lower.contains("black white")) colorFound = "black/white";
        if (colorFound != null)
            out.add(new AttrEv("color", TypedValue.str(COLOR_ALIAS.getOrDefault(colorFound, colorFound)), sourceField, colorFound, "normalized", false));

        // storage / capacity (GB/TB). Declared policy for these fixtures: 1 TB == 1000 GB,
        // and marketing "1024GB" is the nominal 1000 GB tier. Largest capacity wins when both
        // RAM-like and storage-like numbers appear ("16GB 256GB"); numbers followed by RAM are excluded.
        Matcher st = Pattern.compile("\\b(\\d{2,4})\\s?gb\\b(?!\\s?ram)", Pattern.CASE_INSENSITIVE).matcher(t);
        double bestGb = -1; String bestRaw = null;
        while (st.find()) {
            double v = Double.parseDouble(st.group(1));
            if (v > bestGb) { bestGb = v; bestRaw = st.group(); }
        }
        if (bestGb > 0) {
            double nominal = bestGb == 1024 ? 1000 : bestGb == 2048 ? 2000 : bestGb;
            out.add(new AttrEv("storage_gb", TypedValue.num(nominal), sourceField, bestRaw, "normalized", false));
        }
        Matcher tb = Pattern.compile("\\b(\\d{1,2})\\s?tb\\b", Pattern.CASE_INSENSITIVE).matcher(t);
        if (tb.find()) out.add(new AttrEv("storage_gb", TypedValue.num(Double.parseDouble(tb.group(1)) * 1000), sourceField, tb.group(), "normalized", false));

        // volume: ml and fl oz (1 fl oz = 29.5735 ml, rounded to nominal 30/50/100)
        Matcher ml = Pattern.compile("\\b(\\d{1,4})\\s?m[lL]\\b").matcher(t);
        if (ml.find()) out.add(new AttrEv("volume_ml", TypedValue.num(Double.parseDouble(ml.group(1))), sourceField, ml.group(), "normalized", false));
        else {
            Matcher oz = Pattern.compile("\\b(\\d(?:\\.\\d)?)\\s?fl\\s?oz\\b", Pattern.CASE_INSENSITIVE).matcher(t);
            if (oz.find()) {
                double v = Double.parseDouble(oz.group(1)) * 29.5735;
                double nominal = Math.abs(v - 30) < 2 ? 30 : Math.abs(v - 50) < 2 ? 50 : Math.abs(v - 100) < 3 ? 100 : Math.round(v);
                out.add(new AttrEv("volume_ml", TypedValue.num(nominal), sourceField, oz.group(), "inferred", false));
            }
        }

        // shoe / apparel size
        Matcher us = Pattern.compile("\\bus\\s?(\\d{1,2}(?:\\.5)?)\\b", Pattern.CASE_INSENSITIVE).matcher(t);
        if (us.find()) out.add(new AttrEv("size_us", TypedValue.str(us.group(1)), sourceField, us.group(), "normalized", false));
        if (lower.matches(".*\\b(medium)\\b.*")) out.add(new AttrEv("size", TypedValue.str("M"), sourceField, "medium", "normalized", false));
        else if (lower.matches(".*\\b(large)\\b.*") && !lower.contains("x-large")) out.add(new AttrEv("size", TypedValue.str("L"), sourceField, "large", "normalized", false));
        else if (lower.matches(".*\\b(small)\\b.*")) out.add(new AttrEv("size", TypedValue.str("S"), sourceField, "small", "normalized", false));

        // department
        if (lower.matches(".*\\b(men's|mens)\\b.*")) out.add(new AttrEv("department", TypedValue.str("mens"), sourceField, "men's", "normalized", false));
        else if (lower.matches(".*\\b(women's|womens)\\b.*")) out.add(new AttrEv("department", TypedValue.str("womens"), sourceField, "women's", "normalized", false));

        // screen inches
        Matcher in = Pattern.compile("\\b(\\d{2}(?:\\.\\d)?)[\\s-]?(?:inch|in\\b|\")", Pattern.CASE_INSENSITIVE).matcher(t);
        if (in.find()) out.add(new AttrEv("screen_in", TypedValue.num(Double.parseDouble(in.group(1))), sourceField, in.group(), "normalized", false));
        Matcher in2 = Pattern.compile("\\b(1[0-7](?:\\.\\d)?)[\\s-]?(?:inch|in\\b)", Pattern.CASE_INSENSITIVE).matcher(t);
        if (in2.find()) out.add(new AttrEv("screen_in", TypedValue.num(Double.parseDouble(in2.group(1))), sourceField, in2.group(), "normalized", false));

        // watch case size
        Matcher mm = Pattern.compile("\\b(\\d{2})\\s?mm\\b", Pattern.CASE_INSENSITIVE).matcher(t);
        if (mm.find()) out.add(new AttrEv("case_size_mm", TypedValue.num(Double.parseDouble(mm.group(1))), sourceField, mm.group(), "normalized", false));

        // RAM
        Matcher ram = Pattern.compile("\\b(\\d{1,2})\\s?gb\\s?ram\\b", Pattern.CASE_INSENSITIVE).matcher(t);
        if (ram.find()) out.add(new AttrEv("ram_gb", TypedValue.num(Double.parseDouble(ram.group(1))), sourceField, ram.group(), "normalized", false));

        // multipack
        Matcher pk = Pattern.compile("\\b(\\d)\\s?[-\\s]?pack\\b|\\b(\\d)\\s?x\\b", Pattern.CASE_INSENSITIVE).matcher(t);
        if (pk.find()) {
            String n = pk.group(1) != null ? pk.group(1) : pk.group(2);
            out.add(new AttrEv("bundle", TypedValue.str(n + "_pack"), sourceField, pk.group(), "normalized", false));
        }
        if (lower.contains("single pair")) out.add(new AttrEv("bundle", TypedValue.str("single_pair"), sourceField, "single pair", "normalized", false));
        else if (lower.contains("standard tools")) out.add(new AttrEv("bundle", TypedValue.str("standard_tools"), sourceField, "standard tools", "normalized", false));
        else if (lower.contains("watch only")) out.add(new AttrEv("bundle", TypedValue.str("watch_only"), sourceField, "watch only", "normalized", false));
        else if (lower.contains("drive only")) out.add(new AttrEv("bundle", TypedValue.str("drive_only"), sourceField, "drive only", "normalized", false));
        else if (lower.contains("standard pitcher")) out.add(new AttrEv("bundle", TypedValue.str("standard_pitcher"), sourceField, "standard pitcher", "normalized", false));
        else if (lower.contains("gift set") || lower.contains("set with travel")) out.add(new AttrEv("bundle", TypedValue.str("gift_set"), sourceField, "gift set", "normalized", false));

        // edition (consoles)
        if (lower.contains("digital")) out.add(new AttrEv("edition", TypedValue.str("digital"), sourceField, "digital", "normalized", false));
        else if (lower.contains("disc") || lower.contains("optical drive")) out.add(new AttrEv("edition", TypedValue.str("disc"), sourceField, "disc/optical", "normalized", false));

        // generation year (tvs)
        Matcher yr = Pattern.compile("\\b(20\\d{2})\\b").matcher(t);
        if (yr.find()) out.add(new AttrEv("generation", TypedValue.num(Double.parseDouble(yr.group(1))), sourceField, yr.group(), "normalized", false));

        // fragrance formulation
        if (lower.contains("eau de parfum") || lower.matches(".*\\bedp\\b.*")) out.add(new AttrEv("formulation", TypedValue.str("eau_de_parfum"), sourceField, "eau de parfum", "normalized", false));
        else if (lower.contains("eau de toilette") || lower.matches(".*\\bedt\\b.*")) out.add(new AttrEv("formulation", TypedValue.str("eau_de_toilette"), sourceField, "eau de toilette", "normalized", false));

        return out;
    }

    /** Canonicalize a known attribute value (color aliasing, case folding for strings). */
    public static TypedValue canonValue(String key, TypedValue v) {
        if (v == null) return null;
        if ("color".equals(key) && v.str != null) {
            String s = v.str.trim().toLowerCase(Locale.ROOT);
            return TypedValue.str(COLOR_ALIAS.getOrDefault(s, s));
        }
        if (v.str != null) return TypedValue.str(v.str.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
        return v;
    }

    /** Extract a merchant SKU-ish slug from a product/page URL path (last segment), ignoring query params. */
    public static String skuFromUrl(String url) {
        if (url == null) return null;
        try {
            String path = java.net.URI.create(url.trim()).getPath();
            if (path == null || path.isBlank()) return null;
            String[] segs = path.split("/");
            String last = segs.length > 0 ? segs[segs.length - 1] : null;
            return (last == null || last.isBlank()) ? null : last;
        } catch (Exception e) { return null; }
    }
}
