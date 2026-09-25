package com.ledgerflow.merchant.internal;

import com.ledgerflow.audit.AuditLog;
import com.ledgerflow.identity.IdentityService;
import com.ledgerflow.merchant.MerchantAccess;
import com.ledgerflow.merchant.Role;
import com.ledgerflow.shared.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MerchantService {
    private final JdbcTemplate jdbc;
    private final IdentityService identity;
    private final MerchantAccess access;
    private final AuditLog audit;

    MerchantService(JdbcTemplate jdbc, IdentityService identity, MerchantAccess access, AuditLog audit) {
        this.jdbc = jdbc; this.identity = identity; this.access = access; this.audit = audit;
    }

    record Registered(UUID userId, UUID merchantId) { }
    record Merchant(UUID id, String displayName, Role role) { }
    record Member(UUID userId, Role role) { }

    @Transactional
    public Registered register(String email, String password, String merchantName) {
        var user = identity.create(email, password);
        var merchantId = UUID.randomUUID();
        jdbc.update("insert into merchant(id,display_name) values (?,?)", merchantId, merchantName.strip());
        jdbc.update("insert into merchant_membership(merchant_id,user_id,role) values (?,?,'OWNER')", merchantId, user.id());
        audit.append(merchantId, user.id(), "merchant.created", merchantId);
        return new Registered(user.id(), merchantId);
    }

    @Transactional(readOnly = true)
    public List<Merchant> memberships() {
        var user = identity.currentUser();
        return jdbc.query("""
                select m.id,m.display_name,mm.role from merchant m join merchant_membership mm on mm.merchant_id=m.id
                where mm.user_id=? and m.status='ACTIVE' order by m.created_at,m.id
                """, (rs, row) -> new Merchant(rs.getObject(1, UUID.class), rs.getString(2), Role.valueOf(rs.getString(3))), user.id());
    }

    @Transactional(readOnly = true)
    public Merchant merchant(UUID merchantId) {
        var tenant = access.require(merchantId);
        String name = jdbc.queryForObject("select display_name from merchant where id=?", String.class, merchantId);
        return new Merchant(merchantId, name, tenant.role());
    }

    @Transactional
    public Merchant rename(UUID merchantId, String name) {
        var tenant = lockAndAuthorize(merchantId);
        jdbc.update("update merchant set display_name=? where id=?", name.strip(), merchantId);
        audit.append(merchantId, tenant.actorId(), "merchant.updated", merchantId);
        return new Merchant(merchantId, name.strip(), tenant.role());
    }

    @Transactional(readOnly = true)
    public List<Member> members(UUID merchantId) {
        access.require(merchantId);
        return jdbc.query("select user_id,role from merchant_membership where merchant_id=? order by user_id",
                (rs, row) -> new Member(rs.getObject(1, UUID.class), Role.valueOf(rs.getString(2))), merchantId);
    }

    @Transactional
    public Member addMember(UUID merchantId, String email, Role role) {
        var tenant = lockAndAuthorize(merchantId);
        if (role == Role.OWNER && tenant.role() != Role.OWNER) throw forbidden();
        var userId = identity.findUser(email);
        jdbc.update("insert into merchant_membership(merchant_id,user_id,role) values (?,?,?)", merchantId, userId, role.name());
        audit.append(merchantId, tenant.actorId(), "membership.created", userId);
        return new Member(userId, role);
    }

    @Transactional
    public void changeMember(UUID merchantId, UUID userId, Role newRole) {
        var tenant = lockAndAuthorize(merchantId);
        var roles = jdbc.query("select role from merchant_membership where merchant_id=? and user_id=?",
                (rs, row) -> Role.valueOf(rs.getString(1)), merchantId, userId);
        if (roles.isEmpty()) throw new DomainException(404, "member-not-found", "The membership was not found.");
        Role old = roles.getFirst();
        if (tenant.role() != Role.OWNER && (old == Role.OWNER || newRole == Role.OWNER)) throw forbidden();
        if (old == Role.OWNER && newRole != Role.OWNER) {
            Long owners = jdbc.queryForObject("select count(*) from merchant_membership where merchant_id=? and role='OWNER'", Long.class, merchantId);
            if (owners == null || owners <= 1) throw new DomainException(409, "last-owner", "A merchant must retain an owner.");
        }
        if (newRole == null) jdbc.update("delete from merchant_membership where merchant_id=? and user_id=?", merchantId, userId);
        else jdbc.update("update merchant_membership set role=? where merchant_id=? and user_id=?", newRole.name(), merchantId, userId);
        audit.append(merchantId, tenant.actorId(), newRole == null ? "membership.revoked" : "membership.role-changed", userId);
    }

    private MerchantAccess.Tenant lockAndAuthorize(UUID merchantId) {
        access.require(merchantId).requireManagement();
        jdbc.queryForObject("select id from merchant where id=? for update", UUID.class, merchantId);
        var tenant = access.require(merchantId);
        tenant.requireManagement();
        return tenant;
    }

    private DomainException forbidden() { return new DomainException(403, "forbidden", "Only owners can manage ownership."); }
}
