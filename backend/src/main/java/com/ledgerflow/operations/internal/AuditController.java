package com.ledgerflow.operations.internal;

import com.ledgerflow.operations.AuditQueries;
import com.ledgerflow.merchant.MerchantId;
import com.ledgerflow.shared.Cursor;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
class AuditController {
    private final AuditQueries queries;
    AuditController(AuditQueries queries) { this.queries = queries; }
    @GetMapping("/api/v1/audit-events")
    Cursor.Page<AuditQueries.Event> list(@MerchantId UUID merchant, @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) {
        return queries.list(merchant, limit, cursor);
    }
}
