package com.traceability.portal;

import com.traceability.integrations.bosta.BostaException;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Returns portal Step 4c-2 — the Bosta pickup location that returns go back to.
 *
 * The list comes live from Bosta ({@code GET /api/v2/pickup-locations}, a READ) with this
 * tenant's own decrypted API key; it is never cached. Saving validates the chosen id against a
 * fresh fetch of that list. The DB reads are short transactions of their own and the Bosta call
 * runs outside any transaction, so no connection is held across HTTP.
 *
 * Failures are {@link PortalSettingsService.FieldException}s (answered with a body):
 * no active Bosta account → 409 NO_BOSTA_ACCOUNT; Bosta refuses the key → 422
 * BOSTA_KEY_REFUSED; Bosta down → 502 BOSTA_UNAVAILABLE; unknown id → 400 RETURN_LOCATION_UNKNOWN.
 */
@Service
public class ReturnLocationService {

    public static final String FIELD = "returnLocationId";
    static final String KEY_REFUSED_MESSAGE = "Bosta didn't accept the connected API key for locations";

    public record Location(String id, String name) {}

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final BostaV2Client       bosta;
    private final EncryptionService   encryption;

    public ReturnLocationService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                 BostaV2Client bosta, EncryptionService encryption) {
        this.jdbc       = jdbc;
        this.tx         = new TransactionTemplate(txm);
        this.bosta      = bosta;
        this.encryption = encryption;
    }

    /** GET /api/v1/tenant/bosta/return-locations → [{id, name, isDefault, cityName}]. */
    public List<Map<String, Object>> list() {
        return fetch().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.id());
            m.put("name", l.name());
            m.put("isDefault", l.isDefault());
            m.put("cityName", l.cityName());
            return m;
        }).toList();
    }

    /**
     * The location to save for {@code id}. The one already saved is accepted without asking
     * Bosta again (so saving other settings never depends on Bosta being reachable); any other
     * id must be in a fresh fetch of the tenant's list.
     */
    public Location resolveForSave(String id) {
        UUID tenantId = TenantContext.require();
        String wanted = id.trim();
        Map<String, Object> saved = tx.execute(s -> jdbc.queryForList(
            "SELECT return_business_location_id, return_business_location_name FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1", tenantId)
            .stream().findFirst().orElse(null));
        if (saved != null && Objects.equals(saved.get("return_business_location_id"), wanted)) {
            return new Location(wanted, (String) saved.get("return_business_location_name"));
        }
        return fetch().stream()
            .filter(l -> l.id().equals(wanted))
            .findFirst()
            .map(l -> new Location(l.id(), l.name()))
            .orElseThrow(() -> new PortalSettingsService.FieldException(HttpStatus.BAD_REQUEST, FIELD,
                "RETURN_LOCATION_UNKNOWN", "That location isn't in your Bosta account."));
    }

    private List<BostaV2Client.PickupLocation> fetch() {
        UUID tenantId = TenantContext.require();
        String encrypted = tx.execute(s -> jdbc.queryForList(
            "SELECT api_key_encrypted FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1", String.class, tenantId)
            .stream().findFirst().orElse(null));
        if (encrypted == null) {
            throw new PortalSettingsService.FieldException(HttpStatus.CONFLICT, FIELD, "NO_BOSTA_ACCOUNT",
                "Connect Bosta first.");
        }
        try {
            return bosta.listPickupLocations(encryption.decrypt(encrypted));
        } catch (BostaV2Client.KeyRefusedException e) {
            throw new PortalSettingsService.FieldException(HttpStatus.UNPROCESSABLE_ENTITY, FIELD,
                "BOSTA_KEY_REFUSED", KEY_REFUSED_MESSAGE);
        } catch (BostaException e) {
            throw new PortalSettingsService.FieldException(HttpStatus.BAD_GATEWAY, FIELD,
                "BOSTA_UNAVAILABLE", "Couldn't reach Bosta. Please try again.");
        }
    }
}
