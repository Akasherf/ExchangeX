package com.exchangex.api;

import com.exchangex.engine.OrderView;
import com.exchangex.api.repository.OrderRepository;
import org.springframework.beans.factory.ObjectProvider;
import com.exchangex.engine.Side;
import com.exchangex.engine.Trade;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final DurableCommandCoordinator coordinator;
    private final OrderRepository orderRepository;

    public OrderController(DurableCommandCoordinator coordinator, ObjectProvider<OrderRepository> repositories) {
        this.coordinator = coordinator;
        this.orderRepository = repositories.getIfAvailable();
    }

    @PostMapping("/orders")
    public CompletableFuture<ResponseEntity<OrderSubmissionResponse>> submit(
            @Valid @RequestBody OrderRequest request) {

        return coordinator.submitOrder(request.id(), request.side(), request.price(), request.quantity())
                .thenApply(admission -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new OrderSubmissionResponse("PENDING", admission.sequenceId())))
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof IllegalArgumentException iae) {
                        if (iae.getMessage().contains("different payload")) {
                            return ResponseEntity.status(HttpStatus.CONFLICT)
                                    .body(new OrderSubmissionResponse("CONFLICT", 0));
                        }
                        return ResponseEntity.badRequest()
                                .body(new OrderSubmissionResponse("INVALID: " + iae.getMessage(), 0));
                    }
                    if (cause instanceof RejectedExecutionException) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .header("Retry-After", "1")
                                .body(new OrderSubmissionResponse("QUEUE_FULL", 0));
                    }
                    if (cause instanceof CompletionException) {
                        Throwable rootCause = cause.getCause();
                        if (rootCause instanceof RejectedExecutionException) {
                            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                    .header("Retry-After", "1")
                                    .body(new OrderSubmissionResponse("QUEUE_FULL", 0));
                        }
                    }
                    return ResponseEntity.internalServerError()
                            .body(new OrderSubmissionResponse("ERROR", 0));
                });
    }

    @DeleteMapping("/orders/{id}")
    public CompletableFuture<ResponseEntity<CancelResponse>> cancel(
            @PathVariable("id") long id) {

        return coordinator.cancelOrder(id)
                .thenApply(admission -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new CancelResponse("PENDING")))
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof IllegalArgumentException iae) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(new CancelResponse("NOT_FOUND: " + iae.getMessage()));
                    }
                    if (cause instanceof RejectedExecutionException) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .header("Retry-After", "1")
                                .body(new CancelResponse("QUEUE_FULL"));
                    }
                    if (cause instanceof CompletionException) {
                        Throwable rootCause = cause.getCause();
                        if (rootCause instanceof RejectedExecutionException) {
                            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                    .header("Retry-After", "1")
                                    .body(new CancelResponse("QUEUE_FULL"));
                        }
                    }
                    return ResponseEntity.internalServerError()
                            .body(new CancelResponse("ERROR"));
                });
    }

    @GetMapping("/orders/{id}")
    public CompletableFuture<ResponseEntity<OrderStateResponse>> getOrder(
            @PathVariable("id") long id) {

        if (orderRepository == null) {
            // Lightweight controller tests inject a mocked coordinator without a database.
            return coordinator.findOrder(id).thenApply(found -> found
                    .map(r -> ResponseEntity.ok(new OrderStateResponse(
                            r.id(), r.side().name(), r.price(), r.remainingQuantity(), "PENDING", 0)))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new OrderStateResponse(id, null, 0, 0, "NOT_FOUND", 0))));
        }
        return CompletableFuture.completedFuture(orderRepository.findByClientOrderId(id)
                .map(r -> ResponseEntity.ok(new OrderStateResponse(
                        r.clientOrderId(), r.side().name(), r.limitPrice(),
                        r.remainingQuantity(), r.executionStatus(), r.exchangeSequenceId())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new OrderStateResponse(id, null, 0, 0, "NOT_FOUND", 0))));
    }

    @GetMapping("/orderbook/{symbol}")
    public CompletableFuture<OrderBookResponse> book(
            @PathVariable("symbol") String symbol) {

        if (!"AAPL".equals(symbol)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown instrument");
        }

        return coordinator.snapshot()
                .thenApply(snapshot -> new OrderBookResponse(
                        symbol,
                        snapshot.bestBid(),
                        snapshot.bestAsk()
                ));
    }

    @GetMapping("/trades")
    public CompletableFuture<ResponseEntity<List<Trade>>> trades() {
        return coordinator.trades()
                .thenApply(ResponseEntity::ok);
    }

    // Response DTOs

    public record OrderSubmissionResponse(String status, long sequenceId) {}

    public record CancelResponse(String status) {}

    public record OrderStateResponse(
            long id,
            String side,
            long price,
            long remainingQuantity,
            String executionStatus,
            long sequenceId
    ) {}

    public record OrderBookResponse(
            String symbol,
            Optional<OrderView> bestBid,
            Optional<OrderView> bestAsk
    ) {}
}