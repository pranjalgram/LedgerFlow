package com.ledgerflow.ledger.internal;

import com.ledgerflow.ledger.LedgerQueries;
import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.shared.Cursor;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
class LedgerController {
    private final MerchantAccess access;
    private final LedgerQueries ledger;
    LedgerController(MerchantAccess access, LedgerQueries ledger) { this.access = access; this.ledger = ledger; }

    @GetMapping("/accounts")
    List<LedgerQueries.Account> accounts(@RequestHeader("X-Merchant-Id") UUID merchantId) {
        access.require(merchantId);
        return ledger.accounts(merchantId);
    }

    @GetMapping("/transactions")
    Cursor.Page<LedgerQueries.Journal> transactions(@RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) {
        access.require(merchantId);
        return ledger.transactions(merchantId, limit, cursor);
    }

    @GetMapping("/transactions/{id}")
    LedgerQueries.Detail detail(@RequestHeader("X-Merchant-Id") UUID merchantId, @PathVariable UUID id) {
        access.require(merchantId);
        return ledger.detail(merchantId, id);
    }
}
