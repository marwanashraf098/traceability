package com.traceability.review;

import com.traceability.account.UserService;
import com.traceability.identity.AuthService;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.CourierSimulation;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Review mode S5 — the review tenant's steps A and C (step B, the simulated-courier flag, is the ops
 * SQL scripts/ops/review-tenant-flag.sql: app_user can't write tenant_courier_simulation).
 *
 * Everything runs as app_user through the normal services, under TenantContext.runAs — no BYPASSRLS,
 * no owner pool (ReviewTenantSeederGuardTest). The owner's password is passed straight to the signup
 * service and is never logged or returned.
 */
@Service
public class ReviewTenantService {

    private static final Logger log = LoggerFactory.getLogger(ReviewTenantService.class);

    private final AuthService         auth;
    private final JwtService          jwt;
    private final UserService         users;
    private final ReviewTenantSeeder  seeder;
    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;

    public ReviewTenantService(AuthService auth, JwtService jwt, UserService users, ReviewTenantSeeder seeder,
                               JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.auth   = auth;
        this.jwt    = jwt;
        this.users  = users;
        this.seeder = seeder;
        this.jdbc   = jdbc;
        this.tx     = new TransactionTemplate(txm);
    }

    /** Step A: the normal signup (tenant, owner, default fulfillment location), then one PIN worker. */
    public Map<String, Object> create(OpsReviewTenantController.CreateRequest req) {
        String email = req.email() == null ? "" : req.email().trim().toLowerCase(Locale.ROOT);
        if (!email.endsWith("@tracedtech.com")) throw ReviewTenantException.notInternalEmail();

        // @tracedtech.com → AuthService records no ad attribution (isInternalEmail).
        TokenResponse tokens = auth.signup(new SignupRequest(req.tenantName(), req.ownerName(), email, req.phone(),
            req.password(), true, null), null, null);
        var claims  = jwt.verify(tokens.accessToken());
        UUID tenant = UUID.fromString((String) claims.getClaim("tenant"));
        UUID owner  = UUID.fromString(claims.getSubject());

        Map<String, Object> worker = TenantContext.runAs(tenant, () ->
            users.create(owner, "owner", req.workerName(), null, "worker", null, req.workerPin()));

        log.info("Review tenant created: tenant={} owner={} worker={} (not yet flagged — run review-tenant-flag.sql)",
            tenant, owner, worker.get("id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenant.toString());
        out.put("ownerUserId", owner.toString());
        out.put("workerUserId", worker.get("id"));
        out.put("next", "Flag it: psql -v tenant_id=" + tenant + " -f scripts/ops/review-tenant-flag.sql");
        return out;
    }

    /** Step C: refuses unless flagged simulated, refuses when the fixture is already there. */
    public Map<String, Object> seed(UUID tenantId) {
        boolean simulated = TenantContext.runAs(tenantId, () ->
            Boolean.TRUE.equals(tx.execute(s -> CourierSimulation.isSimulated(jdbc, tenantId))));
        if (!simulated) throw ReviewTenantException.notSimulated();

        boolean exists = TenantContext.runAs(tenantId, () -> Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM orders WHERE tenant_id = ? AND external_id LIKE 'review-fixture:%') " +
            "    OR EXISTS (SELECT 1 FROM stores WHERE tenant_id = ? AND shop_domain = ?)",
            Boolean.class, tenantId, tenantId, ReviewTenantSeeder.PLACEHOLDER_SHOP))));
        if (exists) throw ReviewTenantException.fixtureExists();

        UUID owner  = firstUser(tenantId, "owner");
        UUID worker = firstUser(tenantId, "worker");
        if (owner == null || worker == null) throw ReviewTenantException.missingUsers();

        Map<String, Object> summary = seeder.seed(tenantId, owner, worker);
        log.info("Review tenant seeded: tenant={} summary={}", tenantId, summary);
        return summary;
    }

    private UUID firstUser(UUID tenantId, String role) {
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM users WHERE tenant_id = ? AND role = ?::user_role AND active ORDER BY created_at, id LIMIT 1",
                UUID.class, tenantId, role);
            return ids.isEmpty() ? null : ids.get(0);
        }));
    }
}
