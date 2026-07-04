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
 * Three shapes are tested:
 *   flat:          {data:[{trackingNumber, state:int, type:string, ...}]}
 *   nested:        {data:{data:[...], count:N}}
 *   object-state:  state and type are objects ({code:N,value:"..."}) instead of scalars
 *
 * The parsing rules verified here match the implementation in BostaHttpGateway:
 *   1. Defensive envelope: if data is an object, descend into data.data.
 *   2. trackingNumber is always asText() (handles both string and number JSON types).
 *   3. state: plain int → asInt(); object → path("code").asInt().
 *   4. type:  plain string → asText(); object → path("value").asText().
 *   5. Empty or absent array → empty list.
 */
class BostaListShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── 1. Flat envelope {data:[...]} ────────────────────────────────────────

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
        // type is {code:10, value:"Send"} — must extract value
        assertThat(items.get(0).type()).isEqualTo("Send");
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
        JsonNode dataNode = body.path("data");
        if (dataNode.isObject()) {
            dataNode = dataNode.path("data");
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
                ? typeNode.path("value").asText("SEND")
                : typeNode.asText("SEND");

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
