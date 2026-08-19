package com.dealdog.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Jackson + hashing helpers. */
public final class J {
    public static final ObjectMapper M = new ObjectMapper();

    private J() {}

    public static ObjectNode obj() { return M.createObjectNode(); }
    public static ArrayNode arr() { return M.createArrayNode(); }

    public static JsonNode parse(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String write(JsonNode n) {
        try { return M.writeValueAsString(n); } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String pretty(JsonNode n) {
        try { return M.writerWithDefaultPrettyPrinter().writeValueAsString(n); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Canonical (sorted-key) serialization for stable payload hashing. */
    public static String canonical(JsonNode n) {
        try {
            Object o = M.treeToValue(n, Object.class);
            return M.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(o);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String sha1(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(d.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String text(JsonNode n, String... path) {
        JsonNode cur = n;
        for (String p : path) { if (cur == null) return null; cur = cur.get(p); }
        if (cur == null || cur.isNull()) return null;
        return cur.isValueNode() ? cur.asText() : null;
    }

    public static JsonNode at(JsonNode n, String... path) {
        JsonNode cur = n;
        for (String p : path) { if (cur == null) return null; cur = cur.get(p); }
        return (cur == null || cur.isNull()) ? null : cur;
    }
}
