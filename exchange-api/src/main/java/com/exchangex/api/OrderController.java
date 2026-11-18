package com.exchangex.api;

import com.exchangex.engine.OrderView;
import com.exchangex.api.repository.OrderRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    private SymbolCoordinatorRegistry coordinatorRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCoordinatorRegistry(SymbolCoordinatorRegistry registry) {
        this.coordinatorRegistry = registry;
    }

    private DurableCommandCoordinator forSymbol(String symbol) {
        if (coordinatorRegistry != null) return coordinatorRegistry.coordinator(symbol);
        if (!"AAPL".equals(symbol)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown instrument");
        }
        return coordinator;
    }
    private final OrderRepository orderRepository;
    private final JdbcTemplate jdbcTemplate;
    @Value("${exchangex.security.enabled:true}")
    private boolean securityEnabled;

    public OrderController(DurableCommandCoordinator coordinator,
            ObjectProvider<OrderRepository> repositories,
            ObjectProvider<JdbcTemplate> jdbcTemplates) {
        this.coordinator = coordinator;
        this.orderRepository = repositories.getIfAvailable();
        this.jdbcTemplate = jdbcTemplates.getIfAvailable();
    }

    private long authenticatedUserId(Jwt principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {
            long id = Long.parseLong(principal.getSubject());
            if (id > 0 && jdbcTemplate != null && !jdbcTemplate.query(
                    "SELECT 1 FROM exchange_users WHERE id = ? AND enabled = TRUE",
                    (rs, row) -> rs.getInt(1), id).isEmpty()) return id;
        } catch (NumberFormatException ignored) { }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private boolean ownedBy(long orderId, long userId) {
        if (jdbcTemplate == null) return false;
        return !jdbcTemplate.query("SELECT 1 FROM order_ownership WHERE order_id = ? AND user_id = ?",
                (rs, row) -> rs.getInt(1), orderId, userId).isEmpty();
    }

    @PostMapping("/orders")
    public CompletableFuture<ResponseEntity<OrderSubmissionResponse>> submit(
            @Valid @RequestBody OrderRequest request,
            @AuthenticationPrincipal Jwt principal) {

        return submitForSymbol("AAPL", request, principal);
    }

    @PostMapping("/instruments/{symbol}/orders")
    public CompletableFuture<ResponseEntity<OrderSubmissionResponse>> submitBySymbol(
            @PathVariable String symbol, @Valid @RequestBody OrderRequest request,
            @AuthenticationPrincipal Jwt principal) {
        return submitForSymbol(symbol, request, principal);
    }

    private CompletableFuture<ResponseEntity<OrderSubmissionResponse>> submitForSymbol(
            String symbol, OrderRequest request, Jwt principal) {
        DurableCommandCoordinator target = forSymbol(symbol);
        CompletableFuture<DurableCommandCoordinator.AdmissionResult> admission = securityEnabled
                ? target.submitOrderForUser(authenticatedUserId(principal), request.id(), request.side(), request.price(), request.quantity())
                : target.submitOrder(request.id(), request.side(), request.price(), request.quantity());
        return admission
                .thenApply(ack -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new OrderSubmissionResponse("PENDING", ack.sequenceId())))
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof SecurityException) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(new OrderSubmissionResponse("CONFLICT", 0));
                    }
                    if (cause instanceof com.exchangex.api.accounts.TradingClearingService.SelfTradePreventedException) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(new OrderSubmissionResponse("SELF_TRADE_PREVENTED", 0));
                    }
                    if (cause instanceof IllegalArgumentException iae) {
                        if (iae.getMessage().contains("different payload") || iae.getMessage().contains("different instrument")) {
                            return ResponseEntity.status(HttpStatus.CONFLICT)
                                    .body(new OrderSubmissionResponse("CONFLICT", 0));
                        }
                        return ResponseEntity.badRequest()
                                .body(new OrderSubmissionResponse("INVALID: " + iae.getMessage(), 0));
                    }
                    if (cause instanceof com.exchangex.api.accounts.AccountLedger.InsufficientBalanceException) {
                        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                                .body(new OrderSubmissionResponse("INSUFFICIENT_BALANCE", 0));
                    }
                    if (cause instanceof ArithmeticException) {
                        return ResponseEntity.badRequest()
                                .body(new OrderSubmissionResponse("AMOUNT_OVERFLOW", 0));
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
            @PathVariable("id") long id,
            @AuthenticationPrincipal Jwt principal) {

        return cancelForSymbol("AAPL", id, principal);
    }

    @DeleteMapping("/instruments/{symbol}/orders/{id}")
    public CompletableFuture<ResponseEntity<CancelResponse>> cancelBySymbol(
            @PathVariable String symbol, @PathVariable long id,
            @AuthenticationPrincipal Jwt principal) {
        return cancelForSymbol(symbol, id, principal);
    }

    private CompletableFuture<ResponseEntity<CancelResponse>> cancelForSymbol(
            String symbol, long id, Jwt principal) {
        DurableCommandCoordinator target = forSymbol(symbol);
        if (orderRepository != null && !orderHasSymbol(id, symbol)) {
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new CancelResponse("NOT_FOUND")));
        }
        CompletableFuture<DurableCommandCoordinator.AdmissionResult> admission = securityEnabled
                ? target.cancelOrderForUser(authenticatedUserId(principal), id)
                : target.cancelOrder(id);
        return admission
                .thenApply(ack -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new CancelResponse("PENDING")))
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof SecurityException) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(new CancelResponse("NOT_FOUND"));
                    }
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
            @PathVariable("id") long id,
            @AuthenticationPrincipal Jwt principal) {
        return getForSymbol("AAPL", id, principal);
    }

    @GetMapping("/instruments/{symbol}/orders/{id}")
    public CompletableFuture<ResponseEntity<OrderStateResponse>> getOrderBySymbol(
            @PathVariable String symbol, @PathVariable long id,
            @AuthenticationPrincipal Jwt principal) {
        return getForSymbol(symbol, id, principal);
    }

    private boolean orderHasSymbol(long id, String symbol) {
        if (jdbcTemplate == null) return "AAPL".equals(symbol);
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM exchange_commands WHERE order_id=? AND command_type='SUBMIT' AND symbol=?)",
                Boolean.class, id, symbol));
    }

    private CompletableFuture<ResponseEntity<OrderStateResponse>> getForSymbol(
            String symbol, long id, Jwt principal) {
        DurableCommandCoordinator target = forSymbol(symbol);
        if (securityEnabled && !ownedBy(id, authenticatedUserId(principal))) {
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new OrderStateResponse(id, null, 0, 0, "NOT_FOUND", 0)));
        }
        if (orderRepository == null) {
            // Lightweight controller tests inject a mocked coordinator without a database.
            return target.findOrder(id).thenApply(found -> found
                    .map(r -> ResponseEntity.ok(new OrderStateResponse(
                            r.id(), r.side().name(), r.price(), r.remainingQuantity(), "PENDING", 0)))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new OrderStateResponse(id, null, 0, 0, "NOT_FOUND", 0))));
        }
        return CompletableFuture.completedFuture(orderRepository.findByClientOrderId(id)
                .filter(r -> r.symbol().equals(symbol))
                .map(r -> ResponseEntity.ok(new OrderStateResponse(
                        r.clientOrderId(), r.side().name(), r.limitPrice(),
                        r.remainingQuantity(), r.executionStatus(), r.exchangeSequenceId())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new OrderStateResponse(id, null, 0, 0, "NOT_FOUND", 0))));
    }

    @GetMapping("/orderbook/{symbol}")
    public CompletableFuture<OrderBookResponse> book(
            @PathVariable("symbol") String symbol) {

        return forSymbol(symbol).snapshot()
                .thenApply(snapshot -> new OrderBookResponse(
                        symbol,
                        snapshot.bestBid().map(v -> new MarketLevel(v.price(), v.remainingQuantity())),
                        snapshot.bestAsk().map(v -> new MarketLevel(v.price(), v.remainingQuantity()))
                ));
    }

    @GetMapping("/trades")
    public CompletableFuture<ResponseEntity<List<MarketTrade>>> trades() {
        return tradesForSymbol("AAPL");
    }

    @GetMapping("/instruments/{symbol}/trades")
    public CompletableFuture<ResponseEntity<List<MarketTrade>>> tradesBySymbol(@PathVariable String symbol) {
        return tradesForSymbol(symbol);
    }

    private CompletableFuture<ResponseEntity<List<MarketTrade>>> tradesForSymbol(String symbol) {
        return forSymbol(symbol).trades()
                .thenApply(items -> ResponseEntity.ok(items.stream()
                        .map(t -> new MarketTrade(t.price(), t.quantity())).toList()));
    }

    @GetMapping("/instruments")
    public List<String> instruments() {
        if (coordinatorRegistry != null) return coordinatorRegistry.activeSymbols();
        return List.of("AAPL");
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

    /** Public market data excludes client and counterparty order identifiers. */
    public record MarketLevel(long price, long remainingQuantity) {}
    public record MarketTrade(long price, long quantity) {}

    public record OrderBookResponse(
            String symbol,
            Optional<MarketLevel> bestBid,
            Optional<MarketLevel> bestAsk
    ) {}
}