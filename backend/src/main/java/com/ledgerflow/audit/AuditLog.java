package com.ledgerflow.audit;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLog {
    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID merchantId, UUID actorId, String action, UUID resourceId) {
        jdbc.update("""
                insert into audit_event(id,merchant_id,actor_id,action,resource_id,correlation_id)
                values (?,?,?,?,?,?)
                """, UUID.randomUUID(), merchantId, actorId, action, resourceId, MDC.get("requestId"));
    }
}
