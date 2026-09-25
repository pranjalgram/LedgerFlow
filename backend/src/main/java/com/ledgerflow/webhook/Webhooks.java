package com.ledgerflow.webhook;

import com.ledgerflow.audit.AuditLog;
import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.outbox.Outbox;
import com.ledgerflow.outbox.ProcessedEvents;
import com.ledgerflow.shared.Cursor;
import com.ledgerflow.shared.DomainException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class Webhooks {
    public static final String CONSUMER = "ledgerflow-webhooks-v1";
    private static final Set<String> EVENTS = Set.of("payment.created", "payment.processing", "payment.succeeded", "payment.failed",
            "payment.cancelled", "payment.partially_refunded", "payment.refunded", "refund.created", "refund.succeeded", "refund.failed", "transfer.completed", "ledger.transaction.posted");
    private final JdbcTemplate jdbc;
    private final MerchantAccess access;
    private final WebhookSecrets secrets;
    private final WebhookTransport transport;
    private final AuditLog audit;
    private final ProcessedEvents processed;
    private final ObjectMapper json;
    public Webhooks(JdbcTemplate jdbc, MerchantAccess access, WebhookSecrets secrets, WebhookTransport transport,
            AuditLog audit, ProcessedEvents processed, ObjectMapper json) {
        this.jdbc = jdbc; this.access = access; this.secrets = secrets; this.transport = transport;
        this.audit = audit; this.processed = processed; this.json = json;
    }
    public record Endpoint(UUID id, String url, List<String> subscriptions, boolean enabled, Instant createdAt) { }
    public record Created(Endpoint endpoint, String secret) { }
    @Transactional
    public Created create(UUID merchant, String url, List<String> subscriptions) {
        var actor = access.require(merchant); actor.requireManagement();
        transport.validate(url);
        if (subscriptions.isEmpty() || subscriptions.size() > 30 || !EVENTS.containsAll(subscriptions))
            throw new DomainException(400, "invalid-subscriptions", "Choose supported event types.");
        UUID id = UUID.randomUUID(); String secret = secrets.generate();
        String[] events = subscriptions.stream().distinct().sorted().toArray(String[]::new);
        jdbc.update("insert into webhook_endpoint(id,merchant_id,url,encrypted_secret,subscriptions) values (?,?,?,?,?)",
                id, merchant, url, secrets.encrypt(id, secret), events);
        audit.append(merchant, actor.actorId(), "webhook.created", id);
        return new Created(new Endpoint(id, url, List.of(events), true, Instant.now()), secret);
    }
    @Transactional
    public void disable(UUID merchant, UUID endpoint) {
        var actor = access.require(merchant); actor.requireManagement();
        if (jdbc.update("update webhook_endpoint set enabled=false where merchant_id=? and id=?", merchant, endpoint) == 0) throw missing();
        audit.append(merchant, actor.actorId(), "webhook.disabled", endpoint);
    }
    @Transactional(readOnly = true)
    public Cursor.Page<Endpoint> list(UUID merchant, int limit, String cursor) {
        access.require(merchant); var key = Cursor.parse(cursor);
        var rows = jdbc.query("select id,url,subscriptions,enabled,created_at from webhook_endpoint where merchant_id=? and (created_at,id)<(?,?) order by created_at desc,id desc limit ?",
                (rs, row) -> new Endpoint(rs.getObject(1, UUID.class), rs.getString(2), List.of((String[]) rs.getArray(3).getArray()), rs.getBoolean(4), rs.getTimestamp(5).toInstant()),
                merchant, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
    }
    @Transactional
    public void consume(Outbox.Event event) {
        if (!processed.first(CONSUMER, event.id())) return;
        jdbc.update("""
                insert into webhook_delivery(id,merchant_id,endpoint_id,event_id,payload,trace_context)
                select gen_random_uuid(),merchant_id,id,?,?,? from webhook_endpoint
                where merchant_id=? and enabled and ?=any(subscriptions) on conflict do nothing
                """, event.id(), json.writeValueAsString(event), com.ledgerflow.shared.Telemetry.currentTraceParent(), event.merchantId(), event.type());
    }
    public record Delivery(UUID id, UUID endpointId, UUID eventId, String state, int attempts, Instant createdAt,
                           Instant nextAttemptAt, Integer httpStatus, String error) { }
    public record Attempt(int attempt, Integer httpStatus, String error, long durationMs, Instant completedAt) { }
    @Transactional(readOnly = true)
    public Cursor.Page<Delivery> deliveries(UUID merchant, int limit, String cursor) {
        access.require(merchant); var key = Cursor.parse(cursor);
        var rows = jdbc.query("select id,endpoint_id,event_id,state,attempts,created_at,next_attempt_at,last_http_status,last_error from webhook_delivery where merchant_id=? and (created_at,id)<(?,?) order by created_at desc,id desc limit ?",
                (rs, row) -> new Delivery(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), rs.getObject(8, Integer.class), rs.getString(9)),
                merchant, Timestamp.from(key.time()), key.id(), Cursor.limit(limit) + 1);
        return Cursor.page(rows, limit, row -> new Cursor(row.createdAt(), row.id()));
    }
    @Transactional(readOnly = true)
    public List<Attempt> attempts(UUID merchant, UUID delivery, int before) {
        access.require(merchant); requireDelivery(merchant, delivery);
        return jdbc.query("select attempt,http_status,error_code,duration_ms,completed_at from webhook_attempt where delivery_id=? and attempt<? order by attempt desc limit 100",
                (rs, row) -> new Attempt(rs.getInt(1), rs.getObject(2, Integer.class), rs.getString(3), rs.getLong(4), rs.getTimestamp(5).toInstant()), delivery, before);
    }
    @Transactional
    public void replay(UUID merchant, UUID delivery) {
        var actor = access.require(merchant); actor.requireManagement(); requireDelivery(merchant, delivery);
        if (jdbc.update("""
                update webhook_delivery d set state='PENDING',cycle_attempts=0,next_attempt_at=now(),completed_at=null
                where d.id=? and d.merchant_id=? and d.state in ('SUCCEEDED','FAILED')
                and exists(select 1 from webhook_endpoint e where e.id=d.endpoint_id and e.enabled)
                """, delivery, merchant) == 0) throw new DomainException(409, "delivery-not-replayable", "Only completed deliveries to active endpoints can be replayed.");
        audit.append(merchant, actor.actorId(), "webhook.replayed", delivery);
    }
    private void requireDelivery(UUID merchant, UUID delivery) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from webhook_delivery where merchant_id=? and id=?)", Boolean.class, merchant, delivery))) throw missing();
    }
    private static DomainException missing() { return new DomainException(404, "webhook-not-found", "The webhook resource was not found."); }
}
