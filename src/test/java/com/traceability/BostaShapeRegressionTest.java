package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * Shape regression guards for Bosta API responses. No Spring context needed.
 *
 * These tests document the API shapes confirmed by live probe on 2026-06-23 and
 * guard against the two field-path bugs that were silently wrong before the probe:
 *
 *   Bug 1 — AWB: BostaHttpGateway read dataNode.path("pdf") expecting an object shape
 *           {"data":{"pdf":"<base64>"}}, but the live API returns {"data":"<base64>"}
 *           (data is a plain textual node). The gateway always fell through to the
 *           email-path log, never returning a PDF to the caller.
 *
 *   Bug 2 — Receiver: ShipmentLinkService read raw.path("consignee").path("phone"),
 *           but the live API places the recipient at receiver.phone; the consignee key
 *           is absent entirely. Every Mode-B delivery silently had no phone and fell
 *           back to COD_ONLY_AMBIGUOUS.
 */
class BostaShapeRegressionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── reg1: AWB mass-awb response ──────────────────────────────────────────

    @Test
    void reg1_awbResponse_dataIsTextualBase64_yieldsPdfBytes() throws Exception {
        // Live Bosta v0: response.data is the base64 PDF string directly.
        // Bug: the old path was dataNode.path("pdf").asText(null), which returns null
        // when dataNode is a TextNode (not an ObjectNode) — so the PDF was never decoded.
        JsonNode resp = mapper.readTree(fixture("mass-awb-response.json"));

        JsonNode dataNode = resp.path("data");

        // Regression gate 1a: data must be a plain string, not an object.
        // If this fails, the fixture was accidentally changed to the nested shape.
        assertThat(dataNode.isTextual())
            .as("response.data must be a plain string (TextNode), not an object — " +
                "the old bug assumed data.pdf; live API returns data as base64 directly")
            .isTrue();

        // Gateway decodes the string directly — must yield %PDF magic bytes.
        byte[] pdfBytes = Base64.getDecoder().decode(dataNode.asText());
        assertThat(new String(pdfBytes)).startsWith("%PDF");

        // Regression gate 1b: the old broken path must resolve to a MissingNode.
        // If dataNode.path("pdf") ever returns a non-missing node, the fixture shape
        // has regressed to the nested form — a signal that the fix may need revisiting.
        assertThat(dataNode.path("pdf").isMissingNode())
            .as("data.pdf must be absent; data IS the base64 string, not an object " +
                "containing pdf. The old broken path relied on this nested key.")
            .isTrue();
    }

    // ── reg2: delivery receiver shape ────────────────────────────────────────

    @Test
    void reg2_delivery_receiverPhone_isPopulated_consigneeIsAbsent() throws Exception {
        // Live Bosta v0: recipient is at receiver.phone in E.164 format (+20XXXXXXXXXX).
        // Bug: ShipmentLinkService read raw.path("consignee").path("phone") — consignee
        // key is absent entirely in the live API. Every phone lookup silently returned
        // null, causing COD_ONLY_AMBIGUOUS on every Mode-B delivery.
        JsonNode raw = mapper.readTree(fixture("delivery-receiver.json"));

        // Regression gate 2a: receiver.phone must be present and E.164 Egyptian format.
        String phone = raw.path("receiver").path("phone").asText(null);
        assertThat(phone)
            .as("receiver.phone must be populated")
            .isNotNull().isNotBlank();
        assertThat(phone)
            .as("Bosta Egypt uses E.164 +20 prefix")
            .startsWith("+20");

        // Additional receiver fields used in future PII display.
        assertThat(raw.path("receiver").path("fullName").asText(null))
            .as("receiver.fullName must be present")
            .isNotNull().isNotBlank();

        // Regression gate 2b: consignee key must be absent.
        // If this fails, the fixture was accidentally changed to the wrong shape.
        assertThat(raw.path("consignee").isMissingNode())
            .as("consignee key must be absent from live Bosta v0 delivery objects — " +
                "the old bug read consignee.phone which is always missing")
            .isTrue();
    }

    // ── reg3: delivery address is in dropOffAddress (not consignee.address) ──

    @Test
    void reg3_delivery_addressInDropOffAddress_notConsignee() throws Exception {
        JsonNode raw = mapper.readTree(fixture("delivery-receiver.json"));

        // Bosta v0 delivery address: dropOffAddress.city.name, zone.name, firstLine.
        assertThat(raw.path("dropOffAddress").path("city").path("name").asText(null))
            .as("dropOffAddress.city.name must be present")
            .isNotNull().isNotBlank();
        assertThat(raw.path("dropOffAddress").path("zone").path("name").asText(null))
            .as("dropOffAddress.zone.name must be present")
            .isNotNull().isNotBlank();
        assertThat(raw.path("dropOffAddress").path("firstLine").asText(null))
            .as("dropOffAddress.firstLine must be present")
            .isNotNull().isNotBlank();
    }

    // ── reg4: COD is a flat scalar, not a nested amount object ───────────────

    @Test
    void reg4_delivery_codIsFlatScalar_notNestedAmount() throws Exception {
        // Bosta v0: cod is a top-level numeric scalar (e.g. 150), NOT {"amount": 150}.
        // ShipmentLinkService.matchByPhoneAndCod() reads raw.path("cod") directly.
        JsonNode raw = mapper.readTree(fixture("delivery-receiver.json"));

        JsonNode codNode = raw.path("cod");
        assertThat(codNode.isNumber())
            .as("cod must be a numeric scalar, not an object")
            .isTrue();
        assertThat(codNode.path("amount").isMissingNode())
            .as("cod.amount must be absent — cod IS the scalar value, not an object")
            .isTrue();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String fixture(String name) throws Exception {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("bosta/" + name)) {
            assertThat(is).as("fixture bosta/" + name + " must exist on classpath").isNotNull();
            return new String(is.readAllBytes());
        }
    }
}
