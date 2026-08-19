package com.dealdog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealdog.engine.Catalog;
import com.dealdog.engine.Model.*;
import com.dealdog.export.Exporter;
import com.dealdog.ingest.Ingestor;
import com.dealdog.normalize.Norm;
import com.dealdog.policy.Policies;
import com.dealdog.util.J;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural tests for the failure modes considered most dangerous, plus the executable
 * invariants stated in README_TRIAL.md.
 *
 * Run: mvn test
 */
@ExtendWith(TestLogger.class)
public class PipelineTest {

    // ------------------------------------------------------------------ harness
    /**
     * Locate the supplied fixtures. Works from the split layout (deliverable/ next to provided/),
     * from a flat package, or from an explicit DEALDOG_PROVIDED_DIR.
     */
    private static Path data() {
        List<String> roots = new ArrayList<>();
        String env = System.getenv("DEALDOG_PROVIDED_DIR");
        if (env != null && !env.isBlank()) roots.add(env);
        roots.addAll(List.of("../provided", "provided", "..", "."));
        for (String r : roots) {
            Path p = Path.of(r).resolve("data");
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException(
                "cannot locate the supplied data/ directory; set DEALDOG_PROVIDED_DIR");
    }

    private static Catalog freshCatalog() { return new Catalog(Policies.load()); }

    private static List<JsonNode> csvRecords(Path p) throws IOException {
        List<String> lines = Files.readAllLines(p);
        String[] header = splitCsv(lines.get(0));
        List<JsonNode> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] cells = splitCsv(lines.get(i));
            ObjectNode o = J.obj();
            for (int c = 0; c < header.length; c++) o.put(header[c], c < cells.length ? cells[c] : "");
            out.add(o);
        }
        return out;
    }

    /** minimal RFC4180 splitter (quoted commas appear in promotion_text) */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') q = false;
                else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static List<JsonNode> arrayOf(Path p, String key) throws IOException {
        JsonNode doc = J.parse(Files.readString(p));
        JsonNode arr = key == null ? doc : doc.get(key);
        List<JsonNode> out = new ArrayList<>();
        arr.forEach(out::add);
        return out;
    }

    /** Ingest the full supplied history (initial sources + three ordered phases). */
    private static Ingestor loadAll(Catalog cat) throws IOException {
        return loadAll(cat, new Ingestor(cat));
    }

    /**
     * Replay the same history through an EXISTING ingestor, mirroring how the service replays
     * its durable event log: the log is what makes replay idempotent.
     */
    private static Ingestor loadAll(Catalog cat, Ingestor ing) throws IOException {
        Ingestor.Envelope base = new Ingestor.Envelope();
        ing.ingest("affiliate_a", "initial-affiliate-a", csvRecords(data().resolve("initial/affiliate_a.csv")), base);
        ing.ingest("affiliate_b", "initial-affiliate-b", arrayOf(data().resolve("initial/affiliate_b.json"), "products"), base);
        ing.ingest("retailer_api", "initial-retailer-api", arrayOf(data().resolve("initial/retailer_api.json"), "items"), base);
        ing.ingest("community_deals", "initial-community-deals", arrayOf(data().resolve("initial/community_deals.json"), null), base);
        ing.ingest("extension_observations", "initial-extension", arrayOf(data().resolve("initial/extension_observations.json"), "observations"), base);
        Ingestor.Counts c = new Ingestor.Counts();
        for (int phase = 1; phase <= 3; phase++)
            for (JsonNode ev : arrayOf(data().resolve("incremental/incremental_phase_" + phase + ".json"), "events"))
                ing.ingestEvent(ev, c);
        cat.prune();
        return ing;
    }

    private static Listing byRecord(Catalog cat, String recordId) {
        for (Listing l : cat.listings.values()) if (l.sourceRecordId.equals(recordId)) return l;
        return null;
    }

    // ------------------------------------------------------------------ unit-level
    @Test
    void gtinChecksumFailureDowngradesButDoesNotEraseIdentifier() {
        // The supplied fixtures use synthetic GTINs whose check digits do not validate.
        // A failed check digit is evidence QUALITY, never proof the code does not exist:
        // discarding it would destroy the primary blocking index.
        assertEquals("checksum_invalid", Norm.gtinValidity("00850000100011"));
        assertNotNull(Norm.gtinCanonical("00850000100011"));
        assertEquals("malformed", Norm.gtinValidity("0085000010001?"));
        assertNull(Norm.gtinCanonical("0085000010001?"));
        // numeric resemblance alone must never merge two codes
        assertNotEquals(Norm.gtinCanonical("85000020011"), Norm.gtinCanonical("00850000200117"));
    }

    @Test
    void malformedMoneyIsInvalidEvidenceNotAnInventedPrice() {
        assertNull(Norm.parseMoney("USD twelve??"));
        assertEquals(1142.49, Norm.parseMoney("$1,142.49"), 1e-9);
        assertEquals(429.99, Norm.parseMoney("429.99"), 1e-9);
    }

    @Test
    void measurementsAndSpecsDoNotBecomeModelTokens() {
        assertFalse(Norm.familyTokens("Arc Series Two 41 mm wearable").contains("TWO41"));
        assertNull(Norm.family("m2"));                       // form factor, not a model
        assertEquals("P16", Norm.family("P16-256-BLK"));     // single-letter heads stay valid
        assertEquals("V12", Norm.family("V12"));
    }

    @Test
    void unknownCategoryFallsBackToConservativePolicy() {
        Policies p = Policies.load();
        assertTrue(p.isKnownCategory("phones"));
        assertFalse(p.isKnownCategory("totally_novel_category"));
        assertFalse(p.forCategory("totally_novel_category").priceCritical.isEmpty());
    }

    // ------------------------------------------------------------------ adapter boundary
    @Test
    void adapterSelectionIsPerRecordSoOneSourceMayCarrySeveralSchemas() throws IOException {
        Catalog cat = freshCatalog();
        Ingestor ing = new Ingestor(cat);
        List<JsonNode> items = arrayOf(data().resolve("initial/retailer_api.json"), "items");
        ing.ingest("retailer_api", "b", items, new Ingestor.Envelope());
        // ra_0016 is delivered in the "2026-08-compact" shape inside an otherwise v1 feed
        Listing compact = byRecord(cat, "ra_0016");
        Listing classic = byRecord(cat, "ra_0015");
        assertNotNull(compact);
        assertNotNull(classic);
        assertTrue(compact.adapter.contains("2026-08-compact"), "compact record must select the compact adapter");
        assertTrue(classic.adapter.contains("v1"), "classic record must select the v1 adapter");
        assertFalse(compact.quarantined, "a new shape inside a known source must not fail the batch");
        // minor-unit money is decoded, not taken literally
        assertEquals(92.09, compact.money.get(0).amount, 1e-6);
    }

    /**
     * REQUIRED BY THE BRIEF: the same logical source record changes compatible shape/version
     * while referring to one logical listing. Proves source-ID continuity + normalized
     * equivalence, and that each event is parsed with its OWN schema.
     */
    @Test
    void sameLogicalRecordAcrossSchemaVersionsKeepsOneIdentity() {
        Catalog cat = freshCatalog();
        Ingestor ing = new Ingestor(cat);
        Ingestor.Envelope env = new Ingestor.Envelope();

        JsonNode v1 = J.parse("""
            {"observation_id":"ra_9001","store":{"display_name":"BestElectro","store_id":"CFC16"},
             "sku":"SKU-EVOLVE-1",
             "product":{"name":"Quanta NVX Pro Solid State Drive","brand":"Quanta","model":"QNVX","barcode":null,
                        "specifications":{"capacity_gb":1000,"interface":"pcie_4_nvme","form_factor":"m2_2280",
                                          "heatsink":"none","condition":"new","bundle":"drive_only"}},
             "offer":{"price":{"amount":93.5,"currency":"USD"},"listPrice":99.0,"availabilityCode":"in_stock","terms":{}},
             "observed_at":"2026-08-11T09:00:00Z","product_url":"https://bestelectro.invalid/p/SKU-EVOLVE-1",
             "semantics":{"identifier_scopes":{},"product_type":"primary_product","price_kind":"total_purchase_price"}}""");
        env.eventId = "ev-v1";
        ing.ingest("retailer_api", "evolve", List.of(v1), env);

        JsonNode v2 = J.parse("""
            {"schema_version":"2026-08-compact","observation_id":"ra_9001",
             "store":{"display_name":"BestElectro","store_id":"CFC16"},"sku":"SKU-EVOLVE-1",
             "product":{"displayName":"Quanta NVX Pro Solid State Drive",
                        "identity":{"brand":"Quanta","model":"QNVX","codes":{}},
                        "configuration":[{"key":"capacity_gb","value":1000},{"key":"interface","value":"pcie_4_nvme"},
                                         {"key":"form_factor","value":"m2_2280"},{"key":"heatsink","value":"none"},
                                         {"key":"condition","value":"new"},{"key":"bundle","value":"drive_only"}]},
             "offer":{"money":{"minorAmount":9350,"currency":"USD"},"availability":"in_stock","context":{}},
             "observed_at":"2026-08-12T09:00:00Z","product_url":"https://bestelectro.invalid/p/SKU-EVOLVE-1",
             "semantics":{"productType":"primary_product","priceKind":"total_purchase_price"}}""");
        Ingestor.Envelope env2 = new Ingestor.Envelope();
        env2.eventId = "ev-v2";
        ing.ingest("retailer_api", "evolve", List.of(v2), env2);

        long forRecord = cat.listings.values().stream().filter(l -> l.sourceRecordId.equals("ra_9001")).count();
        assertEquals(1, forRecord, "one logical source record must remain one listing across schema versions");

        Listing l = byRecord(cat, "ra_9001");
        assertEquals("2026-08-compact", l.schemaVersion, "current state must reflect the newest schema");
        assertEquals(1000.0, ((Number) l.asserted("capacity_gb").plain()).doubleValue(), 1e-9,
                "normalized equivalence across shapes");
        assertEquals(93.5, l.money.get(0).amount, 1e-9);
        assertEquals(2, l.history.size(), "both events retained in history");
        Set<String> parsers = new HashSet<>();
        l.history.forEach(h -> parsers.add(h.get("schema_version").asText()));
        assertEquals(Set.of("v1", "2026-08-compact"), parsers, "each event keeps its own parser provenance");
    }

    @Test
    void unknownShapeIsQuarantinedWithItsRealSourceRecordId() {
        Catalog cat = freshCatalog();
        Ingestor ing = new Ingestor(cat);
        // a nested/versioned envelope no adapter claims - identity must survive for later joins
        JsonNode weird = J.parse("""
            {"envelope":{"version":9,"data":{"recordId":"aa_9999","attrs":[{"name":"color","value":"black"}]}}}""");
        Ingestor.Counts c = ing.ingest("affiliate_a", "odd", List.of(weird), new Ingestor.Envelope());
        assertEquals(1, c.quarantined);
        Listing l = byRecord(cat, "aa_9999");
        assertNotNull(l, "quarantine must preserve the real nested source-record id");
        assertTrue(l.quarantined);
        assertFalse(l.sourceRecordId.startsWith("quarantine:"));
        assertEquals("REVIEW", l.decision, "quarantined rows still get an explicit decision");
    }

    // ------------------------------------------------------------------ I1 variant safety
    @Test
    void invariantI1_noVariantMixesConflictingPriceCriticalDimensions() throws IOException {
        Catalog cat = freshCatalog();
        loadAll(cat);
        for (Variant v : cat.variants.values()) {
            Product p = cat.products.get(v.productId);
            List<String> critical = cat.policies.forCategory(p == null ? "unknown" : p.category).priceCritical;
            for (String dim : critical) {
                Set<String> seen = new HashSet<>();
                for (String lid : v.listingIds) {
                    Listing l = cat.listings.get(lid);
                    String val = l == null ? null : l.assertedCanon(dim);
                    if (val != null) seen.add(val);
                }
                assertTrue(seen.size() <= 1,
                        "variant " + v.id + " mixes " + dim + " values " + seen + " - unsafe price comparison");
            }
        }
    }

    // ------------------------------------------------------------------ I2 abstention
    @Test
    void invariantI2_matchedListingsCarryEveryPriceCriticalDimension() throws IOException {
        Catalog cat = freshCatalog();
        loadAll(cat);
        for (Listing l : cat.listings.values()) {
            if (!"MATCH".equals(l.decision) || l.variantId == null) continue;
            Product p = cat.products.get(l.productId);
            if (p == null || !cat.policies.isKnownCategory(p.category)) continue;
            Variant v = cat.variants.get(l.variantId);
            for (String dim : cat.policies.forCategory(p.category).priceCritical) {
                // the variant it joined must actually pin the dimension, or nobody in the
                // cluster asserts it (genuinely unknown for the whole model)
                boolean pinned = v.attrs.containsKey(dim);
                boolean anyoneKnows = v.listingIds.stream()
                        .map(cat.listings::get).filter(Objects::nonNull)
                        .anyMatch(x -> x.assertedCanon(dim) != null);
                assertTrue(pinned || !anyoneKnows,
                        "MATCH on " + l.sourceRecordId + " left price-critical " + dim + " unresolved");
            }
        }
    }

    @Test
    void ambiguousVariantAbstainsAndKeepsViableHypotheses() throws IOException {
        Catalog cat = freshCatalog();
        loadAll(cat);
        boolean sawReviewWithHypotheses = false;
        for (Listing l : cat.listings.values()) {
            if (!"REVIEW".equals(l.decision)) continue;
            assertNull(l.variantId, "a REVIEW listing must not claim an exact variant");
            if (l.candidateCount > 1) {
                assertTrue(l.hypotheses.size() > 0,
                        "finite ambiguity on " + l.sourceRecordId + " must retain viable hypotheses");
                sawReviewWithHypotheses = true;
            }
        }
        assertTrue(sawReviewWithHypotheses, "the corpus should exercise finite REVIEW ambiguity");
    }

    /** A copied identifier must not merge different marketed models. */
    @Test
    void identifierCopiedFromAnotherModelDoesNotMerge() throws IOException {
        Catalog cat = freshCatalog();
        loadAll(cat);
        // ab_0028 / ra_0036 / ab_0030 are PinePhone 15 listings carrying PinePhone 16 GTINs
        for (String rec : List.of("ab_0028", "ra_0036", "ab_0030")) {
            Listing l = byRecord(cat, rec);
            assertNotNull(l, rec);
            assertEquals("REVIEW", l.decision, rec + " carries a contradicted identifier and must abstain");
        }
        Listing p16 = byRecord(cat, "ab_0010");
        Listing p15 = byRecord(cat, "ab_0028");
        assertNotEquals(p16.productId, p15.productId, "P15 and P16 must not share a universal product");
    }

    // ------------------------------------------------------------------ I3 idempotency
    @Test
    void invariantI3_byteIdenticalReplayChangesNothing() throws IOException {
        Catalog cat = freshCatalog();
        Ingestor ing = loadAll(cat);
        String before = J.write(new Exporter(cat).fullExport());
        loadAll(cat, ing);                         // replay the entire history verbatim
        String after = J.write(new Exporter(cat).fullExport());
        assertEquals(before, after, "replaying an applied history must not change exported state");
    }

    @Test
    void duplicateEventIdWithMutatedBytesIsAConflictNotASecondApply() {
        Catalog cat = freshCatalog();
        Ingestor ing = new Ingestor(cat);
        JsonNode a = J.parse("""
            {"record_id":"aa_7001","merchant":"BestElectro","merchant_sku":"S1","product_name":"Auralux XM6 black",
             "sale_price":"399.99","retail_price":"449.99","currency":"USD","ean":"00850000100011",
             "manufacturer_part_number":"AL-XM6-B","availability":"in_stock","condition":"new",
             "promotion_text":"","deep_link":"x","last_updated":"2026-08-10T12:00:00Z",
             "price_kind":"total_purchase_price","comparability":"COMPARABLE","upstream_origin":"",
             "product_type":"primary_product","exclusions":"[]"}""");
        ObjectNode b = (ObjectNode) a.deepCopy();
        b.put("sale_price", "1.00");               // same delivery identity, different bytes

        Ingestor.Envelope env = new Ingestor.Envelope();
        env.eventId = "evt-1";
        assertEquals(1, ing.ingest("affiliate_a", "x", List.of(a), env).accepted);

        Ingestor.Envelope env2 = new Ingestor.Envelope();
        env2.eventId = "evt-1";
        Ingestor.Counts c = ing.ingest("affiliate_a", "x", List.of(b), env2);
        assertEquals(0, c.accepted);
        assertEquals(1, c.quarantined, "a repeated event id with mutated payload is a conflict");
        assertEquals(399.99, byRecord(cat, "aa_7001").money.get(0).amount, 1e-9, "first applied value stands");
    }

    @Test
    void batchLevelEventIdDoesNotSuppressLaterRecordsInThatBatch() throws IOException {
        Catalog cat = freshCatalog();
        Ingestor ing = new Ingestor(cat);
        Ingestor.Envelope env = new Ingestor.Envelope();
        env.eventId = "one-transport-id-for-the-whole-batch";
        List<JsonNode> records = arrayOf(data().resolve("initial/affiliate_b.json"), "products");
        Ingestor.Counts c = ing.ingest("affiliate_b", "batch", records, env);
        assertTrue(c.accepted > 20,
                "request-level event id must not collapse a multi-record batch (accepted=" + c.accepted + ")");
    }

    // ------------------------------------------------------------------ I4 field state
    @Test
    void invariantI4_patchOmissionNullAndTombstoneAreDistinct() {
        Catalog cat = freshCatalog();
        Ingestor ing = new Ingestor(cat);

        JsonNode base = J.parse("""
            {"eventId":"ab_8001","advertiser":{"name":"BestElectro","id":"1"},
             "item":{"title":"Auralux SilencePro XM6 Wireless Headphones Black","merchantItemId":"M1",
                     "identifiers":{"globalTradeItemNumber":"00850000100011","manufacturerCode":"AL-XM6-B"},
                     "variant":{"color":"black","condition":"new","bundle":"standalone"}},
             "pricing":{"current":399.99,"original":449.99,"currencyCode":"USD","promotion":{}},
             "stock":{"status":"in_stock","capturedAt":"2026-08-10T12:00:00Z"},
             "semantics":{"identifierScopes":{"gtin":"exact_variant"},"productType":"primary_product",
                          "priceKind":"total_purchase_price","comparabilityHint":"COMPARABLE"}}""");
        Ingestor.Envelope e1 = new Ingestor.Envelope();
        e1.eventId = "s1";
        ing.ingest("affiliate_b", "b", List.of(base), e1);
        Listing l = byRecord(cat, "ab_8001");
        assertEquals("black", l.assertedCanon("color"));

        // partial patch that OMITS colour -> prior value retained
        JsonNode patch = J.parse("""
            {"eventId":"ab_8001","advertiser":{"name":"BestElectro","id":"1"},
             "item":{"title":"Auralux SilencePro XM6 Wireless Headphones Black","merchantItemId":"M1",
                     "identifiers":{"globalTradeItemNumber":"00850000100011","manufacturerCode":"AL-XM6-B"},
                     "variant":{"condition":"new"}},
             "pricing":{"current":389.99,"original":449.99,"currencyCode":"USD","promotion":{}},
             "stock":{"status":"in_stock","capturedAt":"2026-08-11T12:00:00Z"},
             "semantics":{"productType":"primary_product","priceKind":"total_purchase_price"}}""");
        Ingestor.Envelope e2 = new Ingestor.Envelope();
        e2.eventId = "s2";
        e2.updateMode = "partial_patch";
        ing.ingest("affiliate_b", "b", List.of(patch), e2);
        assertEquals("black", l.assertedCanon("color"), "omission in a patch is not a withdrawal");
        assertEquals(389.99, l.money.get(0).amount, 1e-9, "supplied fields still change");

        // tombstone -> identity and history preserved, lifecycle inactive
        Ingestor.Envelope e3 = new Ingestor.Envelope();
        e3.eventId = "s3";
        e3.operation = "unavailable";
        ing.ingest("affiliate_b", "b", List.of(patch), e3);
        assertEquals("inactive", l.lifecycle);
        assertEquals("black", l.assertedCanon("color"), "a tombstone must not erase evidence");
        assertTrue(l.history.size() >= 3, "history retained across lifecycle changes");

        // reappearance reactivates the SAME stable listing identity
        Ingestor.Envelope e4 = new Ingestor.Envelope();
        e4.eventId = "s4";
        ing.ingest("affiliate_b", "b", List.of(base), e4);
        assertEquals("active", l.lifecycle);
        assertEquals(1, cat.listings.values().stream().filter(x -> x.sourceRecordId.equals("ab_8001")).count());
    }

    @Test
    void lateArrivingOlderEventStaysHistoryAndDoesNotRegressCurrentState() {
        Catalog cat = freshCatalog();
        Ingestor ing = new Ingestor(cat);
        String tpl = """
            {"record_id":"aa_7100","merchant":"HomeHub","merchant_sku":"S9",
             "product_name":"Cyclone Home V12 Cordless Vacuum CV12 standard tools",
             "sale_price":"%s","retail_price":"549.99","currency":"USD","ean":"00850001201114",
             "manufacturer_part_number":"CV12-STD","availability":"in_stock","condition":"new",
             "promotion_text":"","deep_link":"x","last_updated":"%s",
             "price_kind":"total_purchase_price","comparability":"COMPARABLE","upstream_origin":"",
             "product_type":"primary_product","exclusions":"[]"}""";

        Ingestor.Envelope e1 = new Ingestor.Envelope();
        e1.eventId = "new";
        ing.ingest("affiliate_a", "t", List.of(J.parse(String.format(tpl, "499.99", "2026-08-10T12:00:00Z"))), e1);

        Ingestor.Envelope e2 = new Ingestor.Envelope();
        e2.eventId = "old";                                    // received last, but OLDER source clock
        ing.ingest("affiliate_a", "t", List.of(J.parse(String.format(tpl, "111.11", "2026-07-15T12:00:00Z"))), e2);

        Listing l = byRecord(cat, "aa_7100");
        assertEquals("2026-08-10T12:00:00Z", l.sourceUpdatedAt, "receipt order alone is not precedence");
        assertEquals(499.99, l.money.get(0).amount, 1e-9, "a stale event must not regress current state");
        assertEquals(2, l.history.size(), "the stale event is still retained as history");
        boolean anyNotApplied = l.history.stream().anyMatch(h -> !h.get("applied").asBoolean());
        assertTrue(anyNotApplied, "history must record that the stale event was not applied");
    }

    // ------------------------------------------------------------------ I5 correction audit
    @Test
    void invariantI5_correctionsAreAuditableAndDoNotRewriteHistory() throws IOException {
        Catalog cat = freshCatalog();
        loadAll(cat);

        // ra_0021 is authoritatively corrected from PinePhone 16 to PinePhone 17
        Listing corrected = byRecord(cat, "ra_0021");
        assertNotNull(corrected);
        assertTrue(corrected.raw.toString().contains("PinePhone 17"), "correction payload applied");

        boolean audited = cat.audits.stream().anyMatch(a ->
                a.listingInternalId.equals(corrected.internalId)
                && !Objects.equals(a.priorProduct, a.newProduct));
        assertTrue(audited, "an assignment change must leave an audit row linking prior -> new");

        for (AuditEvent a : cat.audits) {
            assertNotNull(a.listingInternalId);
            assertNotNull(a.changedAt);
            assertNotNull(a.reason);
        }

        // observations keep the assignment known AT observation time
        boolean sawHistoricalAssignment = false;
        for (Offer o : cat.offers.values())
            for (Observation ob : o.observations)
                if (ob.variantAtObservation != null && !Objects.equals(ob.variantAtObservation, o.variantId))
                    sawHistoricalAssignment = true;
        assertTrue(sawHistoricalAssignment,
                "prior observations must retain their original variant, not be rewritten onto the new one");
    }

    @Test
    void correctionChainNarrowsRatherThanInventingANewAnswer() throws IOException {
        Catalog cat = freshCatalog();
        loadAll(cat);
        // xo_0015 (configurable page) -> corrected to 512GB (inc_018) -> corrected to 128GB (inc_020)
        Listing l = byRecord(cat, "xo_0015");
        assertNotNull(l);
        assertEquals(128.0, ((Number) l.asserted("storage_gb").plain()).doubleValue(), 1e-9,
                "the newest authoritative correction wins");
        assertTrue(l.history.size() >= 3, "every correction stays in history");
    }

    // ------------------------------------------------------------------ I6 rebuild equivalence
    @Test
    void invariantI6_cleanRebuildReproducesTheSamePartitions() throws IOException {
        Catalog a = freshCatalog();
        loadAll(a);
        Catalog b = freshCatalog();
        loadAll(b);
        assertEquals(partitions(a), partitions(b),
                "an incrementally maintained catalog and a clean replay must agree on entity partitions");
        assertEquals(decisionShape(a), decisionShape(b), "decisions and lifecycle must agree");
    }

    private static Set<List<String>> partitions(Catalog cat) {
        Map<String, List<String>> byVariant = new TreeMap<>();
        for (Listing l : cat.listings.values())
            if (l.variantId != null) byVariant.computeIfAbsent(l.variantId, k -> new ArrayList<>()).add(l.sourceRecordId);
        Set<List<String>> out = new TreeSet<>(Comparator.comparing(Object::toString));
        byVariant.values().forEach(v -> { Collections.sort(v); out.add(v); });
        return out;
    }

    private static List<String> decisionShape(Catalog cat) {
        List<String> out = new ArrayList<>();
        for (Listing l : cat.listings.values())
            out.add(l.sourceRecordId + "|" + l.decision + "|" + (l.productId != null) + "|"
                    + (l.variantId != null) + "|" + l.lifecycle);
        Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------------ I7 telemetry honesty
    @Test
    void invariantI7_candidateTelemetryReconcilesExactly() throws IOException {
        Catalog cat = freshCatalog();
        loadAll(cat);
        ArrayNode decisions = new Exporter(cat).decisions();
        assertTrue(decisions.size() > 0);
        for (JsonNode d : decisions) {
            Set<String> generated = new TreeSet<>();
            d.get("candidate_sources").fields().forEachRemaining(e -> e.getValue().forEach(x -> generated.add(x.asText())));
            Set<String> scored = new TreeSet<>();
            d.get("scored_candidate_ids").forEach(x -> scored.add(x.asText()));
            String id = d.get("listing_id").asText();
            assertEquals(generated.size(), d.get("candidate_count").asInt(),
                    "candidate_count must equal the unique candidate union for " + id);
            assertEquals(scored.size(), d.get("scored_candidate_count").asInt(), id);
            assertTrue(generated.containsAll(scored), "scored candidates must be a subset of generated for " + id);
            if ("MATCH".equals(d.get("decision").asText()))
                assertTrue(d.get("positive_signals").size() > 0, "MATCH needs traceable positive evidence: " + id);
        }
    }

    // ------------------------------------------------------------------ offers / promotions
    @Test
    void monetarySemanticsAndComparabilityArePreserved() throws IOException {
        Catalog cat = freshCatalog();
        loadAll(cat);
        boolean sawInstallment = false, sawTradeIn = false, sawConditional = false;
        for (Offer o : cat.offers.values())
            for (Observation ob : o.observations) {
                if ("monthly_installment".equals(ob.priceKind)) { sawInstallment = true; assertNotEquals("COMPARABLE", ob.comparability); }
                if ("trade_in_net_price".equals(ob.priceKind)) { sawTradeIn = true; assertNotEquals("COMPARABLE", ob.comparability); }
                if ("CONDITIONAL".equals(ob.comparability)) { sawConditional = true; assertFalse(ob.terms.isEmpty(), "conditional prices must retain their requirements"); }
            }
        assertTrue(sawInstallment, "a monthly installment must stay distinguishable from a total price");
        assertTrue(sawTradeIn, "a trade-in net amount must stay distinguishable from a sale price");
        assertTrue(sawConditional, "conditional pricing must be represented");
    }

    @Test
    void everyDeliveredRecordIsAuditableInTheExport() throws IOException {
        Catalog cat = freshCatalog();
        loadAll(cat);
        Exporter ex = new Exporter(cat);
        Set<String> normalized = new HashSet<>();
        ex.normalizedListings().forEach(n -> normalized.add(n.get("listing_id").asText()));
        Set<String> decided = new HashSet<>();
        ex.decisions().forEach(d -> decided.add(d.get("listing_id").asText()));
        assertEquals(normalized, decided, "every listing must carry an explicit resolution decision");
        for (JsonNode n : ex.normalizedListings())
            assertTrue(n.has("raw") || n.has("quarantined_evidence"), "raw evidence must be retained");
    }
}
