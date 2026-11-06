package com.traceability;

import com.traceability.portal.PortalFeesRollout;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Returns portal fees — per-store rollout rule (TRACED_PORTAL_FEES_TENANTS). */
class PortalFeesRolloutUnitTest {

    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    @Test
    void emptyList_flagOff_isToday_offForEveryone() {
        for (String raw : new String[]{null, "", "  ", " , ,"}) {
            PortalFeesRollout r = new PortalFeesRollout(false, raw);
            assertThat(r.anyOn()).isFalse();
            assertThat(r.on(A)).isFalse();
            assertThat(r.on(null)).isFalse();
        }
    }

    @Test
    void listedTenantOn_unlistedOff_spacesAndEmptyEntriesIgnored() {
        PortalFeesRollout r = new PortalFeesRollout(false, " " + A + " ,, " + A.toString().toUpperCase() + " ");
        assertThat(r.anyOn()).isTrue();
        assertThat(r.on(A)).isTrue();
        assertThat(r.on(B)).isFalse();
        assertThat(r.on(null)).isFalse();
    }

    @Test
    void flagOn_everyTenant_listIrrelevant() {
        PortalFeesRollout r = new PortalFeesRollout(true, A.toString());
        assertThat(r.on(B)).isTrue();
        assertThat(PortalFeesRollout.of(true).on(B)).isTrue();
        assertThat(PortalFeesRollout.of(false).on(B)).isFalse();
    }

    @Test
    void anEntryThatIsntATenantId_stopsStartup_withoutEchoingIt() {
        for (String bad : new String[]{"nour-studio", A + ",1-1-1-1-1", A + "x"}) {
            assertThatThrownBy(() -> new PortalFeesRollout(false, bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRACED_PORTAL_FEES_TENANTS")
                .hasMessageNotContaining("nour-studio").hasMessageNotContaining("1-1-1-1-1");
        }
    }
}
