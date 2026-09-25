package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class IdentityIntegrationTest extends IntegrationSupport {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void jwtAuthenticatesButCannotReadAnotherMerchant() throws Exception {
        var first = register();
        var second = register();
        assertThat(request("GET", "/api/v1/auth/me", null, first.token(), null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/merchant", null, first.token(), first.merchantId()).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/merchant", null, first.token(), second.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/merchant/members", null, first.token(), second.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/auth/me", null, first.token() + "tampered", null).statusCode()).isEqualTo(401);
        String hash = jdbc.queryForObject("select password_hash from app_user where id=?", String.class, first.userId());
        assertThat(hash).startsWith("$argon2id$");
        assertThatThrownBy(() -> jdbc.update("delete from audit_event where merchant_id=?", first.merchantId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void viewerCannotWriteAndMembershipRevocationIsImmediate() throws Exception {
        var owner = register();
        var viewer = register();
        assertThat(request("POST", "/api/v1/merchant/members", Map.of("email", viewer.email(), "role", "VIEWER"),
                owner.token(), owner.merchantId()).statusCode()).isEqualTo(201);
        assertThat(request("GET", "/api/v1/merchant", null, viewer.token(), owner.merchantId()).statusCode()).isEqualTo(200);
        assertThat(request("PATCH", "/api/v1/merchant", Map.of("displayName", "Not allowed"),
                viewer.token(), owner.merchantId()).statusCode()).isEqualTo(403);
        assertThat(request("DELETE", "/api/v1/merchant/members/" + viewer.userId(), null,
                owner.token(), owner.merchantId()).statusCode()).isEqualTo(204);
        assertThat(request("GET", "/api/v1/merchant", null, viewer.token(), owner.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/api/v1/merchant/members/" + owner.userId(), null,
                owner.token(), owner.merchantId()).statusCode()).isEqualTo(409);
    }

    @Test
    void rotatedTokenReuseRevokesTheEntireSessionIncludingAccessTokens() throws Exception {
        var user = register();
        var refresh = request("POST", "/api/v1/auth/refresh", Map.of("refreshToken", user.refresh()), null, null);
        assertThat(refresh.statusCode()).isEqualTo(200);
        String nextAccess = body(refresh).get("accessToken").asString();
        String nextRefresh = body(refresh).get("refreshToken").asString();
        assertThat(nextRefresh).isNotEqualTo(user.refresh());
        assertThat(request("GET", "/api/v1/auth/me", null, nextAccess, null).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/v1/auth/refresh", Map.of("refreshToken", user.refresh()), null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/auth/me", null, nextAccess, null).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/api/v1/auth/refresh", Map.of("refreshToken", nextRefresh), null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void logoutRevokesSessionAndInvalidCredentialsDoNotExposeAccountDetails() throws Exception {
        var user = register();
        var rejected = request("POST", "/api/v1/auth/login", Map.of("email", user.email(), "password", "incorrect"), null, null);
        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(body(rejected).get("code").asString()).isEqualTo("invalid-credentials");
        assertThat(request("POST", "/api/v1/auth/logout", Map.of("refreshToken", user.refresh()), null, null).statusCode()).isEqualTo(204);
        assertThat(request("GET", "/api/v1/auth/me", null, user.token(), null).statusCode()).isEqualTo(401);
    }

    @Test
    void simultaneousOwnerRemovalCannotLeaveAnOwnerlessMerchant() throws Exception {
        var first = register();
        var second = register();
        assertThat(request("POST", "/api/v1/merchant/members", Map.of("email", second.email(), "role", "OWNER"),
                first.token(), first.merchantId()).statusCode()).isEqualTo(201);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> removeFirst = () -> {
                ready.countDown(); start.await();
                return request("DELETE", "/api/v1/merchant/members/" + first.userId(), null, first.token(), first.merchantId()).statusCode();
            };
            Callable<Integer> removeSecond = () -> {
                ready.countDown(); start.await();
                return request("DELETE", "/api/v1/merchant/members/" + second.userId(), null, second.token(), first.merchantId()).statusCode();
            };
            var a = executor.submit(removeFirst);
            var b = executor.submit(removeSecond);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(204, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from merchant_membership where merchant_id=? and role='OWNER'",
                Long.class, first.merchantId())).isEqualTo(1);
    }
}
