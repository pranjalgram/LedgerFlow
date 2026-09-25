package com.ledgerflow.identity.internal;

import com.ledgerflow.identity.IdentityService;
import com.ledgerflow.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final IdentityService identity;
    AuthController(IdentityService identity) { this.identity = identity; }

    record Login(@NotBlank @Email @Size(max = 254) String email,
                 @NotBlank @Size(max = 128) String password) { }
    record Refresh(@NotBlank @Size(min = 43, max = 43) String refreshToken) { }

    @PostMapping("/login")
    ResponseEntity<IdentityService.AuthTokens> login(@Valid @RequestBody Login request) {
        var tokens = identity.login(request.email(), request.password()).orElseThrow(AuthController::invalid);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens);
    }

    @PostMapping("/refresh")
    ResponseEntity<IdentityService.AuthTokens> refresh(@Valid @RequestBody Refresh request) {
        var tokens = identity.refresh(request.refreshToken()).orElseThrow(AuthController::invalid);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody Refresh request) {
        identity.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    IdentityService.UserView me() { return identity.currentUser(); }

    private static DomainException invalid() {
        return new DomainException(401, "invalid-credentials", "Valid credentials are required.");
    }
}
