# Stage 5 — Durable state, events and temporal semantics

## Storage model

`$DEALDOG_STATE_DIR/dealdog.db` (SQLite, WAL) holds an **append-only event log**: every accepted
transport event with its envelope and payload.

```sql
event(seq INTEGER PRIMARY KEY AUTOINCREMENT,
      source TEXT, batch_id TEXT,
      envelope_json TEXT NOT NULL, payload_json TEXT NOT NULL,
      received_at TEXT,
      event_key TEXT, payload_hash TEXT)

CREATE UNIQUE INDEX idx_event_identity ON event(event_key, payload_hash)
```

**The log itself is idempotent.** Writes use `INSERT OR IGNORE` against
`(event_key, payload_hash)`, so replaying a batch verbatim costs no storage and leaves
`events_stored` unchanged. Deduplicating on the *pair* rather than on the key alone is deliberate:
a repeated delivery identity carrying mutated bytes is a genuine conflict and must survive in the
log, otherwise a rebuild would not reproduce the conflict quarantine.

The catalog — listings, products, variants, offers, observations, assignments, hypotheses,
tombstones, epochs, quarantine, audit history — is a **deterministic projection** rebuilt by
replaying that log on startup (`DealDogService.rebuildFromLog`).

This choice makes three required properties structural rather than aspirational:

- **restart survival** — nothing semantic lives only in RAM; the evaluator may stop and start
  `run.sh` against the same state dir freely
- **idempotent replay** — the same log always replays to the same projection, and replaying a
  delivery does not grow the log
- **clean-rebuild equivalence** — an incrementally maintained catalog and a fresh replay of the
  same semantic history agree by construction (asserted by `invariantI6`)

Opaque internal ids may differ between clean stores; entity partitions, decisions, lifecycle state
and retained observation history do not. Product and variant ids are content-derived
(`up:sha1(category|brand|model|generation)`, `var:sha1(productId|sorted dims)`), so in practice
even the ids reproduce.

## Idempotency

The dedupe key is always **record-scoped**:

```
event_key = source | (event_id or batch_id) | source_record_id
```

- replaying the same event + record → `duplicate`, no state change
- a request-level `event_id`/`idempotency_key` describing a multi-record batch never suppresses
  sibling records inside that batch (asserted by `batchLevelEventIdDoesNotSuppressLaterRecordsInThatBatch`)
- the same delivery identity redelivered with **different bytes** is a conflict: the first
  application stands, the second is quarantined, both are retained
- byte-identical payloads under two *distinct* legitimate event ids are two observations

Observations retain `event_id` and `idempotency_key`, so duplicate suppression and source-version
precedence are inspectable in the export rather than being README-only claims.

## Update modes

`update_mode` (or, when absent, the `operation`) selects the semantics:

| Mode | Behaviour |
|---|---|
| `full_snapshot` (default for plain `upsert`) | replaces the field set within the event's authority scope |
| `partial_patch` | only supplied fields change; **omission retains** the prior value |
| explicit `null` in a patch | a supplied value — withdraws the prior assertion per the source's null semantics; never treated as omission |
| `authoritative_correction` (`correct`) | applies regardless of clock order; always writes an audit row |
| `historical_snapshot` / any older event | retained in history, does **not** regress newer state |
| `listing_tombstone` (`unavailable`) | lifecycle → inactive; identity, evidence and observations preserved |

A tombstoned listing that reappears reactivates the **same stable listing identity** rather than
creating a new one.

## Clocks and precedence

Three clocks are stored separately and never conflated:

- `observed_at` — when the offer/evidence observation applied
- `source_updated_at` — source-state order within an authority scope
- `received_at` — transport arrival

Precedence, deterministic and documented:

1. an authoritative correction always applies
2. once a field carries an authoritative correction, later non-authoritative evidence cannot undo it
3. otherwise a strictly older `source_updated_at` never regresses current state
4. **receipt order alone is never precedence**

A stale event is still appended to `source_history` with `applied: false`, so a late arrival is
auditable rather than invisible.

## Corrections, splits and audit

A `correct` event carries its own record id plus `corrects_listing_id`. The correcting record id
is aliased onto the corrected listing so later events for either id converge on one entity.

Any change of assignment writes:

```json
{"listing_internal_id":"L:retailer_api:ra_0021:1","event_key":"retailer_api|inc_021|ra_0039",
 "prior_universal_product_id":"up:...","prior_variant_id":"var:...",
 "new_universal_product_id":"up:...","new_variant_id":"var:...",
 "reason":"re-resolution under new evidence","authority":"authoritative_correction",
 "changed_at":"..."}
```

Active offers follow the corrected assignment. **Prior observations are not rewritten**: each
observation stores `variant_id_at_observation` / `universal_product_id_at_observation`, so an old
price never appears as though it always belonged to the new variant.

Worked example from the corpus — `xo_0015` (a configurable PinePhone page):
`inc_018` corrects it to 512 GB, then `inc_020` corrects it to 128 GB with MPN `P16-128-BLK`.
Final state is 128 GB, three history entries are retained, and each reassignment is audited. The
second correction *narrows* the earlier ambiguity rather than appearing as an unrelated answer.

## Lifecycle epochs

A tombstone closes the listing's current epoch. If a source explicitly reuses a retired record id
for a different product in a new epoch, a new logical listing is created (`L:<source>:<id>:<n+1>`)
while the old epoch is preserved as history. Late old-epoch events attach to the old epoch and
cannot mutate the new logical listing.
