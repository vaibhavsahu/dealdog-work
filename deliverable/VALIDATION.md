# Validation guide & acceptance-criteria review

> **Paths in this document are relative to `deliverable/`.** Run commands from there
> (`cd deliverable`). The supplied trial package lives in `../provided/`, and generated
> artifacts in `../outputs/`. See the package map in the root [`README.md`](../README.md).

How to verify this submission, and an honest assessment of it against
`SUBMISSION_CONTRACT.md`, `validation/README.md` and `CANDIDATE_INSTRUCTIONS.md`.

Ready-to-run commands for every endpoint live in
[`docs/07-curl-cookbook.md`](docs/07-curl-cookbook.md).

- [Part 0 — One command](#part-0--one-command)
- [Part 1 — Running validation](#part-1--running-validation)
- [Part 2 — Acceptance criteria](#part-2--acceptance-criteria)
- [Part 3 — Known gaps](#part-3--known-gaps-and-deliberate-deferrals)

---

## Part 0 — One command

```bash
./scripts/acceptance.sh
```

Runs every acceptance criterion end to end and prints `[PASS]` / `[FAIL]` per step, exiting
non-zero if anything fails (so it is CI-usable). It uses a throwaway state dir and port 8099, and
leaves the repository untouched apart from regenerating `outputs/`.

| Step | Verifies |
|---|---|
| 1 | `mvn clean test` — 22 automated tests |
| 2 | `run.sh` serves on `$PORT`; `/health` returns 200 |
| 3 | Corpus ingests over HTTP — 5 initial sources, then 3 phases in order |
| 4 | Artifacts generated; **the supplied `validate_outputs.py` passes 27/27 labelled pairs** |
| 5 | 35 artifact conformance checks against `SUBMISSION_CONTRACT.md` (`scripts/check_artifacts.py`) |
| 6 | Replay idempotency — every batch re-POSTs as `accepted=0`, export byte-identical |
| 7 | Durable restart — stop/start on the same state dir, catalog rebuilt identically |
| 8 | `/resolve` returns MATCH / REVIEW / NO_MATCH appropriately and mutates nothing |

Expected tail:

```
==========================================================
 ACCEPTANCE: 14 passed, 0 failed
==========================================================
```

The individual checks in step 5 are listed with stable ids (`C1`–`C35`) so a finding can be traced
to a specific contract requirement. To run just those against the committed artifacts:

```bash
python3 scripts/check_artifacts.py \
  --normalized ../outputs/normalized_listings.json \
  --catalog    ../outputs/catalog.json \
  --decisions  ../outputs/resolution_decisions.json
```

The sections below break the same work into individual steps for anyone who wants to run or debug
them one at a time.

---

## Part 1 — Running validation

### 0. Prerequisites

JDK 17+, Maven, `python3` (used only by the supplied validator and by the output-generation
script to reshape fixtures into ingest envelopes), and `curl`. No network access, credentials or
paid services are required at run time.

```bash
cd dealdog-work-trial/deliverable
java -version && mvn -version && python3 --version
```

### 1. Build and run the automated tests

```bash
mvn clean test
```

Maven compiles before testing, so no separate build step is needed.

**Expect:** `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`.

Each test method is logged as it runs (via the `TestLogger` JUnit extension), so the console shows
exactly what was exercised rather than one per-class summary line:

```
> RUN  [01] adapterSelectionIsPerRecordSoOneSourceMayCarrySeveralSchemas
    PASS  (206 ms)

> RUN  [02] ambiguousVariantAbstainsAndKeepsViableHypotheses
    PASS  (219 ms)
...
----------------------------------------------------------
 DealDog test methods: 22 passed, 0 failed, 0 skipped
----------------------------------------------------------
```

A failing method prints `FAIL (<ms>) <ExceptionType>: <message>` in place of `PASS`, and Surefire
is configured with `trimStackTrace=false` so the full stack trace reaches the console.

To run a single method:

```bash
mvn test -Dtest='PipelineTest#invariantI3_byteIdenticalReplayChangesNothing'
```

These 22 tests cover the seven executable invariants (I1–I7 in `README_TRIAL.md` §9), adapter
selection under schema drift, quarantine identity retention, GTIN/money edge cases, the
update-mode matrix, temporal precedence, the correction chain, and offer/promotion semantics.

### 2. Regenerate the required artifacts end to end

```bash
./scripts/generate_outputs.sh
```

This is the **full end-to-end smoke test**: it boots the service on a fresh temporary
`DEALDOG_STATE_DIR`, waits for `/health`, POSTs the five initial sources and then the three
incremental phases **in order** over HTTP, pulls `GET /evaluation/export`, writes the three
artifacts into `outputs/`, and finally runs the public validator against them.

**Expect** (identical to the committed artifacts):

```json
{"status":"valid","normalized_listings":140,"universal_products":24,
 "variants":56,"offers":98,"decisions":140,"public_pairs_checked":27}
```

### 3. Run the public validator on its own

Against the committed artifacts, without rebuilding anything:

```bash
python3 ../provided/../provided/validation/validate_outputs.py \
  --normalized ../outputs/normalized_listings.json \
  --catalog    ../outputs/catalog.json \
  --decisions  ../outputs/resolution_decisions.json
```

### 4. Replay / idempotency

#### 4a. The supplied validator's replay mode

`validate_outputs.py` accepts `--before-replay` / `--after-replay`:

Prerequisite: the service must be running **and already loaded** (`./scripts/load_corpus.sh`),
otherwise the snapshots are empty and the check is vacuous.

```bash
# Use -f, not just -s: with -s alone curl stays silent AND exits 0 when the service is
# unreachable, writing a zero-byte file that later fails with
#   json.decoder.JSONDecodeError: Expecting value: line 1 column 1 (char 0)
curl -sf localhost:8080/evaluation/export -o /tmp/before.json \
  || { echo "service not reachable on :8080"; exit 1; }
python3 -c 'import json;print("before:",json.load(open("/tmp/before.json"))["stats"])'

# re-POST, verbatim, a batch you already ingested (same source AND same batch_id)
curl -sf -X POST localhost:8080/ingest -H 'Content-Type: application/json' \
  --data-binary @<(python3 -c '
import csv, json
print(json.dumps({"source":"affiliate_a","batch_id":"initial-affiliate-a","operation":"upsert",
                  "records":list(csv.DictReader(open("../provided/data/initial/affiliate_a.csv")))}))') \
  | python3 -m json.tool          # expect accepted=0, duplicates=20 (the CSV has 20 data rows)

curl -sf localhost:8080/evaluation/export -o /tmp/after.json
python3 -c 'import json;print("after: ",json.load(open("/tmp/after.json"))["stats"])'

python3 ../provided/../provided/validation/validate_outputs.py \
  --normalized ../outputs/normalized_listings.json \
  --catalog    ../outputs/catalog.json \
  --decisions  ../outputs/resolution_decisions.json \
  --before-replay /tmp/before.json --after-replay /tmp/after.json
```

**How to read the result — the check is silent on success.** `replay changed <collection> count`
is the *failure* message; it only ever appears as an `AssertionError` traceback. If the replay
check passes you simply get the usual summary JSON, with no extra line about replay. Seeing

```json
{"status":"valid","normalized_listings":140, ... ,"public_pairs_checked":27}
```

means every assertion passed, replay included.

Two limits of this mode are worth knowing:

- it compares only the **lengths** of five collections — not their content, and not `observations`
  or `resolution_history`, which are the collections most likely to grow on a faulty replay;
- it is **trivially satisfied if nothing was re-POSTed** between the two exports, since it then
  compares a snapshot against itself.

#### 4b. Stronger replay check (positive evidence)

Because of those limits, this repo ships a check that proves the property instead of asserting it
silently:

```bash
./run.sh &                                # rebuilds automatically if sources changed
PORT=8080 ./scripts/load_corpus.sh        # ingest the corpus into THIS instance
PORT=8080 ./scripts/verify_replay.sh
```

(`generate_outputs.sh` starts its own throwaway service on a temp state dir, so it does not
populate an instance you launched by hand — `load_corpus.sh` is the one that does.)

It snapshots the export, re-POSTs **every** already-applied batch verbatim, asserts the service
reports `accepted=0` for each, and then byte-diffs the entire export including `observations` and
`resolution_history`.

**Expect:**

```
  01_affiliate_a.json                accepted=0    duplicates=20
  02_affiliate_b.json                accepted=0    duplicates=25
  ...
  normalized_listings         140 ->   140
  observations                145 ->   145
  resolution_history            7 ->     7

REPLAY CHECK PASSED: every batch deduplicated, export byte-identical
```

> Replay identity is scoped by `source | (event_id or batch_id) | source_record_id`. Re-POSTing the
> same records under a *different* `batch_id` is a new delivery, not a replay, and legitimately
> records new observations — so use the original `batch_id` when testing replay.

### 5. Durable-state restart

The evaluator performs completed-request restarts against the same state dir. To reproduce:

```bash
export DEALDOG_STATE_DIR=/tmp/dealdog-state PORT=8080
./run.sh &                                   # ingest some data, then:
curl -sf localhost:8080/health                # note "events_stored"
kill %1

./run.sh &                                   # same state dir
curl -sf localhost:8080/health                # same "events_stored"
curl -sf localhost:8080/evaluation/export | python3 -c "import json,sys; print(json.load(sys.stdin)['stats'])"
```

**Expect:** identical `events_stored` and identical `stats` after the restart. `events_stored`
counts distinct durable events, so it also stays fixed across replays (§4b). Clusters,
assignments, hypotheses, tombstones, source epochs, dedupe identities and observation history are
all rebuilt by replaying the durable event log — none of it lives only in RAM.

### 6. `/resolve` is lookup-only

`POST /resolve` must never mutate the catalog, and repeated calls in a different order must return
the same logical result:

```bash
curl -sf localhost:8080/evaluation/export > /tmp/x1.json

curl -sf -X POST localhost:8080/resolve -H 'Content-Type: application/json' -d '{
  "url":"https://bestelectro.invalid/product/C7BF-XM6-5E8",
  "title":"Auralux SilencePro XM6 Wireless Headphones Black",
  "price":"$429.99",
  "metadata":{"brand":"Auralux","model":"XM6","color":"Blk"}}'

curl -sf -X POST localhost:8080/resolve -H 'Content-Type: application/json' \
  -d '{"page_url":"https://x.invalid/p/1","title":"PinePhone 16","observed_price":"$799.00"}'

curl -sf localhost:8080/evaluation/export > /tmp/x2.json
diff <(python3 -m json.tool /tmp/x1.json) <(python3 -m json.tool /tmp/x2.json) && echo "RESOLVE IS READ-ONLY"
```

**Expect:** empty diff. The first call should return `MATCH` with a variant id, populated
`positive_signals`, and candidate telemetry; the second should return `REVIEW`/`NO_MATCH` rather
than inventing an entity.

### 7. Spot-check the interesting decisions

```bash
python3 - <<'PY'
import json, collections
dec = {d['listing_id']: d for d in json.load(open('../outputs/resolution_decisions.json'))}
print(collections.Counter(d['decision'] for d in dec.values()))
for lid in ['ab_0028','ra_0036','ab_0030',   # PinePhone 15 listings carrying PinePhone 16 GTINs
            'ra_0021','xo_0015','ab_0001',   # authoritative corrections
            'ab_0011','ra_0013']:            # same configurable page, different selections
    d = dec[lid]
    print(f"{lid:9} {d['decision']:8} product={str(d['universal_product_id'])[:14]:14} variant={str(d['variant_id'])[:14]}")
PY
```

**Expect:** the three P15-with-P16-GTIN listings are `REVIEW` (they must not merge two phone
generations); `ab_0011` and `ra_0013` share a product but sit on **different** variants.

---

## Part 2 — Acceptance criteria

Legend: **✅ met** · **◑ met conservatively / partial by design** · **⚠ implemented but not
exercised by the supplied corpus**

### 2.1 `SUBMISSION_CONTRACT.md` — service lifecycle

| Requirement | Status | Evidence |
|---|---|---|
| Root-level executable `run.sh` | ✅ | `run.sh`, builds on demand then `exec java -jar` |
| Serves on `PORT`, default 8080 | ✅ | `application.properties`: `server.port=${PORT:8080}` |
| Stays in foreground; ready without paid credentials | ✅ | `exec` in foreground; no external services |
| Durable state in `DEALDOG_STATE_DIR` | ✅ | SQLite event log at `$DEALDOG_STATE_DIR/dealdog.db` |
| Survives completed-request stop/start | ✅ | `DealDogService.rebuildFromLog()` replays on boot — verify with §1.5 |
| No database prescribed / candidate-configured store | ✅ | embedded SQLite, single file, no server |

### 2.2 `SUBMISSION_CONTRACT.md` — endpoints

| Requirement | Status | Evidence |
|---|---|---|
| `GET /health` → 200 when ingestion can begin | ✅ | `Controllers.health` |
| `POST /ingest` **and** `/v1/ingestions` | ✅ | both mapped |
| Accepts `source`/`batch_id`/`records`/`operation`/`event_id`/`update_mode`/`source_updated_at`/`received_at` | ✅ | `DealDogService.envelopeFrom` |
| Returns accepted, rejected/quarantined, duplicate, corrected counts | ✅ | `{accepted, quarantined, duplicates, corrected, rejected, received}` |
| Replaying an identical batch creates no duplicate state | ✅ | invariant **I3**; §1.4 |
| Immutable `event_id`: same id + different bytes ⇒ conflict, not two applies | ✅ | `duplicateEventIdWithMutatedBytesIsAConflictNotASecondApply` |
| Identical bytes under two distinct event ids ⇒ two observations | ✅ | dedupe keys on `(scope, record)`, not payload hash |
| Request-level id must not discard later records in the batch | ✅ | `batchLevelEventIdDoesNotSuppressLaterRecordsInThatBatch` (25/25 accepted) |
| Accepted record retains source id + meaningful evidence | ✅ | 140/140 rows carry `raw` + `provenance`; no anonymous acceptance |
| Quarantine preserves a nested/versioned source-record id | ✅ ⚠ | `unknownShapeIsQuarantinedWithItsRealSourceRecordId`; corpus produces 0 quarantines |
| `POST /resolve` with the documented response shape | ✅ | decision, ids, confidence, signals, hypotheses, offers, comparability, full telemetry |
| `/resolve` does not mutate; order-independent | ✅ | runs pipeline with `commit=false`; verify with §1.6 |
| `GET /evaluation/export` and `/v1/evaluation/export` | ✅ | both mapped; same document as the three files |
| Async ingestion needs deterministic completion | n/a | ingestion is synchronous — completion is the response |

### 2.3 `SUBMISSION_CONTRACT.md` — required artifacts

Verified directly against the committed `outputs/`.

| Requirement | Status | Measured |
|---|---|---|
| `normalized_listings.json` top-level array, one row per unique source listing | ✅ | 140 rows |
| `listing_id` **is** the source record id | ✅ | 140/140 (`internal_listing_id` carried separately) |
| `raw` retained | ✅ | 140/140 |
| `taxonomy`, `normalized_attributes`, `unknown_attributes` | ✅ | 140/140 present; 36 rows carry non-empty unknown attributes |
| Provenance distinguishes explicit / normalized / inferred / invalid / conflicting | ✅ | all five combinations present in the corpus |
| `catalog.json` with `universal_products`, `variants`, `offers`, `stats` | ✅ | 24 / 56 / 98 + `stats` |
| Every variant references a real product | ✅ | 56/56 |
| Priced offers expose `price_kind` | ✅ | 97/97 priced offers |
| Priced offers expose `comparability` in the enum | ✅ | 97/97 (`COMPARABLE`, `CONDITIONAL`, `NOT_COMPARABLE` seen; `UNKNOWN` at observation level) |
| Seller and condition stay offer-level | ✅ | 98/98 |
| Source listing links + historical observations retained | ✅ | 98/98 offers link listings; 145 observations |
| Monetary roles stay distinguishable | ✅ | observations carry `total_purchase_price`, `monthly_installment`, `trade_in_net_price` |
| Structured promotion requirements retained | ✅ | 34 observations carry terms; 25 distinct requirement keys (coupon, subscribe & save, membership, trade-in, financing, min-quantity, stacking, gift-card timing, location) |
| `resolution_decisions.json`, one row per unique source listing | ✅ | 140 rows, 140 unique ids, exactly matching the normalized set |
| `candidate_count` == unique union of `candidate_sources` | ✅ | 140/140 |
| `scored_candidate_ids` exact subset; count == its size | ✅ | 140/140 |
| `MATCH` carries traceable positive evidence | ✅ | 125/125 |
| Finite `REVIEW` ambiguity preserves viable hypotheses | ✅ | 4/4 (`candidate_count > 1`) |
| `REVIEW` may keep a product id with a null variant id | ✅ | 9 rows; **0** REVIEW rows claim a variant |
| Signals are structured assertions, not decorative text | ✅ | 612/612 name a canonical field; 607/612 also cite source field or raw value |
| Observations retain `event_id` / `idempotency_key` | ✅ | 145/145 carry `event_key`; 26 incremental observations carry `idempotency_key` |
| `variant_id_at_observation` (history not rewritten) | ✅ | 145/145 |
| Corrections auditable via `resolution_history` | ✅ | 7 audit rows linking listing, event, prior→new assignment, reason, authority, time |
| Every delivered record auditable in the export | ✅ | decision set == normalized set |

### 2.4 `validation/README.md`

| Check | Status |
|---|---|
| Structural checks | ✅ pass |
| Labelled public pairs (same product/same variant, same product/different variant, different products) | ✅ **27/27** |
| Raw + provenance retention | ✅ pass |
| Traceable evidence for automatic matches | ✅ pass |
| Finite-review hypotheses | ✅ pass |
| Exact reconciliation of candidate block ids vs candidate/scored counts | ✅ pass |
| Optional replay check | ✅ see §1.4 |

> The validator's own caveat applies: passing is **necessary, not sufficient**. Private evaluation
> emphasises unseen contradictions, counterfactual consistency, hypothesis narrowing, typed-attribute
> retention, partial/null/stale/tombstone behaviour, clean-rebuild equivalence and order sensitivity.
> Part 3 states where I expect the most exposure.

### 2.5 `CANDIDATE_INSTRUCTIONS.md` — "What to build" (items 1–27)

| # | Requirement | Status | Where |
|---|---|---|---|
| 1 | Extensible adapter boundary for every visible source | ✅ | `Adapters`, 6 registered + resolve adapter |
| 2 | Preserve each raw source record | ✅ | `raw` on every listing |
| 3 | Field/value provenance incl. invalid and conflicting | ✅ | `provenance[]`, five derivation/validity combinations |
| 4 | Normalize attributes; map source categories to a universal taxonomy | ✅ | `Norm`, `Policies.inferCategory` |
| 5 | Category-specific variant dimensions, not one global list | ✅ | driven by `IDENTITY_POLICY.json` |
| 6 | Stable internal ids for products, variants, listings, offers | ✅ | content-derived `up:`/`var:`/`L:`/`of:` |
| 7 | Candidates via indexes/blocking, not global scoring | ✅ | five indexes, capped |
| 8 | MATCH / NO_MATCH / REVIEW from positive and negative evidence | ✅ | 125 / 1 / 14 |
| 9 | Prevent cluster-level contradictions | ✅ | invariant **I1** |
| 10 | Distinguish standalone, manufacturer bundle, retailer bundle, multipack, total capacity, seller, condition | ✅ | `bundle` is price-critical; condition offer-level; see `public_diff_02/03/04` |
| 11 | Ingest incrementally without rebuilding | ✅ | events applied to the live projection |
| 12 | Preserve identity when unrelated records arrive | ✅ | invariant **I6** |
| 13 | Idempotent replay of initial and incremental inputs | ✅ | invariant **I3** |
| 14 | Retain offer history/provenance explaining current state | ✅ | 145 observations + `source_history` |
| 15 | Lookup-oriented `POST /resolve` that does not mutate | ✅ | §1.6 |
| 16 | Export listings, catalog, decisions, candidate telemetry | ✅ | three artifacts + export endpoint |
| 17 | Automated tests for the most dangerous failure modes | ✅ | 22 tests |
| 18 | Retain unanticipated structured attributes without a column each | ✅ | typed EAV; 36 rows carry unknown attributes |
| 19 | Preserve identifier scope (family/style/variant/configurable/offer) | ✅ | `scope` on every identifier; gates evidence strength |
| 20 | Expose offer comparability | ✅ | four-valued, on offers and observations |
| 21 | Merge/correction/split structurally possible and documented | ◑ | merge + correction automatic and audited; split occurs on authoritative correction, not proactively |
| 22 | Distinguish snapshot / omitted field / explicit null / unchanged | ✅ ⚠ | invariant **I4**; the supplied incremental data contains no `partial_patch`, so this is unit-tested only |
| 23 | Source history + deterministic current state when clocks disagree | ✅ | documented precedence; `lateArrivingOlderEvent…` test |
| 24 | Unavailability as lifecycle state, safe reappearance | ✅ | `inc_010` tombstone; reactivation tested |
| 25 | Reversible assignments with audit trail | ✅ | 7 audit rows; `ra_0021` moves P16→P17 |
| 26 | Preserve viable hypotheses on REVIEW; narrow them later | ✅ | hypotheses retained; `xo_0015` correction chain narrows |
| 27 | Explanations and blocking telemetry traceable to retained evidence | ✅ | 612 structured signals; telemetry reconciles 140/140 |

### 2.6 Required submission checklist

| Item | Status |
|---|---|
| Source code + dependency manifest | ✅ `src/`, `pom.xml` |
| Root-level `run.sh` + one-command setup/run | ✅ |
| Generated `normalized_listings.json`, `catalog.json`, `resolution_decisions.json` | ✅ in `outputs/` |
| `README_TRIAL.md` covering all 15 required topics | ✅ |
| ≥5 executable invariants spanning identity, uncertainty, incremental field state, temporal/correction, rebuildability | ✅ **7** (I1–I7), each with a test |
| Automated tests + run command | ✅ `mvn clean test` |
| Test where a source changes shape/version for the same logical record | ✅ `sameLogicalRecordAcrossSchemaVersionsKeepsOneIdentity` |
| Deterministic offline path, no paid credentials | ✅ |

---

## Part 3 — Known gaps and deliberate deferrals

Stated plainly, because these are where private evaluation is most likely to find exposure.

1. **Quarantine is unexercised by the corpus.** Every supplied record is claimed by an adapter, so
   `outputs/` contains zero quarantine rows. The path — including preserving a nested source-record
   id rather than a synthetic one — is covered only by unit test. Hidden sources with novel
   transports are the most likely place this first runs in anger.
2. **`partial_patch` and explicit-null withdrawal are unit-tested only.** The supplied incremental
   envelope uses `upsert` / `correct` / `unavailable`; the hidden transport may add `patch`.
3. **Lifecycle-epoch reuse is unexercised.** Retiring a listing and reusing its source id in a new
   epoch is implemented, but no supplied record does it.
4. **Split is reactive, not proactive.** An authoritative correction moves a listing (and its active
   offers) with a full audit trail; the system does not spontaneously split an existing cluster on
   accumulated evidence alone.
5. **Promotion arithmetic is intentionally partial.** A stacked effective price is computed only
   where the evidence declares the stacking rule; otherwise the structured requirements are retained
   and the arithmetic is declined. `cd_0009` (explicitly stackable) is representable;
   `ab_0016` (mutually exclusive) is retained without inventing a combined figure.
6. **Offer-level `price_kind` reflects the latest observation.** Installment and trade-in
   observations retain their own `price_kind` and `NOT_COMPARABLE` status, but where a seller page
   also publishes a total price, the offer's current roll-up is that total price. Nothing is lost —
   it is a roll-up choice, and every monetary role stays queryable at observation level.
7. **Category inference is heuristic.** Attribute-shape first, keyword second, conservative default
   otherwise. A misfiled hidden category degrades toward abstention rather than toward an unsafe
   merge, which is the intended direction.
8. **Blocking runs in memory over the projection.** Correct and bounded per query, but a production
   deployment would push the block keys into the store (see `README_TRIAL.md` §12).
9. **No fuzzy/ML title similarity.** Matching is rule- and evidence-based throughout. This costs
   recall on listings whose only signal is loose prose — a deliberate trade for false-positive
   avoidance and explainability.

### Summary

Every checkable requirement in `SUBMISSION_CONTRACT.md`, `validation/README.md` and the
`CANDIDATE_INSTRUCTIONS.md` build list is met, with the caveats above. The public validator passes
27/27 pairs and all structural, evidence and telemetry checks; the automated suite is 22/22; and
the decision mix (125 MATCH / 14 REVIEW / 1 NO_MATCH over 140 listings) avoids both scored failure
modes — it neither merges aggressively nor abstains indiscriminately.
