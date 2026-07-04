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

    // ── reg5: fetchDelivery — state is an object, code extracted ─────────────

    @Test
    void reg5_fetchDelivery_stateIsObject_codeExtracted() throws Exception {
        // Live Bosta v0: state is {"value":"Out for Delivery","code":41,...}, NOT a flat int.
        // Bug: old code read data.path("state").asInt(-1) which returns -1 for an ObjectNode.
        // Fix: read stateNode.isObject() ? stateNode.path("code").asInt(-1) : stateNode.asInt(-1).
        JsonNode raw = mapper.readTree(fixture("delivery-receiver.json"));

        JsonNode stateNode = raw.path("state");

        // Gate 5a: state must be an object (guards the fixture shape itself).
        assertThat(stateNode.isObject())
            .as("state must be an object in live Bosta v0 — flat int shape would regress parsing")
            .isTrue();

        // Gate 5b: old broken read returns -1 for an ObjectNode.
        assertThat(stateNode.asInt(-1))
            .as("data.path(\"state\").asInt(-1) on an ObjectNode must return -1 — " +
                "proves the old path was silently wrong and why fetchDelivery always returned code=-1")
            .isEqualTo(-1);

        // Gate 5c: correct extraction yields the actual code.
        int code = stateNode.isObject() ? stateNode.path("code").asInt(-1) : stateNode.asInt(-1);
        assertThat(code)
            .as("state.code must be the numeric state code")
            .isEqualTo(41);
    }

    // ── reg6: fetchDelivery — type is an object, value extracted + uppercased ─

    @Test
    void reg6_fetchDelivery_typeIsObject_valueExtractedAndUppercased() throws Exception {
        // Live Bosta v0: type is {"code":10,"value":"Send"}, NOT a flat string.
        // Bug: data.path("type").asText("SEND") returns the default "SEND" for ObjectNode —
        //      RTO deliveries silently returned "SEND" and were mapped to the wrong state.
        // Fix: isObject() ? path("value").asText("SEND").toUpperCase() : asText("SEND").toUpperCase()
        JsonNode raw = mapper.readTree(fixture("delivery-receiver.json"));

        JsonNode typeNode = raw.path("type");

        // Gate 6a: type must be an object.
        assertThat(typeNode.isObject())
            .as("type must be an object {code:N, value:\"...\"} in live Bosta v0")
            .isTrue();

        // Gate 6b: old broken read returns "" for ObjectNode (asText(default) ignores default for ContainerNodes).
        // The BostaStateMapper lookup key becomes "41:" which matches neither "41:SEND" nor "41:ALL"
        // for code 41, so every SEND/RTO delivery silently became an exception state.
        assertThat(typeNode.asText("SEND"))
            .as("data.path(\"type\").asText(\"SEND\") on ObjectNode returns empty string, not the type name — " +
                "BostaStateMapper gets \"\" and cannot find the mapping")
            .isEqualTo("");

        // Gate 6c: correct extraction + normalization.
        String type = typeNode.isObject()
            ? typeNode.path("value").asText("SEND").toUpperCase()
            : typeNode.asText("SEND").toUpperCase();
        assertThat(type)
            .as("type.value must be extracted and uppercased for BostaStateMapper key lookup")
            .isEqualTo("SEND");
    }

    // ── reg7: fetchDelivery — shopifyInfo.orderId preferred over top-level shopifyOrderId

    @Test
    void reg7_fetchDelivery_shopifyInfoOrderId_preferred() throws Exception {
        // Live Bosta v0: Shopify linkage lives under shopifyInfo.orderId (numeric string).
        // Old code read top-level shopifyOrderId which is absent in the real API.
        JsonNode raw = mapper.readTree(fixture("delivery-receiver.json"));

        // Gate 7a: shopifyInfo.orderId present.
        String shopifyOrderId = raw.path("shopifyInfo").path("orderId").asText(null);
        assertThat(shopifyOrderId)
            .as("shopifyInfo.orderId must be present for order matching")
            .isNotNull().isNotBlank();

        // Gate 7b: top-level shopifyOrderId must be absent (old path was wrong).
        assertThat(raw.path("shopifyOrderId").isMissingNode())
            .as("top-level shopifyOrderId must be absent — correct path is shopifyInfo.orderId")
            .isTrue();

        // Gate 7c: shopifyInfo.orderNumber holds the #-prefixed reference.
        assertThat(raw.path("shopifyInfo").path("orderNumber").asText(null))
            .as("shopifyInfo.orderNumber must be present")
            .isNotNull();
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
