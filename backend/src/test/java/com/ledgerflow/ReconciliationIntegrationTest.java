package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.ledgerflow.reconciliation.ReconciliationQueue;
import com.ledgerflow.reconciliation.ReconciliationScanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ReconciliationIntegrationTest extends IntegrationSupport {
    @Autowired ReconciliationQueue queue;
    @Autowired ReconciliationScanner scanner;
    @Autowired JdbcTemplate jdbc;

    @Test
    void cleanReportsAreRepeatableAndTenantScoped() throws Exception {
        var owner = register(); UUID wallet = wallet(owner);
        fund(owner, wallet, 1000);
        UUID first = run(owner);
        assertThat(status(first)).isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("select records_processed from reconciliation_run where id=?", Long.class, first)).isGreaterThan(0);
        assertThat(status(run(owner))).isEqualTo("PASSED");
        var overview = request("GET", "/api/v1/overview", null, owner.token(), owner.merchantId());
        assertThat(overview.statusCode()).isEqualTo(200);
        assertThat(body(overview).get("walletBalanceMinor").asString()).isEqualTo("1000");
        assertThat(body(request("GET", "/api/v1/operations/health", null, owner.token(), owner.merchantId())).get("outboxPending").asLong()).isGreaterThan(0);
        assertThat(request("GET", "/api/v1/audit-events", null, owner.token(), owner.merchantId()).statusCode()).isEqualTo(200);
        var outsider = register();
        assertThat(request("GET", "/api/v1/overview", null, outsider.token(), owner.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/operations/dead-letters", null, outsider.token(), owner.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/reconciliation-runs/" + first, null, outsider.token(), outsider.merchantId()).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/v1/reconciliation-runs/" + first + "/discrepancies", null, outsider.token(), outsider.merchantId()).statusCode()).isEqualTo(404);
    }

    @Test
    void detectsPrivilegedCorruptionWithoutRepairingHistory() throws Exception {
        var owner = register(); UUID wallet = wallet(owner); fund(owner, wallet, 1000);
        UUID entry = jdbc.queryForObject("select id from ledger_entry where merchant_id=? and side='CREDIT'", UUID.class, owner.merchantId());
        // Only this test's privileged connection bypasses triggers; application runtime cannot do this.
        corruptEntry(entry, 1001);
        try {
            UUID run = run(owner);
            assertThat(status(run)).isEqualTo("DISCREPANCIES");
            assertThat(jdbc.queryForList("select code from reconciliation_discrepancy where run_id=?", String.class, run))
                    .contains("JOURNAL_UNBALANCED", "BALANCE_MISMATCH", "POSTING_MISMATCH");
            assertThat(jdbc.queryForObject("select amount_minor from ledger_entry where id=?", Long.class, entry)).isEqualTo(1001);
            assertThat(status(run(owner))).isEqualTo("DISCREPANCIES");
        } finally { corruptEntry(entry, 1000); }
        assertThat(status(run(owner))).isEqualTo("PASSED");
    }

    @Test
    void abandonedRunCanBeReclaimedWithoutStaleWorkerCompletion() throws Exception {
        var owner = register();
        var created = request("POST", "/api/v1/reconciliation-runs", null, owner.token(), owner.merchantId());
        UUID id = UUID.fromString(body(created).get("id").asString());
        assertThat(request("POST", "/api/v1/reconciliation-runs", null, owner.token(), owner.merchantId()).statusCode()).isEqualTo(409);
        var old = queue.claim().orElseThrow();
        jdbc.update("update reconciliation_run set lease_until=now()-interval '1 second' where id=?", id);
        var current = queue.claim().orElseThrow();
        scanner.scan(old); assertThat(status(id)).isEqualTo("RUNNING");
        scanner.scan(current); assertThat(status(id)).isEqualTo("PASSED");
    }

    @Test
    void scansDuringConcurrentFundingDoNotReportTornBalances() throws Exception {
        var owner = register(); UUID wallet = wallet(owner);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                for (int index = 0; index < 20; index++) fund(owner, wallet, 100);
                return true;
            });
            for (int index = 0; index < 5; index++) assertThat(status(run(owner))).isEqualTo("PASSED");
            assertThat(writer.get(30, TimeUnit.SECONDS)).isTrue();
        }
    }
    private UUID run(Account owner) throws Exception {
        var created = request("POST", "/api/v1/reconciliation-runs", null, owner.token(), owner.merchantId());
        assertThat(created.statusCode()).isEqualTo(202);
        UUID id = UUID.fromString(body(created).get("id").asString());
        var job = queue.claim().orElseThrow(); assertThat(job.id()).isEqualTo(id);
        scanner.scan(job); return id;
    }
    private String status(UUID id) { return jdbc.queryForObject("select status from reconciliation_run where id=?", String.class, id); }
    private UUID wallet(Account owner) throws Exception {
        var response = request("POST", "/api/v1/wallets", Map.of("label", "Reconciliation wallet", "kind", "CUSTOMER", "currency", "INR"), owner.token(), owner.merchantId());
        assertThat(response.statusCode()).isEqualTo(201); return UUID.fromString(body(response).get("id").asString());
    }
    private void fund(Account owner, UUID wallet, long amount) throws Exception {
        assertThat(request("POST", "/api/v1/wallets/" + wallet + "/funding", Map.of("amount", amount, "currency", "INR"), owner.token(), owner.merchantId(), UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
    }
    private void corruptEntry(UUID entry, long amount) throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
                var statement = connection.createStatement();
                var update = connection.prepareStatement("update ledger_entry set amount_minor=? where id=?")) {
            statement.execute("set session_replication_role='replica'");
            update.setLong(1, amount); update.setObject(2, entry); update.executeUpdate();
        }
    }
}
