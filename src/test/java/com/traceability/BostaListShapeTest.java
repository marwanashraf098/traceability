package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Shape regression tests for the Bosta list-deliveries response.
 * No Spring context — verifies the parsing rules implemented in
 * BostaHttpGateway.listDeliveriesPage() against fixture files.
 *
 * Shapes tested:
 *   real:          {deliveries:[...], count:N}   ← live Bosta v0 API (primary)
 *   flat:          {data:[...]}                  ← defensive fallback
 *   nested:        {data:{data:[...]}}            ← defensive fallback
 *   object-state:  state/type are objects         ← live v0 per-item shape
 *
 * Parsing rules mirrored from BostaHttpGateway:
 *   1. Try "deliveries" key first (real v0); fall back to "data" / "data.data".
 *   2. trackingNumber is always asText() (handles both string and number JSON types).
 *   3. state: object {code:N} → path("code").asInt(); flat int → asInt().
 *   4. type:  object {value:"..."} → path("value").asText().toUpperCase(); flat → asText().toUpperCase().
 *   5. Empty or absent array → empty list.
 */
class BostaListShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── 0. Real Bosta v0 envelope {deliveries:[...], count:N} ───────────────

    @Test
    void listShape_realEnvelope_parsesTwoItems() throws Exception {
        JsonNode body = mapper.readTree(fixture("list-deliveries-real.json"));

        List<ParsedItem> items = parseItems(body);

        assertThat(items).hasSize(2);

        // Item 0: state code 24 (Processing), type "Send" → normalized "SEND"
        assertThat(items.get(0).trackingNumber()).isEqualTo("8958142126");
        assertThat(items.get(0).stateCode()).isEqualTo(24);
        assertThat(items.get(0).type()).isEqualTo("SEND");

        // Item 1: state code 45 (Delivered), type "Send" → normalized "SEND"
        assertThat(items.get(1).trackingNumber()).isEqualTo("8958142127");
        assertThat(items.get(1).stateCode()).isEqualTo(45);
        assertThat(items.get(1).type()).isEqualTo("SEND");
    }

    @Test
    void listShape_realEnvelope_countFieldPresent() throws Exception {
        // Pagination stop condition: stop when seen >= count
        JsonNode body = mapper.readTree(fixture("list-deliveries-real.json"));
        assertThat(body.path("count").asInt(-1))
            .as("real envelope must carry a count field for pagination")
            .isEqualTo(3535);
    }

    @Test
    void listShape_emptyDeliveriesArray_returnsEmptyList() throws Exception {
        JsonNode body = mapper.readTree("{\"deliveries\":[],\"count\":0}");
        assertThat(parseItems(body)).isEmpty();
    }

    // ── 1. Flat envelope {data:[...]} (defensive fallback) ───────────────────

    @Test
    void listShape_flatEnvelope_parsesTwoItems() throws Exception {
        JsonNode body = mapper.readTree(fixture("list-deliveries-flat.json"));

        List<ParsedItem> items = parseItems(body);

        assertThat(items).hasSize(2);

        assertThat(items.get(0).trackingNumber()).isEqualTo("BOS-BACK-001");
        assertThat(items.get(0).stateCode()).isEqualTo(45);
        assertThat(items.get(0).type()).isEqualTo("SEND");

        assertThat(items.get(1).trackingNumber()).isEqualTo("BOS-BACK-002");
        assertThat(items.get(1).stateCode()).isEqualTo(41);
        assertThat(items.get(1).type()).isEqualTo("RTO");
    }

    // ── 2. Nested envelope {data:{data:[...]}} ───────────────────────────────

    @Test
    void listShape_nestedEnvelope_parsesItems() throws Exception {
        JsonNode body = mapper.readTree(fixture("list-deliveries-nested.json"));

        List<ParsedItem> items = parseItems(body);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).trackingNumber()).isEqualTo("BOS-BACK-003");
        assertThat(items.get(0).stateCode()).isEqualTo(45);
        assertThat(items.get(0).type()).isEqualTo("SEND");
    }

    // ── 3. Object-typed state and type fields ────────────────────────────────

    @Test
    void listShape_objectStateAndType_parsesCorrectly() throws Exception {
        JsonNode body = mapper.readTree(fixture("list-deliveries-object-state.json"));

        List<ParsedItem> items = parseItems(body);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).trackingNumber()).isEqualTo("BOS-BACK-004");
        // state is {code:45, value:"Delivered"} — must extract code
        assertThat(items.get(0).stateCode()).isEqualTo(45);
        // type is {code:10, value:"Send"} — must extract value AND normalize to uppercase
        assertThat(items.get(0).type()).isEqualTo("SEND");
    }

    // ── 4. Empty array → empty list ──────────────────────────────────────────

    @Test
    void listShape_emptyArray_returnsEmptyList() throws Exception {
        JsonNode body = mapper.readTree("{\"data\":[],\"count\":0}");
        assertThat(parseItems(body)).isEmpty();
    }

    // ── 5. Missing data field → empty list ───────────────────────────────────

    @Test
    void listShape_missingDataField_returnsEmptyList() throws Exception {
        JsonNode body = mapper.readTree("{\"success\":true}");
        assertThat(parseItems(body)).isEmpty();
    }

    // ── 6. Envelope regression: flat data.data must NOT be double-unwrapped ──

    @Test
    void listShape_flatEnvelope_dataNodeIsArray_notObject() throws Exception {
        JsonNode body = mapper.readTree(fixture("list-deliveries-flat.json"));
        JsonNode dataNode = body.path("data");
        assertThat(dataNode.isArray())
            .as("list-deliveries-flat.json: data must be a direct array (not nested object)")
            .isTrue();
    }

    @Test
    void listShape_nestedEnvelope_dataNodeIsObject_containsDataArray() throws Exception {
        JsonNode body = mapper.readTree(fixture("list-deliveries-nested.json"));
        JsonNode dataNode = body.path("data");
        assertThat(dataNode.isObject())
            .as("list-deliveries-nested.json: data must be an object wrapping a data array")
            .isTrue();
        assertThat(dataNode.path("data").isArray())
            .as("nested data.data must be an array")
            .isTrue();
    }

    // ── Parsing helper (mirrors BostaHttpGateway.listDeliveriesPage logic) ───

    private record ParsedItem(String trackingNumber, int stateCode, String type) {}

    private List<ParsedItem> parseItems(JsonNode body) {
        // mirrors BostaHttpGateway.listDeliveriesPage()
        JsonNode dataNode = body.path("deliveries");
        if (!dataNode.isArray()) {
            dataNode = body.path("data");
            if (dataNode.isObject()) dataNode = dataNode.path("data");
        }
        if (!dataNode.isArray() || dataNode.isEmpty()) return List.of();

        List<ParsedItem> result = new ArrayList<>();
        for (JsonNode item : dataNode) {
            String tn = item.path("trackingNumber").asText(null);
            if (tn == null || tn.isBlank()) continue;

            JsonNode stateNode = item.path("state");
            int stateCode = stateNode.isObject()
                ? stateNode.path("code").asInt(-1)
                : stateNode.asInt(-1);

            JsonNode typeNode = item.path("type");
            String type = typeNode.isObject()
                ? typeNode.path("value").asText("SEND").toUpperCase()
                : typeNode.asText("SEND").toUpperCase();

            result.add(new ParsedItem(tn, stateCode, type));
        }
        return result;
    }

    private String fixture(String name) throws Exception {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("bosta/" + name)) {
            assertThat(is).as("fixture bosta/" + name + " must exist on classpath").isNotNull();
            return new String(is.readAllBytes());
        }
    }
}
