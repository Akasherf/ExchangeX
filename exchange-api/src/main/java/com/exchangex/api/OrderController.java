
package com.exchangex.api;

import com.exchangex.engine.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final OrderCommandProcessor processor;

    public OrderController(OrderCommandProcessor processor) {
        this.processor = processor;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public CompletableFuture<List<Trade>> submit(
            @Valid @RequestBody OrderRequest request) {

        return processor.submit(
                new Order(
                        request.id(),
                        "AAPL",
                        request.side(),
                        request.price(),
                        request.quantity()
                )
        );
    }

    @DeleteMapping("/orders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public CompletableFuture<Void> cancel(
            @PathVariable("id") long id) {

        return processor.cancel(id).thenApply(cancelled -> {
            if (!cancelled) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Active order not found"
                );
            }
            return null;
        });
    }

    @GetMapping("/orders/{id}")
    public CompletableFuture<OrderView> getOrder(
            @PathVariable("id") long id) {

        return processor.find(id).thenApply(found ->
                found.orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Active order not found"
                        )
                )
        );
    }

    @GetMapping("/orderbook/{symbol}")
    public CompletableFuture<OrderBookResponse> book(
            @PathVariable("symbol") String symbol) {

        if (!"AAPL".equals(symbol)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Unknown instrument"
            );
        }

        return processor.snapshot().thenApply(snapshot ->
                new OrderBookResponse(
                        symbol,
                        snapshot.bestBid(),
                        snapshot.bestAsk()
                )
        );
    }

    @GetMapping("/trades")
    public CompletableFuture<List<Trade>> trades() {
        return processor.trades();
    }

    public record OrderBookResponse(
            String symbol,
            Optional<OrderView> bestBid,
            Optional<OrderView> bestAsk
    ) {}
}
