package com.ledgerflow.merchant.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/api-keys")
class ApiKeyController {
    private final ApiKeys keys;
    ApiKeyController(ApiKeys keys) { this.keys = keys; }
    record Create(@NotBlank @Size(max = 80) String name, boolean canWrite) { }

    @PostMapping
    ResponseEntity<ApiKeys.Created> create(@RequestHeader("X-Merchant-Id") UUID merchant, @Valid @RequestBody Create request) {
        var key = keys.create(merchant, request.name(), request.canWrite());
        return ResponseEntity.created(URI.create("/api/v1/api-keys")).cacheControl(CacheControl.noStore()).body(key);
    }

    @GetMapping
    List<ApiKeys.Key> list(@RequestHeader("X-Merchant-Id") UUID merchant) { return keys.list(merchant); }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@RequestHeader("X-Merchant-Id") UUID merchant, @PathVariable UUID id) {
        keys.revoke(merchant, id); return ResponseEntity.noContent().build();
    }
}
