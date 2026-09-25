package com.ledgerflow.merchant.internal;

import com.ledgerflow.merchant.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class MerchantController {
    private final MerchantService merchants;
    MerchantController(MerchantService merchants) { this.merchants = merchants; }

    record Registration(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(min = 12, max = 128) String password,
                        @NotBlank @Size(max = 120) String merchantName) { }
    record Rename(@NotBlank @Size(max = 120) String displayName) { }
    record AddMember(@NotBlank @Email @Size(max = 254) String email, @NotNull Role role) { }
    record ChangeRole(@NotNull Role role) { }

    @PostMapping("/auth/register")
    ResponseEntity<MerchantService.Registered> register(@Valid @RequestBody Registration request) {
        return ResponseEntity.created(URI.create("/api/v1/merchant"))
                .body(merchants.register(request.email(), request.password(), request.merchantName()));
    }

    @GetMapping("/merchants")
    List<MerchantService.Merchant> memberships() { return merchants.memberships(); }

    @GetMapping("/merchant")
    MerchantService.Merchant merchant(@RequestHeader("X-Merchant-Id") UUID merchantId) { return merchants.merchant(merchantId); }

    @PatchMapping("/merchant")
    MerchantService.Merchant rename(@RequestHeader("X-Merchant-Id") UUID merchantId, @Valid @RequestBody Rename request) {
        return merchants.rename(merchantId, request.displayName());
    }

    @GetMapping("/merchant/members")
    List<MerchantService.Member> members(@RequestHeader("X-Merchant-Id") UUID merchantId) { return merchants.members(merchantId); }

    @PostMapping("/merchant/members")
    ResponseEntity<MerchantService.Member> addMember(@RequestHeader("X-Merchant-Id") UUID merchantId,
            @Valid @RequestBody AddMember request) {
        var member = merchants.addMember(merchantId, request.email(), request.role());
        return ResponseEntity.created(URI.create("/api/v1/merchant/members/" + member.userId())).body(member);
    }

    @PatchMapping("/merchant/members/{userId}")
    ResponseEntity<Void> changeRole(@RequestHeader("X-Merchant-Id") UUID merchantId, @PathVariable UUID userId,
            @Valid @RequestBody ChangeRole request) {
        merchants.changeMember(merchantId, userId, request.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/merchant/members/{userId}")
    ResponseEntity<Void> removeMember(@RequestHeader("X-Merchant-Id") UUID merchantId, @PathVariable UUID userId) {
        merchants.changeMember(merchantId, userId, null);
        return ResponseEntity.noContent().build();
    }
}
