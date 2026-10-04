package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The v0 Bosta gateway's HTTP timeouts (2026-10-04).
 *
 *   gt1 a Bosta that never answers: the read timeout fires, the Resilience4j retry makes exactly 3
 *       attempts (bounded), and the call fails as a BostaTransientException long before Bosta would
 *       have answered — before, there was no read timeout at all
 */
class BostaGatewayTimeoutTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) { }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void gt1_readTimeoutFires_retriesBounded() {
        BostaHttpGateway gateway = new BostaHttpGateway(RestClient.builder(), new ObjectMapper(),
            "http://127.0.0.1:" + server.getAddress().getPort(), "v0", BostaRateLimiter.unlimited(),
            Duration.ofSeconds(1), Duration.ofMillis(300));

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> gateway.fetchDelivery("key-femine", "9432163061"))
            .isInstanceOf(BostaTransientException.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(ms).as("3 × 300 ms read timeout + 2 × 1 s retry wait, not Bosta's 10 s").isLessThan(5_000);
        assertThat(requests.get()).as("Resilience4j: exactly 3 attempts").isEqualTo(3);
    }
}
