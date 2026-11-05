package com.traceability.integrations.shopify;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The main Shopify Admin API client's timeouts (2026-11-05). A local HTTP server (port 0) has one path that
 * never answers and one that answers at once. The client is built exactly as ShopifyHttpGateway's Spring
 * constructor builds it ({@link ShopifyHttpGateway#timeoutFactory}), with a short read timeout.
 *
 *   t1 a call that hangs ends with a timeout (ResourceAccessException — what the named writes classify as
 *      ambiguous and the read retry retries) instead of waiting forever
 *   t2 while one call hangs, other calls ("other events") on the same client still complete
 *
 * Revert-to-confirm: build the client without the factory (Spring Boot's default, no read timeout) → t1 hits
 * the preemptive 10 s limit.
 */
class ShopifyHttpTimeoutTest {

    HttpServer http;
    final CountDownLatch release = new CountDownLatch(1);
    String base;

    @BeforeEach
    void start() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/hang", ex -> {
            try { release.await(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        http.createContext("/ok", ex -> {
            byte[] b = "{\"data\":{\"shop\":{\"name\":\"ok\"}}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        release.countDown();
        http.stop(0);
    }

    RestClient client() {
        return RestClient.builder()
            .requestFactory(ShopifyHttpGateway.timeoutFactory(Duration.ofSeconds(2), Duration.ofMillis(700)))
            .build();
    }

    @Test
    void t1_hangingCall_timesOut() {
        RestClient c = client();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
            assertThatThrownBy(() -> c.post().uri(base + "/hang").body("{}").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class));
    }

    @Test
    void t2_otherCallsStillComplete_whileOneHangs() throws Exception {
        RestClient c = client();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Throwable> hung = CompletableFuture.supplyAsync(() -> {
                try { c.post().uri(base + "/hang").body("{}").retrieve().toBodilessEntity(); return null; }
                catch (Throwable t) { return t; }
            }, pool);
            for (int i = 0; i < 3; i++) {
                String body = c.post().uri(base + "/ok").body("{}").retrieve().body(String.class);
                assertThat(body).contains("\"name\":\"ok\"");
            }
            assertThat(hung.get(10, TimeUnit.SECONDS)).isInstanceOf(ResourceAccessException.class);
        } finally {
            pool.shutdownNow();
        }
    }
}
