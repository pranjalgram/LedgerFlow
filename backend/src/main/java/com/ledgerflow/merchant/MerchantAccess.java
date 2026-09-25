package com.ledgerflow.merchant;

import com.ledgerflow.identity.IdentityService;
import com.ledgerflow.shared.DomainException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantAccess {
    private final IdentityService identity;
    private final JdbcTemplate jdbc;
    public MerchantAccess(IdentityService identity, JdbcTemplate jdbc) { this.identity = identity; this.jdbc = jdbc; }

    public record Tenant(UUID merchantId, UUID actorId, Role role) {
        public void requireWrite() {
            if (!role.canWrite()) throw new DomainException(403, "forbidden", "This role cannot modify resources.");
        }
        public void requireManagement() {
            if (!role.canManage()) throw new DomainException(403, "forbidden", "Merchant management permission is required.");
        }
    }

    @Transactional(readOnly = true)
    public Tenant require(UUID merchantId) {
        var user = identity.currentUser();
        var roles = jdbc.query("""
                select mm.role from merchant_membership mm join merchant m on m.id=mm.merchant_id
                where mm.merchant_id=? and mm.user_id=? and m.status='ACTIVE'
                """, (rs, row) -> Role.valueOf(rs.getString(1)), merchantId, user.id());
        if (roles.isEmpty()) throw new DomainException(404, "merchant-not-found", "The merchant was not found.");
        return new Tenant(merchantId, user.id(), roles.getFirst());
    }
}
