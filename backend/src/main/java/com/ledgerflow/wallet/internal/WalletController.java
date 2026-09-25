package com.ledgerflow.wallet.internal;

import com.ledgerflow.shared.Cursor;
import com.ledgerflow.shared.Money;
import com.ledgerflow.wallet.WalletQueries;
import com.ledgerflow.wallet.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class WalletController {
    private final WalletService wallets;
    private final WalletQueries queries;
    WalletController(WalletService wallets, WalletQueries queries) { this.wallets = wallets; this.queries = queries; }

    record Create(@NotBlank @Size(max = 120) String label, @NotNull WalletService.Kind kind,
                  @NotNull Money.Currency currency, @Size(min = 1, max = 120) String customerId) { }
    record Transfer(@NotNull UUID sourceWalletId, @NotNull UUID destinationWalletId, long amount, @NotNull Money.Currency currency) { }

    @PostMapping("/wallets")
    ResponseEntity<WalletService.Wallet> create(@com.ledgerflow.merchant.MerchantId UUID merchant, @Valid @RequestBody Create request) {
        var wallet = wallets.create(merchant, request.label(), request.kind(), request.currency(), request.customerId());
        return ResponseEntity.created(URI.create("/api/v1/wallets/" + wallet.id() + "/balance")).body(wallet);
    }

    @GetMapping("/wallets")
    Cursor.Page<WalletQueries.WalletRow> list(@com.ledgerflow.merchant.MerchantId UUID merchant,
            @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) { return queries.list(merchant, limit, cursor); }

    @GetMapping("/wallets/{id}/balance")
    WalletQueries.Balance balance(@com.ledgerflow.merchant.MerchantId UUID merchant, @PathVariable UUID id) { return queries.balance(merchant, id); }

    @GetMapping("/wallets/{id}/transactions")
    Cursor.Page<WalletQueries.History> history(@com.ledgerflow.merchant.MerchantId UUID merchant, @PathVariable UUID id,
            @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) { return queries.history(merchant, id, limit, cursor); }

    @PostMapping("/transfers")
    ResponseEntity<tools.jackson.databind.JsonNode> transfer(@com.ledgerflow.merchant.MerchantId UUID merchant,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody Transfer request) {
        return wallets.transfer(merchant, new WalletService.TransferRequest(request.sourceWalletId(), request.destinationWalletId(), request.amount(), request.currency()), key).http();
    }

    @GetMapping("/transfers")
    Cursor.Page<WalletQueries.Transfer> transfers(@com.ledgerflow.merchant.MerchantId UUID merchant,
            @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) { return queries.transfers(merchant, limit, cursor); }

    @GetMapping("/transfers/{id}")
    WalletQueries.Transfer transfer(@com.ledgerflow.merchant.MerchantId UUID merchant, @PathVariable UUID id) { return queries.transfer(merchant, id); }
}
