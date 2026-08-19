package com.dealdog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealdog.util.J;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HTTP surface required by SUBMISSION_CONTRACT.md. */
@RestController
public class Controllers {
    private final DealDogService svc;
    public Controllers(DealDogService svc) { this.svc = svc; }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> health() {
        ObjectNode o = J.obj();
        o.put("status", "ok");
        o.put("state_dir", svc.store().path().getParent().toString());
        // distinct durable events: a byte-identical redelivery is the same event, so this does
        // not grow when a batch is replayed
        o.put("events_stored", svc.store().count());
        ObjectNode c = o.putObject("catalog");
        c.put("listings", svc.catalog().listings.size());
        c.put("universal_products", svc.catalog().products.size());
        c.put("variants", svc.catalog().variants.size());
        c.put("offers", svc.catalog().offers.size());
        return ResponseEntity.ok(o);
    }

    @PostMapping(value = {"/ingest", "/v1/ingestions"},
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> ingest(@RequestBody JsonNode body) {
        return ResponseEntity.ok(svc.ingest(body));
    }

    @PostMapping(value = "/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> resolve(@RequestBody JsonNode body) {
        return ResponseEntity.ok(svc.resolve(body));
    }

    @GetMapping(value = {"/evaluation/export", "/v1/evaluation/export"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> export() {
        return ResponseEntity.ok(svc.export());
    }
}
