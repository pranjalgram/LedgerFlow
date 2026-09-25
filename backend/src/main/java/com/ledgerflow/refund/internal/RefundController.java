package com.ledgerflow.refund.internal;

import com.ledgerflow.merchant.MerchantId;
import com.ledgerflow.refund.RefundQueries;
import com.ledgerflow.refund.RefundService;
import com.ledgerflow.shared.Cursor;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
class RefundController {
    private final RefundService refunds;
    private final RefundQueries queries;
    RefundController(RefundService refunds, RefundQueries queries) { this.refunds = refunds; this.queries = queries; }

    @PostMapping("/payments/{id}/refunds")
    ResponseEntity<JsonNode> create(@MerchantId UUID merchant, @PathVariable UUID id, @Valid @RequestBody RefundService.Create request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) { return refunds.create(merchant, id, request, key).http(); }
    @GetMapping("/refunds")
    Cursor.Page<RefundQueries.Refund> list(@MerchantId UUID merchant, @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) UUID paymentId) { return queries.list(merchant, limit, cursor, paymentId); }
    @GetMapping("/refunds/{id}")
    RefundQueries.Refund detail(@MerchantId UUID merchant, @PathVariable UUID id) { return queries.detail(merchant, id); }
}
