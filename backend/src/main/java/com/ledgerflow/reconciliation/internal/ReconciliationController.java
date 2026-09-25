package com.ledgerflow.reconciliation.internal;

import com.ledgerflow.merchant.MerchantId;
import com.ledgerflow.reconciliation.ReconciliationRuns;
import com.ledgerflow.shared.Cursor;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reconciliation-runs")
class ReconciliationController {
    private final ReconciliationRuns runs;
    ReconciliationController(ReconciliationRuns runs) { this.runs = runs; }
    @PostMapping
    ResponseEntity<Map<String, UUID>> create(@MerchantId UUID merchant) {
        UUID id = runs.create(merchant); return ResponseEntity.accepted().location(URI.create("/api/v1/reconciliation-runs/" + id)).body(Map.of("id", id));
    }
    @GetMapping
    Cursor.Page<ReconciliationRuns.Run> list(@MerchantId UUID merchant, @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) {
        return runs.list(merchant, limit, cursor);
    }
    @GetMapping("/{id}")
    ReconciliationRuns.Run get(@MerchantId UUID merchant, @PathVariable UUID id) { return runs.get(merchant, id); }
    @GetMapping("/{id}/discrepancies")
    ReconciliationRuns.Findings findings(@MerchantId UUID merchant, @PathVariable UUID id, @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) UUID cursor) {
        return runs.findings(merchant, id, limit, cursor);
    }
}
