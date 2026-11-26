package com.traceability.setup;

import com.traceability.account.OnboardingProgressService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wizard entry (2026-11-26) — the web setup step "What will you use Traced for?", shown before Connect Shopify while
 * the tenant has no intent. Sets {@code onboarding_prefs.intent} ONCE: 'orders' | 'bazar' | 'both'.
 * <ul>
 *   <li>Refused 409 {@code SETUP_PURPOSE_ALREADY_SET} when an intent is stored (signup's ?for=bazar, the App Store
 *       form, or an earlier answer) and 409 {@code SETUP_FINISHED} when the gate is already passed
 *       ({@link SetupStatus#step} null). The tenant row is locked first, so two answers can't both land.</li>
 *   <li>Routing follows the existing rules: 'bazar' skips the address gate and Bosta (SetupStatus / setupIntro read
 *       intent = 'bazar' exactly); 'orders' and 'both' take the full flow ('both' is offered the wizard at the end).</li>
 *   <li>Nothing else is written: bazar is on by default since V244 (the answer never switches it).</li>
 * </ul>
 */
@Service
public class SetupPurposeService {

    private final JdbcTemplate jdbc;
    private final OnboardingProgressService prefs;

    public SetupPurposeService(JdbcTemplate jdbc, OnboardingProgressService prefs) {
        this.jdbc = jdbc;
        this.prefs = prefs;
    }

    @Transactional
    public Map<String, Object> set(UUID actorUserId, String purpose) {
        UUID tenantId = TenantContext.require();
        if (purpose == null || !OnboardingProgressService.SETUP_PURPOSES.contains(purpose)) {
            throw SetupException.invalid("SETUP_PURPOSE_INVALID",
                "Choose what you'll use Traced for.", "اختر ما ستستخدم Traced من أجله.");
        }
        String current = jdbc.queryForObject(
            "SELECT COALESCE(onboarding_prefs->>'intent', '') FROM tenants WHERE id = ? FOR UPDATE", String.class, tenantId);
        if (current != null && !current.isEmpty()) {
            throw new SetupException("SETUP_PURPOSE_ALREADY_SET",
                "You've already chosen what you'll use Traced for.", "لقد اخترت بالفعل ما ستستخدم Traced من أجله.",
                HttpStatus.CONFLICT);
        }
        if (SetupStatus.step(jdbc, tenantId) == null) {
            throw new SetupException("SETUP_FINISHED",
                "Your setup is already finished.", "تم الانتهاء من الإعداد بالفعل.", HttpStatus.CONFLICT);
        }
        prefs.recordSetupPurpose(actorUserId, purpose);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", purpose);
        out.put("setupStep", SetupStatus.step(jdbc, tenantId));
        return out;
    }
}
