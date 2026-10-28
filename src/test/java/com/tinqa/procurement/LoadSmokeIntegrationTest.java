package com.tinqa.procurement;

import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A short burst of concurrent traffic on the busiest admin screens, in every build: every call answered as a single
 * call is, nothing crashes or times out waiting for a database connection, and latency stays within budget. The full
 * load, stress, spike and soak runs are the k6 scripts in load/, run against a deployed environment.
 */
@DisplayName("Load smoke")
class LoadSmokeIntegrationTest extends IntegrationTestSupport {

    // More threads than the connection pool has connections (10), so the pool is contended on purpose
    private static final int THREADS = 16;
    private static final int CALLS_PER_THREAD = 25;
    private static final long P95_BUDGET_MILLIS = 750;
    private static final List<String> URLS = List.of(
            "/v1/admin/items?size=20", "/v1/admin/products?size=20", "/v1/admin/dealers?size=20", "/v1/access/me");

    @Test
    void adminScreensHoldUpUnderConcurrentTraffic() throws Exception {
        String token = l2a.token();
        // Warm-up, and what each endpoint answers to a single call
        Map<String, Integer> expected = URLS.stream().collect(Collectors.toMap(url -> url, url -> status(url, token)));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> workers = new ArrayList<>();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < THREADS; t++) {
            int offset = t;
            workers.add(pool.submit(() -> {
                start.await();
                List<String> wrong = new ArrayList<>();
                for (int i = 0; i < CALLS_PER_THREAD; i++) {
                    String url = URLS.get((offset + i) % URLS.size());
                    long began = System.nanoTime();
                    int status = status(url, token);
                    latencies.add((System.nanoTime() - began) / 1_000_000);
                    if (status != expected.get(url)) wrong.add(url + " -> " + status + " (alone: " + expected.get(url) + ")");
                }
                return wrong;
            }));
        }
        long began = System.nanoTime();
        start.countDown();
        List<String> wrong = new ArrayList<>();
        for (Future<List<String>> worker : workers) wrong.addAll(worker.get(2, TimeUnit.MINUTES));
        long elapsed = Math.max(1, (System.nanoTime() - began) / 1_000_000);
        pool.shutdown();

        List<Long> sorted = latencies.stream().sorted().toList();
        long p50 = sorted.get(sorted.size() / 2);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        System.out.printf("[load smoke] %d calls on %d threads in %d ms (%d/s): p50 %d ms, p95 %d ms, max %d ms%n",
                sorted.size(), THREADS, elapsed, sorted.size() * 1000L / elapsed, p50, p95, sorted.getLast());

        assertThat(expected.values()).as("every endpoint answers a single call").allMatch(status -> status == 200);
        assertThat(wrong).as("answers changed under concurrent load").isEmpty();
        assertThat(sorted).hasSize(THREADS * CALLS_PER_THREAD);
        assertThat(p95).as("p95 latency (ms)").isLessThanOrEqualTo(P95_BUDGET_MILLIS);
    }

    private int status(String url, String token) {
        try {
            return mockMvc.perform(MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
    }
}
