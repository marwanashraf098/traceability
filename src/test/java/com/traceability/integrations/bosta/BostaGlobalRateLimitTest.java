package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The global Bosta budget (2026-10-04) — every key shares it, on top of the per-key bucket.
 *
 *   g1 a global budget across keys: three keys with ample per-key budget together stay under the
 *      global rate
 *   g2 user-facing priority on the global bucket: a waybill print waiting behind background callers
 *      of OTHER keys takes the next global token
 *   g3 background reserve: background stops while only the reserve (20% of the burst) is left;
 *      a user-facing call still goes straight through
 *   g4 a 429 on key A blocks key B (and user-facing calls) for its retry-after
 *   g5 a wait longer than max-wait is a BostaRateLimitException carrying the remaining block, and it
 *      does not extend the block
 *   g6 end to end through the v0 gateway: Bosta 429s key A (retryAfter 1) → key B's next call waits it out
 */
class BostaGlobalRateLimitTest {

    @Test
    void g1_globalBudgetAcrossKeys() throws Exception {
        BostaRateLimiter limiter = new BostaRateLimiter(100, 5, 60_000, 10, 1, 0);
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> fs = new ArrayList<>();
        for (String key : List.of("key-broek", "key-femine", "key-blnco")) {
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    limiter.acquire(key, BostaRateLimiter.Priority.BACKGROUND);
                    times.add(System.nanoTime());
                }
            }));
        }
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        assertThat(sorted).hasSize(30);
        for (int i = 0; i < sorted.size(); i++) {
            int inWindow = 0;
            for (int j = i; j < sorted.size() && sorted.get(j) - sorted.get(i) < 1_000_000_000L; j++) inWindow++;
            assertThat(inWindow).as("requests in any 1 s window, all keys together").isLessThanOrEqualTo(11);
        }
        assertThat(sorted.get(29) - sorted.get(0)).as("30 calls at 10/s overall").isGreaterThan(2_500_000_000L);
    }

    @Test
    void g2_userFacingGoesFirst_acrossKeys() throws Exception {
        BostaRateLimiter limiter = new BostaRateLimiter(100, 5, 60_000, 4, 1, 0);
        limiter.acquire("key-poll", BostaRateLimiter.Priority.BACKGROUND);   // global budget empty
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 3; i++) {
            int n = i;
            pool.submit(() -> { limiter.acquire("key-poll-" + n, BostaRateLimiter.Priority.BACKGROUND); order.add("bg" + n); return null; });
        }
        Thread.sleep(50);
        Future<?> print = pool.submit(() -> {
            limiter.acquire("key-print", BostaRateLimiter.Priority.USER_FACING);
            order.add("print");
            return null;
        });
        print.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(order.indexOf("print")).as("the next global token goes to the waybill print").isZero();
    }

    @Test
    void g3_backgroundLeavesTheReserve_userFacingDoesNot() {
        // global 0.01/s (no meaningful refill), burst 5, reserve 20% = 1 token; max wait 100 ms
        BostaRateLimiter limiter = new BostaRateLimiter(100, 10, 100, 0.01, 5, 0.2);
        for (int i = 0; i < 4; i++) limiter.acquire("key-" + i, BostaRateLimiter.Priority.BACKGROUND);

        assertThatThrownBy(() -> limiter.acquire("key-4", BostaRateLimiter.Priority.BACKGROUND))
            .as("background must leave the last 20%").isInstanceOf(BostaRateLimitException.class);
        long t0 = System.nanoTime();
        limiter.acquire("key-print", BostaRateLimiter.Priority.USER_FACING);
        assertThat(System.nanoTime() - t0).as("the reserve is there for user-facing calls").isLessThan(50_000_000L);
    }

    @Test
    void g4_429OnKeyA_blocksKeyB() {
        BostaRateLimiter limiter = new BostaRateLimiter(100, 5, 60_000, 100, 5, 0.2);
        limiter.onRateLimited("key-femine", 1, "test");

        long t0 = System.nanoTime();
        limiter.acquire("key-blnco", BostaRateLimiter.Priority.BACKGROUND);
        assertThat(System.nanoTime() - t0).as("another key waited out the retry-after").isGreaterThan(900_000_000L);

        BostaRateLimiter limiter2 = new BostaRateLimiter(100, 5, 60_000, 100, 5, 0.2);
        limiter2.onRateLimited("key-femine", 1, "test");
        long t1 = System.nanoTime();
        limiter2.acquire("key-jumi", BostaRateLimiter.Priority.USER_FACING);
        assertThat(System.nanoTime() - t1).as("user-facing calls too").isGreaterThan(900_000_000L);
    }

    @Test
    void g5_overMaxWait_isARateLimitSignal_withTheRemainingBlock_andDoesNotExtendIt() throws Exception {
        BostaRateLimiter limiter = new BostaRateLimiter(100, 5, 100, 100, 5, 0.2);
        limiter.onRateLimited("key-broek", 3, "test");

        long remaining = 0;
        for (int i = 0; i < 5; i++) {
            try {
                limiter.acquire("key-femine", BostaRateLimiter.Priority.BACKGROUND);
            } catch (BostaRateLimitException e) {
                remaining = e.getRetryAfterSeconds();
            }
        }
        assertThat(remaining).isBetween(1L, 3L);

        Thread.sleep(3_100);
        long t0 = System.nanoTime();
        limiter.acquire("key-femine", BostaRateLimiter.Priority.BACKGROUND);
        assertThat(System.nanoTime() - t0).as("refusals did not extend the 3 s block").isLessThan(200_000_000L);
    }

    @Test
    void g6_gateway_429OnKeyA_blocksKeyB() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BostaRateLimiter limiter = new BostaRateLimiter(100, 5, 60_000, 100, 5, 0.2);
        BostaHttpGateway gateway = new BostaHttpGateway(builder, new ObjectMapper(), "https://bosta.test", "v0", limiter);
        server.expect(requestTo("https://bosta.test/api/v0/deliveries/9432163061"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"errorCode\":429,\"retryAfter\":1}"));
        server.expect(requestTo("https://bosta.test/api/v0/deliveries/8948149267"))
            .andRespond(withSuccess("{\"data\":{\"trackingNumber\":\"8948149267\",\"state\":{\"code\":24}," +
                "\"type\":{\"code\":10,\"value\":\"Send\"}}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.fetchDelivery("key-femine", "9432163061"))
            .isInstanceOf(BostaRateLimitException.class);
        long t0 = System.nanoTime();
        assertThat(gateway.fetchDelivery("key-blnco", "8948149267").stateCode()).isEqualTo(24);
        assertThat(System.nanoTime() - t0).as("key B waited out key A's retry-after").isGreaterThan(600_000_000L);
        server.verify();
    }
}
