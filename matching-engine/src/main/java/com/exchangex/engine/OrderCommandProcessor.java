
package com.exchangex.engine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;

public final class OrderCommandProcessor implements AutoCloseable {

    private final MatchingEngine engine;
    private final ThreadPoolExecutor executor;

    public OrderCommandProcessor(
            MatchingEngine engine,
            int queueCapacity
    ) {
        this.engine = Objects.requireNonNull(engine);

        if (queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Queue capacity must be positive"
            );
        }

        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> new Thread(r, "exchange-matching-worker"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public CompletableFuture<List<Trade>> submit(Order order) {
        Objects.requireNonNull(order);

        return execute(() -> engine.submit(order));
    }

    public CompletableFuture<Boolean> cancel(long orderId) {
        return execute(() -> engine.cancel(orderId));
    }

     <T> CompletableFuture<T> execute(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();

        try {
            executor.execute(() -> {
                try {
                    result.complete(task.call());
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
        }

        return result;
    }

    @Override
    public void close() {
        executor.shutdown();
    }
    public boolean awaitTermination(
            long timeout,
            TimeUnit unit
    ) throws InterruptedException {

        return executor.awaitTermination(timeout, unit);
    }

    public CompletableFuture<Optional<OrderView>> find(long orderId) {
        return execute(() -> engine.find(orderId));
    }

    public record BookSnapshot(
            Optional<OrderView> bestBid,
            Optional<OrderView> bestAsk
    ) {}

    public CompletableFuture<BookSnapshot> snapshot() {
        return execute(() ->
                new BookSnapshot(engine.bestBid(), engine.bestAsk())
        );
    }

    public CompletableFuture<List<Trade>> trades() {
        return execute(engine::trades);
    }
}
