
package com.exchangex.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class OrderConcurrencyTest {

    @Test
    void shouldHandleConcurrentOrderSubmissions() throws Exception {

        MatchingEngine engine = new MatchingEngine("AAPL");

        int orderCount = 100;

        try (OrderCommandProcessor processor =
                     new OrderCommandProcessor(engine, 200);
             ExecutorService clients = Executors.newFixedThreadPool(10)) {

            CountDownLatch start = new CountDownLatch(1);

            List<Future<CompletableFuture<List<Trade>>>> requests =
                    new ArrayList<>();

            for (int i = 1; i <= orderCount; i++) {
                final long id = i;

                requests.add(clients.submit(() -> {
                    start.await();

                    return processor.submit(
                            new Order(id, "AAPL", Side.BUY, 100L, 1L)
                    );
                }));
            }

            // Release all client threads together.
            start.countDown();

            List<CompletableFuture<List<Trade>>> results =
                    new ArrayList<>();

            for (var request : requests) {
                results.add(request.get(10, TimeUnit.SECONDS));
            }

            // Wait for every accepted order to finish processing.
            CompletableFuture.allOf(
                    results.toArray(new CompletableFuture[0])
            ).get(10, TimeUnit.SECONDS);

            // Every order must remain active.
            for (long id = 1; id <= orderCount; id++) {
                assertEquals(
                        1L,
                        engine.find(id).orElseThrow().remainingQuantity()
                );
            }

            assertTrue(engine.trades().isEmpty());
            assertEquals(100L,
                    results.stream().filter(f -> f.join().isEmpty()).count());
        }
    }
}
