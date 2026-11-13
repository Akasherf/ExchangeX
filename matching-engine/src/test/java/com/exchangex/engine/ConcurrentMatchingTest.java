
package com.exchangex.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentMatchingTest {

    @Test
    void shouldMatchConcurrentBuyAndSellOrders() throws Exception {

        MatchingEngine engine = new MatchingEngine("AAPL");

        int count = 100;

        try (OrderCommandProcessor processor =
                     new OrderCommandProcessor(engine, 250);
             ExecutorService clients =
                     Executors.newFixedThreadPool(10)) {

            CountDownLatch start = new CountDownLatch(1);

            List<Future<CompletableFuture<List<Trade>>>>
                    requests = new ArrayList<>();

            for (int i = 1; i <= count; i++) {
                final long buyId = i;
                final long sellId = i + count;

                requests.add(clients.submit(() -> {
                    start.await();
                    return processor.submit(
                            new Order(
                                    buyId, "AAPL",
                                    Side.BUY, 100L, 10L
                            )
                    );
                }));

                requests.add(clients.submit(() -> {
                    start.await();
                    return processor.submit(
                            new Order(
                                    sellId, "AAPL",
                                    Side.SELL, 99L, 10L
                            )
                    );
                }));
            }

            start.countDown();

            List<CompletableFuture<List<Trade>>> results =
                    new ArrayList<>();

            for (var request : requests) {
                results.add(request.get(10, TimeUnit.SECONDS));
            }

            CompletableFuture.allOf(
                    results.toArray(new CompletableFuture[0])
            ).get(10, TimeUnit.SECONDS);

            long totalExecuted = engine.trades().stream()
                    .mapToLong(Trade::quantity)
                    .sum();

            assertEquals(1000L, totalExecuted);

            assertTrue(engine.bestBid().isEmpty());
            assertTrue(engine.bestAsk().isEmpty());

            for (long id = 1; id <= 200; id++) {
                assertTrue(engine.find(id).isEmpty());
            }
        }
    }
}
