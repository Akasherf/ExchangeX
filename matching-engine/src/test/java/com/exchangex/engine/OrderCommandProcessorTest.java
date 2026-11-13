
package com.exchangex.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class OrderCommandProcessorTest {

    @Test
    void shouldProcessOrdersSequentially() throws Exception {

        MatchingEngine engine = new MatchingEngine("AAPL");

        try (OrderCommandProcessor processor =
                     new OrderCommandProcessor(engine, 100)) {

            var buy = processor.submit(
                    new Order(1L, "AAPL", Side.BUY, 100L, 10L)
            );

            var sell = processor.submit(
                    new Order(2L, "AAPL", Side.SELL, 99L, 6L)
            );

            List<Trade> buyTrades =
                    buy.get(5, TimeUnit.SECONDS);

            List<Trade> sellTrades =
                    sell.get(5, TimeUnit.SECONDS);

            assertTrue(buyTrades.isEmpty());
            assertEquals(1, sellTrades.size());

            assertEquals(6L, sellTrades.get(0).quantity());
            assertEquals(
                    4L,
                    engine.find(1L).orElseThrow().remainingQuantity()
            );
        }
    }
    @Test
    void acceptedCommandsShouldFinishAfterShutdown()
            throws Exception {

        MatchingEngine engine = new MatchingEngine("AAPL");

        OrderCommandProcessor processor =
                new OrderCommandProcessor(engine, 100);

        var first = processor.submit(
                new Order(1L, "AAPL", Side.BUY, 100L, 10L)
        );

        var second = processor.submit(
                new Order(2L, "AAPL", Side.SELL, 99L, 5L)
        );

        processor.close();

        assertTrue(
                processor.awaitTermination(5, TimeUnit.SECONDS)
        );

        assertTrue(first.isDone());
        assertTrue(second.isDone());

        // Verify that accepted commands succeeded,
        // rather than merely completing exceptionally.
        assertTrue(first.join().isEmpty());
        assertEquals(1, second.join().size());

        assertEquals(
                5L,
                engine.find(1L)
                        .orElseThrow()
                        .remainingQuantity()
        );
    }

    @Test
    void shouldRejectCommandWhenQueueIsFull() throws Exception {

        MatchingEngine engine = new MatchingEngine("AAPL");

        try (OrderCommandProcessor processor =
                     new OrderCommandProcessor(engine, 1)) {

            CountDownLatch workerStarted = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);

            CompletableFuture<Void> blockingTask =
                    processor.execute(() -> {
                        workerStarted.countDown();
                        releaseWorker.await();
                        return null;
                    });

            try {
                assertTrue(
                        workerStarted.await(5, TimeUnit.SECONDS)
                );

                // Worker is blocked, so this command fills the queue.
                var waitingOrder = processor.submit(
                        new Order(1L, "AAPL", Side.BUY, 100L, 10L)
                );

                // Worker busy + queue full = rejection.
                var rejectedOrder = processor.submit(
                        new Order(2L, "AAPL", Side.BUY, 100L, 10L)
                );

                CompletionException error = assertThrows(
                        CompletionException.class,
                        rejectedOrder::join
                );

                assertInstanceOf(
                        RejectedExecutionException.class,
                        error.getCause()
                );

                // A rejected command must not modify the engine.
                assertTrue(engine.find(2L).isEmpty());

            } finally {
                // Always release worker, even if an assertion fails.
                releaseWorker.countDown();
            }

            blockingTask.get(5, TimeUnit.SECONDS);
        }
    }

}
