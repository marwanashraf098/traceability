package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L1 (2026-11-12) — Traced only ever edits its OWN location's settings, and only to turn selling ON.
 *
 * (a) WHAT may be edited:
 *   a1 every locationEdit document in src/main: its input is either the $input variable of the address edit (whose
 *      variables builder carries only `address`) or the literal { fulfillsOnlineOrders: true } — never false, never
 *      name, never metafields
 *   a2 the fulfils-online edit sends ONE variable (id); the value true is a literal in the document (wire check)
 *   a3 no source file anywhere in src/main sends fulfillsOnlineOrders false / a locationEdit name
 * (b) WHICH location:
 *   b1 enableOnlineFulfillment( is called only by TracedLocationOnlineService, with main.gid(), and main() reads the
 *      tenant's is_fulfillment row; deliveryProfiles.addLocationToGroup( only by ShopifyFulfillmentActivationService
 *      with ctx.locationGid() from its is_fulfillment query
 *   b2 the "Turn back on" endpoint takes no target from the request (no @RequestBody / @PathVariable / @RequestParam)
 */
class TracedLocationEditGuardTest {

    static final Path MAIN = Path.of("src/main/java");
    static final Path ONLINE = Path.of("src/main/java/com/traceability/inventory/TracedLocationOnlineService.java");
    static final Path ACTIVATION = Path.of("src/main/java/com/traceability/inventory/ShopifyFulfillmentActivationService.java");
    static final Path CONTROLLER = Path.of("src/main/java/com/traceability/inventory/LocationController.java");

    static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void a1_everyLocationEdit_onlyAddressOrTurnOnline() throws IOException {
        int edits = 0;
        for (Path p : sources()) {
            String src = Files.readString(p);
            Matcher m = Pattern.compile("locationEdit\\s*\\(([^)]*)\\)").matcher(src);
            while (m.find()) {
                String args = m.group(1).replaceAll("\\s+", " ").trim();
                if (!args.contains("input:")) continue;                     // a javadoc mention, not a document
                edits++;
                assertThat(args).as("%s: %s", p, args)
                    .isIn("id: $id, input: $input", "id: $id, input: { fulfillsOnlineOrders: true }");
            }
        }
        assertThat(edits).as("the address edit and the online edit").isEqualTo(2);
        // The address edit's variables: { id, input: { address } } — nothing else in input.
        ObjectNode vars = invokeEditAddressVariables();
        assertThat(vars.path("input").fieldNames()).toIterable().containsExactly("address");
    }

    @Test
    void a2_turnOnline_sendsOnlyTheId_andTheLiteralTrue() throws Exception {
        ShopifyGateway shopify = mock(ShopifyGateway.class);
        ObjectMapper mapper = new ObjectMapper();
        when(shopify.executeGraphQLPublic(any(), any(), any(), any())).thenReturn(
            mapper.readTree("{\"locationEdit\":{\"location\":{\"id\":\"gid://shopify/Location/1\",\"fulfillsOnlineOrders\":true},\"userErrors\":[]}}"));
        ShopifyLocationGateway gw = newImpl(shopify, mapper);

        gw.enableOnlineFulfillment("s.myshopify.com", "tok", "gid://shopify/Location/1");

        ArgumentCaptor<String> doc = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JsonNode> vars = ArgumentCaptor.forClass(JsonNode.class);
        verify(shopify).executeGraphQLPublic(eq("s.myshopify.com"), eq("tok"), doc.capture(), vars.capture());
        assertThat(doc.getValue()).contains("fulfillsOnlineOrders: true").doesNotContain("false")
            .doesNotContain("name").doesNotContain("metafields").doesNotContain("address");
        List<String> keys = new ArrayList<>();
        vars.getValue().fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("id");
    }

    @Test
    void a3_noSourceTurnsSellingOff() throws IOException {
        for (Path p : sources()) {
            String code = Files.readString(p).replaceAll("\\s+", "");
            assertThat(code).as(p.toString())
                .doesNotContain("fulfillsOnlineOrders:false")
                .doesNotContain("put(\"fulfillsOnlineOrders\",false)");
        }
    }

    @Test
    void b1_writesTargetOnlyTheFulfillmentRow() throws IOException {
        Set<String> enableCallers = new TreeSet<>();
        Set<String> joinCallers = new TreeSet<>();
        for (Path p : sources()) {
            String src = Files.readString(p);
            if (src.contains(".enableOnlineFulfillment(")) enableCallers.add(p.getFileName().toString());
            if (src.contains(".addLocationToGroup(")) joinCallers.add(p.getFileName().toString());
        }
        assertThat(enableCallers).containsExactly("TracedLocationOnlineService.java");
        assertThat(joinCallers).containsExactly("ShopifyFulfillmentActivationService.java");

        String online = Files.readString(ONLINE);
        Matcher e = Pattern.compile("\\.enableOnlineFulfillment\\(([^;]*)\\);").matcher(online);
        int n = 0;
        while (e.find()) { n++; assertThat(e.group(1).replaceAll("\\s+", "")).endsWith(",main.gid()"); }
        assertThat(n).isEqualTo(1);
        assertThat(online.replaceAll("\\s+", " ")).contains("WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked'");
        assertThat(online).doesNotContain("@RequestBody");

        String act = Files.readString(ACTIVATION);
        Matcher j = Pattern.compile("\\.addLocationToGroup\\(([^;]*)\\);").matcher(act);
        int k = 0;
        while (j.find()) { k++; assertThat(j.group(1).replaceAll("\\s+", "")).endsWith(",ctx.locationGid()"); }
        assertThat(k).isEqualTo(2);                                          // activate() + joinWithReadBack()
        assertThat(act.replaceAll("\\s+", " ")).contains("WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked'");
    }

    @Test
    void b2_turnBackOn_takesNoTargetFromTheRequest() throws IOException {
        String src = Files.readString(CONTROLLER);
        int at = src.indexOf("@PostMapping(\"/shopify/turn-back-on\")");
        assertThat(at).isPositive();
        String sig = src.substring(at, src.indexOf('{', src.indexOf("public", at)));
        assertThat(sig).contains("@PreAuthorize(\"hasRole('OWNER')\")")
            .doesNotContain("@RequestBody").doesNotContain("@PathVariable").doesNotContain("@RequestParam");
    }

    // ── reflection helpers (the impl and its static builder are package-private) ──────────────────────────────

    private static ShopifyLocationGateway newImpl(ShopifyGateway shopify, ObjectMapper mapper) throws Exception {
        Class<?> c = Class.forName("com.traceability.integrations.shopify.ShopifyLocationGatewayImpl");
        Constructor<?> k = c.getDeclaredConstructor(ShopifyGateway.class, ObjectMapper.class);
        k.setAccessible(true);
        return (ShopifyLocationGateway) k.newInstance(shopify, mapper);
    }

    private static ObjectNode invokeEditAddressVariables() {
        try {
            Class<?> c = Class.forName("com.traceability.integrations.shopify.ShopifyLocationGatewayImpl");
            var m = c.getDeclaredMethod("editAddressVariables", ObjectMapper.class, String.class,
                ShopifyLocationGateway.LocationAddress.class);
            m.setAccessible(true);
            return (ObjectNode) m.invoke(null, new ObjectMapper(), "gid://shopify/Location/1",
                new ShopifyLocationGateway.LocationAddress("1 St", null, "Cairo", null, null, "EG"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
