package com.ledgerflow.wallet.internal;

import com.ledgerflow.shared.Money;
import com.ledgerflow.wallet.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@Profile("demo")
@RestController
class DemoFundingController {
    private final WalletService wallets;
    DemoFundingController(WalletService wallets) { this.wallets = wallets; }
    record Fund(long amount, @NotNull Money.Currency currency) { }

    @PostMapping("/api/v1/wallets/{id}/funding")
    ResponseEntity<JsonNode> fund(@RequestHeader("X-Merchant-Id") UUID merchant, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody Fund request) {
        return wallets.fund(merchant, id, new Money(request.amount(), request.currency()), key).http();
    }
}
