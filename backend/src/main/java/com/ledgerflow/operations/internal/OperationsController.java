package com.ledgerflow.operations.internal;

import com.ledgerflow.merchant.MerchantId;
import com.ledgerflow.operations.OperationsQueries;
import com.ledgerflow.shared.Cursor;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
class OperationsController {
    private final OperationsQueries queries;
    OperationsController(OperationsQueries queries) { this.queries = queries; }
    @GetMapping("/api/v1/overview")
    OperationsQueries.Overview overview(@MerchantId UUID merchant) { return queries.overview(merchant); }
    @GetMapping("/api/v1/operations/health")
    OperationsQueries.Health health(@MerchantId UUID merchant) { return queries.health(merchant); }
    @GetMapping("/api/v1/operations/outbox")
    Cursor.Page<OperationsQueries.Event> events(@MerchantId UUID merchant, @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) { return queries.events(merchant, limit, cursor); }
    @GetMapping("/api/v1/operations/dead-letters")
    Cursor.Page<OperationsQueries.DeadLetter> deadLetters(@MerchantId UUID merchant, @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) { return queries.deadLetters(merchant, limit, cursor); }
}
