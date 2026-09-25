package com.ledgerflow.payment.internal;

import com.ledgerflow.merchant.MerchantId;
import com.ledgerflow.payment.*;
import com.ledgerflow.shared.Cursor;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {
    private final PaymentService payments;
    private final PaymentQueries queries;
    PaymentController(PaymentService payments, PaymentQueries queries) { this.payments = payments; this.queries = queries; }
    record Action() { }

    @PostMapping
    ResponseEntity<JsonNode> create(@MerchantId UUID merchant, @Valid @RequestBody PaymentService.Create request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) { return payments.create(merchant, request, key).http(); }
    @PostMapping("/{id}/confirm")
    ResponseEntity<JsonNode> confirm(@MerchantId UUID merchant, @PathVariable UUID id, @RequestBody(required = false) Action action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) { return payments.confirm(merchant, id, key).http(); }
    @PostMapping("/{id}/cancel")
    ResponseEntity<JsonNode> cancel(@MerchantId UUID merchant, @PathVariable UUID id, @RequestBody(required = false) Action action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) { return payments.cancel(merchant, id, key).http(); }
    @GetMapping
    Cursor.Page<Payment> list(@MerchantId UUID merchant, @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) PaymentState status,
            @RequestParam(required = false) String reference) { return queries.list(merchant, limit, cursor, status, reference); }
    @GetMapping("/{id}")
    Payment detail(@MerchantId UUID merchant, @PathVariable UUID id) { return queries.detail(merchant, id); }
    @GetMapping("/{id}/timeline")
    List<PaymentQueries.Transition> timeline(@MerchantId UUID merchant, @PathVariable UUID id) { return queries.timeline(merchant, id); }
}
