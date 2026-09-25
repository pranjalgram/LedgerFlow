package com.ledgerflow.webhook.internal;

import com.ledgerflow.merchant.MerchantId;
import com.ledgerflow.shared.Cursor;
import com.ledgerflow.webhook.Webhooks;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class WebhookController {
    private final Webhooks webhooks;
    WebhookController(Webhooks webhooks) { this.webhooks = webhooks; }
    record Create(@NotBlank @Size(max = 2048) String url, @NotEmpty @Size(max = 30) List<@NotBlank String> subscriptions) { }
    @PostMapping("/api/v1/webhooks")
    ResponseEntity<Webhooks.Created> create(@MerchantId UUID merchant, @Valid @RequestBody Create request) {
        var created = webhooks.create(merchant, request.url(), request.subscriptions());
        return ResponseEntity.created(URI.create("/api/v1/webhooks/" + created.endpoint().id())).cacheControl(CacheControl.noStore()).body(created);
    }
    @GetMapping("/api/v1/webhooks")
    Cursor.Page<Webhooks.Endpoint> list(@MerchantId UUID merchant, @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) {
        return webhooks.list(merchant, limit, cursor);
    }
    @DeleteMapping("/api/v1/webhooks/{id}")
    ResponseEntity<Void> disable(@MerchantId UUID merchant, @PathVariable UUID id) { webhooks.disable(merchant, id); return ResponseEntity.noContent().build(); }
    @GetMapping("/api/v1/webhook-deliveries")
    Cursor.Page<Webhooks.Delivery> deliveries(@MerchantId UUID merchant, @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) {
        return webhooks.deliveries(merchant, limit, cursor);
    }
    @GetMapping("/api/v1/webhook-deliveries/{id}/attempts")
    List<Webhooks.Attempt> attempts(@MerchantId UUID merchant, @PathVariable UUID id, @RequestParam(defaultValue = "2147483647") int before) {
        return webhooks.attempts(merchant, id, before);
    }
    @PostMapping("/api/v1/webhook-deliveries/{id}/replay")
    ResponseEntity<Void> replay(@MerchantId UUID merchant, @PathVariable UUID id) { webhooks.replay(merchant, id); return ResponseEntity.accepted().build(); }
}
