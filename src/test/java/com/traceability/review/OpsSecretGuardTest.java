package com.traceability.review;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review mode S5 — o1 (unit half): TRACED_OPS_SECRET unset / blank → 404 (the ops endpoints don't
 * exist); set → a missing or wrong X-Ops-Secret is 403, the right one passes.
 */
class OpsSecretGuardTest {

    private static ResponseStatusException status(HttpStatus s) {
        return new ResponseStatusException(s);
    }

    @Test
    void unsetOrBlank_isNotFound_whateverTheHeader() {
        for (String secret : new String[]{null, "", "   "}) {
            OpsSecretGuard g = new OpsSecretGuard(secret);
            assertThatThrownBy(() -> g.check("anything")).isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
            assertThatThrownBy(() -> g.check(null)).isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void set_missingOrWrongHeader_isForbidden_rightOnePasses() {
        OpsSecretGuard g = new OpsSecretGuard("s3cr3t-ops-value");
        assertThatThrownBy(() -> g.check(null)).isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> g.check("wrong")).isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> g.check("s3cr3t-ops-value ")).isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
        assertThatCode(() -> g.check("s3cr3t-ops-value")).doesNotThrowAnyException();
    }
}
