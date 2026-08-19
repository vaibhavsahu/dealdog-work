package com.dealdog.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.dealdog.util.J;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Durable append-only event store in SQLite at $DEALDOG_STATE_DIR/dealdog.db.
 *
 * Every accepted transport event is persisted with its envelope; the in-memory catalog
 * projection is rebuilt by replaying this log on startup. That makes the required
 * properties structural rather than aspirational:
 *   - restart survival (clusters, assignments, hypotheses, tombstones, epochs, history)
 *   - idempotent replay (the same log replays to the same projection)
 *   - clean-rebuild equivalence (rebuild == incremental, by construction)
 */
public final class EventStore {
    private final Connection conn;
    private final Path dbPath;

    public EventStore(Path stateDir) {
        try {
            Files.createDirectories(stateDir);
            this.dbPath = stateDir.resolve("dealdog.db");
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=FULL");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS event(
                      seq INTEGER PRIMARY KEY AUTOINCREMENT,
                      source TEXT, batch_id TEXT, envelope_json TEXT NOT NULL,
                      payload_json TEXT NOT NULL, received_at TEXT,
                      event_key TEXT, payload_hash TEXT)
                    """);
                // migrate a log written before delivery identity was recorded
                for (String col : new String[]{"event_key", "payload_hash"})
                    try { s.execute("ALTER TABLE event ADD COLUMN " + col + " TEXT"); }
                    catch (SQLException ignored) { /* column already present */ }
                // Collapse redeliveries written by an older build that appended every delivery.
                // Grouping on the payload (not just the key) keeps genuine conflicts — two
                // different payloads under one delivery identity remain separate rows.
                int healed = s.executeUpdate("""
                    DELETE FROM event
                     WHERE event_key IS NULL
                       AND seq NOT IN (SELECT MIN(seq) FROM event
                                        WHERE event_key IS NULL
                                        GROUP BY source, IFNULL(batch_id,''), envelope_json, payload_json)
                    """);
                if (healed > 0)
                    System.out.println("[EventStore] collapsed " + healed + " redelivered rows from a legacy log");

                // A byte-identical redelivery is the SAME durable event, so the log itself is
                // idempotent. A repeated key with mutated bytes is a different row on purpose:
                // it must survive so a rebuild reproduces the conflict quarantine.
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_event_identity ON event(event_key, payload_hash)");
            }
        } catch (Exception e) { throw new RuntimeException("cannot open state store", e); }
    }

    public Path path() { return dbPath; }

    /**
     * Append a delivery to the durable log.
     *
     * @return true if this was a new durable event, false if it was a byte-identical redelivery
     *         that the log already holds (replay therefore costs no storage).
     */
    public synchronized boolean append(String source, String batchId, JsonNode envelope, JsonNode payload,
                                       String eventKey, String payloadHash) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO event(source,batch_id,envelope_json,payload_json,received_at,event_key,payload_hash)"
                        + " VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, source);
            ps.setString(2, batchId);
            ps.setString(3, J.write(envelope));
            ps.setString(4, J.write(payload));
            ps.setString(5, java.time.Instant.now().toString());
            ps.setString(6, eventKey);
            ps.setString(7, payloadHash);
            return ps.executeUpdate() > 0;
        } catch (Exception e) { throw new RuntimeException("cannot append event", e); }
    }

    public record StoredEvent(long seq, String source, String batchId, JsonNode envelope, JsonNode payload) {}

    public synchronized List<StoredEvent> replayAll() {
        List<StoredEvent> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT seq,source,batch_id,envelope_json,payload_json FROM event ORDER BY seq")) {
            while (rs.next())
                out.add(new StoredEvent(rs.getLong(1), rs.getString(2), rs.getString(3),
                        J.parse(rs.getString(4)), J.parse(rs.getString(5))));
        } catch (Exception e) { throw new RuntimeException("cannot replay events", e); }
        return out;
    }

    public synchronized long count() {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM event")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) { return 0; }
    }

    public synchronized void close() {
        try { conn.close(); } catch (Exception ignored) { }
    }
}
