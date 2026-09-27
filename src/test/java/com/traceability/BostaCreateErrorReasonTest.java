package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.traceability.integrations.bosta.BostaV2Client;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bosta's error reason on failed creates (createReturnPickup AND createExchange): for any non-2xx
 * only Bosta's error fields (message, errorCode, validation messages) are appended to the result
 * message, truncated to 300; a non-JSON body is "non-JSON body" plus its first 120 characters
 * unless they hold a 7+ digit run. Outcome mapping unchanged (4xx NOT_CREATED, 5xx AMBIGUOUS)
 * and still exactly one POST. Real local HTTP server, realistic bodies.
 */
class BostaCreateErrorReasonTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(500);
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<String> contentType = new AtomicReference<>("application/json");
    private final AtomicInteger posts = new AtomicInteger();
    private BostaV2Client client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if ("POST".equals(ex.getRequestMethod())) posts.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType.get());
            ex.sendResponseHeaders(status.get(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(out); }
            ex.close();
        });
        server.start();
        client = new BostaV2Client(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() { server.stop(0); }

    private BostaV2Client.CreateResult crp() {
        return client.createReturnPickup("key", new BostaV2Client.ReturnPickup("req-1", "#1", "loc", "12 Placeholder Street", null,
            null, null, null, "Cairo", "D1", "Mona", null, "01000000001", 1, "d", "n"));
    }

    private BostaV2Client.CreateResult exchange() {
        return client.createExchange("key", new BostaV2Client.Exchange("req-2", "#2", "loc", "12 Placeholder Street", null,
            null, null, null, "Cairo", "D1", "Mona", null, "01000000001", "out", "back", "n"));
    }

    @Test
    void json500_messageAndErrorCodeRecorded_stillAmbiguous_onePost() {
        status.set(500);
        body.set("{\"success\":false,\"message\":\"Cannot read properties of undefined (reading 'zone')\",\"errorCode\":1000,\"data\":null}");
        for (BostaV2Client.CreateResult r : new BostaV2Client.CreateResult[]{crp(), exchange()}) {
            assertThat(r.outcome()).isEqualTo(BostaV2Client.CreateOutcome.AMBIGUOUS);
            assertThat(r.message()).isEqualTo("Bosta answered with a server error (HTTP 500). "
                + "Bosta said: Cannot read properties of undefined (reading 'zone') (Bosta error 1000)");
            assertThat(r.bostaErrorCode()).isEqualTo("1000");
        }
        assertThat(posts.get()).as("one POST per call, no retry").isEqualTo(2);
    }

    @Test
    void json400_validationMessagesRecorded_otherFieldsNot_notCreated() {
        status.set(400);
        body.set("{\"success\":false,\"message\":\"Validation failed\",\"errorCode\":\"1025\","
            + "\"errors\":[{\"field\":\"dropOffAddress.districtId\",\"message\":\"districtId is not allowed for this city\"},"
            + "{\"msg\":\"receiver.phone must be a valid Egyptian phone\"}],"
            + "\"receiver\":{\"phone\":\"01012345678\"}}");
        BostaV2Client.CreateResult r = crp();
        assertThat(r.outcome()).isEqualTo(BostaV2Client.CreateOutcome.NOT_CREATED);
        assertThat(r.message()).isEqualTo("Validation failed Bosta said: districtId is not allowed for this city; "
            + "receiver.phone must be a valid Egyptian phone (Bosta error 1025)");
        assertThat(r.message()).doesNotContain("01012345678");
    }

    @Test
    void longMessage_truncatedTo300() {
        status.set(500);
        body.set("{\"message\":\"" + "x".repeat(900) + "\",\"errorCode\":7}");
        BostaV2Client.CreateResult r = exchange();
        String detail = r.message().substring(r.message().indexOf("Bosta said: ") + "Bosta said: ".length());
        assertThat(detail).hasSize(300);
    }

    @Test
    void html502_nonJsonSnippet_upTo120_butNotWhenItHoldsLongDigits() {
        status.set(502);
        contentType.set("text/html");
        body.set("<html><head><title>502 Bad Gateway</title></head><body><center><h1>502 Bad Gateway</h1></center><hr><center>nginx</center></body></html>");
        BostaV2Client.CreateResult r = crp();
        assertThat(r.outcome()).isEqualTo(BostaV2Client.CreateOutcome.AMBIGUOUS);
        String snippet = body.get().substring(0, 120);
        assertThat(r.message()).isEqualTo("Bosta answered with a server error (HTTP 502). Bosta said: non-JSON body: " + snippet);

        body.set("<html><body>Customer 01012345678 could not be routed</body></html>");
        assertThat(exchange().message()).isEqualTo("Bosta answered with a server error (HTTP 502). Bosta said: non-JSON body");
    }

    @Test
    void empty503_nonJsonBodyOnly() {
        status.set(503);
        body.set("");
        BostaV2Client.CreateResult r = crp();
        assertThat(r.outcome()).isEqualTo(BostaV2Client.CreateOutcome.AMBIGUOUS);
        assertThat(r.message()).isEqualTo("Bosta answered with a server error (HTTP 503). Bosta said: non-JSON body");
    }

    @Test
    void rateLimited429_codeAppended_stillNotCreated() {
        status.set(429);
        body.set("{\"success\":false,\"errorCode\":429,\"retryAfter\":60}");
        BostaV2Client.CreateResult r = exchange();
        assertThat(r.outcome()).isEqualTo(BostaV2Client.CreateOutcome.NOT_CREATED);
        assertThat(r.message()).isEqualTo("Bosta is rate-limiting requests. Try again in a few minutes. (Bosta error 429)");
    }
}
